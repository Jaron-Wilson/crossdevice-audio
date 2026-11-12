#!/usr/bin/env python3
"""Unified headphone control panel (runs on the laptop).
Crusher ANC 2  -> RFCOMM AT (channel 9), per-request.
AirPods Pro 2  -> Apple AAP over L2CAP PSM 0x1001, persistent background connection.
Serves 127.0.0.1:8970: one page + JSON API for both. Replaces librepods for AirPods."""
import json, re, socket, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import os
PORT = 8970
# Your devices live in ~/.config/headphone-panel/config.json (see config.example.json):
#   {"crusher_mac": "...", "crusher_channel": 9, "airpods_mac": "...", "phone_bt": "...", "airpods_model": "2420"}
CONFIG_FILE = os.path.expanduser("~/.config/headphone-panel/config.json")
try:
    CONFIG = json.load(open(CONFIG_FILE))
except (OSError, ValueError):
    CONFIG = {}
# ---- Crusher (RFCOMM AT) ----
CR_MAC = CONFIG.get("crusher_mac", ""); CR_CH = int(CONFIG.get("crusher_channel", 9))
CR_LOCK = threading.Lock()
def cr_at(cmds, timeout=5.0):
    return "\n".join(cr_at_each(cmds, timeout))
def cr_at_each(cmds, timeout=5.0):
    """Send AT commands over one RFCOMM session; returns one reply string per command."""
    last = None
    for _ in range(4):
        try:
            s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            s.settimeout(timeout); s.connect((CR_MAC, CR_CH)); break
        except OSError as e:
            last = e; time.sleep(0.5)
    else:
        raise last
    out = []
    try:
        for c in cmds:
            s.sendall(c.encode()+b"\r"); time.sleep(0.35); s.settimeout(3); buf=b""
            try:
                while True:
                    x=s.recv(1024)
                    if not x: break
                    buf+=x
                    if b"OK" in buf or b"ERROR" in buf: break
            except socket.timeout: pass
            out.append(buf.decode(errors="replace"))
    finally:
        s.close()
    return out
def crusher_connected():
    """Fast: ask BlueZ whether the Crusher has a link (no RFCOMM attempt, so no multi-second timeout)."""
    import subprocess
    try:
        out = subprocess.run(["bluetoothctl", "info", CR_MAC], capture_output=True, text=True, timeout=3).stdout
    except Exception:
        return False
    return "Connected: yes" in out
CR_CACHE = {"t": 0.0, "v": None}
def crusher_state(max_age=20.0):
    """Crusher state, cached: an RFCOMM query takes 1-2 s, and the widget/ring read this often."""
    if not crusher_connected():
        CR_CACHE["v"] = None; CR_EXTRAS["v"] = None
        return {"online": False, "error": "not connected"}
    if CR_CACHE["v"] is not None and time.time() - CR_CACHE["t"] < max_age:
        return CR_CACHE["v"]
    v = crusher_state_live()
    if v.get("online"):
        v["extras"] = crusher_extras()
        CR_CACHE.update(t=time.time(), v=v)
    return v
def crusher_cache_set(**kw):
    if CR_CACHE["v"]: CR_CACHE["v"] = {**CR_CACHE["v"], **kw}
def crusher_setting(name, value):
    """Set one of the Crusher extras. Returns the AT reply."""
    if name == "bass_on":
        cmd = f"AT.CRUSHER=state,{'on' if value else 'off'}"
    elif name == "transparency_level":
        cmd = f"AT.UIAUDIO=transparency,,{max(0, min(100, int(value)))}"
    elif name in ("at_on_call", "at_on_pause"):
        cmd = f"AT.UIAUDIO={'atoncall' if name == 'at_on_call' else 'atonpause'},{'on' if value else 'off'}"
    elif name == "anc_filter":
        v = "off" if value in ("off", None) else str(max(0, min(255, int(value))))
        cmd = f"AT.UIAUDIO=anc_filter,{v}"
    else:
        raise ValueError("unknown Crusher setting")
    with CR_LOCK: r = cr_at([cmd])
    if not _cr_answered(r):
        raise RuntimeError(f"Crusher refused {cmd}: {r.strip()[:80]}")
    if name == "bass_on":
        crusher_cache_set(bass_on=bool(value))
    else:
        if CR_EXTRAS["v"] is not None: CR_EXTRAS["v"][name] = value if name != "transparency_level" else int(value)
        if CR_CACHE["v"]: CR_CACHE["v"]["extras"] = CR_EXTRAS["v"] or {}
    return r
