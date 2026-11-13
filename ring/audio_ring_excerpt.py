"""Excerpt from the Alt+Q "flow" ring (a plugin of my flowlink app, not published here).

Only the Audio part is shown:
  * fan geometry: the Audio wedge "pies out" into an outer band of choices, picked by angle;
  * audio_children(): the fan only offers controls a connected device can actually do;
  * eq_cycle(): the "EQ" wedge steps through the shared EQ presets (laptop: panel API, PC: Equalizer APO);
  * win_bt_connected(): driver-free "is this headset connected?" on Windows (BluetoothApis, ~1 ms);
  * a background poller, so opening the ring never waits on Bluetooth.
"""
import json
import math
import threading
import time

R_OUT = 148
R_IN = 62
R_SUB_IN = 156
R_SUB_OUT = 216
SUB_FAN_DEG = 212.0        # total arc the children spread across, centred on the parent
SUB_GAP_DEG = 2.0
WEDGE_ANGLE = {"right": 0.0, "down": 90.0, "left": 180.0, "up": 270.0}
MAX_FAN = 12               # beyond this the slices get too thin to flick reliably (212 deg / 12 = ~18 deg)


# ---- fan geometry ---------------------------------------------------------

def sub_arcs(parent: str, n: int) -> list[tuple[float, float]]:
    """Angular (a0, a1) spans for ``n`` child slices fanned around ``parent``'s wedge."""
    if n <= 0:
        return []
    base = WEDGE_ANGLE[parent]
    a0 = base - SUB_FAN_DEG / 2
    step = SUB_FAN_DEG / n
    return [(a0 + step * i, a0 + step * (i + 1)) for i in range(n)]


def child_from_offset(parent: str, n: int, dx: float, dy: float) -> int | None:
    """Index of the hovered child slice for a cursor offset, or None if outside the fan."""
    if n <= 0:
        return None
    ang = math.degrees(math.atan2(dy, dx))
    base = WEDGE_ANGLE[parent]
    # fold the angle into [base-180, base+180) so the fan never straddles the 0/360 seam
    rel = (ang - base + 180.0) % 360.0 - 180.0
    if abs(rel) > SUB_FAN_DEG / 2:
        return None
    idx = int((rel + SUB_FAN_DEG / 2) / (SUB_FAN_DEG / n))
    return max(0, min(n - 1, idx))


# ---- which controls exist ---------------------------------------------------

CRUSHER_ADDR = 0xAABBCCDDEE01          # Skullcandy Crusher ANC 2: ANC, transparency, bass
AIRPODS_ADDR = 0xAABBCCDDEE02          # AirPods Pro 2: ANC, transparency, adaptive (no bass)
HP_PANEL_LOCAL = "http://127.0.0.1:8970"                        # panel.py on the laptop
HP_PANEL_LAPTOP = "https://your-laptop.your-tailnet.ts.net:8970"  # same panel, from the PC
EQ_APO_SCRIPT = r"C:\path\to\crossdevice-audio\windows\eq_apo.py"  # PC: writes Equalizer APO config


def audio_children(caps: dict, linux: bool) -> list[dict]:
    """The Audio fan's items. Volume and transport always; a headphone control only when a
    connected device can actually do it (bass = Crusher only, adaptive = AirPods only).
    The EQ wedge appears when a source-side EQ exists on this machine (caps["eq"]) and the fan has room."""
    cr, ap = bool(caps.get("crusher")), bool(caps.get("airpods"))
    who = "both" if cr and ap else ("Crusher" if cr else "AirPods")
    kids = [{"key": "voldown", "title": "Vol −", "sub": "quieter", "icon": "voldown"},
            {"key": "volup", "title": "Vol +", "sub": "louder", "icon": "volup"}]
    if cr or ap:
        kids += [{"key": "anc", "title": "ANC", "sub": who, "icon": "sound"},
                 {"key": "transparency", "title": "Clear", "sub": who, "icon": "sound"}]
    if ap:
        kids.append({"key": "adaptive", "title": "Adaptive", "sub": "AirPods", "icon": "sound"})
    if cr:
        kids += [{"key": "bassdown", "title": "Bass −", "sub": "Crusher", "icon": "sound"},
                 {"key": "bassup", "title": "Bass +", "sub": "Crusher", "icon": "sound"}]
    kids += [{"key": "prev", "title": "Prev", "sub": "track", "icon": "prev"},
             {"key": "playpause", "title": "Play/Pause", "sub": "laptop" if linux else "media", "icon": "play"},
             {"key": "next", "title": "Next", "sub": "track", "icon": "next"}]
    if linux:
        kids.append({"key": "output", "title": "Output", "sub": "switch", "icon": "sound"})
    if caps.get("eq") and len(kids) < MAX_FAN:
        # one wedge that cycles Flat -> Bass boost -> Vocal -> Treble; the sub-label shows the current one
        kids.insert(len(kids) - (4 if linux else 3),
                    {"key": "eq", "title": "EQ", "sub": caps.get("eq_preset") or "preset", "icon": "sound"})
    return kids


