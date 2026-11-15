#!/usr/bin/env python3
r"""Crusher Adaptive on WINDOWS: the PC's own microphone hears the room, the Crusher's mode follows.

Same decision as the laptop panel (shared/crusher_adaptive.py); this file is the Windows side:
  * the mic is read with winmm's waveIn through ctypes (no extra package besides numpy). Only a mic that
    is NOT a Bluetooth headset is ever opened: opening the Crusher's (or any headset's) Hands-Free mic
    would switch it to call mode. Names with Hands-Free / Headset / Bluetooth / Crusher / AirPods are
    refused, and the system default (WAVE_MAPPER) is never used, because it can be the headset.
  * the Crusher is driven through crusher_win.py (RFCOMM channel 9 via Winsock).
  * a tiny local server on 127.0.0.1:8971 lets the Alt+Q ring (and crusher_win.py) turn it on, off, or
    tell it about a manual change.

Only loudness numbers are kept; the audio is measured in memory and dropped. Nothing is recorded or sent.

Usage:
  pythonw crusher_adaptive_win.py serve          # the background daemon (put a shortcut in shell:startup)
  python  crusher_adaptive_win.py on | off       # turn Adaptive on / off (on starts the daemon if needed)
  python  crusher_adaptive_win.py status         # JSON state: room dB, zone, what it chose
  python  crusher_adaptive_win.py manual         # what crusher_win.py anc|transparency calls
  python  crusher_adaptive_win.py set sensitivity=60 conversation=on duck=off mic="Microphone Array" pause_min=10
  python  crusher_adaptive_win.py mics           # the PC's microphones and which ones are safe to use
  python  crusher_adaptive_win.py measure [secs] # print the room level and speech flag; the Crusher isn't touched

Needs: numpy (pip install numpy) and CRUSHER_MAC set, like crusher_win.py.
"""
import ctypes
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
for _p in (HERE, os.path.join(HERE, "..", "shared")):
    if os.path.isfile(os.path.join(_p, "crusher_adaptive.py")) and _p not in sys.path:
        sys.path.insert(0, _p)
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import crusher_adaptive as ca   # noqa: E402

PORT = 8971
URL = f"http://127.0.0.1:{PORT}"
SETTINGS_FILE = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "crossdevice-audio",
                             "crusher-adaptive.json")
# a mic whose name says it is a headset or Bluetooth is never opened (it would switch the headset to calls)
UNSAFE_MIC = re.compile(r"(?i)hands[- ]?free|headset|bluetooth|crusher|skullcandy|airpods|\bbt\b")
DUCK_STEPS = 4


def mic_is_safe(name):
    return not UNSAFE_MIC.search(name or "")


# ---------------------------------------------------------------------------------------------------
# waveIn (winmm) through ctypes

WAVE_FORMAT_PCM = 1
WHDR_DONE = 0x1
CALLBACK_NULL = 0


class WAVEFORMATEX(ctypes.Structure):
    _pack_ = 1                       # mmsystem.h packs it: sizeof == 18
    _fields_ = [("wFormatTag", ctypes.c_ushort), ("nChannels", ctypes.c_ushort),
                ("nSamplesPerSec", ctypes.c_ulong), ("nAvgBytesPerSec", ctypes.c_ulong),
                ("nBlockAlign", ctypes.c_ushort), ("wBitsPerSample", ctypes.c_ushort), ("cbSize", ctypes.c_ushort)]


class WAVEHDR(ctypes.Structure):
    _fields_ = [("lpData", ctypes.c_void_p), ("dwBufferLength", ctypes.c_ulong),
                ("dwBytesRecorded", ctypes.c_ulong), ("dwUser", ctypes.c_size_t), ("dwFlags", ctypes.c_ulong),
                ("dwLoops", ctypes.c_ulong), ("lpNext", ctypes.c_void_p), ("reserved", ctypes.c_size_t)]


