"""Spatial audio for the laptop's output: stereo played as two virtual speakers in front of you
(+-30 degrees) through an HRTF, optionally steered by the AirPods' head tracking.

  * Renderer: PipeWire filter-chain "sofa/spatializer" (libmysofa) with the MIT KEMAR HRTF
    (MIT_KEMAR_normal_pinna.sofa, shipped by the distro's libmysofa package; MIT Media Lab, free to
    use with attribution). Registered as a WirePlumber smart filter in front of the default output,
    next to the EQ, so the default output and its volume stay the real headphones.
  * Head tracking (experimental): AAP opcode 0x17 packets, start/stop packets from LibrePods
    (bluetooth/AACPManager.kt). The panel integrates the rotation rate about the gravity-like vector
    in each packet (see panel.py AirPods._head); the rate's scale isn't documented, so it starts from
    a guess and "Calibrate" (turn your head 90 degrees right) measures scale and direction.
  * The speakers are turned against the head: turn right by y and the speakers move to 30+y / 330+y
    (the spatializer's azimuth grows to the left). The yaw is smoothed and slowly pulled back to
    centre (like Apple's "follow device"), and "Recenter" makes the current direction the front.
  * Updates are `pw-cli set-param` on the filter node (about 10 ms each; a piped long-lived pw-cli
    does not apply commands), at most 15 per second and only when the angle moved a degree or more.
"""
import json
import os
import signal
import subprocess
import threading
import time

SOFA_PATHS = ("~/.local/share/headphone-panel/hrtf.sofa",
              "/usr/share/libmysofa/MIT_KEMAR_normal_pinna.sofa", "/usr/share/libmysofa/default.sofa")
NAME = "hp_spatial"
SPREAD = 30.0              # virtual speakers at +-30 degrees
FOLLOW_TAU = 12.0          # seconds: how fast the front drifts back to where you look
SMOOTH_TAU = 0.08          # seconds: yaw smoothing
MIN_STEP = 1.0             # degrees: smaller changes are not sent
MAX_RATE = 15.0            # updates per second


def _run(cmd, timeout=5):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def sofa_file():
    for p in SOFA_PATHS:
        p = os.path.expanduser(p)
        if os.path.exists(p):
            return p
    return ""


def graph(az_l=SPREAD, az_r=360.0 - SPREAD, el=0.0, sofa=None):
    sofa = sofa or sofa_file()
    def sp(n, az):
        return {"type": "sofa", "label": "spatializer", "name": n,
                "config": {"filename": sofa, "blocksize": 256, "tailsize": 1024},
                "control": {"Azimuth": float(az), "Elevation": float(el), "Radius": 3.0}}
    def mix(n):
        return {"type": "builtin", "label": "mixer", "name": n, "control": {"Gain 1": 0.7, "Gain 2": 0.7}}
    return {"nodes": [sp("spL", az_l), sp("spR", az_r), mix("mixL"), mix("mixR")],
            "links": [{"output": "spL:Out L", "input": "mixL:In 1"}, {"output": "spR:Out L", "input": "mixL:In 2"},
                      {"output": "spL:Out R", "input": "mixR:In 1"}, {"output": "spR:Out R", "input": "mixR:In 2"}],
            "inputs": ["spL:In", "spR:In"], "outputs": ["mixL:Out", "mixR:Out"]}


