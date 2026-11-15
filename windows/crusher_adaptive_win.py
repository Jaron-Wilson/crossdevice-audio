#!/usr/bin/env python3
r"""Crusher Adaptive on WINDOWS: the PC's own microphone hears the room, the Crusher's mode follows.

Same decision as the laptop panel (shared/crusher_adaptive.py); this file is the Windows side:
  * the mic is read with winmm's waveIn through ctypes (no extra package besides numpy). As the room mic,
    only a mic that is NOT a Bluetooth headset is opened: opening the Crusher's (or any headset's)
    Hands-Free mic switches it to call mode. Names with Hands-Free / Headset / Bluetooth / Crusher /
    AirPods are refused, and the system default (WAVE_MAPPER) is never used, because it can be the
    headset. Virtual inputs (VB-Audio CABLE, Voicemeeter, Stereo Mix, Steam Streaming Mic) are not picked
    automatically either: they carry the PC's own sound, not the room.
  * optional, off by default (setting headset_mic): the Crusher's own mic, opened only once nothing has
    played on the PC for 10 s and closed within about a second when something plays (Windows' audio
    meters, read through ctypes COM). While it is open the Crusher is in call mode (HFP).
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
  python  crusher_adaptive_win.py measure --headset [secs]   # the same through the Crusher's mic (call mode
                                                             # while it runs; refused while something plays)
  python  crusher_adaptive_win.py playing        # is anything playing on this PC right now (audio meters)

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
# inputs that carry the PC's own sound, not the room: never picked automatically (still usable by name)
VIRTUAL_MIC = re.compile(r"(?i)cable output|vb-audio|voicemeeter|stereo mix|what u hear|wave out|"
                         r"steam streaming|virtual|loopback|nvidia broadcast")
# the Crusher's own (Hands-Free) mic, for the headset_mic option only
CRUSHER_MIC = re.compile(os.environ.get("CRUSHER_MIC_NAME", r"(?i)crusher|skullcandy"))
DUCK_STEPS = 4


def mic_is_safe(name):
    return not UNSAFE_MIC.search(name or "")


def mic_is_virtual(name):
    return bool(VIRTUAL_MIC.search(name or ""))


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
            raise RuntimeError(f"'{want}' looks like a headset mic; as the room mic Adaptive never opens one "
                               "(it means call mode). Use the headset_mic setting instead")
    room = [m for m in safe if not mic_is_virtual(m[1])]
    if not room:
        raise RuntimeError("no room microphone on this PC (only headset or virtual inputs); plug in a USB mic "
                           "or webcam, or turn on headset_mic")
    return room[0]


def crusher_mic():
    """The Crusher's Hands-Free mic as a waveIn device (id, name), or None."""
    for i, name, _safe in list_mics():
        if CRUSHER_MIC.search(name):
            return i, name
    return None


class WaveInCapture:
    NBUF, BUF_S = 8, 0.1

    def __init__(self, want="", rate=ca.RATE, headset=False):
        if headset:                                   # the headset_mic option: the Crusher's own mic
            hit = crusher_mic()
            if hit is None:
                raise RuntimeError("the Crusher's Hands-Free mic is not in the PC's input list")
            dev, name = hit
            name += " (call mode)"
        else:
            dev, name, safe = pick_mic(want)
            if not safe:                              # belt and braces: pick_mic only returns safe ones
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
# Is anything playing? The peak meters of the active output endpoints (Core Audio, ctypes COM)

class _GUID(ctypes.Structure):
    _fields_ = [("Data1", ctypes.c_ulong), ("Data2", ctypes.c_ushort), ("Data3", ctypes.c_ushort),
                ("Data4", ctypes.c_ubyte * 8)]

    def __init__(self, s):
        import uuid
        b = uuid.UUID(s).bytes
        super().__init__(int.from_bytes(b[0:4], "big"), int.from_bytes(b[4:6], "big"),
                         int.from_bytes(b[6:8], "big"), (ctypes.c_ubyte * 8)(*b[8:]))


CLSID_MMDeviceEnumerator = "BCDE0395-E52F-467C-8E3D-C4579291692E"
IID_IMMDeviceEnumerator = "A95664D2-9614-4F35-A746-DE8DB63617E6"
IID_IAudioMeterInformation = "C02216F6-8C67-4B5B-9D00-D008E73E0064"
PLAYING_PEAK = 1e-4          # a peak above this on any active output = something is playing