class WAVEINCAPSW(ctypes.Structure):
    _fields_ = [("wMid", ctypes.c_ushort), ("wPid", ctypes.c_ushort), ("vDriverVersion", ctypes.c_uint),
                ("szPname", ctypes.c_wchar * 32), ("dwFormats", ctypes.c_ulong), ("wChannels", ctypes.c_ushort),
                ("wReserved1", ctypes.c_ushort)]


_winmm = None


def winmm():
    global _winmm
    if _winmm is None:
        w = ctypes.WinDLL("winmm")
        w.waveInGetNumDevs.restype = ctypes.c_uint
        w.waveInGetDevCapsW.argtypes = [ctypes.c_size_t, ctypes.POINTER(WAVEINCAPSW), ctypes.c_uint]
        w.waveInOpen.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_uint, ctypes.POINTER(WAVEFORMATEX),
                                 ctypes.c_size_t, ctypes.c_size_t, ctypes.c_ulong]
        for fn in ("waveInPrepareHeader", "waveInUnprepareHeader", "waveInAddBuffer"):
            getattr(w, fn).argtypes = [ctypes.c_void_p, ctypes.POINTER(WAVEHDR), ctypes.c_uint]
        for fn in ("waveInStart", "waveInStop", "waveInReset", "waveInClose"):
            getattr(w, fn).argtypes = [ctypes.c_void_p]
        _winmm = w
    return _winmm


def list_mics():
    """[(device id, name, safe)] for every waveIn device. Names are cut at 31 characters by Windows."""
    w = winmm()
    out = []
    for i in range(w.waveInGetNumDevs()):
        caps = WAVEINCAPSW()
        if w.waveInGetDevCapsW(i, ctypes.byref(caps), ctypes.sizeof(caps)) == 0:
            out.append((i, caps.szPname, mic_is_safe(caps.szPname)))
    return out


def pick_mic(want=""):
    """The device to open: the first safe one whose name contains `want` (or any safe one)."""
    mics = list_mics()
    safe = [m for m in mics if m[2]]
    if want:
        hit = [m for m in safe if want.lower() in m[1].lower()]
        if hit:
            return hit[0]
        if any(want.lower() in m[1].lower() for m in mics):
            raise RuntimeError(f"'{want}' looks like a headset mic; Adaptive never opens one (it means call mode)")
    if not safe:
        raise RuntimeError("no safe microphone on this PC (only headset mics); plug in a USB mic or webcam")
    return safe[0]


class WaveInCapture:
    NBUF, BUF_S = 8, 0.1

    def __init__(self, want="", rate=ca.RATE):
        dev, name, safe = pick_mic(want)
        if not safe:                                  # belt and braces: pick_mic only returns safe ones
            raise RuntimeError("refusing a headset microphone")
        self.label, self.w, self.rate = name, winmm(), rate
        fmt = WAVEFORMATEX(WAVE_FORMAT_PCM, 1, rate, rate * 2, 2, 16, 0)
        self.h = ctypes.c_void_p()
        rc = self.w.waveInOpen(ctypes.byref(self.h), dev, ctypes.byref(fmt), 0, 0, CALLBACK_NULL)
        if rc != 0:
            raise RuntimeError(f"waveInOpen({name}) failed: MMRESULT {rc}")
        n = int(rate * self.BUF_S) * 2
        self.bufs = [ctypes.create_string_buffer(n) for _ in range(self.NBUF)]
        self.hdrs = [WAVEHDR() for _ in range(self.NBUF)]
        for b, hd in zip(self.bufs, self.hdrs):
            hd.lpData, hd.dwBufferLength = ctypes.cast(b, ctypes.c_void_p), n
            self._queue(hd)
        self.i = 0
        self.leftover = b""
        self.w.waveInStart(self.h)

    def _queue(self, hd):
        hd.dwFlags, hd.dwBytesRecorded = 0, 0
        self.w.waveInPrepareHeader(self.h, ctypes.byref(hd), ctypes.sizeof(hd))
        self.w.waveInAddBuffer(self.h, ctypes.byref(hd), ctypes.sizeof(hd))

    def read(self, n):
        import numpy as np
        want, data, t0 = n * 2, self.leftover, time.monotonic()
        while len(data) < want:
            if self.h is None:
                return None
            hd = self.hdrs[self.i]
            if hd.dwFlags & WHDR_DONE:
                data += ctypes.string_at(hd.lpData, hd.dwBytesRecorded)
                self.w.waveInUnprepareHeader(self.h, ctypes.byref(hd), ctypes.sizeof(hd))
                self._queue(hd)
                self.i = (self.i + 1) % self.NBUF
                t0 = time.monotonic()
            elif time.monotonic() - t0 > 3:
                return None                           # the device stopped delivering
            else:
                time.sleep(0.01)
        self.leftover = data[want:]
        return np.frombuffer(data[:want], dtype="<i2").astype(np.float32) / 32768.0

    def close(self):
        h, self.h = self.h, None
        if h:
            try:
                self.w.waveInReset(h)
                for hd in self.hdrs:
                    self.w.waveInUnprepareHeader(h, ctypes.byref(hd), ctypes.sizeof(hd))
                self.w.waveInClose(h)
            except Exception:
                pass


