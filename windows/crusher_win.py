#!/usr/bin/env python3
r"""Skullcandy Crusher ANC 2 controller for WINDOWS.

CPython's socket.AF_BLUETOOTH is Linux-only, so this talks RFCOMM channel 9
(the Crusher's BOS_AT, plain-text AT commands) directly through Winsock AF_BTH
via ctypes. Same AT protocol as laptop/panel.py.

Usage:
  python crusher_win.py state [--json]
  python crusher_win.py anc on|off
  python crusher_win.py transparency on|off
  python crusher_win.py bass <0-10>
  python crusher_win.py crusher on|off            # sensory bass on/off
  python crusher_win.py transparency-level <0-100>
  python crusher_win.py atoncall on|off           # hear-through during calls
  python crusher_win.py atonpause on|off          # hear-through while paused
  python crusher_win.py anc-filter <n>|off        # ANC filter id (range not known)
  python crusher_win.py volume up|down [steps]
  python crusher_win.py probe                     # read-only: which extra commands this firmware answers

The extras come from Skull-iQ's AT code (generic Bragi AT commands); not every firmware answers all of
them, so `state` only shows the ones that replied OK. See docs/PROTOCOL.md.
"""
import json
import re
import os
import ctypes
import sys
import time

AF_BTH = 32
SOCK_STREAM = 1
BTHPROTO_RFCOMM = 3
SOL_SOCKET = 0xFFFF
SO_RCVTIMEO = 0x1006
SO_SNDTIMEO = 0x1005
INVALID_SOCKET = ctypes.c_void_p(-1).value

MAC_INT = int(os.environ.get("CRUSHER_MAC", "00:00:00:00:00:00").replace(":", ""), 16)  # set CRUSHER_MAC
CH = 9

ws2 = ctypes.WinDLL("ws2_32.dll")


class GUID(ctypes.Structure):
    _fields_ = [("Data1", ctypes.c_ulong), ("Data2", ctypes.c_ushort),
                ("Data3", ctypes.c_ushort), ("Data4", ctypes.c_ubyte * 8)]


class SOCKADDR_BTH(ctypes.Structure):
    _fields_ = [("addressFamily", ctypes.c_ushort), ("btAddr", ctypes.c_ulonglong),
                ("serviceClassId", GUID), ("port", ctypes.c_ulong)]


ws2.socket.restype = ctypes.c_void_p
ws2.socket.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int]
ws2.connect.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int]
ws2.send.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
ws2.recv.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
ws2.closesocket.argtypes = [ctypes.c_void_p]
ws2.setsockopt.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_int]


def _startup():
    buf = ctypes.create_string_buffer(512)
    rc = ws2.WSAStartup(0x0202, buf)
    if rc != 0:
        raise OSError(f"WSAStartup failed: {rc}")


class Crusher:
    def __init__(self, addr=MAC_INT, ch=CH, timeout_ms=6000):
        _startup()
        self.s = ws2.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if not self.s or self.s == INVALID_SOCKET:
            raise OSError(f"socket() failed, WSAGetLastError={ws2.WSAGetLastError()}")
        for opt in (SO_RCVTIMEO, SO_SNDTIMEO):
            t = ctypes.c_ulong(timeout_ms)
            ws2.setsockopt(self.s, SOL_SOCKET, opt, ctypes.byref(t), ctypes.sizeof(t))
        sa = SOCKADDR_BTH()
        sa.addressFamily = AF_BTH
        sa.btAddr = addr
        sa.port = ch
        if ws2.connect(self.s, ctypes.byref(sa), ctypes.sizeof(sa)) != 0:
            err = ws2.WSAGetLastError()
            ws2.closesocket(self.s)
            raise OSError(f"connect() failed, WSAGetLastError={err} "
                          "(10060=timeout/not reachable, 10050/10064=device off or not connected to this PC)")

    def cmd(self, c):
        data = c.encode() + b"\r"
        ws2.send(self.s, data, len(data), 0)
        time.sleep(0.35)
        bufp = ctypes.create_string_buffer(1024)
        out = b""
        start = time.time()
        while time.time() - start < 3.5:
            n = ws2.recv(self.s, bufp, 1024, 0)
            if n <= 0:
                break
            out += bufp.raw[:n]
            if b"OK" in out or b"ERROR" in out:
                break
        return out.decode(errors="replace").strip()

    def close(self):
        try:
            ws2.closesocket(self.s)
        except Exception:
            pass