def _vcall(obj, index, restype, *argtypes):
    """A COM method by its vtable slot."""
    vtbl = ctypes.cast(obj, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    return ctypes.WINFUNCTYPE(restype, ctypes.c_void_p, *argtypes)(vtbl[index])


def _release(obj):
    if obj:
        _vcall(obj, 2, ctypes.c_ulong)(obj)


_com_threads = set()


def output_peaks():
    """[peak 0..1] of every active output endpoint right now."""
    ole = ctypes.OleDLL("ole32")
    tid = threading.get_ident()
    if tid not in _com_threads:
        try:
            ole.CoInitializeEx(None, 0)              # COINIT_MULTITHREADED
        except OSError:
            pass                                      # already initialised differently on this thread: fine
        _com_threads.add(tid)
    enum = ctypes.c_void_p()
    ole.CoCreateInstance(ctypes.byref(_GUID(CLSID_MMDeviceEnumerator)), None, 0x17,
                         ctypes.byref(_GUID(IID_IMMDeviceEnumerator)), ctypes.byref(enum))
    coll = ctypes.c_void_p()
    peaks = []
    try:
        hr = _vcall(enum, 3, ctypes.c_long, ctypes.c_int, ctypes.c_ulong, ctypes.POINTER(ctypes.c_void_p))(
            enum, 0, 1, ctypes.byref(coll))          # eRender, DEVICE_STATE_ACTIVE
        if hr != 0:
            raise OSError(f"EnumAudioEndpoints failed: {hr:#x}")
        n = ctypes.c_uint()
        _vcall(coll, 3, ctypes.c_long, ctypes.POINTER(ctypes.c_uint))(coll, ctypes.byref(n))
        for i in range(n.value):
            dev, meter = ctypes.c_void_p(), ctypes.c_void_p()
            try:
                _vcall(coll, 4, ctypes.c_long, ctypes.c_uint, ctypes.POINTER(ctypes.c_void_p))(
                    coll, i, ctypes.byref(dev))
                hr = _vcall(dev, 3, ctypes.c_long, ctypes.POINTER(_GUID), ctypes.c_ulong, ctypes.c_void_p,
                            ctypes.POINTER(ctypes.c_void_p))(
                    dev, ctypes.byref(_GUID(IID_IAudioMeterInformation)), 0x17, None, ctypes.byref(meter))
                if hr == 0:
                    p = ctypes.c_float()
                    _vcall(meter, 3, ctypes.c_long, ctypes.POINTER(ctypes.c_float))(meter, ctypes.byref(p))
                    peaks.append(float(p.value))
            finally:
                _release(meter)
                _release(dev)
    finally:
        _release(coll)
        _release(enum)
    return peaks


def media_playing():
    """True while anything plays on this PC (any active output's peak meter moves)."""
    return any(p > PLAYING_PEAK for p in output_peaks())


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
        load=load_settings, save=save_settings, log=lambda m: print(time.strftime("%H:%M:%S"), m, flush=True),
        open_headset=lambda: WaveInCapture(headset=True), media_playing=media_playing)


# ---------------------------------------------------------------------------------------------------
# Local server (127.0.0.1 only) and CLI

SETTABLE = ("enabled", "sensitivity", "conversation", "duck", "mic", "pause_min", "headset_mic")


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
                    st["mics"] = [{"name": n, "safe": s, "virtual": mic_is_virtual(n)} for _, n, s in list_mics()]
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
        if k in ("conversation", "duck", "enabled", "headset_mic"):
            out[k] = v.lower() in ("1", "on", "true", "yes")
        elif k in ("sensitivity", "pause_min"):
            out[k] = int(v)
        elif k == "mic":
            out[k] = v
    return out


def measure(secs, headset=False):
    if headset and media_playing():
        sys.exit("something is playing on this PC; the headset mic is only opened while nothing plays")
    cap = WaveInCapture(load_settings().get("mic", ""), headset=headset)
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
            if CRUSHER_MIC.search(name):
                what = "the Crusher's mic: only with headset_mic, while nothing plays (call mode)"
            elif not safe:
                what = "NEVER USED (headset / Bluetooth: call mode)"
            elif mic_is_virtual(name):
                what = "virtual input (the PC's own sound): only if you name it"
            else:
                what = "ok"
            print(f"{i}: {name}  {what}")
        return
    if cmd == "measure":
        hs = "--headset" in a
        rest = [x for x in a[1:] if x != "--headset"]
        return measure(float(rest[0]) if rest else 20, headset=hs)
    if cmd == "playing":
        peaks = output_peaks()
        print(json.dumps({"playing": any(p > PLAYING_PEAK for p in peaks), "peaks": [round(p, 5) for p in peaks]}))
        return
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
