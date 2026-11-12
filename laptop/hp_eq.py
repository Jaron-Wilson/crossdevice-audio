"""Source-side EQ for the laptop: a PipeWire filter-chain sink with one biquad per band.

The AirPods have no EQ over AAP and the Crusher's only tone control is its sensory bass, so the EQ
runs on the device that plays the audio. Here that is a small `pipewire -c <conf>` process holding a
filter-chain registered as a WirePlumber *smart filter*: WirePlumber puts it in front of the default
output (or of `eq_target` from config.json), so whatever plays to the headphones goes through it.

  * The default output stays the real headphones. That keeps ONE volume: the laptop's volume slider
    is the AirPods' own (AVRCP absolute) volume, and the EQ sink itself always passes at 100%.
  * Band changes are applied live with `pw-cli set-param` (no audio gap); if that fails the process is
    restarted with the new values.
  * Headroom: with lsp-plugins-ladspa installed, the end of the chain is a 120 Hz Linkwitz-Riley split
    with an LSP compressor on the low band (only while bass/sub is boosted) and an LSP limiter at
    -1 dBFS (5 ms lookahead), so boosts only pull the signal down by up to 2 dB (as on Android). Without it, the pre-cut is
    about half the curve's peak and a very loud bass-heavy track at +12 can still touch 0 dBFS.
  * Disabling it stops the process; the default output is untouched.

The band layout and the presets come from shared/eq_presets.json, the same file Android and Windows use.
"""
import json
import os
import shutil
import signal
import subprocess
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
STATE_FILE = os.path.expanduser("~/.config/headphone-panel/eq.json")
CONF_FILE = os.path.expanduser("~/.cache/headphone-panel/eq-filter-chain.conf")
SINK = "hp_eq.input"           # the virtual sink apps play into
OUT = "hp_eq.output"           # the filter's output stream, linked to the real headphones
SMART_NAME = "hp_eq"           # WirePlumber smart-filter name (the notch filter orders itself before it)
PANEL_SINKS = ("hp_eq.input", "hp_notch_out")


def load_model():
    for p in (os.path.join(HERE, "eq_presets.json"), os.path.join(HERE, "..", "shared", "eq_presets.json")):
        try:
            with open(p) as f:
                return json.load(f)
        except OSError:
            continue
    raise RuntimeError("eq_presets.json not found next to panel.py or in ../shared/")


MODEL = load_model()
BANDS = MODEL["bands"]
GMIN, GMAX = MODEL["gain_min"], MODEL["gain_max"]
PRESETS = {p["key"]: p for p in MODEL["presets"]}
LABEL = {"lowshelf": "bq_lowshelf", "peaking": "bq_peaking", "highshelf": "bq_highshelf"}
SUBSONIC = MODEL.get("subsonic") or {"freq": 25, "q": 0.707}
HP_OFF = 5.0               # the subsonic high-pass parked here when unused (transparent above ~15 Hz)


def subsonic_freq(gains):
    """The high-pass under the sub band: on only while the sub band is boosted."""
    keys = [b["key"] for b in BANDS]
    sub = gains[keys.index("sub")] if "sub" in keys and len(gains) > keys.index("sub") else 0.0
    return float(SUBSONIC["freq"]) if sub > 0 else HP_OFF


def clamp_gains(gains):
    g = [float(x) for x in gains][:len(BANDS)]
    g += [0.0] * (len(BANDS) - len(g))
    return [round(max(GMIN, min(GMAX, x)), 1) for x in g]


HEADROOM_SHARE = 0.5      # cut by this share of the EQ curve's peak boost