# ---------------------------------------------------------------------------------------------------
# The Crusher side

class CrusherLink:
    """Per-batch RFCOMM sessions through crusher_win.py, plus what this firmware answers (cached while
    the Crusher stays connected)."""

    def __init__(self):
        import crusher_win
        self.cw = crusher_win
        self.level_ok = None
        self.lock = threading.Lock()

    def connected(self):
        c = bool(self.cw.bt_connected())
        if not c:
            self.level_ok = None
        return c

    def _session(self, cmds):
        with self.lock:
            c = self.cw.Crusher()
            try:
                return [c.cmd(x) for x in cmds]
            finally:
                c.close()

    def send(self, cmds):
        rs = self._session(cmds)
        bad = [c for c, r in zip(cmds, rs) if "ERROR" in r or not r.strip()]
        if bad:
            raise RuntimeError("Crusher refused " + ", ".join(bad))

    def has_level(self):
        if self.level_ok is None:
            try:
                self.level_ok = self.cw._ok(self._session(["AT.UIAUDIO=transparency"])[0])
            except Exception:
                return False
        return self.level_ok

    def read_mode(self):
        txt = self._session(["AT.UIAUDIO?"])[0]
        if "anc" not in txt and "transparency" not in txt:
            return None
        return ca.mode_of({"anc": bool(re.search(r"anc:\s*on", txt)),
                           "transparency": bool(re.search(r"transparency:\s*on", txt))})

    def duck(self, on):
        self.send([f"AT.VOLUME={'down' if on else 'up'},{DUCK_STEPS}"])


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


def make_runner():
    link = CrusherLink()
    return ca.AdaptiveRunner(
        open_capture=lambda mic: WaveInCapture(mic), send=link.send, read_mode=link.read_mode,
        connected=link.connected, has_level=link.has_level, gate=None, duck=link.duck,
        load=load_settings, save=save_settings, log=lambda m: print(time.strftime("%H:%M:%S"), m, flush=True))


# ---------------------------------------------------------------------------------------------------
# Local server (127.0.0.1 only) and CLI

SETTABLE = ("enabled", "sensitivity", "conversation", "duck", "mic", "pause_min")


def serve():
    runner = make_runner().start()

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _j(self, code, obj):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("content-type", "application/json")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/state":
                st = runner.state()
                try:
                    st["mics"] = [{"name": n, "safe": s} for _, n, s in list_mics()]
                except Exception:
                    pass
                return self._j(200, st)
            self._j(404, {})

        def do_POST(self):
            n = int(self.headers.get("content-length", 0))
            body = self.rfile.read(n).decode() if n else ""
            try:
                if self.path == "/enable":
                    return self._j(200, runner.set(enabled=True))
                if self.path == "/disable":
                    return self._j(200, runner.set(enabled=False))
                if self.path == "/manual":
                    runner.manual("changed on this PC")
                    return self._j(200, runner.state())
                if self.path == "/set":
                    j = json.loads(body or "{}")
                    return self._j(200, runner.set(**{k: v for k, v in j.items() if k in SETTABLE}))
            except Exception as e:
                return self._j(500, {"error": str(e)})
            self._j(404, {})

    print(f"crusher adaptive on {URL}", flush=True)
    try:
        ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
    finally:
        runner.shutdown()


