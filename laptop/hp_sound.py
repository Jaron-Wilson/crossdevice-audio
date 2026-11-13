"""Sound viewer and notch filters for the panel.

What this can and can't do, honestly:
  * The AirPods' ANC microphones and ANC filters are internal to the AirPods. AAP gives no access to
    that audio and no way to tell the ANC which sound to remove. The only AirPods mic a computer can
    hear is the call (HFP) mic: mono, low quality, and while it's open the music drops to call
    quality too. So the viewer listens to the laptop mic by default, the AirPods mic only on request.
  * Outside sound can only be reduced by the AirPods' own ANC (Noise Cancellation / Adaptive), which
    works best on steady low sounds (engines, fans, hum) and much less on voices or high sounds.
  * Sound that goes THROUGH the laptop can be notched out exactly: a PipeWire filter-chain on the
    laptop mic (calls, recordings) or on the laptop's output (what plays into the headphones).

Analyzer: `parec` (PipeWire's pulse server) streams mono float32 at 24 kHz; numpy FFTs it about 8
times a second, only while someone is watching.
"""
import json
import os
import signal
import subprocess
import threading
import time

os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")   # one small FFT at a time: no thread pool per core
os.environ.setdefault("OMP_NUM_THREADS", "1")
try:
    import numpy as np
except Exception:          # the viewer is optional; the rest of the panel works without numpy
    np = None

RATE = 24000
NFFT = 4096                # 5.9 Hz per bin: enough to tell 50 Hz from 60 Hz hum
HOP = 3000                 # a frame every 125 ms
FMIN, FMAX = 20.0, 11000.0
NBINS = 160                # log-spaced display bins sent to the browser
HIST = 24                  # ~3 s of fine spectra kept for "what is this band"


def _run(cmd, timeout=5):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def sources():
    """Microphones the viewer may use: the laptop's own (alsa) only. The AirPods' mic is never offered:
    opening it switches them to call mode (HFP), which drops music to phone quality and turns their
    noise control into a call."""
    try:
        out = _run(["pactl", "list", "short", "sources"]).stdout
    except Exception:
        return []
    muted = _muted_sources()
    res = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 2 or parts[1].endswith(".monitor"):
            continue
        name = parts[1]
        if name.startswith("alsa_input."):
            res.append({"id": name, "kind": "laptop", "label": "Laptop microphone", "muted": name in muted})
    return res


def laptop_mic():
    s = sources()
    return s[0]["id"] if s else ""


def keep_default_source_off_headphones():
    """If the default input is a Bluetooth headset mic, make the laptop mic the default instead: any app
    that opens the default mic would otherwise pull the AirPods into call mode. Returns what it did."""
    try:
        cur = _run(["pactl", "get-default-source"]).stdout.strip()
    except Exception:
        return ""
    if not cur.startswith("bluez_input."):
        return ""
    mic = laptop_mic()
    if mic:
        _run(["pactl", "set-default-source", mic])
        return mic
    return ""


def _muted_sources():
    try:
        out = _run(["pactl", "list", "sources"]).stdout
    except Exception:
        return set()
    muted, cur = set(), None
    for line in out.splitlines():
        t = line.strip()
        if t.startswith("Name: "): cur = t[6:]
        elif t == "Mute: yes" and cur: muted.add(cur)
    return muted


def unmute(source):
    _run(["pactl", "set-source-mute", source, "0"])