# Extras found in Skull-iQ's AT code (generic Bragi AT commands, see docs/PROTOCOL.md). Not every
# firmware implements them, so each is read once per connection and only offered if it answered.
CR_EXTRA_Q = {"transparency_level": "AT.UIAUDIO=transparency", "at_on_call": "AT.UIAUDIO=atoncall",
              "at_on_pause": "AT.UIAUDIO=atonpause", "anc_filter": "AT.UIAUDIO=anc_filter"}
CR_EXTRAS = {"v": None}
def _cr_answered(r):
    return "OK" in r and "ERROR" not in r
def parse_cr_extras(replies):
    out = {}
    for (k, _), r in zip(CR_EXTRA_Q.items(), replies):
        if not _cr_answered(r):
            continue
        if k == "transparency_level":
            m = re.search(r":\s*(?:on|off)?\s*,\s*(\d+)", r)
            if m: out[k] = int(m.group(1))
        elif k == "anc_filter":
            m = re.search(r":\s*(\d+|off)", r)
            if m: out[k] = m.group(1) if m.group(1) == "off" else int(m.group(1))
        else:
            m = re.search(r":\s*(on|off)", r)
            if m: out[k] = m.group(1) == "on"
    return out
def crusher_extras():
    if CR_EXTRAS["v"] is None:
        try:
            with CR_LOCK: CR_EXTRAS["v"] = parse_cr_extras(cr_at_each(list(CR_EXTRA_Q.values())))
        except Exception:
            return {}
    return CR_EXTRAS["v"]
def crusher_state_live():
    try:
        with CR_LOCK: txt = cr_at(["AT.UIAUDIO?","AT.CRUSHER?"])
    except Exception as e:
        return {"online": False, "error": str(e)}
    lvl = re.search(r"level:\s*(\d+)\s*/\s*(\d+)", txt)
    get = lambda p:(re.search(p,txt).group(1) if re.search(p,txt) else None)
    return {"online": True,
            "anc": get(r"anc:\s*(on|off)")=="on",
            "transparency": get(r"transparency:\s*(on|off)")=="on",
            "bass": int(lvl.group(1)) if lvl else None,
            "bass_on": (get(r"(?i)state:\s*(on|off)") or "").lower() == "on" if get(r"(?i)state:\s*(on|off)") else None,
            "bass_max": int(lvl.group(2)) if lvl else 10}

# ---- AirPods (AAP over L2CAP, persistent) ----
AP_MAC = CONFIG.get("airpods_mac", ""); PSM = 0x1001
HS=bytes.fromhex("00000400010002000000000000000000")
# notification mask: all ones, as LibrePods sends it
NOTIFY=bytes.fromhex("040004000F00FFFFFFFF"); FEAT=bytes.fromhex("040004004D00FF00000000000000")
# Reported after NOTIFY on AirPods Pro 2 (checked live): 0x0D 0x1B 0x26 0x28 0x2E and others. NOT reported:
# 0x0A ear detection, 0x1A press-and-hold modes, 0x34 allow Off -> the panel remembers what it last set.
NC=bytes.fromhex("0400040009000d"); BAT=bytes.fromhex("040004000400")
MODE={"off":1,"anc":2,"transparency":3,"adaptive":4}; MNAME={1:"off",2:"anc",3:"transparency",4:"adaptive"}
# AAP control commands (04 00 04 00 09 00 <id> <4 data bytes>), ids from LibrePods' docs/source.
# The AirPods push the current value of each after the notification request, and echo every change.
CTL_HDR = bytes.fromhex("040004000900")
AP_SETTINGS = {                       # name: (id, kind)
    "adaptive_level": (0x2E, "level"),            # 0-100: 0 lets the most noise in, 100 filters the most
    "conversational_awareness": (0x28, "bool"),
    "personalized_volume": (0x26, "bool"),
    "ear_detection": (0x0A, "bool"),
    "press_hold_modes": (0x1A, "mask"),           # which modes a stem press-and-hold cycles through
    "allow_off": (0x34, "bool"),                  # offer "Off" in that cycle
    "one_bud_anc": (0x1B, "bool"),                # noise cancelling with one AirPod in
}
MASK_BITS = {"off": 1, "anc": 2, "transparency": 4, "adaptive": 8}
def ap_encode(name, value):
    cid, kind = AP_SETTINGS[name]
    if kind == "bool":
        b = 1 if value in (True, 1, "1", "on", "true") else 2
    elif kind == "level":
        b = max(0, min(100, int(value)))
    else:
        modes = value if isinstance(value, list) else str(value).split(",")
        b = sum(MASK_BITS[m] for m in set(modes) if m in MASK_BITS)
        if bin(b).count("1") < 2:
            raise ValueError("press and hold needs at least two modes")
    return CTL_HDR + bytes([cid, b, 0, 0, 0])
