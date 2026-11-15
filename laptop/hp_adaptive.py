"""Crusher Adaptive on the laptop: the PipeWire side of shared/crusher_adaptive.py.

  * The mic is a laptop microphone only (hp_sound.sources(): alsa_input.*, never a monitor and never a
    Bluetooth headset mic, which would switch the headphones to call mode). parec streams mono float32 at
    16 kHz into memory; nothing is written anywhere.
  * While the laptop's own speakers (an alsa_output.* sink) are playing, readings are ignored: the mic
    would mostly hear the laptop itself. Music going to the Crusher over Bluetooth doesn't count.
  * "Lower media while someone talks" turns the default output down with pactl and puts it back, unless
    you changed the volume yourself meanwhile.
"""
import json
import os
import re
import signal
import subprocess
import sys
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (HERE, os.path.join(HERE, "..", "shared")):
    if os.path.isfile(os.path.join(_p, "crusher_adaptive.py")) and _p not in sys.path:
        sys.path.insert(0, _p)
import crusher_adaptive as ca   # noqa: E402

SETTINGS_FILE = os.path.expanduser("~/.config/headphone-panel/crusher-adaptive.json")
DUCK_TO = 0.35          # of the current volume while someone talks


def _run(cmd, timeout=4):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def mics():
    """Microphones Adaptive may use: the laptop's own only (same rule as the sound viewer)."""
    try:
        import hp_sound
        return [{"id": m["id"], "label": m["label"], "muted": m["muted"]} for m in hp_sound.sources()]
    except Exception:
        return []


def safe_mic(name):
    """The source to open: the chosen one if it is still a laptop mic, else the first laptop mic."""
    ids = [m["id"] for m in mics()]
    if name and name in ids:
        return name
    if not ids:
        raise RuntimeError("no laptop microphone found (Adaptive never uses the headphones' mic)")
    return ids[0]


class ParecCapture:
    def __init__(self, source, rate=ca.RATE):
        if not source.startswith("alsa_input.") or source.endswith(".monitor"):
            raise RuntimeError("only a laptop microphone can be used")
        self.label = source
        self.proc = subprocess.Popen(
            ["parec", "-d", source, "--rate", str(rate), "--channels", "1", "--format", "float32le", "--raw",
             "--latency-msec", "100", "--client-name", "Headphones panel",
             "--stream-name", "Crusher Adaptive (levels only)"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)

    def read(self, n):
        import numpy as np
        want, data = n * 4, b""
        while len(data) < want:
            p = self.proc
            if p is None:
                return None
            chunk = p.stdout.read(want - len(data))
            if not chunk:
                return None
            data += chunk
        return np.frombuffer(data, dtype=np.float32)

    def close(self):
        p, self.proc = self.proc, None
        if p and p.poll() is None:
            try:
                os.killpg(p.pid, signal.SIGTERM)
                p.wait(2)
            except Exception:
                try:
                    p.kill()
                except Exception:
                    pass


def speakers_playing():
    """True while one of the laptop's own outputs (speakers, its headphone jack, HDMI) is playing."""
    try:
        out = _run(["pactl", "list", "short", "sinks"], timeout=3).stdout
    except Exception:
        return False
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) >= 5 and parts[1].startswith("alsa_output.") and parts[4].strip() == "RUNNING":
            return True
    return False


class Ducker:
    """Lower the default output's volume and put it back, unless it was changed in between."""

    def __init__(self):
        self.saved = None      # (original %, the % we set)
        self.lock = threading.Lock()

    @staticmethod
    def _get():
        out = _run(["pactl", "get-sink-volume", "@DEFAULT_SINK@"], timeout=3).stdout
        m = re.search(r"(\d+)%", out)
        return int(m.group(1)) if m else None

    @staticmethod
    def _set(pct):
        _run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{int(pct)}%"], timeout=3)

    def __call__(self, on):
        with self.lock:
            if on:
                if self.saved:
                    return
                cur = self._get()
                if cur is None:
                    return
                low = max(5, round(cur * DUCK_TO))
                self._set(low)
                self.saved = (cur, low)
            else:
                if not self.saved:
                    return
                orig, low = self.saved
                self.saved = None
                cur = self._get()
                if cur is not None and abs(cur - low) <= 2:      # untouched since we lowered it
                    self._set(orig)


def load_settings():
    try:
        with open(SETTINGS_FILE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_settings(d):
    try:
        os.makedirs(os.path.dirname(SETTINGS_FILE), exist_ok=True)
        tmp = SETTINGS_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(d, f)
        os.replace(tmp, SETTINGS_FILE)
    except OSError:
        pass


def make_runner(send, read_mode, connected, has_level, log=print):
    """The panel passes its Crusher functions; this adds the laptop's mic, gate, ducking and settings."""
    return ca.AdaptiveRunner(
        open_capture=lambda mic: ParecCapture(safe_mic(mic)),
        send=send, read_mode=read_mode, connected=connected, has_level=has_level,
        gate=speakers_playing, duck=Ducker(), load=load_settings, save=save_settings, log=log)