def _biquad_db(t, f0, q, g, f, fs=48000.0):
    """RBJ cookbook magnitude (dB) of one band at frequency f (the same filters as PipeWire's bq_*)."""
    import cmath, math
    A, w = 10 ** (g / 40), 2 * math.pi * f0 / fs
    c, a = math.cos(w), math.sin(w) / (2 * q)
    r = 2 * math.sqrt(A) * a
    if t == "peaking":
        b, d = (1 + a * A, -2 * c, 1 - a * A), (1 + a / A, -2 * c, 1 - a / A)
    elif t == "highpass":
        b, d = ((1 + c) / 2, -(1 + c), (1 + c) / 2), (1 + a, -2 * c, 1 - a)
    elif t == "lowshelf":
        b = (A * ((A + 1) - (A - 1) * c + r), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - r))
        d = ((A + 1) + (A - 1) * c + r, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - r)
    else:
        b = (A * ((A + 1) + (A - 1) * c + r), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - r))
        d = ((A + 1) - (A - 1) * c + r, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - r)
    z = cmath.exp(-1j * 2 * math.pi * f / fs)
    h = (b[0] + b[1] * z + b[2] * z * z) / (d[0] + d[1] * z + d[2] * z * z)
    return 20 * math.log10(abs(h) + 1e-12)


def curve_peak(gains):
    """Largest boost of the whole EQ curve over the audible music range (40 Hz - 16 kHz)."""
    best = 0.0
    for i in range(61):
        f = 40 * (16000 / 40) ** (i / 60)
        best = max(best, sum(_biquad_db(b["type"], b["freq"], b["q"], g, f) for b, g in zip(BANDS, gains))
                   + _biquad_db("highpass", subsonic_freq(gains), SUBSONIC["q"], 0, f))
    return best


# Optional limiter as the last stage: LSP "Limiter Mono" (lsp-plugins-ladspa), one per channel.
# With it the EQ needs only a small pre-cut (as on Android); without it, the half-peak cut above.
LSP_SO = "/usr/lib/ladspa/lsp-plugins-ladspa.so"
LSP_LIMITER = "http://lsp-plug.in/plugins/ladspa/limiter_mono"
LIMITER_CONTROL = {
    "Threshold (G)": 10 ** (-1.0 / 20),      # -1 dBFS
    "Lookahead (ms)": 5.0,
    "Attack time (ms)": 1.0,
    "Release time (ms)": 20.0,               # the plugin's maximum
    "Automatic level regulation": 0.0,
    "Gain boost": 0.0,
    "Input gain (G)": 1.0,
    "Output gain (G)": 1.0,
    "Dithering": 0.0,
}


# Low band (below 120 Hz, Linkwitz-Riley 4th-order split, so low + high sum back flat) gets its own
# LSP compressor while bass is boosted: big bass hits are tamed there instead of making the final
# limiter duck the whole mix (vocals dropping on every kick = "Rumble sounds muted").
LSP_COMP = "http://lsp-plug.in/plugins/ladspa/compressor_mono"
CROSSOVER_HZ = 120.0
LOWCOMP_CONTROL = {
    "Attack threshold (G)": 10 ** (-8.0 / 20),    # -8 dBFS
    "Ratio": 4.0,
    "Attack time (ms)": 5.0,
    "Release time (ms)": 150.0,
    "Knee (G)": 10 ** (-6.0 / 20),
    "Sidechain lookahead (ms)": 0.0,               # no lookahead: its delay would comb the crossover
    "Makeup gain (G)": 1.0,
    "Input gain (G)": 1.0,
    "Output gain (G)": 1.0,
}


def low_boosted(gains):
    keys = [b["key"] for b in BANDS]
    return any(gains[keys.index(k)] > 0 for k in ("bass", "sub") if k in keys and keys.index(k) < len(gains))


def limiter_available():
    return os.path.exists(LSP_SO)


def preamp(gains, limiter=None):
    """Headroom. With the limiter: a quarter of the curve's peak, at most 2 dB (the limiter catches the
    rest). Without: about half the curve's peak boost. 0.5 dB steps."""
    if limiter is None:
        limiter = limiter_available()
    if limiter:
        return -min(2.0, round(0.25 * curve_peak(gains) * 2) / 2)
    return -round(HEADROOM_SHARE * curve_peak(gains) * 2) / 2