RAW_LOG = "/tmp/aap-raw.log"
def raw_log(tag, p):
    """Append one AAP packet to the raw log (capped at ~1 MB, then truncated)."""
    try:
        if os.path.exists(RAW_LOG) and os.path.getsize(RAW_LOG) > 1_000_000:
            open(RAW_LOG, "w").close()
        with open(RAW_LOG, "a") as f: f.write(time.strftime("%T ") + tag + " " + p.hex() + "\n")
    except OSError:
        pass
# Settings the AirPods never reported but we set: remembered so the panel can show them (marked as such).
AP_REMEMBER = os.path.expanduser("~/.config/headphone-panel/airpods-settings.json")
def ap_remembered():
    try: return json.load(open(AP_REMEMBER))
    except (OSError, ValueError): return {}
def ap_remember(name, value):
    d = ap_remembered(); d[name] = value
    try:
        os.makedirs(os.path.dirname(AP_REMEMBER), exist_ok=True)
        with open(AP_REMEMBER, "w") as f: json.dump(d, f)
    except OSError:
        pass
def ap_decode(kind, data):
    v = data[0]
    if kind == "bool": return {1: True, 2: False}.get(v)
    if kind == "level": return v
    return [m for m, bit in MASK_BITS.items() if v & bit]
BT={2:"left",4:"right",8:"case"}; BS={1:"discharging",2:"charging",4:"disconnected"}
# ---- AirPods battery from their BLE "proximity pairing" broadcast (Apple 0x004C, type 0x07).
# Works whichever host is playing: the AAP battery reports only reach the active host.
BLE = {"battery": {}, "t": 0.0}
AP_MODEL = bytes.fromhex(CONFIG.get("airpods_model", "2420"))   # AirPods Pro 2 (USB-C) = 2420 (modalias p2024)
def ble_decode(m):
    h = m.hex()
    flip = (int(h[10], 16) & 0x02) == 0
    L, R = int(h[12 if flip else 13], 16), int(h[13 if flip else 12], 16)
    case, ch = int(h[15], 16), int(h[14], 16)
    out = {}
    for name, v, charging in (("left", L, ch & (0b10 if flip else 0b01)),
                              ("right", R, ch & (0b01 if flip else 0b10)),
                              ("case", case, ch & 0b100)):
        if v <= 10:
            out[name] = {"level": v * 10, "status": "charging" if charging else "discharging"}
    return out
def ble_loop():
    try:
        import asyncio
        from bleak import BleakScanner
    except Exception:
        return
    async def run():
        while True:
            found = {}
            def cb(d, adv):
                m = (adv.manufacturer_data or {}).get(0x004C)
                if m and len(m) >= 8 and m[0] == 0x07 and bytes(m[3:5]) == AP_MODEL:
                    found[d.address] = (adv.rssi, bytes(m))
            try:
                async with BleakScanner(cb):
                    await asyncio.sleep(6)
            except Exception:
                pass
            if found:
                b = ble_decode(max(found.values(), key=lambda x: x[0])[1])
                if b:
                    BLE.update(battery=b, t=time.time())
            await asyncio.sleep(40)
    asyncio.run(run())
threading.Thread(target=ble_loop, daemon=True).start()

# Hand-off with the phone: the phone and this laptop can't share the AirPods (each one joining kicks
# the other), so while the phone has them the laptop lets go. The phone's widget app reports it.
import os, subprocess
HANDOFF_FILE = os.path.expanduser("~/.cache/hp-handoff")
# your phone's Bluetooth address: lets the laptop step aside even when the phone can't reach it
PHONE_BT = bytes.fromhex(CONFIG.get("phone_bt", "").replace(":", "")) or b"\xff" * 7