def _call(path, data=None, timeout=3):
    """GET when data is None, else POST (bytes as they are, anything else as JSON)."""
    if data is not None and not isinstance(data, bytes):
        data = json.dumps(data).encode()
    req = urllib.request.Request(URL + path, data=data, method="GET" if data is None else "POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def _running():
    try:
        _call("/state", timeout=1)
        return True
    except Exception:
        return False


def _start_daemon():
    exe = sys.executable
    pyw = os.path.join(os.path.dirname(exe), "pythonw.exe")
    flags = 0x00000008 | 0x00000200 | 0x08000000   # DETACHED_PROCESS | NEW_PROCESS_GROUP | NO_WINDOW
    subprocess.Popen([pyw if os.path.exists(pyw) else exe, os.path.abspath(__file__), "serve"],
                     creationflags=flags, close_fds=True, cwd=HERE)
    for _ in range(40):
        if _running():
            return True
        time.sleep(0.25)
    return False


def _parse_kv(args):
    out = {}
    for a in args:
        k, _, v = a.partition("=")
        if k in ("conversation", "duck", "enabled"):
            out[k] = v.lower() in ("1", "on", "true", "yes")
        elif k in ("sensitivity", "pause_min"):
            out[k] = int(v)
        elif k == "mic":
            out[k] = v
    return out


def measure(secs):
    cap = WaveInCapture(load_settings().get("mic", ""))
    print(f"listening on: {cap.label} (nothing is recorded)")
    m = ca.AmbientMeter()
    pol = ca.Policy(ca.Settings(load_settings()))
    try:
        t_end = time.monotonic() + secs
        while time.monotonic() < t_end:
            x = cap.read(ca.RATE // 4)
            if x is None:
                break
            for r in m.feed(x):
                pol.zone = pol.classify(r.level)
                print(f"room {r.level:6.1f} dB (now {r.db:6.1f})  {pol.zone:8s}  steady={'yes' if r.steady else 'no '}"
                      f"  voices={'yes' if r.speech else 'no '}", flush=True)
    finally:
        cap.close()


def main():
    a = sys.argv[1:] or ["status"]
    cmd = a[0]
    if cmd == "serve":
        return serve()
    if cmd == "mics":
        for i, name, safe in list_mics():
            print(f"{i}: {name}  {'ok' if safe else 'NEVER USED (headset / Bluetooth: call mode)'}")
        return
    if cmd == "measure":
        return measure(float(a[1]) if len(a) > 1 else 20)
    if cmd in ("on", "off", "set") and not _running():
        if cmd == "off":
            s = load_settings()
            s["enabled"] = False
            save_settings(s)
            print(json.dumps({"enabled": False}))
            return
        if not _start_daemon():
            sys.exit("could not start the Adaptive daemon (run: python crusher_adaptive_win.py serve)")
    try:
        if cmd == "on":
            st = _call("/enable", b"")
        elif cmd == "off":
            st = _call("/disable", b"")
        elif cmd == "manual":
            st = _call("/manual", b"")
        elif cmd == "set":
            st = _call("/set", _parse_kv(a[1:]))
        elif cmd == "status":
            st = _call("/state")
        else:
            print(__doc__)
            sys.exit(2)
    except Exception as e:
        if cmd in ("status", "manual"):
            print(json.dumps({"running": False, "enabled": bool(load_settings().get("enabled"))}))
            return
        sys.exit(f"Adaptive daemon not reachable: {e}")
    print(json.dumps(st))


if __name__ == "__main__":
    main()
