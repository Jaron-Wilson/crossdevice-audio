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
    """Microphones worth showing: the laptop's own (alsa) and the AirPods' call mic (bluez)."""
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
        elif name.startswith("bluez_input."):
            res.append({"id": name, "kind": "bluetooth", "muted": name in muted,
                        "label": "Headphone microphone (call mode: lowers music quality while on)"})
    return res


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


def card_of(source):
    """bluez_input.AA:BB:.. -> bluez_card.AA_BB_.. (the card whose profile the call mic needs)."""
    return "bluez_card." + source.split(".", 1)[1].split(".")[0].replace(":", "_")


def card_profile(card):
    try:
        out = _run(["pactl", "list", "cards"]).stdout
    except Exception:
        return None
    cur = None
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("Name: "):
            cur = s[6:]
        elif s.startswith("Active Profile: ") and cur == card:
            return s[16:]
    return None


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
        self.restore = None        # (card, profile) to put back after using a headphone mic
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
        if source.startswith("bluez_input."):
            card = card_of(source)
            prof = card_profile(card)
            if prof and not prof.startswith("headset"):
                self.restore = (card, prof)
        self.proc = subprocess.Popen(
            ["parec", "-d", source, "--rate", str(RATE), "--channels", "1", "--format", "float32le",
             "--raw", "--latency-msec", "60", "--client-name", "Headphones panel",
             "--stream-name", "Sound viewer"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
        self.source = source
        self.started = time.time()
        self.hist = []
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()
        if self.restore:
            # WirePlumber normally switches to call mode by itself once the call mic is opened; if it
            # didn't (autoswitch turned off), do it here. _stop() switches back either way.
            card, proc = self.restore[0], self.proc
            def ensure():
                time.sleep(2.5)
                if proc is self.proc and not (card_profile(card) or "").startswith("headset"):
                    _run(["pactl", "set-card-profile", card, "headset-head-unit"])
            threading.Thread(target=ensure, daemon=True).start()

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
        if self.restore:
            card, prof = self.restore
            self.restore = None
            # WirePlumber switches back by itself when the call mic closes; make sure it did
            def back():
                time.sleep(2.5)
                if card_profile(card) != prof:
                    _run(["pactl", "set-card-profile", card, prof])
            if wait: back()
            else: threading.Thread(target=back, daemon=True).start()

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
                idle = self.watchers == 0 and time.time() - self.last_watch > 4
                # never leave the headphones in call quality for long
                hfp_long = self.restore and time.time() - self.started > 180
                if idle or hfp_long:
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


class Notch:
    """A PipeWire filter-chain with up to 6 notches. kind "mic": a virtual microphone (the laptop mic
    with the notches) made the default input. kind "output": a virtual output in front of the current
    default output, made the default. Off puts the old default back and stops the process."""

    NAMES = {"mic": ("hp_notch_mic", "Microphone (notch filter)"),
             "output": ("hp_notch_out", "Headphones (notch filter)")}

    def __init__(self, kind):
        self.kind = kind
        self.name, self.desc = self.NAMES[kind]
        self.proc = None
        self.freqs = []
        self.prev_default = ""
        self.error = ""
        self.lock = threading.Lock()
        self.conf = os.path.expanduser(f"~/.cache/headphone-panel/{self.name}.conf")

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def state(self):
        return {"on": self.running(), "freqs": self.freqs, "error": self.error}

    def _conf(self, freqs, target):
        nodes = [{"type": "builtin", "name": f"n{i}", "label": "bq_notch",
                  "control": {"Freq": float(f), "Q": 8.0 if f < 300 else 4.0}} for i, f in enumerate(freqs)]
        links = [{"output": f"n{i}:Out", "input": f"n{i + 1}:In"} for i in range(len(nodes) - 1)]
        if self.kind == "mic":
            cap = {"node.name": self.name + ".capture", "node.passive": True, "target.object": target}
            play = {"node.name": self.name, "media.class": "Audio/Source", "node.description": self.desc}
            ch, pos = 1, ["MONO"]
        else:
            cap = {"node.name": self.name, "media.class": "Audio/Sink", "node.description": self.desc}
            play = {"node.name": self.name + ".output", "node.passive": True, "node.dont-reconnect": True,
                    "target.object": target}
            ch, pos = 2, ["FL", "FR"]
        args = {"node.description": self.desc, "media.name": self.desc,
                "filter.graph": {"nodes": nodes, "links": links},
                "audio.channels": ch, "audio.position": pos, "capture.props": cap, "playback.props": play}
        return ("context.spa-libs = {\n    audio.convert.* = audioconvert/libspa-audioconvert\n"
                "    support.* = support/libspa-support\n}\n"
                "context.modules = [\n"
                "    { name = libpipewire-module-rt args = {} flags = [ ifexists nofail ] }\n"
                "    { name = libpipewire-module-protocol-native }\n"
                "    { name = libpipewire-module-client-node }\n"
                "    { name = libpipewire-module-adapter }\n"
                "    { name = libpipewire-module-filter-chain args = " + json.dumps(args) + " }\n]\n")

    def _default(self):
        what = "source" if self.kind == "mic" else "sink"
        try:
            return _run(["pactl", f"get-default-{what}"]).stdout.strip()
        except Exception:
            return ""

    def _set_default(self, name):
        _run(["pactl", "set-default-" + ("source" if self.kind == "mic" else "sink"), name])

    def _exists(self):
        what = "sources" if self.kind == "mic" else "sinks"
        try:
            return any(l.split("\t")[1] == self.name
                       for l in _run(["pactl", "list", "short", what]).stdout.splitlines() if "\t" in l)
        except Exception:
            return False

    def set(self, freqs, target=None):
        """Turn on with these notch frequencies (Hz), or off with an empty list."""
        freqs = sorted({round(max(20.0, min(11000.0, float(f))), 1) for f in freqs})[:6]
        with self.lock:
            self._off()
            if not freqs:
                return self.state()
            cur = self._default()
            if not target:
                if self.kind == "mic":
                    # notch the laptop's own mic: the headphone call mic would drop the music quality
                    mics = [s["id"] for s in sources() if s["kind"] == "laptop"]
                    target = mics[0] if mics else cur
                else:
                    target = cur
            if not target:
                self.error = "no input/output to filter"
                return self.state()
            os.makedirs(os.path.dirname(self.conf), exist_ok=True)
            with open(self.conf, "w") as f:
                f.write(self._conf(freqs, target))
            self.proc = subprocess.Popen(["pipewire", "-c", self.conf], stdout=subprocess.DEVNULL,
                                         stderr=subprocess.PIPE, start_new_session=True)
            for _ in range(30):
                if self.proc.poll() is not None:
                    self.error = (self.proc.stderr.read() or b"").decode(errors="replace")[-300:] or "pipewire exited"
                    self.proc = None
                    return self.state()
                if self._exists():
                    break
                time.sleep(0.1)
            self.prev_default = cur if cur != self.name else self.prev_default
            self.freqs = freqs
            self.error = ""
            self._set_default(self.name)
            return self.state()

    def _off(self):
        if self.running():
            if self._default() == self.name and self.prev_default:
                self._set_default(self.prev_default)
            try:
                os.killpg(self.proc.pid, signal.SIGTERM)
                self.proc.wait(3)
            except Exception:
                try: self.proc.kill()
                except Exception: pass
        self.proc = None
        self.freqs = []

    def shutdown(self):
        with self.lock:
            self._off()