class AirPods(threading.Thread):
    daemon = True
    def __init__(self):
        super().__init__()
        self.sock=None; self.lock=threading.Lock()
        self.mode=None; self.battery={}; self.online=False; self.ctl={}
        self.ear=None        # (primary, secondary): 0 in ear, 1 out of ear, 2 in case (AAP 0x06)
        self.speaking=None   # (level, time) from Conversation Awareness (AAP 0x4B)
        self.echo={}     # id -> time the AirPods last sent that setting (a report or the echo of a change)
        self.wake = threading.Event(); self.fast = False
        try: self.phone = open(HANDOFF_FILE).read().strip() == "phone"
        except OSError: self.phone = False
    def set_handoff(self, phone):
        self.phone = phone
        os.makedirs(os.path.dirname(HANDOFF_FILE), exist_ok=True)
        with open(HANDOFF_FILE, "w") as f: f.write("phone" if phone else "laptop")
        if phone:
            with self.lock:
                if self.sock:
                    try: self.sock.shutdown(socket.SHUT_RDWR)
                    except OSError: pass
            subprocess.run(["bluetoothctl", "disconnect", AP_MAC], capture_output=True, timeout=10)
        self.wake.set()
    def _parse(self,p):
        if p[:6]==bytes.fromhex("040004000600") and len(p)>=8:
            self.ear=(p[6],p[7]); raw_log("<", p)
        if p[:6]==bytes.fromhex("040004004b00") and len(p)>=10:
            self.speaking=(p[9],time.time()); raw_log("<", p)
        if p[:6]==CTL_HDR and len(p)>=8:
            self.ctl[p[6]]=bytes(p[7:11]); self.echo[p[6]]=time.time()
        i=p.find(NC)
        if i>=0 and len(p)>i+7: self.mode=p[i+7]
        j=p.find(BAT)
        if j>=0 and len(p)>j+6:
            cnt=p[j+6]; off=j+7; b={}
            for _ in range(cnt):
                if off+4<len(p):
                    b[BT.get(p[off],str(p[off]))]={"level":p[off+2],"status":BS.get(p[off+3],p[off+3])}
                    off+=5
            if b: self.battery=b
    def run(self):
        while True:
            if self.phone:                      # the phone has them: stay off until it lets go
                self.wake.wait(); self.wake.clear(); continue
            try:
                s=socket.socket(socket.AF_BLUETOOTH,socket.SOCK_SEQPACKET,socket.BTPROTO_L2CAP)
                s.settimeout(8); s.connect((AP_MAC,PSM))
                s.sendall(HS); time.sleep(0.3); s.sendall(FEAT); time.sleep(0.2); s.sendall(NOTIFY)
                with self.lock: self.sock=s
                self.online=True; s.settimeout(2)
                while not self.phone:
                    try:
                        p=s.recv(1024)
                    except socket.timeout:
                        continue
                    if not p: break
                    self._parse(p)
                    # Fallback that needs no network: the AirPods' connected-devices list (0x2E)
                    # shows the phone joining, so step aside even if the phone couldn't reach us.
                    if len(p)>4 and p[4]==0x2E and PHONE_BT in p and not self.phone:
                        threading.Thread(target=self.set_handoff, args=(True,), daemon=True).start()
                    if len(p)>6 and p[4] in (0x09,0x0E,0x2E,0x10):
                        raw_log("<", p)
            except Exception as e:
                if repr(e) != getattr(self, "last_err", None):      # log each new kind of failure once
                    self.last_err = repr(e); print("AirPods AAP:", repr(e), flush=True)
            with self.lock: self.sock=None
            self.online=False; self.mode=None; self.battery={}; self.ctl={}; self.echo={}; self.ear=None; self.speaking=None
            if self.phone:
                subprocess.run(["bluetoothctl", "disconnect", AP_MAC], capture_output=True, timeout=10)
            # long backoff (a short one fights another host); a hand-off back wakes us at once
            if self.fast:
                self.fast = False; time.sleep(1); continue
            self.wake.wait(60); self.wake.clear()
    def set_mode(self,name):
        with self.lock:
            if not self.sock: return False
            try:
                self.sock.sendall(bytes.fromhex("0400040009000D")+bytes([MODE[name],0,0,0])); return True
            except Exception:
                return False
    def set_mode_wait(self, name, wait=1.5):
        """Set the noise mode and wait for the AirPods to echo it (they do for 0x0D)."""
        t0=time.time()
        if not self.set_mode(name): return False, False
        while time.time()-t0<wait:
            if self.echo.get(0x0D,0)>=t0 and self.mode==MODE[name]: return True, True
            time.sleep(0.05)
        return True, False
    def block(self, level, mode="adaptive"):
        """Block noise with the AirPods themselves: Adaptive mode + its noise level (0x2E, 0 = hear the
        most, 100 = block the most), or full ANC. The AirPods echo the mode at once; the level is applied
        silently and only reported again when the AAP link reopens, so read it back to confirm."""
        res={"mode":mode}
        ok,res["mode_confirmed"]=self.set_mode_wait(mode)
        if not ok: return None
        if mode=="adaptive" and level is not None:
            lvl=max(0,min(100,int(level)))
            sent,_=self.set_setting("adaptive_level",lvl)
            if not sent: return None
            res["level"]=lvl
            t0=time.time(); time.sleep(0.3); self.refresh()
            while time.time()-t0<8:
                if self.online and self.echo.get(0x2E,0)>t0: break
                time.sleep(0.1)
            got=self.ctl.get(0x2E) if self.echo.get(0x2E,0)>t0 else None
            res["level_reported"]=got[0] if got else None
            res["level_confirmed"]=bool(got) and got[0]==lvl
            res["mode_now"]=MNAME.get(self.mode,self.mode)
        return res
    def send(self, pkt):
        with self.lock:
            if not self.sock: return False
            try: self.sock.sendall(pkt)
            except Exception: return False
        raw_log(">", pkt)
        return True
    def set_setting(self, name, value, wait=0.6):
        """Send a setting; returns (sent, echoed). AirPods Pro 2 apply a setting without echoing it
        back to the host that sent it; reconnecting (refresh) makes them report it again."""
        pkt = ap_encode(name, value)
        t0 = time.time()
        if not self.send(pkt): return False, False
        while time.time() - t0 < wait:
            if self.echo.get(pkt[6], 0) >= t0: break
            time.sleep(0.05)
        echoed = self.echo.get(pkt[6], 0) >= t0
        if not echoed:
            self.ctl[pkt[6]] = pkt[7:11]      # optimistic: some settings are accepted silently
        ap_remember(name, ap_decode(AP_SETTINGS[name][1], pkt[7:11]))
        return True, echoed
    def refresh(self):
        """Read the settings back: they only report them right after the AAP connection opens, so drop the
        AAP link (not the Bluetooth/audio link) and reconnect at once."""
        with self.lock:
            if not self.sock: return False
            self.fast = True
            try: self.sock.shutdown(socket.SHUT_RDWR)
            except OSError: pass
        return True
    def settings(self):
        """Last value the AirPods reported for each setting, else the last value we set (remembered);
        None = unknown."""
        mem = ap_remembered()
        return {n: (ap_decode(k, self.ctl[i]) if i in self.ctl else mem.get(n))
                for n, (i, k) in AP_SETTINGS.items()}
    def reported(self):
        """Which settings the AirPods themselves have reported on this connection."""
        return [n for n, (i, _) in AP_SETTINGS.items() if i in self.echo]
    def state(self):
        bat = self.battery
        if not bat and time.time() - BLE["t"] < 300:
            bat = BLE["battery"]
        EAR={0:"in ear",1:"out of ear",2:"in case"}
        ear=[EAR.get(x,str(x)) for x in self.ear] if self.ear else None
        sp=None
        if self.speaking and time.time()-self.speaking[1]<600:
            lv=self.speaking[0]
            sp={"speaking": lv in (1,2) or (3<lv<8), "level": lv, "ago": round(time.time()-self.speaking[1])}
        return {"online": self.online, "mode": MNAME.get(self.mode, self.mode), "battery": bat,
                "ear": ear, "speaking": sp,
                "on_phone": self.phone, "settings": self.settings(), "reported": self.reported()}