def conf_text(gains, target):
    nodes = [{"type": "builtin", "name": "pre", "label": "bq_highshelf",
              "control": {"Freq": 10.0, "Q": 0.707, "Gain": preamp(gains)}}]
    for b, g in zip(BANDS, gains):
        nodes.append({"type": "builtin", "name": b["key"], "label": LABEL[b["type"]],
                      "control": {"Freq": float(b["freq"]), "Q": float(b["q"]), "Gain": float(g)}})
    nodes.append({"type": "builtin", "name": "subsonic", "label": "bq_highpass",
                  "control": {"Freq": subsonic_freq(gains), "Q": float(SUBSONIC["q"])}})
    port_in = lambda n: n["name"] + (":Input" if n["type"] == "ladspa" else ":In")
    port_out = lambda n: n["name"] + (":Output" if n["type"] == "ladspa" else ":Out")
    links = [{"output": port_out(a), "input": port_in(b)} for a, b in zip(nodes, nodes[1:])]
    if limiter_available():
        last = nodes[-1]
        bq = lambda n, kind: {"type": "builtin", "name": n, "label": kind,
                              "control": {"Freq": CROSSOVER_HZ, "Q": 0.7071}}
        split = [bq("lp1", "bq_lowpass"), bq("lp2", "bq_lowpass"),
                 {"type": "ladspa", "name": "lowcomp", "plugin": LSP_SO, "label": LSP_COMP,
                  "control": {**LOWCOMP_CONTROL, "Bypass": 0.0 if low_boosted(gains) else 1.0}},
                 bq("hp1", "bq_highpass"), bq("hp2", "bq_highpass"),
                 {"type": "builtin", "name": "sum", "label": "mixer"},
                 {"type": "ladspa", "name": "limiter", "plugin": LSP_SO, "label": LSP_LIMITER,
                  "control": dict(LIMITER_CONTROL)}]
        nodes += split
        links += [{"output": port_out(last), "input": "lp1:In"}, {"output": "lp1:Out", "input": "lp2:In"},
                  {"output": "lp2:Out", "input": "lowcomp:Input"}, {"output": "lowcomp:Output", "input": "sum:In 1"},
                  {"output": port_out(last), "input": "hp1:In"}, {"output": "hp1:Out", "input": "hp2:In"},
                  {"output": "hp2:Out", "input": "sum:In 2"}, {"output": "sum:Out", "input": "limiter:Input"}]
    play = {"node.name": OUT, "node.passive": True}
    cap = {"node.name": SINK, "media.class": "Audio/Sink", "filter.smart": True,
           "filter.smart.name": SMART_NAME}
    if target:
        cap["filter.smart.target"] = target
    args = {
        "node.description": "Headphones EQ",
        "media.name": "Headphones EQ",
        "filter.graph": {"nodes": nodes, "links": links, "inputs": [port_in(nodes[0])],
                         "outputs": [port_out(nodes[-1])]},
        "audio.channels": 2,
        "audio.position": ["FL", "FR"],
        "capture.props": cap,
        "playback.props": play,
    }
    # PipeWire's SPA-JSON reads plain JSON, so json.dumps is a valid config body.
    return ("context.properties = { log.level = 0 }\n"
            "context.spa-libs = {\n"
            "    audio.convert.* = audioconvert/libspa-audioconvert\n"
            "    support.*       = support/libspa-support\n"
            "}\n"
            "context.modules = [\n"
            "    { name = libpipewire-module-rt args = {} flags = [ ifexists nofail ] }\n"
            "    { name = libpipewire-module-protocol-native }\n"
            "    { name = libpipewire-module-client-node }\n"
            "    { name = libpipewire-module-adapter }\n"
            "    { name = libpipewire-module-filter-chain args = " + json.dumps(args, indent=2) + " }\n"
            "]\n")


def _run(cmd, timeout=5):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def sinks():
    """Names of the current audio sinks (via pactl, which pipewire-pulse provides)."""
    try:
        out = _run(["pactl", "list", "short", "sinks"]).stdout
    except Exception:
        return []
    return [line.split("\t")[1] for line in out.splitlines() if "\t" in line]


def default_sink():
    try:
        return _run(["pactl", "get-default-sink"]).stdout.strip()
    except Exception:
        return ""


def real_sink(prefer=""):
    """A real output device to use as the default when the default is one of the panel's filters."""
    present = [n for n in sinks() if n not in PANEL_SINKS and not n.startswith("hp_")]
    if prefer in present:
        return prefer
    for pre in ("bluez_output.", "alsa_output."):
        for n in present:
            if n.startswith(pre) and "HDMI" not in n:
                return n
    return present[0] if present else ""