def eq_cycle(linux: bool) -> str:
    """Next EQ preset. Returns its label for the ring's toast."""
    if linux:
        import urllib.request
        req = urllib.request.Request(HP_PANEL_LOCAL + "/api/eq/cycle", data=b"", method="POST")
        with urllib.request.urlopen(req, timeout=8) as r:
            st = json.loads(r.read().decode())
    else:  # pragma: no cover - needs Windows + Equalizer APO
        import subprocess
        import sys
        out = subprocess.run([sys.executable, EQ_APO_SCRIPT, "cycle"], capture_output=True, text=True, timeout=10)
        st = json.loads(out.stdout.strip().splitlines()[-1])
    labels = {"flat": "Flat", "bass": "Bass boost", "vocal": "Vocal", "treble": "Treble"}
    return labels.get(st.get("preset"), "Custom")


def win_bt_connected(addr: int) -> bool:  # pragma: no cover - needs Windows
    """True if this paired classic device has a live link to the PC's radio (BluetoothApis, ~1 ms)."""
    import ctypes
    from ctypes import wintypes

    class SYSTEMTIME(ctypes.Structure):
        _fields_ = [(n, ctypes.c_ushort) for n in ("y", "mo", "dow", "d", "h", "mi", "s", "ms")]

    class BDI(ctypes.Structure):
        _fields_ = [("dwSize", wintypes.DWORD), ("Address", ctypes.c_ulonglong), ("cod", wintypes.ULONG),
                    ("fConnected", wintypes.BOOL), ("fRemembered", wintypes.BOOL),
                    ("fAuthenticated", wintypes.BOOL), ("seen", SYSTEMTIME), ("used", SYSTEMTIME),
                    ("name", ctypes.c_wchar * 248)]

    class FRP(ctypes.Structure):
        _fields_ = [("dwSize", wintypes.DWORD)]

    bt = ctypes.WinDLL("BluetoothApis.dll")
    k32 = ctypes.WinDLL("kernel32")
    bt.BluetoothFindFirstRadio.restype = wintypes.HANDLE
    bt.BluetoothFindFirstRadio.argtypes = [ctypes.POINTER(FRP), ctypes.POINTER(wintypes.HANDLE)]
    bt.BluetoothFindRadioClose.argtypes = [wintypes.HANDLE]
    bt.BluetoothGetDeviceInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(BDI)]
    k32.CloseHandle.argtypes = [wintypes.HANDLE]
    p, h = FRP(ctypes.sizeof(FRP)), wintypes.HANDLE()
    find = bt.BluetoothFindFirstRadio(ctypes.byref(p), ctypes.byref(h))
    if not find:
        return False
    try:
        info = BDI()
        info.dwSize, info.Address = ctypes.sizeof(BDI), addr
        return bt.BluetoothGetDeviceInfo(h, ctypes.byref(info)) == 0 and bool(info.fConnected)
    finally:
        bt.BluetoothFindRadioClose(find)
        k32.CloseHandle(h)


def _win_has_apo() -> bool:  # pragma: no cover - needs Windows
    """Equalizer APO installed? (Its config folder exists.) No APO, no EQ wedge."""
    import os
    return os.path.isdir(os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "EqualizerAPO", "config"))


def _panel_caps(base: str, timeout: float) -> dict:
    import urllib.request
    with urllib.request.urlopen(base + "/api/caps", timeout=timeout) as r:
        return json.loads(r.read().decode())


# ---- polling (methods of the ring plugin) ----------------------------------

class HeadphoneCaps:
    """Keeps self._hp_caps fresh; the ring reads it when it opens."""

    def __init__(self, linux: bool, log=print):
        self.linux = linux
        self._hp_caps = {"crusher": False, "airpods": False}
        self.ctx = type("Ctx", (), {"log": staticmethod(lambda *a: log(a[0] % a[1:]))})()

    def _hp_caps_now(self) -> dict:
        if self.linux:
            c = _panel_caps(HP_PANEL_LOCAL, 3)
            return {"crusher": bool(c.get("crusher")), "airpods": bool(c.get("airpods")), "eq": bool(c.get("eq"))}
        caps = {"crusher": win_bt_connected(CRUSHER_ADDR), "airpods": False, "eq": _win_has_apo()}
        # AirPods modes go through the laptop, which holds their control link as a 2nd host.
        if win_bt_connected(AIRPODS_ADDR):
            caps["airpods"] = bool(_panel_caps(HP_PANEL_LAPTOP, 4).get("airpods"))
        return caps

    def _poll_hp_caps(self) -> None:
        last = None
        while True:
            try:
                caps = self._hp_caps_now()
            except Exception:  # noqa: BLE001 - panel down / radio off: no headphone controls
                caps = {"crusher": False, "airpods": False, "eq": False}
            self._hp_caps = caps
            if caps != last:
                self.ctx.log("flow: headphone controls: %s", caps)
                last = caps
            time.sleep(4)

    def start(self) -> None:
        threading.Thread(target=self._poll_hp_caps, daemon=True).start()