AP = AirPods(); AP.start()

# ---- Source-side EQ (PipeWire filter-chain), see hp_eq.py ----
try:
    import hp_eq
    EQ = hp_eq.Eq(CONFIG)
except Exception as e:      # missing eq_presets.json etc.: the rest of the panel still works
    EQ = None; EQ_ERR = str(e)
def eq_loop():
    time.sleep(1)
    while True:
        audio_tick()
        time.sleep(5)
threading.Thread(target=eq_loop, daemon=True).start()
def eq_state():
    return EQ.state() if EQ else {"available": False, "error": EQ_ERR}

# ---- Sound viewer + notch filters, see hp_sound.py ----
try:
    import hp_sound
    SOUND = hp_sound.Analyzer()
    NOTCH = {k: hp_sound.Notch(k) for k in ("mic", "output")}
except Exception as e:
    SOUND = None; NOTCH = {}; SOUND_ERR = str(e)
def _output_target():
    dev = hp_eq.real_sink(hp_eq.default_sink()) if EQ else ""
    return dev, (hp_eq.smart_target(dev) if dev else None)
if NOTCH and EQ:
    NOTCH["output"].output_target = _output_target
def audio_tick():
    """Every few seconds: EQ attached, default output on a real device, and the default input never the
    AirPods mic (an app opening it would switch them to call mode)."""
    if NOTCH:
        try: hp_sound.keep_default_source_off_headphones()
        except Exception: pass
    if EQ:
        try: EQ.tick()
        except Exception: pass
    if "output" in NOTCH:
        try: NOTCH["output"].check_target()
        except Exception: pass