def conf_text(match, name=NAME, az=(SPREAD, 360.0 - SPREAD)):
    cap = {"node.name": name, "media.class": "Audio/Sink", "node.description": "Spatial audio",
           "filter.smart": True, "filter.smart.name": name}
    if match:
        cap["filter.smart.target"] = match
    args = {"node.description": "Spatial audio", "media.name": "Spatial audio",
            "filter.graph": graph(*az), "audio.channels": 2, "audio.position": ["FL", "FR"],
            "capture.props": cap, "playback.props": {"node.name": name + ".output", "node.passive": True}}
    return ("context.properties = { log.level = 0 }\n"
            "context.spa-libs = {\n    audio.convert.* = audioconvert/libspa-audioconvert\n"
            "    support.* = support/libspa-support\n}\n"
            "context.modules = [\n"
            "    { name = libpipewire-module-rt args = {} flags = [ ifexists nofail ] }\n"
            "    { name = libpipewire-module-protocol-native }\n"
            "    { name = libpipewire-module-client-node }\n"
            "    { name = libpipewire-module-adapter }\n"
            "    { name = libpipewire-module-filter-chain args = " + json.dumps(args) + " }\n]\n")


def node_id(name):
    try:
        objs = json.loads(_run(["pw-dump"], timeout=8).stdout or "[]")
    except Exception:
        return None
    for o in objs:
        if o.get("type") == "PipeWire:Interface:Node" and \
                ((o.get("info") or {}).get("props") or {}).get("node.name") == name:
            return o["id"]
    return None


def wrap(a):
    return (a + 180.0) % 360.0 - 180.0


