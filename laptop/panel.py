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
    return "\n".join(out)
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
        CR_CACHE["v"] = None
        return {"online": False, "error": "not connected"}
    if CR_CACHE["v"] is not None and time.time() - CR_CACHE["t"] < max_age:
        return CR_CACHE["v"]
    v = crusher_state_live()
    if v.get("online"):
        CR_CACHE.update(t=time.time(), v=v)
    return v
def crusher_cache_set(**kw):
    if CR_CACHE["v"]: CR_CACHE["v"] = {**CR_CACHE["v"], **kw}
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
            "bass_max": int(lvl.group(2)) if lvl else 10}

# ---- AirPods (AAP over L2CAP, persistent) ----
AP_MAC = CONFIG.get("airpods_mac", ""); PSM = 0x1001
HS=bytes.fromhex("00000400010002000000000000000000")
NOTIFY=bytes.fromhex("040004000F00FFFFFEFF"); FEAT=bytes.fromhex("040004004D00FF00000000000000")
NC=bytes.fromhex("0400040009000d"); BAT=bytes.fromhex("040004000400")
MODE={"off":1,"anc":2,"transparency":3,"adaptive":4}; MNAME={1:"off",2:"anc",3:"transparency",4:"adaptive"}
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
        self.mode=None; self.battery={}; self.online=False
        self.wake = threading.Event()
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
                    if len(p)>6 and (p[4] in (0x0E,0x2E,0x10) or (p[4]==0x09 and p[6] in (0x06,0x20))):
                        with open("/tmp/aap-raw.log","a") as f: f.write(time.strftime("%T ")+p.hex()+"\n")
            except Exception:
                pass
            with self.lock: self.sock=None
            self.online=False; self.mode=None; self.battery={}
            if self.phone:
                subprocess.run(["bluetoothctl", "disconnect", AP_MAC], capture_output=True, timeout=10)
            # long backoff (a short one fights another host); a hand-off back wakes us at once
            self.wake.wait(60); self.wake.clear()
    def set_mode(self,name):
        with self.lock:
            if not self.sock: return False
            try:
                self.sock.sendall(bytes.fromhex("0400040009000D")+bytes([MODE[name],0,0,0])); return True
            except Exception:
                return False
    def state(self):
        bat = self.battery
        if not bat and time.time() - BLE["t"] < 300:
            bat = BLE["battery"]
        return {"online": self.online, "mode": MNAME.get(self.mode, self.mode), "battery": bat,
                "on_phone": self.phone}

AP = AirPods(); AP.start()

HTML = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Headphones</title><style>
body{font:17px/1.5 system-ui,sans-serif;background:#0f1419;color:#e8edf2;margin:0;padding:22px;max-width:480px}
h1{font-size:20px;margin:0 0 4px}.dev{font-size:17px;font-weight:700;margin:0 0 10px}
.card{background:#1a2230;border:1px solid #2b3647;border-radius:14px;padding:16px 18px;margin:14px 0}
.k{color:#8aa6b4;font-size:12px;text-transform:uppercase;letter-spacing:.04em;margin-bottom:8px}
.seg{display:flex;gap:8px}.seg button{flex:1}
button{font:600 15px system-ui;border:0;border-radius:10px;padding:11px 10px;color:#fff;background:#273345;cursor:pointer}
button.on{background:#2f7d4f}button:active{transform:scale(.97)}
input[type=range]{width:100%;accent-color:#3fa7ff;height:28px}
.row{display:flex;justify-content:space-between;align-items:center;gap:12px}
.lvl{font-size:26px;font-weight:700;color:#5fd0ff;min-width:50px;text-align:right}
.batt{color:#9fb;font-size:14px}.off{display:none}#none{color:#8aa6b4}
</style>
<h1>🎧 Headphones</h1>
<div id=none class=off>No controllable headphones connected.</div>
<div id=cr>
 <div class=dev>Crusher ANC 2</div>
 <div class=card><div class=k>Listening mode</div><div class=seg>
  <button id=cr_anc onclick="cr('anc')">ANC</button>
  <button id=cr_tr onclick="cr('transparency')">Transparency</button>
  <button id=cr_off onclick="cr('off')">Off</button></div></div>
 <div class=card><div class=k>Crusher bass</div>
  <div class=row><input id=cr_bass type=range min=0 max=10 step=1 onchange="crbass(this.value)"><span class=lvl id=cr_lvl>–</span></div></div>
</div>
<div id=ap>
 <div class=dev>AirPods Pro <span id=ap_batt class=batt></span></div>
 <div class=card><div class=k>Noise control</div><div class=seg>
  <button id=ap_off onclick="ap('off')">Off</button>
  <button id=ap_anc onclick="ap('anc')">ANC</button>
  <button id=ap_tr onclick="ap('transparency')">Transp.</button>
  <button id=ap_ad onclick="ap('adaptive')">Adaptive</button></div></div>
</div>
<script>
async function load(){let s=await (await fetch('/api/state')).json();
 let c=s.crusher||{};
 cr_anc.className=c.anc?'on':'';cr_tr.className=c.transparency?'on':'';cr_off.className=(c.online&&!c.anc&&!c.transparency)?'on':'';
 document.getElementById('cr').className=c.online?'':'off';
 if(c.bass!=null){cr_bass.value=c.bass;cr_lvl.textContent=c.bass+'/'+(c.bass_max||10);}
 let a=s.airpods||{};
 for(const [k,id] of [['off','ap_off'],['anc','ap_anc'],['transparency','ap_tr'],['adaptive','ap_ad']])
   document.getElementById(id).className=(a.mode===k)?'on':'';
 document.getElementById('ap').className=a.online?'':'off';
 let bt=a.battery||{},parts=[];for(const k of ['left','right','case'])if(bt[k])parts.push(k[0].toUpperCase()+' '+bt[k].level+'%');
 ap_batt.textContent=parts.join('  ');
 document.getElementById('none').className=(c.online||a.online)?'off':'';}
async function cr(m){await fetch('/api/crusher/mode',{method:'POST',body:m});setTimeout(load,400)}
async function crbass(v){cr_lvl.textContent=v+'/10';await fetch('/api/crusher/bass',{method:'POST',body:v})}
async function ap(m){await fetch('/api/airpods/mode',{method:'POST',body:m});setTimeout(load,500)}
load();setInterval(load,4000);
</script>"""

class H(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def _s(self,code,body,ct="application/json"):
        self.send_response(code); self.send_header("content-type",ct); self.end_headers()
        self.wfile.write(body.encode() if isinstance(body,str) else body)
    def do_GET(self):
        if self.path=="/" : return self._s(200,HTML,"text/html; charset=utf-8")
        if self.path=="/api/caps":
            # which controls exist right now: the flick ring hides the rest
            return self._s(200,json.dumps({"crusher":crusher_connected(),"airpods":AP.online}))
        if self.path=="/api/state":
            return self._s(200,json.dumps({"crusher":crusher_state(),"airpods":AP.state()}))
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
            if self.path=="/api/airpods/mode":
                if body not in MODE: return self._s(400,json.dumps({"error":"unknown mode"}))
                ok=AP.set_mode(body)
                return self._s(200 if ok else 503,"{}" if ok else json.dumps({"error":"AirPods not connected"}))
        except Exception as e:
            return self._s(500,json.dumps({"error":str(e)}))
        self._s(404,"{}")

if __name__=="__main__":
    print(f"panel on 127.0.0.1:{PORT}")
    ThreadingHTTPServer(("127.0.0.1",PORT),H).serve_forever()