def notch_restore():
    time.sleep(3)
    for n in NOTCH.values():
        try: n.restore_saved()
        except Exception as e: n.error = str(e)
threading.Thread(target=notch_restore, daemon=True).start()
def sound_state():
    if not SOUND: return {"available": False, "error": SOUND_ERR}
    return {"available": SOUND.available(), "error": "" if SOUND.available() else "numpy is not installed",
            "sources": hp_sound.sources(), "listening": SOUND.source, "bins": SOUND.bin_centers(),
            "notch": {k: n.state() for k, n in NOTCH.items()}}

HTML = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "panel.html"), encoding="utf-8").read()

class H(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def _s(self,code,body,ct="application/json"):
        self.send_response(code); self.send_header("content-type",ct); self.end_headers()
        self.wfile.write(body.encode() if isinstance(body,str) else body)
    def sse(self, src):
        """Stream spectrum frames as server-sent events until the viewer goes away."""
        if not SOUND or not SOUND.available(): return self._s(503,json.dumps({"error":"sound viewer unavailable"}))
        if src not in [x["id"] for x in hp_sound.sources()]: return self._s(400,json.dumps({"error":"unknown microphone"}))
        self.send_response(200); self.send_header("content-type","text/event-stream")
        self.send_header("cache-control","no-cache"); self.send_header("x-accel-buffering","no"); self.end_headers()
        try:
            for f in SOUND.watch(src):
                self.wfile.write(b"data: "+json.dumps(f,separators=(",",":")).encode()+b"\n\n"); self.wfile.flush()
                if "error" in f: break
            self.wfile.write(b"event: end\ndata: {}\n\n"); self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
    def do_GET(self):
        from urllib.parse import urlsplit, parse_qs
        u=urlsplit(self.path); q={k:v[0] for k,v in parse_qs(u.query).items()}
        if u.path=="/api/sound": return self._s(200,json.dumps(sound_state()))
        if u.path=="/api/sound/stream": return self.sse(q.get("src",""))
        if u.path=="/api/sound/inspect":
            if not SOUND: return self._s(503,json.dumps({"error":SOUND_ERR}))
            try: return self._s(200,json.dumps(SOUND.inspect(float(q.get("lo",0)),float(q.get("hi",0)))))
            except ValueError: return self._s(400,json.dumps({"error":"lo/hi must be numbers"}))
        if self.path=="/" : return self._s(200,HTML,"text/html; charset=utf-8")
        if self.path=="/api/caps":
            # which controls exist right now: the flick ring hides the rest
            return self._s(200,json.dumps({"crusher":crusher_connected(),"airpods":AP.online,
                                           "eq":bool(EQ and EQ.available())}))
        if self.path=="/api/state":
            return self._s(200,json.dumps({"crusher":crusher_state(),"airpods":AP.state(),"eq":eq_state()}))
        if self.path=="/api/eq":
            return self._s(200,json.dumps(eq_state()))
        self._s(404,"{}")
    def do_POST(self):
        n=int(self.headers.get("content-length",0)); body=self.rfile.read(n).decode().strip()
        try:
            if self.path=="/api/crusher/mode":
                with CR_LOCK:
                    if body=="anc": cr_at(["AT.UIAUDIO=transparency,off","AT.UIAUDIO=anc,on"]); crusher_cache_set(anc=True,transparency=False)
                    elif body=="transparency": cr_at(["AT.UIAUDIO=transparency,on"]); crusher_cache_set(anc=False,transparency=True)
                    elif body=="off": cr_at(["AT.UIAUDIO=transparency,off","AT.UIAUDIO=anc,off"]); crusher_cache_set(anc=False,transparency=False)
                return self._s(200,"{}")
            if self.path=="/api/crusher/bass":
                lvl=max(0,min(10,int(body)))
                with CR_LOCK: cr_at([f"AT.CRUSHER=level,{lvl}"])
                crusher_cache_set(bass=lvl)
                return self._s(200,"{}")
            if self.path=="/api/handoff":
                if body not in ("phone","laptop"): return self._s(400,json.dumps({"error":"phone|laptop"}))
                threading.Thread(target=AP.set_handoff, args=(body=="phone",), daemon=True).start()
                return self._s(200,json.dumps({"ok":True,"airpods_with":body}))
            if self.path=="/api/airpods/setting":
                j=json.loads(body or "{}")
                if j.get("name") not in AP_SETTINGS: return self._s(400,json.dumps({"error":"unknown setting"}))
                ok,echoed=AP.set_setting(j["name"], j.get("value"))
                if not ok: return self._s(503,json.dumps({"error":"AirPods not connected"}))
                return self._s(200,json.dumps({"echoed":echoed,"settings":AP.settings(),"reported":AP.reported()}))
            if self.path=="/api/airpods/refresh":
                return self._s(200 if AP.refresh() else 503,json.dumps({"reported":AP.reported()}))
            if self.path=="/api/crusher/setting":
                j=json.loads(body or "{}")
                crusher_setting(j.get("name"), j.get("value"))
                return self._s(200,"{}")
            if self.path in ("/api/eq","/api/eq/cycle"):
                if not EQ: return self._s(503,json.dumps({"error":EQ_ERR}))
                if self.path=="/api/eq/cycle": return self._s(200,json.dumps(EQ.cycle()))
                j=json.loads(body or "{}")
                return self._s(200,json.dumps(EQ.set(enabled=j.get("enabled"),preset=j.get("preset"),gains=j.get("gains"))))
            if self.path=="/api/sound/stop":
                if SOUND: SOUND.stop()
                return self._s(200,"{}")
            if self.path=="/api/sound/unmute":
                if not SOUND or body not in [x["id"] for x in hp_sound.sources()]: return self._s(400,json.dumps({"error":"unknown microphone"}))
                hp_sound.unmute(body); return self._s(200,"{}")
            if self.path=="/api/sound/notch":
                j=json.loads(body or "{}")
                if j.get("kind") not in NOTCH: return self._s(400,json.dumps({"error":"kind must be mic or output"}))
                return self._s(200,json.dumps(NOTCH[j["kind"]].set(j.get("freqs") or [], hum=bool(j.get("hum")))))
            if self.path=="/api/airpods/block":
                j=json.loads(body or "{}")
                mode=j.get("mode","adaptive")
                if mode not in ("adaptive","anc"): return self._s(400,json.dumps({"error":"mode must be adaptive or anc"}))
                r=AP.block(j.get("level"), mode)
                if r is None: return self._s(503,json.dumps({"error":"AirPods not connected"}))
                return self._s(200,json.dumps(r))
            if self.path=="/api/airpods/mode":
                if body not in MODE: return self._s(400,json.dumps({"error":"unknown mode"}))
                ok=AP.set_mode(body)
                return self._s(200 if ok else 503,"{}" if ok else json.dumps({"error":"AirPods not connected"}))
        except ValueError as e:
            return self._s(400,json.dumps({"error":str(e)}))
        except Exception as e:
            return self._s(500,json.dumps({"error":str(e)}))
        self._s(404,"{}")

if __name__=="__main__":
    import signal, sys
    def cleanup():
        if SOUND: SOUND.stop(wait=True)
        for n in NOTCH.values(): n.shutdown()
        if EQ: EQ.shutdown()      # put the real headphones back as the default output
    def bye(*_):
        cleanup(); sys.exit(0)
    signal.signal(signal.SIGTERM, bye)
    print(f"panel on 127.0.0.1:{PORT}")
    try:
        ThreadingHTTPServer(("127.0.0.1",PORT),H).serve_forever()
    finally:
        cleanup()