class Spatial:
    """mode: "off" | "fixed" | "tracked". The panel feeds head rotation rates via head()."""

    def __init__(self, ap=None, name=NAME):
        self.ap = ap                       # AirPods controller (start/stop the IMU stream)
        self.name = name
        self.mode = "off"
        self.proc = None
        self.cli = None
        self.nid = None
        self.match = None
        self.error = ""
        self.lock = threading.Lock()
        self.conf = os.path.expanduser(f"~/.cache/headphone-panel/{name}.conf")
        self.yaw = 0.0                     # smoothed head yaw (degrees, right = +)
        self.pitch = 0.0
        self.center = 0.0                  # the direction that counts as "front"
        self.last_t = 0.0
        self.sent = None                   # last (azL, azR) sent
        self.sent_t = 0.0
        self.updates = 0
        self.samples = 0
        self.output_target = lambda: ("", None)   # set by the panel (same as the notch filter)
        self.deg_per_unit = 1.0 / 16.4     # head-rate units -> degrees (a common gyro scale; calibrate)
        self.bias = 0.0
        self.cal = None                    # running sum while calibrating

    # ---- process ----
    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def _start(self, match):
        if not sofa_file():
            self.error = "no HRTF (.sofa) file found; install libmysofa's data or put one at " + SOFA_PATHS[0]
            return False
        os.makedirs(os.path.dirname(self.conf), exist_ok=True)
        with open(self.conf, "w") as f:
            f.write(conf_text(match, self.name))
        self.proc = subprocess.Popen(["pipewire", "-c", self.conf], stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL, start_new_session=True)
        for _ in range(50):
            if self.proc.poll() is not None:
                self.error = "the spatializer exited (is PipeWire built with libmysofa?)"
                self.proc = None
                return False
            self.nid = node_id(self.name)
            if self.nid is not None:
                break
            time.sleep(0.1)
        else:
            self.error = "the spatializer did not appear"
            return False
        self.match = match
        self.sent = None
        self.error = ""
        return True

    def _stop(self):
        for p in (self.cli, self.proc):
            if p and p.poll() is None:
                try:
                    os.killpg(p.pid, signal.SIGTERM)
                    p.wait(3)
                except Exception:
                    try: p.kill()
                    except Exception: pass
        self.proc = self.cli = None
        self.nid = None
        self.sent = None

    def _send(self, az_l, az_r, el=0.0):
        """Set both speakers' angles on the running filter (one pw-cli kept open)."""
        if self.nid is None:
            return False
        if self.cli is not None and self.cli.poll() is None:
            return False                   # the previous update is still running: skip this one
        props = (f'{{ params = [ "spL:Azimuth" {az_l:.1f} "spR:Azimuth" {az_r:.1f} '
                 f'"spL:Elevation" {el:.1f} "spR:Elevation" {el:.1f} ] }}')
        try:
            self.cli = subprocess.Popen(["pw-cli", "set-param", str(self.nid), "Props", props],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                        start_new_session=True)
        except Exception:
            return False
        self.sent = (az_l, az_r)
        self.sent_t = time.time()
        self.updates += 1
        return True

    # ---- public ----
    def set_mode(self, mode):
        if mode not in ("off", "fixed", "tracked"):
            raise ValueError("mode must be off, fixed or tracked")
        with self.lock:
            if mode == "off":
                self._stop()
            elif not self.running():
                dev, match = self.output_target()
                if not match or not self._start(match):
                    self.mode = "off"
                    if not match:
                        self.error = "no output device found"
                    self._head(False)
                    return self.state()
            self.mode = mode
            self.yaw = self.center = 0.0
            if mode != "off":
                self._send(SPREAD, 360.0 - SPREAD)
            self._head(mode == "tracked")
            return self.state()

    def _head(self, on):
        if self.ap is not None:
            try:
                self.ap.head_tracking(on)
            except Exception as e:
                self.error = f"head tracking: {e}"

    def recenter(self):
        self.center = self.yaw

    def calibrate(self, step):
        """step "start": look ahead and press; "done": after turning the head 90 degrees right."""
        if step == "start":
            self.cal = 0.0
            return {"ok": True, "text": "Now turn your head 90 degrees to the right, then press Done."}
        if self.cal is None:
            return {"ok": False, "text": "Press Calibrate first."}
        total, self.cal = self.cal, None
        if abs(total) < 50:
            return {"ok": False, "text": "No turn was seen (is head tracking on?). Try again."}
        self.deg_per_unit = 90.0 / total           # sign included: a right turn reads as +90
        self.yaw = self.center = 0.0
        return {"ok": True, "text": "Calibrated."}

    def head(self, rate, dt):
        """Rotation rate about the vertical (raw units) for dt seconds, from the AirPods."""
        self.samples += 1
        if abs(rate - self.bias) < 15:             # still: learn the gyro's offset slowly
            self.bias += (rate - self.bias) * 0.02
        r = rate - self.bias
        if self.cal is not None:
            self.cal += r * dt
        self.yaw = wrap(self.yaw + r * dt * self.deg_per_unit)
        self.center += wrap(self.yaw - self.center) * min(1.0, dt / FOLLOW_TAU)
        if self.mode == "tracked":
            self.apply(wrap(self.yaw - self.center))

    def apply(self, rel_yaw, pitch=0.0):
        """Turn the virtual speakers against a head turned `rel_yaw` degrees to the right."""
        az_l = (SPREAD + rel_yaw) % 360.0
        az_r = (360.0 - SPREAD + rel_yaw) % 360.0
        el = 0.0                                   # no pitch: the packets' tilt isn't verified
        if self.sent and time.time() - self.sent_t < 1.0 / MAX_RATE:
            return False
        if self.sent and abs(wrap(az_l - self.sent[0])) < MIN_STEP:
            return False
        with self.lock:
            if not self.running():
                return False
            return self._send(az_l, az_r, el)

    def check_target(self):
        """Follow the default output (AirPods -> Crusher...)."""
        if not self.running():
            return
        dev, match = self.output_target()
        if match and match != self.match:
            mode = self.mode
            with self.lock:
                self._stop()
            self.set_mode(mode)

    def state(self):
        rel = wrap(self.yaw - self.center)
        return {"mode": self.mode, "running": self.running(), "error": self.error,
                "yaw": round(rel, 1), "pitch": 0.0, "samples": self.samples,
                "calibrating": self.cal is not None, "deg_per_unit": self.deg_per_unit,
                "speakers": [round(x, 1) for x in self.sent] if self.sent else None,
                "hrtf": os.path.basename(sofa_file())}

    def shutdown(self):
        with self.lock:
            self._stop()
        self._head(False)