def fix_default_sink(prefer=""):
    """The panel's filters are smart filters in front of the real device: the default output must be
    the device itself (the old panel made its EQ sink the default; WirePlumber remembers that)."""
    cur = default_sink()
    if cur in PANEL_SINKS or cur.startswith("hp_"):
        dev = real_sink(prefer)
        if dev:
            _run(["pactl", "set-default-sink", dev])
            return dev
    return ""


def smart_target(sink):
    """WirePlumber match rules for the device behind `sink`. Bluetooth outputs in PipeWire 1.x are
    themselves smart-filter loopbacks (the volume lives there) and a smart filter can't target another
    one, so for those use the loopback's own target: the chain becomes app -> bluez_output (volume) ->
    panel filters -> the Bluetooth device. Anything else is matched by name."""
    if not sink:
        return None
    try:
        objs = json.loads(_run(["pw-dump"], timeout=8).stdout or "[]")
    except Exception:
        objs = []
    for o in objs:
        props = (o.get("info") or {}).get("props") or {}
        if o.get("type") == "PipeWire:Interface:Node" and props.get("node.name") == sink:
            t = props.get("filter.smart.target")
            if props.get("filter.smart") in (True, "true") and t:
                try:
                    return json.loads(t)
                except ValueError:
                    pass
            break
    return {"node.name": sink}


def node_id(name):
    try:
        objs = json.loads(_run(["pw-dump"], timeout=8).stdout or "[]")
    except Exception:
        return None
    for o in objs:
        if o.get("type") == "PipeWire:Interface:Node" and \
                (o.get("info", {}).get("props", {}) or {}).get("node.name") == name:
            return o["id"]
    return None


