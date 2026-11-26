"""Crusher Adaptive on the laptop: the PipeWire side of shared/crusher_adaptive.py.

  * The only mic is the Crusher's own. Its bluez card is switched to the headset (HFP) profile only once no
    sink has played for 10 s, the bluez_input source is read with parec (mono float32 at 16 kHz, into
    memory; nothing is written anywhere), and the previous profile (A2DP) is put back when it closes, which
    happens within about a second of any sink starting to play. The laptop's own mics are never opened.
  * While anything plays, Adaptive senses nothing and keeps the last mode it chose.
"""
import json
import os
import signal
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (HERE, os.path.join(HERE, "..", "shared")):
    if os.path.isfile(os.path.join(_p, "crusher_adaptive.py")) and _p not in sys.path:
        sys.path.insert(0, _p)
import crusher_adaptive as ca   # noqa: E402

SETTINGS_FILE = os.path.expanduser("~/.config/headphone-panel/crusher-adaptive.json")


def _run(cmd, timeout=4):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


class ParecCapture:
    """parec on one PipeWire source, mono float32, into memory."""

    def _start(self, source, rate, stream):
        self.proc = subprocess.Popen(
            ["parec", "-d", source, "--rate", str(rate), "--channels", "1", "--format", "float32le", "--raw",
             "--latency-msec", "100", "--client-name", "Headphones panel", "--stream-name", stream],
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


def _cards():
    return json.loads(_run(["pactl", "-f", "json", "list", "cards"], timeout=4).stdout or "[]")


def _card_of(mac):
    want = "bluez_card." + mac.upper().replace(":", "_")
    for c in _cards():
        if c.get("name") == want:
            return c
    return None


def _source_of(mac):
    tag = mac.upper().replace(":", "_")
    out = _run(["pactl", "list", "short", "sources"], timeout=3).stdout
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) >= 2 and parts[1].startswith("bluez_input.") and tag in parts[1]:
            return parts[1]
    return None


class HeadsetCapture(ParecCapture):
    """The Crusher's own mic: the card goes to its headset (HFP) profile while this is open, and back to the
    profile it had (A2DP) on close. Only opened while nothing plays (AdaptiveRunner checks that)."""

    def __init__(self, mac, rate=ca.RATE, wait_s=5.0):
        if not mac:
            raise RuntimeError("no crusher_mac in the panel config")
        card = _card_of(mac)
        if card is None:
            raise RuntimeError("the Crusher's audio card is not on this laptop (connected elsewhere?)")
        self.card, self.prev = card["name"], card.get("active_profile") or ""
        names = list((card.get("profiles") or {}).keys())
        hfp = [n for n in names if n.startswith("headset-head-unit") and
               card["profiles"][n].get("available", True) is not False]
        if not hfp:
            raise RuntimeError("the Crusher's card offers no headset (HFP) profile")
        pick = "headset-head-unit" if "headset-head-unit" in hfp else hfp[0]
        self.proc = None
        if not self.prev.startswith("headset-head-unit"):
            _run(["pactl", "set-card-profile", self.card, pick], timeout=4)
        src, t_end = None, time.monotonic() + wait_s
        while src is None and time.monotonic() < t_end:
            src = _source_of(mac)
            if src is None:
                time.sleep(0.25)
        if src is None:
            self._restore()
            raise RuntimeError("the Crusher's mic did not appear after switching to the headset profile")
        if not src.startswith("bluez_input."):
            self._restore()
            raise RuntimeError("refusing a source that is not the Crusher's mic")
        self.label = "Crusher mic (call profile)"
        self._start(src, rate, "Crusher Adaptive, Crusher mic (levels only)")

    def _restore(self):
        if self.prev and not self.prev.startswith("headset-head-unit"):
            try:
                _run(["pactl", "set-card-profile", self.card, self.prev], timeout=4)
            except Exception:
                pass

    def close(self):
        super().close()
        self._restore()


def media_playing():
    """True while any output plays (the speakers, the jack, the EQ's input or the Bluetooth headset)."""
    out = _run(["pactl", "list", "short", "sinks"], timeout=3).stdout
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) >= 5 and parts[4].strip() == "RUNNING":
            return True
    return False


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


def make_runner(send, read_mode, connected, has_level, log=print, mac=""):
    """The panel passes its Crusher functions; this adds the Crusher's mic, the media check and settings."""
    def open_headset():
        return HeadsetCapture(mac)
    return ca.AdaptiveRunner(
        open_headset=open_headset, send=send, media_playing=media_playing, read_mode=read_mode,
        connected=connected, has_level=has_level, load=load_settings, save=save_settings, log=log)
