#!/usr/bin/env python3
r"""Skullcandy Crusher ANC 2 controller for WINDOWS.

CPython's socket.AF_BLUETOOTH is Linux-only, so this talks RFCOMM channel 9
(the Crusher's BOS_AT, plain-text AT commands) directly through Winsock AF_BTH
via ctypes. Same AT protocol as laptop/panel.py.

Usage:
  python crusher_win.py state
  python crusher_win.py anc on|off
  python crusher_win.py transparency on|off
  python crusher_win.py bass <0-10>
  python crusher_win.py crusher on|off
"""
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


def main():
    a = sys.argv[1:] or ["state"]
    c = Crusher()
    try:
        if a[0] == "state":
            print(c.cmd("AT.UIAUDIO?"))
            print(c.cmd("AT.CRUSHER?"))
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
