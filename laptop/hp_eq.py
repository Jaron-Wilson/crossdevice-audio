"""Source-side EQ for the laptop: a PipeWire filter-chain sink with one biquad per band.

The AirPods have no EQ over AAP and the Crusher's only tone control is its sensory bass, so the EQ
runs on the device that plays the audio. Here that is a small `pipewire -c <conf>` process holding a
filter-chain: a virtual sink ("Headphones EQ") whose output goes to the real headphone sink.

  * Enabling it makes the EQ sink the default output and points its output at whatever the default was
    (the AirPods or the Crusher), or at `eq_target` from config.json.
  * Band changes are applied live with `pw-cli set-param` (no audio gap); if that fails the process is
    restarted with the new values.
  * Disabling it puts the old default sink back and stops the process.

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


def clamp_gains(gains):
    g = [float(x) for x in gains][:len(BANDS)]
    g += [0.0] * (len(BANDS) - len(g))
    return [round(max(GMIN, min(GMAX, x)), 1) for x in g]


def preamp(gains):
    """Headroom so boosts don't clip: pull everything down by the largest boost."""
    return -max([0.0] + list(gains))


def conf_text(gains, target):
    nodes = [{"type": "builtin", "name": "pre", "label": "bq_highshelf",
              "control": {"Freq": 10.0, "Q": 0.707, "Gain": preamp(gains)}}]
    for b, g in zip(BANDS, gains):
        nodes.append({"type": "builtin", "name": b["key"], "label": LABEL[b["type"]],
                      "control": {"Freq": float(b["freq"]), "Q": float(b["q"]), "Gain": float(g)}})
    links = [{"output": f"{a['name']}:Out", "input": f"{b['name']}:In"} for a, b in zip(nodes, nodes[1:])]
    play = {"node.name": OUT, "node.passive": True, "node.dont-reconnect": True}
    if target:
        play.update({"target.object": target, "node.target": target})
    args = {
        "node.description": "Headphones EQ",
        "media.name": "Headphones EQ",
        "filter.graph": {"nodes": nodes, "links": links},
        "audio.channels": 2,
        "audio.position": ["FL", "FR"],
        "capture.props": {"node.name": SINK, "media.class": "Audio/Sink"},
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
        self.s = {"enabled": False, "preset": "flat", "gains": [0.0] * len(BANDS), "target": ""}
        try:
            with open(STATE_FILE) as f:
                saved = json.load(f)
            self.s.update({k: saved[k] for k in self.s if k in saved})
            self.s["gains"] = clamp_gains(self.s["gains"])
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
        return {**self.s, "running": self.running(), "available": self.available(), "error": self.error,
                "bands": BANDS, "presets": MODEL["presets"], "gain_min": GMIN, "gain_max": GMAX}

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    # ---- process ----
    def _start(self):
        os.makedirs(os.path.dirname(CONF_FILE), exist_ok=True)
        with open(CONF_FILE, "w") as f:
            f.write(conf_text(self.s["gains"], self.s["target"]))
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
                if default_sink() == SINK and self.s["target"] in sinks():
                    _run(["pactl", "set-default-sink", self.s["target"]])
                self._stop()
            return
        cur = default_sink() if follow_default else ""
        if cur and cur != SINK and not self.config.get("eq_target"):
            if cur != self.s["target"]:
                changed_target = True
            self.s["target"] = cur            # EQ whatever was playing (AirPods, Crusher, speakers)
        elif self.config.get("eq_target"):
            self.s["target"] = self.config["eq_target"]
        if self.running() and not changed_target:
            if changed_gains and not self._live_set():
                self._stop()
        elif self.running():
            self._stop()
        if not self.running() and not self._start():
            return
        if default_sink() != SINK:
            _run(["pactl", "set-default-sink", SINK])
        self._save()

    def tick(self):
        """Called every few seconds: keep the EQ attached to the headphones as they come and go."""
        with self.lock:
            if not self.s["enabled"] or not self.available():
                return
            present = sinks()
            tgt = self.s["target"]
            if tgt and tgt not in present:
                # headphones gone: don't leave the default on an EQ that plays into nothing
                if self.running():
                    self._stop()
                return
            if not self.running():
                self._apply(follow_default=False)    # back on the same headphones as before

    def shutdown(self):
        with self.lock:
            if self.running() and default_sink() == SINK and self.s["target"] in sinks():
                _run(["pactl", "set-default-sink", self.s["target"]])
            self._stop()