class Analyzer:
    """One capture at a time, shared by every viewer; stops a few seconds after the last one leaves."""

    def __init__(self):
        self.lock = threading.Lock()
        self.cond = threading.Condition()
        self.proc = None
        self.source = None
        self.frame = None          # latest {"seq", "bins"}
        self.seq = 0
        self.hist = []             # recent fine spectra (dB), for inspect()
        self.watchers = 0
        self.last_watch = 0.0
        self.error = ""
        self.started = 0.0
        edges = np.geomspace(FMIN, FMAX, NBINS + 1) if np is not None else []
        self.freqs = np.fft.rfftfreq(NFFT, 1.0 / RATE) if np is not None else []
        self.edges = edges
        if np is not None:
            idx = np.searchsorted(self.freqs, edges)
            self.bin_idx = [(int(a), max(int(a) + 1, int(b))) for a, b in zip(idx[:-1], idx[1:])]
            self.window = np.hanning(NFFT).astype(np.float32)
        threading.Thread(target=self._reaper, daemon=True).start()

    def available(self):
        return np is not None

    def bin_centers(self):
        if np is None:
            return []
        return [round(float(np.sqrt(a * b)), 1) for a, b in zip(self.edges[:-1], self.edges[1:])]

    # ---- capture ----
    def _start(self, source):
        self._stop()
        self.error = ""
        if not source.startswith("alsa_input."):
            self.error = "only the laptop microphone can be used"
            return
        self.proc = subprocess.Popen(
            ["parec", "-d", source, "--rate", str(RATE), "--channels", "1", "--format", "float32le",
             "--raw", "--latency-msec", "60", "--client-name", "Headphones panel",
             "--stream-name", "Sound viewer"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
        self.source = source
        self.started = time.time()
        self.hist = []
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()

    def _stop(self, wait=False):
        p, self.proc = self.proc, None
        if p and p.poll() is None:
            try:
                os.killpg(p.pid, signal.SIGTERM)
                p.wait(2)
            except Exception:
                try: p.kill()
                except Exception: pass
        self.source = None

    def _read(self, proc):
        buf = np.zeros(NFFT, dtype=np.float32)
        need = HOP * 4
        while proc is self.proc and proc.poll() is None:
            data = proc.stdout.read(need)
            if not data:
                break
            x = np.frombuffer(data[: len(data) // 4 * 4], dtype=np.float32)
            buf = np.concatenate([buf, x])[-NFFT:]
            spec = np.abs(np.fft.rfft(buf * self.window)) / (NFFT / 4)
            db = 20 * np.log10(spec + 1e-9)
            bins = [int(max(-120, min(0, db[a:b].max()))) for a, b in self.bin_idx]
            with self.cond:
                self.hist.append(db.astype(np.float32))
                if len(self.hist) > HIST:
                    self.hist.pop(0)
                self.seq += 1
                self.frame = {"seq": self.seq, "bins": bins}
                self.cond.notify_all()
        if proc is self.proc:
            self.error = "the microphone stopped"
            with self.cond:
                self.cond.notify_all()

    def _reaper(self):
        while True:
            time.sleep(1)
            with self.lock:
                if not self.proc:
                    continue
                if self.watchers == 0 and time.time() - self.last_watch > 4:
                    self._stop()

    # ---- public ----
    def watch(self, source):
        """Generator of frames for one viewer."""
        with self.lock:
            if self.proc is None or self.source != source or self.proc.poll() is not None:
                self._start(source)
            self.watchers += 1
        try:
            seq = 0
            while True:
                with self.cond:
                    self.cond.wait_for(lambda: (self.frame and self.frame["seq"] != seq) or self.error
                                       or self.source != source, timeout=2)
                    if self.source != source:
                        return
                    if self.error:
                        yield {"error": self.error}
                        return
                    f = self.frame
                if f and f["seq"] != seq:
                    seq = f["seq"]
                    yield f
                else:
                    yield {"keepalive": True}
        finally:
            with self.lock:
                self.watchers -= 1
                self.last_watch = time.time()

    def stop(self, wait=False):
        with self.lock:
            self._stop(wait)

    def inspect(self, lo, hi):
        """What is in the band lo..hi Hz over the last ~3 s: peak, level, steadiness, a rough label."""
        with self.cond:
            hist = list(self.hist)
        if np is None or len(hist) < 4:
            return {"error": "listening... try again in a moment"}
        lo, hi = max(FMIN, min(lo, hi)), min(FMAX, max(lo, hi))
        a = int(np.searchsorted(self.freqs, lo)); b = max(a + 1, int(np.searchsorted(self.freqs, hi)))
        h = np.stack(hist)[:, a:b]                     # time x freq, dB
        avg = 10 * np.log10(np.mean(10 ** (h / 10), axis=0) + 1e-12)
        k = int(np.argmax(avg))
        # parabolic interpolation for a peak frequency finer than one bin
        f0 = float(self.freqs[a + k])
        if 0 < k < len(avg) - 1:
            y0, y1, y2 = avg[k - 1], avg[k], avg[k + 1]
            d = 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2 + 1e-9)
            f0 += float(d) * RATE / NFFT
        level = float(10 * np.log10(np.mean(10 ** (avg / 10)) + 1e-12))
        # a tone is a peak inside the band; a maximum at the band's edge is just a slope
        inside = 0 < k < len(avg) - 1
        tonal = float(avg[k] - np.median(avg)) if len(avg) > 4 and inside else 0.0
        band_t = 10 * np.log10(np.mean(10 ** (h / 10), axis=1) + 1e-12)   # band level over time
        steady = float(np.std(band_t))
        is_tonal, is_steady = tonal > 12, steady < 3.0
        if float(avg.max()) < -105:
            return {"lo": round(lo), "hi": round(hi), "error": "nothing heard in that range (silent, or the mic is muted)"}
        label = classify(f0, is_tonal, is_steady)
        return {"lo": round(lo), "hi": round(hi), "peak_hz": round(f0, 1), "level_db": round(level, 1),
                "tonal": is_tonal, "steady": is_steady, "tonal_db": round(tonal, 1),
                "variation_db": round(steady, 1), "label": label,
                "anc": anc_advice(f0, is_steady, is_tonal)}


def classify(f, tonal, steady):
    for base in (50.0, 60.0):
        for n in (1, 2, 3):
            if tonal and abs(f - base * n) < 3 * n:
                return f"mains hum ({int(base)} Hz power{'' if n == 1 else f', harmonic x{n}'})"
    if f < 40:
        return "deep rumble (wind, traffic, building vibration)"
    if f < 250:
        if tonal:
            return "motor or transformer hum"
        return "low rumble (engines, traffic, ventilation)"
    if f < 1000:
        if tonal:
            return "motor / fan tone"
        return "fan or air noise" if steady else "voices (low part) or general noise"
    if f < 4000:
        if tonal:
            return "whine, beep or alarm tone"
        return "voices / speech" if not steady else "fan or air hiss"
    return "high whine (electronics)" if tonal else "hiss (air, water, sibilance)"


def anc_advice(f, steady, tonal):
    if f < 1000 and steady:
        return {"good": True, "text": "Steady and low: the AirPods' Noise Cancellation handles this kind of "
                                      "sound well. Switch to Noise Cancellation (or set Adaptive to let in "
                                      "less noise)."}
    if f < 1000:
        return {"good": True, "text": "Low but changing: Noise Cancellation reduces it, though not completely."}
    if f < 4000:
        return {"good": False, "text": "ANC only partly reduces mid sounds like voices; the ear tips' seal "
                                       "does most of the work here. Noise Cancellation still helps a bit."}
    return {"good": False, "text": "High sounds are mostly blocked by the ear tips' seal, not ANC. "
                                   "A good tip fit matters more than the mode."}


# ---- notch filters on sound that passes through the laptop ----

STATE_FILE = os.path.expanduser("~/.config/headphone-panel/notch.json")
MAINS = (50.0, 60.0)


def notch_q(f):
    """Narrow, but wide enough (-3 dB width >= ~10 Hz) that a peak estimated a few Hz off still lands
    in the notch."""
    return round(max(2.0, min(8.0, f / 12.0)), 2)


def snap_mains(f):
    """Hum harmonics sit exactly on multiples of 50/60 Hz: snap a reading that is close to one."""
    for base in MAINS:
        n = round(f / base)
        if 1 <= n <= 6 and abs(f - base * n) < 2.5 * n:
            return base * n
    return f


def notch_graph(freqs):
    nodes = [{"type": "builtin", "name": f"n{i}", "label": "bq_notch",
              "control": {"Freq": float(f), "Q": notch_q(f)}} for i, f in enumerate(freqs)]
    if len(nodes) == 3:
        # PipeWire 1.6.2: a filter-chain graph of exactly 3 builtin nodes comes up with NO ports (checked:
        # 1, 2, 4, 5, 6 nodes are fine, 3 always fails, stereo and mono). With no ports the filter
        # passes nothing and nothing links to it: that is why "notch + harmonics" did nothing.
        # A neutral 4th stage (a 0 dB peaking band) avoids it.
        nodes.append({"type": "builtin", "name": f"n{len(nodes)}", "label": "bq_peaking",
                      "control": {"Freq": 1000.0, "Q": 1.0, "Gain": 0.0}})
    links = [{"output": f"n{i}:Out", "input": f"n{i + 1}:In"} for i in range(len(nodes) - 1)]
    return {"nodes": nodes, "links": links, "inputs": ["n0:In"], "outputs": [f"n{len(nodes) - 1}:Out"]}


def _conf_text(args):
    return ("context.properties = { log.level = 0 }\n"
            "context.spa-libs = {\n    audio.convert.* = audioconvert/libspa-audioconvert\n"
            "    support.* = support/libspa-support\n}\n"
            "context.modules = [\n"
            "    { name = libpipewire-module-rt args = {} flags = [ ifexists nofail ] }\n"
            "    { name = libpipewire-module-protocol-native }\n"
            "    { name = libpipewire-module-client-node }\n"
            "    { name = libpipewire-module-adapter }\n"
            "    { name = libpipewire-module-filter-chain args = " + json.dumps(args) + " }\n]\n")


def _pa_json(what):
    try:
        return json.loads(_run(["pactl", "-f", "json", "list", what]).stdout or "[]")
    except Exception:
        return []


def _names(kind):
    """index -> name for sinks or sources."""
    return {x["index"]: x["name"] for x in _pa_json(kind)}


# ---- silent measurement: the filter between a private sink and a null sink ----
MRATE = 48000


def _tone_wav(path, tones, secs):
    import wave
    t = np.arange(int(MRATE * secs)) / MRATE
    x = sum(10 ** (a / 20) * np.sin(2 * np.pi * f * t) for f, a in tones)
    x = x * np.minimum(1, np.minimum(t, t[::-1]) / 0.05)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(MRATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def _capture(play_target, monitor, wav, secs):
    rec = subprocess.Popen(["parec", "-d", monitor, "--rate", str(MRATE), "--channels", "1",
                            "--format", "float32le", "--raw", "--latency-msec", "20"],
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
    time.sleep(0.5)
    # never let a test tone fall back to the default output (the headphones someone is wearing)
    _run(["pw-play", "--target", play_target, "-P", "{ node.dont-fallback = true node.dont-reconnect = true }",
          wav], timeout=secs + 10)
    time.sleep(0.4)
    os.killpg(rec.pid, signal.SIGTERM)
    data = rec.stdout.read()
    return np.frombuffer(data[: len(data) // 4 * 4], dtype=np.float32)


def _levels(y, freqs):
    n = MRATE
    if len(y) < n:
        return None, None
    env = np.convolve(np.abs(y), np.ones(4800) / 4800, mode="same")
    on = np.where(env > env.max() * 0.3)[0]
    if len(on) == 0:
        return None, None
    a = max(0, (on[0] + on[-1]) // 2 - n // 2)
    seg = y[a:a + n]
    if len(seg) < n:
        return None, None
    spec = np.abs(np.fft.rfft(seg * np.hanning(n))) / (n / 4)
    lv = [20 * np.log10(spec[max(0, int(round(f)) - 3): int(round(f)) + 4].max() + 1e-12) for f in freqs]
    return lv, 20 * np.log10(float(np.abs(y[on[0]:on[-1]]).max()) + 1e-12)


def measure(graph, tones, channels=2, secs=1.6):
    """Play `tones` [(Hz, dBFS)] through a private copy of the filter graph into a null sink and
    through a bypass, and compare. Nothing is audible and the real audio path is not touched."""
    if np is None:
        return {"error": "numpy is not installed"}
    import tempfile
    uid = f"{os.getpid()}{int(time.time() * 1000) % 100000}"
    null, fname = f"hp_mtest{uid}", f"hp_mtest{uid}_in"
    tmp = tempfile.mkdtemp(prefix="hp-measure-")
    wav, conf = os.path.join(tmp, "t.wav"), os.path.join(tmp, "f.conf")
    _tone_wav(wav, tones, secs)
    mod = _run(["pactl", "load-module", "module-null-sink", f"sink_name={null}", "channels=1",
                "sink_properties=device.description=PanelFilterTest node.passive=true priority.session=0"]).stdout.strip()
    proc = None
    try:
        if not mod.isdigit() or null not in _names("sinks").values():
            return {"error": "could not create the silent test sink"}
        pos = ["FL", "FR"] if channels == 2 else ["MONO"]
        with open(conf, "w") as f:
            f.write(_conf_text({"node.description": "Panel filter test", "media.name": "Panel filter test",
                                "filter.graph": graph, "audio.channels": channels, "audio.position": pos,
                                "capture.props": {"node.name": fname, "media.class": "Audio/Sink",
                                                  "node.passive": True, "priority.session": 0},
                                "playback.props": {"node.name": fname + ".output", "node.passive": True,
                                                   "node.dont-reconnect": True, "target.object": null}}))
        proc = subprocess.Popen(["pipewire", "-c", conf], stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, start_new_session=True)
        for _ in range(40):
            if fname in _names("sinks").values():
                break
            time.sleep(0.1)
        else:
            return {"error": "the test filter did not start"}
        time.sleep(0.3)
        freqs = [f for f, _ in tones]
        lb, pb = _levels(_capture(null, null + ".monitor", wav, secs), freqs)
        lf, pf = _levels(_capture(fname, null + ".monitor", wav, secs), freqs)
        if lb is None or lf is None:
            return {"error": "no test signal came through"}
        return {"tones": [{"hz": round(f, 1), "bypass_db": round(a, 1), "filtered_db": round(b, 1),
                           "change_db": round(b - a, 1)} for f, a, b in zip(freqs, lb, lf)],
                "peak_bypass_dbfs": round(pb, 1), "peak_filtered_dbfs": round(pf, 1)}
    finally:
        if proc:
            try:
                os.killpg(proc.pid, signal.SIGTERM); proc.wait(3)
            except Exception:
                pass
        if mod.isdigit():
            _run(["pactl", "unload-module", mod])
        for p in (wav, conf):
            try: os.remove(p)
            except OSError: pass
        try: os.rmdir(tmp)
        except OSError: pass


def measure_notch(freqs):
    """Depth at each notch and the largest change at reference tones away from all notches."""
    refs = []
    for f in freqs:
        for r in (f / 2, f * 1.6, f * 3.2):
            if 25 <= r <= 11000 and all(abs(np.log2(r / g)) > 0.5 for g in freqs) and \
                    all(abs(np.log2(r / x)) > 0.2 for x in refs):
                refs.append(r)
    tones = [(f, -20) for f in freqs] + [(r, -20) for r in refs[:4]]
    m = measure(notch_graph(freqs), tones)
    if "error" in m:
        return m
    t = m["tones"]
    return {"notch": [{"hz": x["hz"], "db": x["change_db"]} for x in t[:len(freqs)]],
            "elsewhere_max_db": round(max([abs(x["change_db"]) for x in t[len(freqs):]] or [0]), 1),
            "elsewhere": [{"hz": x["hz"], "db": x["change_db"]} for x in t[len(freqs):]],
            "t": time.time()}


class Notch:
    """A PipeWire filter-chain with up to 6 notches, for sound that passes THROUGH the laptop. Both are
    WirePlumber smart filters, so no default device changes and apps don't need moving:

    kind "mic": sits in front of the laptop mic; apps recording from the laptop mic get the notched sound.
    kind "output": sits in front of the default output (before the EQ, if that is on); everything
    playing to the headphones goes through it.
    The on/off state survives a panel restart (notch.json)."""

    NAMES = {"mic": ("hp_notch_mic", "Laptop mic (notch filter)"),
             "output": ("hp_notch_out", "Laptop output (notch filter)")}

    def __init__(self, kind):
        self.kind = kind
        self.name, self.desc = self.NAMES[kind]
        self.proc = None
        self.freqs = []
        self.device = ""
        self.error = ""
        self.measured = None
        self.lock = threading.Lock()
        self.conf = os.path.expanduser(f"~/.cache/headphone-panel/{self.name}.conf")
        self.match = None
        # set by the panel: (device name, WirePlumber match rules) of the current output device
        self.output_target = lambda: ("", None)

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def check_target(self):
        """Output notch: follow the default output when it changes (AirPods -> Crusher...)."""
        if self.kind != "output" or not self.running():
            return
        dev, match = self.output_target()
        if match and match != self.match:
            self.set(self.freqs, save=False)

    def _links(self):
        """(apps feeding the filter, devices the filter feeds), from pw-link."""
        try:
            out = _run(["pw-link", "-l"]).stdout
        except Exception:
            return [], []
        ins, outs, cur = set(), set(), None
        node_in = self.name if self.kind == "output" else self.name + ".capture"
        node_out = self.name + ".output" if self.kind == "output" else self.name
        for line in out.splitlines():
            if not line.startswith(" "):
                cur = line.rsplit(":", 1)[0]          # node names can contain ':' (Bluetooth addresses)
                continue
            peer = line.strip()[3:].strip().rsplit(":", 1)[0]
            if line.strip().startswith("|<-") and cur == (self.name if self.kind == "output" else node_in):
                ins.add(peer)
            if line.strip().startswith("|->") and cur == node_out:
                outs.add(peer)
        return sorted(ins), sorted(outs)

    def state(self):
        on = self.running()
        ins, outs = self._links() if on else ([], [])
        return {"on": on, "freqs": self.freqs, "error": self.error, "device": self.device,
                "from": ins, "to": outs, "measured": self.measured if on else None}

    def _conf(self, freqs, match):
        smart = {"filter.smart": True, "filter.smart.name": self.name}
        if match:
            smart["filter.smart.target"] = match
        if self.kind == "mic":
            cap = {"node.name": self.name + ".capture", "node.passive": True}
            play = {"node.name": self.name, "media.class": "Audio/Source", "node.description": self.desc,
                    **smart}
            ch, pos = 1, ["MONO"]
        else:
            cap = {"node.name": self.name, "media.class": "Audio/Sink", "node.description": self.desc,
                   **smart}
            play = {"node.name": self.name + ".output", "node.passive": True}
            ch, pos = 2, ["FL", "FR"]
        return _conf_text({"node.description": self.desc, "media.name": self.desc,
                           "filter.graph": notch_graph(freqs), "audio.channels": ch, "audio.position": pos,
                           "capture.props": cap, "playback.props": play})

    def set(self, freqs, hum=False, save=True):
        """Turn on with these notch frequencies (Hz), or off with an empty list."""
        freqs = [snap_mains(f) if hum else f for f in (float(x) for x in freqs)]
        freqs = sorted({round(max(20.0, min(11000.0, f)), 1) for f in freqs})[:6]
        with self.lock:
            self._off()
            if save:
                self._save(freqs)
            if not freqs:
                self.error = ""
                return self.state()
            if self.kind == "mic":
                self.device = laptop_mic()      # never the AirPods mic: that means call mode
                self.match = {"node.name": self.device}
                if not self.device:
                    self.error = "no laptop microphone found"
                    return self.state()
            else:
                self.device, self.match = self.output_target()
                if not self.match:
                    self.error = "no output device found"
                    return self.state()
            os.makedirs(os.path.dirname(self.conf), exist_ok=True)
            with open(self.conf, "w") as f:
                f.write(self._conf(freqs, self.match))
            self.proc = subprocess.Popen(["pipewire", "-c", self.conf], stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, start_new_session=True)
            what = "sources" if self.kind == "mic" else "sinks"
            self.error = ""
            for _ in range(40):
                if self.proc.poll() is not None:
                    self.error = "the filter process exited (pipewire -c failed)"
                    self.proc = None
                    return self.state()
                if self.name in _names(what).values():
                    break
                time.sleep(0.1)
            else:
                self.error = "the filter did not appear in PipeWire"
            self.freqs = freqs
            self.measured = {"pending": True}
        threading.Thread(target=self._measure, args=(freqs,), daemon=True).start()
        return self.state()

    def _measure(self, freqs):
        try:
            m = measure_notch(freqs)
        except Exception as e:
            m = {"error": str(e)[:200]}
        with self.lock:
            if self.freqs == freqs:
                self.measured = m

    def _off(self):
        if self.running():
            try:
                os.killpg(self.proc.pid, signal.SIGTERM)
                self.proc.wait(3)
            except Exception:
                try: self.proc.kill()
                except Exception: pass
        self.proc = None
        self.freqs = []
        self.measured = None

    def _save(self, freqs):
        try:
            d = json.load(open(STATE_FILE))
        except (OSError, ValueError):
            d = {}
        d[self.kind] = freqs
        try:
            os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
            with open(STATE_FILE, "w") as f:
                json.dump(d, f)
        except OSError:
            pass

    def restore_saved(self):
        """After a panel restart: turn back on with the saved notches."""
        try:
            freqs = json.load(open(STATE_FILE)).get(self.kind) or []
        except (OSError, ValueError):
            freqs = []
        if freqs and not self.running():
            self.set(freqs, save=False)

    def shutdown(self):
        """Panel stopping: take the filter down but keep the saved state."""
        with self.lock:
            self._off()