EXTRA_Q = {"transparency_level": "AT.UIAUDIO=transparency", "at_on_call": "AT.UIAUDIO=atoncall",
           "at_on_pause": "AT.UIAUDIO=atonpause", "anc_filter": "AT.UIAUDIO=anc_filter"}


def _ok(r):
    return "OK" in r and "ERROR" not in r


def read_state(c):
    """The same fields as the laptop panel's /api/state 'crusher' object."""
    txt = c.cmd("AT.UIAUDIO?") + "\n" + c.cmd("AT.CRUSHER?")
    lvl = re.search(r"level:\s*(\d+)\s*/\s*(\d+)", txt)
    st = re.search(r"(?i)state:\s*(on|off)", txt)
    out = {"online": True,
           "anc": bool(re.search(r"anc:\s*on", txt)),
           "transparency": bool(re.search(r"transparency:\s*on", txt)),
           "bass": int(lvl.group(1)) if lvl else None,
           "bass_max": int(lvl.group(2)) if lvl else 10,
           "bass_on": st.group(1).lower() == "on" if st else None,
           "extras": {}}
    for k, q in EXTRA_Q.items():
        r = c.cmd(q)
        if not _ok(r):
            continue
        if k == "transparency_level":
            m = re.search(r":\s*(?:on|off)?\s*,\s*(\d+)", r)
            if m: out["extras"][k] = int(m.group(1))
        elif k == "anc_filter":
            m = re.search(r":\s*(\d+|off)", r)
            if m: out["extras"][k] = m.group(1) if m.group(1) == "off" else int(m.group(1))
        else:
            m = re.search(r":\s*(on|off)", r)
            if m: out["extras"][k] = m.group(1) == "on"
    return out


def main():
    a = sys.argv[1:] or ["state"]
    c = Crusher()
    try:
        if a[0] == "state":
            if "--json" in a:
                print(json.dumps(read_state(c)))
            else:
                print(c.cmd("AT.UIAUDIO?"))
                print(c.cmd("AT.CRUSHER?"))
        elif a[0] == "probe":
            for q in ["AT.UIAUDIO?", "AT.CRUSHER?", *EXTRA_Q.values(), "AT.FEATURE=count"]:
                print(f"{q!r} -> {c.cmd(q)!r}")
        elif a[0] == "transparency-level":
            print(c.cmd(f"AT.UIAUDIO=transparency,,{max(0, min(100, int(a[1])))}"))
        elif a[0] in ("atoncall", "atonpause"):
            print(c.cmd(f"AT.UIAUDIO={a[0]},{'on' if a[1] == 'on' else 'off'}"))
        elif a[0] == "anc-filter":
            v = "off" if a[1] == "off" else str(int(a[1]))
            print(c.cmd(f"AT.UIAUDIO=anc_filter,{v}"))
        elif a[0] == "volume":
            steps = int(a[2]) if len(a) > 2 else 1
            print(c.cmd(f"AT.VOLUME={'up' if a[1] == 'up' else 'down'},{steps}"))
        elif a[0] == "anc":
            print(c.cmd(f"AT.UIAUDIO=anc,{a[1]}"))
        elif a[0] == "transparency":
            print(c.cmd(f"AT.UIAUDIO=transparency,{a[1]}"))
        elif a[0] == "bass":
            lvl = max(0, min(10, int(a[1])))
            print(c.cmd(f"AT.CRUSHER=level,{lvl}"))
        elif a[0] == "crusher":
            print(c.cmd(f"AT.CRUSHER=state,{a[1]}"))
        else:
            print("unknown cmd")
            sys.exit(2)
    finally:
        c.close()


if __name__ == "__main__":
    main()