class Eq:
    """The EQ state (persisted) and the filter-chain process that applies it."""

    def __init__(self, config=None):
        self.config = config or {}
        self.lock = threading.Lock()
        self.proc = None
        self.error = ""
        self.match = None          # WirePlumber match rules of the device the running EQ sits in front of
        self.s = {"enabled": False, "preset": "flat", "gains": [0.0] * len(BANDS), "target": ""}
        try:
            with open(STATE_FILE) as f:
                saved = json.load(f)
            self.s.update({k: saved[k] for k in self.s if k in saved})
            self.s["gains"] = clamp_gains(self.s["gains"])
            if self.s["preset"] in PRESETS:       # a named preset means the model's current values
                self.s["gains"] = clamp_gains(PRESETS[self.s["preset"]]["gains"])
        except (OSError, ValueError):
            pass

    # ---- state ----
    def _save(self):
        os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
        with open(STATE_FILE, "w") as f:
            json.dump(self.s, f)

    def available(self):
        return bool(shutil.which("pipewire") and shutil.which("pactl"))

    def state(self):
        cur = default_sink()
        into = cur if cur and cur not in PANEL_SINKS else self.s.get("target", "")
        return {**self.s, "running": self.running(), "available": self.available(), "error": self.error,
                "into": self.config.get("eq_target") or into, "headroom_db": preamp(self.s["gains"]),
                "bands": BANDS, "presets": MODEL["presets"], "gain_min": GMIN, "gain_max": GMAX,
                "limiter": limiter_available(),
                "subsonic_hz": subsonic_freq(self.s["gains"]) if subsonic_freq(self.s["gains"]) > HP_OFF else 0}

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    # ---- process ----
    def _start(self):
        os.makedirs(os.path.dirname(CONF_FILE), exist_ok=True)
        with open(CONF_FILE, "w") as f:
            f.write(conf_text(self.s["gains"], self.match))
        # own process group, so stopping it never touches the panel
        self.proc = subprocess.Popen(["pipewire", "-c", CONF_FILE], stdout=subprocess.DEVNULL,
                                     stderr=subprocess.PIPE, start_new_session=True)
        for _ in range(30):                       # wait for the sink to appear (usually < 0.5 s)
            if self.proc.poll() is not None:
                self.error = (self.proc.stderr.read() or b"").decode(errors="replace")[-400:] or "pipewire exited"
                self.proc = None
                return False
            if SINK in sinks():
                self.error = ""
                return True
            time.sleep(0.1)
        self.error = "EQ sink did not appear"
        return False

    def _stop(self):
        if self.proc and self.proc.poll() is None:
            try:
                os.killpg(self.proc.pid, signal.SIGTERM)
                self.proc.wait(3)
            except Exception:
                try: self.proc.kill()
                except Exception: pass
        self.proc = None

    def _live_set(self):
        """Change gains in place. Returns False if the running filter couldn't be updated."""
        nid = node_id(SINK)
        if nid is None:
            return False
        params = [f'"pre:Gain" {preamp(self.s["gains"])}']
        params += [f'"{b["key"]}:Gain" {g}' for b, g in zip(BANDS, self.s["gains"])]
        params.append(f'"subsonic:Freq" {subsonic_freq(self.s["gains"])}')
        if limiter_available():
            params.append(f'"lowcomp:Bypass" {0.0 if low_boosted(self.s["gains"]) else 1.0}')
        try:
            r = _run(["pw-cli", "set-param", str(nid), "Props", "{ params = [ " + " ".join(params) + " ] }"])
        except Exception:
            return False
        return r.returncode == 0 and "rror" not in (r.stderr + r.stdout)

    # ---- public ----
    def set(self, enabled=None, preset=None, gains=None, target=None):
        with self.lock:
            if preset is not None:
                if preset not in PRESETS and preset != "custom":
                    raise ValueError("unknown preset")
                self.s["preset"] = preset
                if preset in PRESETS:
                    self.s["gains"] = clamp_gains(PRESETS[preset]["gains"])
            if gains is not None:
                self.s["gains"] = clamp_gains(gains)
                match = [k for k, p in PRESETS.items() if clamp_gains(p["gains"]) == self.s["gains"]]
                self.s["preset"] = match[0] if match else "custom"
            if target is not None:
                self.s["target"] = target
            if enabled is not None:
                self.s["enabled"] = bool(enabled)
            self._save()
            self._apply(changed_gains=gains is not None or preset is not None, changed_target=target is not None)
            return self.state()

    def cycle(self):
        keys = list(PRESETS)
        cur = self.s["preset"]
        nxt = keys[(keys.index(cur) + 1) % len(keys)] if cur in keys else keys[0]
        return self.set(enabled=True, preset=nxt)

    def _apply(self, changed_gains=False, changed_target=False, follow_default=True):
        if not self.available():
            self.error = "PipeWire (pipewire, pactl) not found"
            return
        if not self.s["enabled"]:
            if self.running():
                self._stop()
            fix_default_sink(self.s.get("target", ""))
            return
        dev = self.config.get("eq_target") or real_sink(default_sink() if default_sink() not in PANEL_SINKS
                                                        else self.s.get("target", ""))
        target = smart_target(dev)
        if target != self.match:
            changed_target = True
        if self.running() and not changed_target:
            if changed_gains and not self._live_set():
                self._stop()
        elif self.running():
            self._stop()
        if not self.running():
            self.match = target
            if not self._start():
                return
        fix_default_sink(self.s.get("target", ""))
        self._save()

    def headroom(self):
        return preamp(self.s["gains"])

    def tick(self):
        """Called every few seconds: keep the default output on a real device and the EQ running."""
        with self.lock:
            cur = default_sink()
            if cur and cur not in PANEL_SINKS and not cur.startswith("hp_"):
                self.s["target"] = cur          # remembered: where to go back to if the default gets lost
            fix_default_sink(self.s.get("target", ""))
            if self.s["enabled"] and self.available():
                if not self.running():
                    self._apply(follow_default=False)
                else:
                    # the default output changed (AirPods -> Crusher...): move the EQ in front of it
                    dev = self.config.get("eq_target") or real_sink(self.s.get("target", ""))
                    if dev and smart_target(dev) != self.match:
                        self._apply(changed_target=True)

    def shutdown(self):
        with self.lock:
            self._stop()
            fix_default_sink(self.s.get("target", ""))
