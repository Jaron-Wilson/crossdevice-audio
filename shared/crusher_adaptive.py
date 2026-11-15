"""Crusher Adaptive: an "Adaptive" noise mode for the Skullcandy Crusher ANC 2, done outside the headphones.

The Crusher has no adaptive ANC and reports nothing it senses (docs/PROTOCOL.md), so this listens to the
room with the HOST's own microphone (the laptop's or the PC's, never the headset's: opening a Bluetooth
headset mic switches it to call mode) and moves the Crusher between its modes over the AT channel:

    quiet room              -> Transparency at a moderate level
    moderate, varying noise -> Transparency at a lower level (Off if the firmware has no level)
    loud, or steady noise   -> ANC
    someone talking nearby  -> Transparency high (optionally lower the media), back ~5 s after the speech stops

Only numbers are kept: a loudness in dB and a few speech features per window. No audio is recorded, stored
or sent anywhere; each block of samples is dropped as soon as it has been measured.

This module is platform-free (numpy for the measuring part only), shared by the laptop panel
(laptop/hp_adaptive.py) and the Windows daemon (windows/crusher_adaptive_win.py). The pieces:

    AmbientMeter   samples -> Reading (A-weighted dBFS, smoothed level, steady?, speech nearby?)
    Policy         Reading + time -> the Target to send now, with hysteresis, hold, dwell, conversation
                   and manual-override pause. Pure Python, unit-tested with synthetic sequences.
    RateLimiter    at most one command batch per few seconds
    commands_for   Target -> Crusher AT commands (only what changed)
    AdaptiveRunner the loop: mic -> meter -> policy -> AT, with the platform's glue passed in
"""
import collections
import math
import threading
import time

try:
    import numpy as np
except Exception:          # the policy works without numpy; the meter needs it
    np = None

RATE = 16000               # Hz; plenty for loudness and the 300-3400 Hz speech band
WINDOW_S = 1.0             # loudness window
HOP_S = 0.5                # a reading every half second (windows overlap by half)
FRAME = 320                # 20 ms frames for the speech envelope (50 per second)
ENV_FRAMES = 100           # 2 s of envelope for the syllable-rate check
FLOOR_DB = -120.0
PITCH_N = 2 * FRAME        # 40 ms for the pitch check (two speech frames)


# ---------------------------------------------------------------------------------------------------
# Measuring

def a_weight_db(f):
    """IEC 61672 A-weighting in dB at frequency f (Hz); 0 dB at 1 kHz."""
    f = max(float(f), 1e-3)
    f2 = f * f
    ra = (12194.0 ** 2 * f2 * f2) / ((f2 + 20.6 ** 2) * math.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2))
                                     * (f2 + 12194.0 ** 2))
    return 20.0 * math.log10(ra) + 2.0


class Reading:
    """One measurement. level is the smoothed A-weighted dBFS (RMS; a full-scale sine reads -3 dB)."""
    __slots__ = ("t", "db", "level", "steady", "speech", "band_ratio", "mod_ratio", "depth", "held")

    def __init__(self, t, db, level, steady=False, speech=False, band_ratio=0.0, mod_ratio=0.0, depth=0.0,
                 held=False):
        self.t, self.db, self.level, self.steady, self.speech = t, db, level, steady, speech
        self.band_ratio, self.mod_ratio, self.depth, self.held = band_ratio, mod_ratio, depth, held

    def __repr__(self):
        return (f"Reading(t={self.t:.1f}, db={self.db:.1f}, level={self.level:.1f}, steady={self.steady}, "
                f"speech={self.speech}, held={self.held})")


class AmbientMeter:
    """Turns mono float samples (-1..1) into a Reading every HOP_S seconds.

    Loudness: A-weighted RMS over a 1 s window (the A-weighted share of the window's spectrum times its
    plain RMS power), then an exponential smoothing so one door slam doesn't swing the level.

    Speech nearby: in 20 ms frames, the energy in 300-3400 Hz (the voice band). Speech is when, over the
    last 2 s, that band holds most of the energy, its level swings by several dB, and the swings come at
    syllable rate (2-8 Hz, about 4 syllables a second). A fan or engine fails the swing test; music often
    passes, which is fine (it is a nearby sound worth hearing). No voice is recognised, only that rhythm.
    """
    SPEECH_RATIO = 0.30      # share of 80-8000 Hz energy that is in 300-3400 Hz
    SPEECH_DEPTH = 3.5       # dB, standard deviation of the voice-band envelope
    SPEECH_MOD = 0.32        # share of envelope modulation at 2-8 Hz (of 0.5-25 Hz)
    VOICED_R = 0.5           # autocorrelation at the pitch lag for a frame to count as voiced
    SPEECH_VOICED = 0.2      # share of the louder frames (top 20 dB) that must be voiced
    ENV_RANGE = 30.0         # dB below the loudest frame that the envelope is clipped at
    SPEECH_MIN_DB = -70.0    # the loud frames of the voice band must be at least this (dBFS)
    STEADY_STD = 2.0         # dB over the last STEADY_N readings: at or under this it becomes steady,
    UNSTEADY_STD = 3.0       # and only over this does it stop being steady (hysteresis)
    STEADY_N = 12            # 6 s at a 0.5 s hop

    def __init__(self, rate=RATE, alpha=0.35):
        if np is None:
            raise RuntimeError("numpy is not installed (pip install numpy)")
        self.rate = rate
        self.alpha = alpha
        self.win = int(rate * WINDOW_S)
        self.hop = int(rate * HOP_S)
        freqs = np.fft.rfftfreq(self.win, 1.0 / rate)
        self.aw = np.array([10 ** (a_weight_db(f) / 10) if f > 0 else 0.0 for f in freqs])
        self.hann = np.hanning(self.win)
        ff = np.fft.rfftfreq(FRAME, 1.0 / rate)
        self.band = (ff >= 300) & (ff <= 3400)
        self.total = (ff >= 80) & (ff <= 8000)
        self.fhann = np.hanning(FRAME)
        pf = np.fft.rfftfreq(2 * PITCH_N, 1.0 / rate)                 # zero-padded: a linear autocorrelation
        self.pband = ((pf >= 300) & (pf <= 3400)).astype(np.float64)
        lags = np.arange(PITCH_N)
        self.unbias = PITCH_N / np.maximum(1, PITCH_N - lags)          # undo the shrinking overlap at long lags
        self.lag_lo, self.lag_hi = int(rate / 500), int(rate / 80)     # voice pitch 80-500 Hz
        mf = np.fft.rfftfreq(ENV_FRAMES, FRAME / rate)      # envelope modulation frequencies
        self.syll = (mf >= 2.0) & (mf <= 8.0)
        self.modall = (mf >= 0.5) & (mf <= 25.0)
        self.mhann = np.hanning(ENV_FRAMES)
        self.reset()

    def reset(self):
        self.buf = np.zeros(0, dtype=np.float32)
        self.pending = np.zeros(0, dtype=np.float32)     # samples not yet cut into speech frames
        self.prev = np.zeros(FRAME)                      # the previous frame, for the 40 ms pitch check
        self.voiced = collections.deque(maxlen=ENV_FRAMES)  # per frame: a voice-like pitch was found
        self.env = collections.deque(maxlen=ENV_FRAMES)  # voice-band dB per 20 ms frame
        self.eb = collections.deque(maxlen=ENV_FRAMES)   # voice-band energy per frame
        self.et = collections.deque(maxlen=ENV_FRAMES)   # 80-8000 Hz energy per frame
        self.hist = collections.deque(maxlen=self.STEADY_N)
        self.level = None
        self.steady = False
        self.samples = 0

    def window_db(self, w):
        """A-weighted RMS of one window, dBFS."""
        w = np.asarray(w, dtype=np.float64)
        w = w - w.mean()
        p = float(np.mean(w * w))
        if p <= 0:
            return FLOOR_DB
        spec = np.abs(np.fft.rfft(w * self.hann)) ** 2
        tot = float(spec.sum())
        if tot <= 0:
            return FLOOR_DB
        pa = p * float((spec * self.aw).sum()) / tot
        return max(FLOOR_DB, 10 * math.log10(max(pa, 1e-12)))

    def _frames(self, x):
        self.pending = np.concatenate([self.pending, x])
        n = len(self.pending) // FRAME
        for i in range(n):
            f = self.pending[i * FRAME:(i + 1) * FRAME].astype(np.float64)
            f = f - f.mean()
            s = np.abs(np.fft.rfft(f * self.fhann)) ** 2
            b, t = float(s[self.band].sum()), float(s[self.total].sum())
            self.eb.append(b)
            self.et.append(t)
            # per-frame power of the band, normalised to dBFS-ish (Hann sum^2 scaling)
            self.env.append(max(FLOOR_DB, 10 * math.log10(b / (FRAME * FRAME / 4) + 1e-12)))
            self.voiced.append(self.pitch_strength(np.concatenate([self.prev, f])) >= self.VOICED_R)
            self.prev = f
        self.pending = self.pending[n * FRAME:]

    def pitch_strength(self, x):
        """Peak normalised autocorrelation of the voice band at a pitch lag (80-500 Hz), counting only true
        local peaks: voiced speech has one at its pitch period; noise, clicks and rumble decay with no peak."""
        X = np.fft.rfft(x, 2 * PITCH_N)
        ac = np.fft.irfft(np.abs(X) ** 2 * self.pband)[:PITCH_N]
        if ac[0] <= 1e-12:
            return 0.0
        r = ac / ac[0] * self.unbias
        lo, hi = self.lag_lo, self.lag_hi
        seg = r[lo:hi + 1]
        peaks = (seg[1:-1] > seg[:-2]) & (seg[1:-1] >= seg[2:])
        if not peaks.any():
            return 0.0
        return float(seg[1:-1][peaks].max())

    def speech_features(self):
        """(speech, band_ratio, mod_ratio, depth) over the last 2 s of frames."""
        if len(self.env) < ENV_FRAMES:
            return False, 0.0, 0.0, 0.0
        tot = sum(self.et)
        ratio = sum(self.eb) / tot if tot > 0 else 0.0
        e = np.array(self.env)
        e = np.maximum(e, float(e.max()) - self.ENV_RANGE)   # pauses between words count as quiet, not -120 dB
        depth = float(e.std())
        m = np.abs(np.fft.rfft((e - e.mean()) * self.mhann)) ** 2
        allm = float(m[self.modall].sum())
        mod = float(m[self.syll].sum()) / allm if allm > 0 else 0.0
        loud = float(np.percentile(e, 90))
        top = e >= float(e.max()) - 20.0
        v = np.array(self.voiced, dtype=bool)
        voiced = float(v[top].mean()) if top.any() and len(v) == len(e) else 0.0
        self.voiced_frac = voiced
        speech = (ratio >= self.SPEECH_RATIO and depth >= self.SPEECH_DEPTH and mod >= self.SPEECH_MOD
                  and loud >= self.SPEECH_MIN_DB and voiced >= self.SPEECH_VOICED)
        return speech, ratio, mod, depth

    def feed(self, x):
        """Add samples; returns the Readings completed by them (usually zero or one)."""
        x = np.asarray(x, dtype=np.float32).ravel()
        out = []
        self._frames(x)
        self.buf = np.concatenate([self.buf, x])
        while len(self.buf) >= self.win:
            w = self.buf[:self.win]
            self.buf = self.buf[self.hop:]
            self.samples += self.hop
            db = self.window_db(w)
            self.level = db if self.level is None else self.level + self.alpha * (db - self.level)
            self.hist.append(db)
            if len(self.hist) >= self.STEADY_N:
                sd = float(np.std(np.array(self.hist)))
                self.steady = sd <= (self.UNSTEADY_STD if self.steady else self.STEADY_STD)
            steady = self.steady
            sp, ratio, mod, depth = self.speech_features()
            out.append(Reading(self.samples / self.rate, db, self.level, steady, sp, ratio, mod, depth))
        return out


# ---------------------------------------------------------------------------------------------------
# Deciding

class Target(collections.namedtuple("Target", "mode level conv duck")):
    """What the Crusher should be doing. mode: anc | transparency | off; level: transparency 0-100 or None;
    conv: chosen for a conversation; duck: lower the media volume while it lasts."""

    def label(self):
        name = {"anc": "ANC", "transparency": "Transparency", "off": "Off"}[self.mode]
        if self.mode == "transparency" and self.level is not None:
            name += f" {self.level}"
        return name + (" (conversation)" if self.conv else "")

    def as_dict(self):
        return {"mode": self.mode, "level": self.level, "conversation": self.conv, "duck": self.duck,
                "label": self.label()}


def T(mode, level=None, conv=False, duck=False):
    return Target(mode, level, conv, duck)


class Settings:
    """User settings, with defaults and clamping. Times are seconds unless named _min."""
    DEFAULTS = {
        "enabled": False,
        "sensitivity": 50,          # 0-100: higher reacts to quieter noise
        "conversation": True,       # conversation awareness
        "duck": False,              # lower the media volume while someone talks
        "mic": "",                  # "" = automatic (the first safe built-in mic)
        "pause_min": 10,            # after a manual change: pause this long; 0 = until turned back on
        "quiet_level": 50,          # transparency level in a quiet room
        "moderate_level": 20,       # transparency level in moderate, varying noise
        "conv_level": 100,          # transparency level while someone talks
        "hold_s": 4.0,              # a new situation must last this long before it is acted on
        "dwell_s": 12.0,            # and at least this long since the last switch
        "conv_return_s": 5.0,       # back to the room's mode this long after the speech stops
        "min_gap_s": 3.0,           # never more than one command batch per this
    }
    LIMITS = {"sensitivity": (0, 100), "pause_min": (0, 24 * 60), "quiet_level": (0, 100),
              "moderate_level": (0, 100), "conv_level": (0, 100), "hold_s": (0.5, 120), "dwell_s": (0, 600),
              "conv_return_s": (1, 120), "min_gap_s": (1, 60)}

    def __init__(self, d=None):
        self.d = dict(self.DEFAULTS)
        self.update(d or {})

    def update(self, d):
        for k, v in (d or {}).items():
            if k not in self.DEFAULTS or v is None:
                continue
            dv = self.DEFAULTS[k]
            if isinstance(dv, bool):
                v = v in (True, 1, "1", "on", "true", "yes")
            elif isinstance(dv, (int, float)):
                v = type(dv)(float(v))
                lo, hi = self.LIMITS.get(k, (None, None))
                if lo is not None:
                    v = max(type(dv)(lo), min(type(dv)(hi), v))
            else:
                v = str(v)
            self.d[k] = v
        return self

    def __getattr__(self, k):
        try:
            return self.__dict__["d"][k]
        except KeyError:
            raise AttributeError(k)

    def as_dict(self):
        return dict(self.d)


ZONES = ("quiet", "moderate", "loud")
QUIET_MAX = -50.0      # dBFS(A) at sensitivity 50: above this the room is no longer quiet
LOUD_MIN = -36.0       # and above this it is loud
HYST = 4.0             # dB between the up and the down threshold of each boundary
CONV_ENTER = 2         # consecutive speech readings (~1 s) to call it a conversation


class Policy:
    """The decision, with no I/O and an explicit clock so it can be tested with synthetic sequences.

    tick(reading, now) returns the Target to send now, or None. The caller sends it (when its rate
    limiter allows) and then calls committed(target, now); until then tick keeps proposing it.

    - Zones by the smoothed level with hysteresis (HYST dB), so a level sitting on a threshold never flaps.
    - A new zone target must persist hold_s, and dwell_s must have passed since the last switch.
    - Conversation: CONV_ENTER speech readings in a row switch to transparency high at once; conv_return_s
      without speech switches back at once (the dwell does not delay the return).
    - Readings taken while the room's own level is being spoken over don't move the zone (voices are not
      noise to block), and readings marked held (the host's speakers playing) are ignored entirely.
    - manual(now) pauses everything for pause_min minutes (0 = until resume()).
    """

    def __init__(self, settings=None, has_level=True):
        self.s = settings or Settings()
        self.has_level = has_level
        self.reset()

    def reset(self):
        self.zone = None
        self.steady = False
        self.applied = None           # the Target last sent (None = unknown)
        self.pending = None           # (Target, since)
        self.last_switch = -math.inf
        self.conv = False
        self.speech_run = 0
        self.last_speech = -math.inf
        self.paused_until = None

    # ---- thresholds and targets ----
    def thresholds(self):
        shift = (self.s.sensitivity - 50) * 0.2           # +-10 dB across the slider
        return QUIET_MAX - shift, LOUD_MIN - shift

    def classify(self, level):
        q, l = self.thresholds()
        h = HYST / 2
        z = self.zone
        if z is None:
            return "quiet" if level < q else ("moderate" if level < l else "loud")
        if z == "quiet":
            if level > l + h:
                return "loud"
            return "moderate" if level > q + h else "quiet"
        if z == "moderate":
            if level > l + h:
                return "loud"
            return "quiet" if level < q - h else "moderate"
        if level < q - h:
            return "quiet"
        return "moderate" if level < l - h else "loud"

    def zone_target(self):
        lvl = self.has_level
        if self.zone == "quiet":
            return T("transparency", self.s.quiet_level if lvl else None)
        if self.zone == "loud" or self.steady:
            return T("anc")
        return T("transparency", self.s.moderate_level) if lvl else T("off")

    def conv_target(self):
        return T("transparency", self.s.conv_level if self.has_level else None, conv=True, duck=self.s.duck)

    def desired(self):
        if self.zone is None:
            return None
        return self.conv_target() if self.conv else self.zone_target()

    # ---- manual override ----
    def manual(self, now):
        self.paused_until = math.inf if self.s.pause_min <= 0 else now + self.s.pause_min * 60.0

    def resume(self):
        """Clear a pause; the next decision starts fresh (it doesn't assume the Crusher is where we left it)."""
        paused = self.paused_until is not None
        self.reset()
        return paused

    def paused(self, now):
        if self.paused_until is None:
            return False
        if now >= self.paused_until:
            self.resume()
            return False
        return True

    def paused_for(self, now):
        if not self.paused(now):
            return 0.0
        return self.paused_until - now

    # ---- the decision ----
    def tick(self, r, now):
        if self.paused(now):
            return None
        if not r.held:
            if self.s.conversation:
                if r.speech:
                    self.speech_run += 1
                    if self.conv or self.speech_run >= CONV_ENTER:
                        self.last_speech = now
                else:
                    self.speech_run = 0
                if not self.conv and self.speech_run >= CONV_ENTER:
                    self.conv = True
            else:
                self.conv = False
                self.speech_run = 0
            if not (r.speech and self.zone is not None):
                self.zone = self.classify(r.level)
                self.steady = r.steady and self.zone != "quiet"
        if self.conv and now - self.last_speech >= self.s.conv_return_s:
            self.conv = False
        want = self.desired()
        if want is None:
            return None
        if want == self.applied:
            self.pending = None
            return None
        if want.conv:
            return want                                   # someone is talking: now
        if self.applied is not None and self.applied.conv:
            return want                                   # the talking stopped: back now
        if self.pending is None or self.pending[0] != want:
            self.pending = (want, now)
        if now - self.pending[1] >= self.s.hold_s and now - self.last_switch >= self.s.dwell_s:
            return want
        return None

    def committed(self, target, now):
        self.applied = target
        self.last_switch = now
        self.pending = None


# ---------------------------------------------------------------------------------------------------
# Sending

class RateLimiter:
    """At most one command batch per min_gap seconds (a failed send can push the next try further out)."""

    def __init__(self, min_gap=3.0):
        self.min_gap = min_gap
        self.next_ok = -math.inf

    def ready(self, now):
        return now >= self.next_ok

    def mark(self, now, extra=0.0):
        self.next_ok = now + self.min_gap + extra


def commands_for(prev, t, has_level=True):
    """AT commands to go from prev (a Target or None = unknown) to t. Same commands as the panel's mode
    buttons; the transparency level only when the firmware has it and it changed."""
    out = []
    same_mode = prev is not None and prev.mode == t.mode
    if t.mode == "anc":
        if not same_mode:
            out = ["AT.UIAUDIO=transparency,off", "AT.UIAUDIO=anc,on"]
    elif t.mode == "off":
        if not same_mode:
            out = ["AT.UIAUDIO=transparency,off", "AT.UIAUDIO=anc,off"]
    else:
        if not same_mode:
            out = ["AT.UIAUDIO=transparency,on"]
        if has_level and t.level is not None and (not same_mode or prev.level != t.level):
            out.append(f"AT.UIAUDIO=transparency,,{max(0, min(100, int(t.level)))}")
    return out


def mode_of(state):
    """'anc' | 'transparency' | 'off' from a crusher state dict ({anc, transparency})."""
    if state.get("anc"):
        return "anc"
    return "transparency" if state.get("transparency") else "off"


# ---------------------------------------------------------------------------------------------------
# The loop

class AdaptiveRunner:
    """Glues a microphone, the meter, the policy and the Crusher together. Platform pieces are passed in:

      open_capture(mic) -> object with read(n) -> float32 array (None at the end) and close()
      send(cmds)        -> raises on failure (Crusher refused or not reachable)
      read_mode()       -> 'anc' | 'transparency' | 'off' | None (to notice a change made elsewhere)
      connected()       -> is the Crusher connected to this machine (the mic stays closed if not)
      has_level()       -> does this firmware answer the transparency level
      gate()            -> True while the host's own speakers play (readings are ignored then)
      duck(on)          -> lower / restore the media volume
      load() / save(d)  -> settings persistence

    The mic is open only while Adaptive is on, not paused and the Crusher is connected.
    """
    BLOCK_S = 0.25
    MODE_CHECK_S = 30.0
    CONNECT_CHECK_S = 5.0
    GATE_CHECK_S = 3.0
    MANUAL_GRACE_S = 8.0      # a mode read this soon after our own send isn't taken as a manual change

    def __init__(self, open_capture, send, read_mode=None, connected=None, has_level=None, gate=None,
                 duck=None, load=None, save=None, clock=time.monotonic, log=print, rate=RATE):
        self.open_capture, self.send = open_capture, send
        self.read_mode = read_mode or (lambda: None)
        self.connected = connected or (lambda: True)
        self.has_level = has_level or (lambda: True)
        self.gate = gate or (lambda: False)
        self.duck = duck
        self._save = save or (lambda d: None)
        self.clock, self.log, self.rate = clock, log, rate
        self.settings = Settings((load or (lambda: {}))() or {})
        self.policy = Policy(self.settings)
        self.limiter = RateLimiter(self.settings.min_gap_s)
        self.meter = None
        self.cap = None
        self.lock = threading.RLock()
        self.wake = threading.Event()
        self.last = None              # last Reading
        self.status = "off"
        self.error = ""
        self.ducked = False
        self.last_send = -math.inf
        self.last_mode_check = -math.inf
        self.last_conn = (-math.inf, False)
        self.last_gate = (-math.inf, False)
        self.mic_open = ""
        self.thread = None

    # ---- control ----
    def set(self, **kw):
        """Change settings. enabled=True also clears a manual pause; enabled=False stops and restores."""
        with self.lock:
            was = self.settings.enabled
            self.settings.update(kw)
            self.limiter.min_gap = self.settings.min_gap_s
            if "enabled" in kw:
                if self.settings.enabled:
                    self.policy.resume()
                elif was:
                    self._stop_listening()
                    self._unduck()
                    self.policy.reset()
            if "mic" in kw and self.cap is not None:
                self._close()             # reopen on the new mic
            self._save(self.settings.as_dict())
        self.wake.set()
        return self.state()

    def manual(self, source="manual"):
        """A mode was changed by hand (panel, ring, CLI, the headset's button): step aside for a while."""
        with self.lock:
            if not self.settings.enabled:
                return False
            self.policy.manual(self.clock())
            self._stop_listening()
            self._unduck()
            self.log(f"crusher adaptive: paused ({source})")
        self.wake.set()
        return True

    def shutdown(self):
        """Close the mic and put the volume back (the program is exiting)."""
        with self.lock:
            self._stop_listening()
            self._unduck()

    def state(self):
        with self.lock:
            now = self.clock()
            r = self.last
            q, l = self.policy.thresholds()
            paused = self.policy.paused_for(now) if self.settings.enabled else 0.0
            return {
                "available": np is not None,
                "enabled": self.settings.enabled,
                "listening": self.cap is not None,
                "mic": self.mic_open,
                "status": self.status,
                "error": self.error,
                "ambient_db": round(r.level, 1) if r else None,
                "raw_db": round(r.db, 1) if r else None,
                "zone": self.policy.zone,
                "steady": self.policy.steady,
                "speech": bool(r and r.speech),
                "held": bool(r and r.held),
                "conversation": self.policy.conv,
                "target": self.policy.applied.as_dict() if self.policy.applied else None,
                "paused_for_s": None if paused == math.inf else round(paused),
                "paused": paused > 0,
                "thresholds": {"quiet_max": round(q, 1), "loud_min": round(l, 1)},
                "settings": self.settings.as_dict(),
            }

    # ---- internals ----
    def _close(self):
        c, self.cap = self.cap, None
        self.mic_open = ""
        if c is not None:
            try:
                c.close()
            except Exception:
                pass

    def _stop_listening(self):
        self._close()
        if self.meter is not None:
            self.meter.reset()
        self.last = None

    def _unduck(self):
        if self.ducked and self.duck:
            try:
                self.duck(False)
            except Exception as e:
                self.log(f"crusher adaptive: restore volume: {e!r}")
        self.ducked = False

    def _cached(self, attr, fn, every, now):
        t, v = getattr(self, attr)
        if now - t >= every:
            try:
                v = bool(fn())
            except Exception:
                v = False
            setattr(self, attr, (now, v))
        return v

    def apply(self, target, now):
        """Send one target (rate limited). Returns True if it was sent and committed."""
        if not self.limiter.ready(now):
            return False
        hl = bool(self.has_level())
        self.policy.has_level = hl
        cmds = commands_for(self.policy.applied, target, hl)
        try:
            if cmds:
                self.send(cmds)
        except Exception as e:
            self.error = f"Crusher: {e}"
            self.limiter.mark(now, extra=7.0)
            return False
        self.limiter.mark(now)
        if target.duck != self.ducked and self.duck:
            try:
                self.duck(target.duck)
                self.ducked = target.duck
            except Exception as e:
                self.log(f"crusher adaptive: volume: {e!r}")
        self.error = ""
        self.last_send = now
        self.policy.committed(target, now)
        self.log(f"crusher adaptive: {target.label()} (room {self.last.level:.0f} dB, {self.policy.zone})"
                 if self.last else f"crusher adaptive: {target.label()}")
        return True

    def process(self, block, now=None):
        """Measure one block of samples and act on the readings it completes."""
        if self.meter is None:
            self.meter = AmbientMeter(self.rate)
        for r in self.meter.feed(block):
            now = self.clock() if now is None else now
            r.held = self._cached("last_gate", self.gate, self.GATE_CHECK_S, now)
            self.last = r
            t = self.policy.tick(r, now)
            if t is not None:
                self.apply(t, now)

    def observe(self, mode, now):
        """The Crusher's mode as read back; a change we didn't make pauses Adaptive."""
        a = self.policy.applied
        if mode is None or a is None or now - self.last_send < self.MANUAL_GRACE_S:
            return False
        if mode != a.mode:
            self.manual("changed on the headset or elsewhere")
            return True
        return False

    def step(self):
        """One pass of the loop. Returns how long to wait before the next pass (0 = read audio again now)."""
        with self.lock:
            now = self.clock()
            if not self.settings.enabled:
                self._stop_listening()
                self.status = "off"
                return 1.0
            if self.policy.paused(now):
                self._stop_listening()
                left = self.policy.paused_for(now)
                self.status = ("paused after a manual change" +
                               ("" if left == math.inf else f" ({math.ceil(left / 60)} min left)"))
                return 1.0
            if not self._cached("last_conn", self.connected, self.CONNECT_CHECK_S, now):
                self._stop_listening()
                self._unduck()
                self.policy.reset()
                self.status = "waiting for the Crusher to connect"
                return 2.0
            if self.cap is None:
                try:
                    self.cap = self.open_capture(self.settings.mic)
                    self.mic_open = getattr(self.cap, "label", "") or self.settings.mic or "auto"
                    self.error = ""
                except Exception as e:
                    self.cap = None
                    self.status = "no microphone"
                    self.error = str(e)
                    return 5.0
            cap = self.cap
        block = cap.read(int(self.rate * self.BLOCK_S))      # blocks ~0.25 s, outside the lock
        with self.lock:
            if cap is not self.cap:
                return 0.0
            if block is None:
                self._close()
                self.error = "the microphone stopped"
                return 2.0
            now = self.clock()
            self.process(block, now)
            if self.last is None:
                self.status = "listening..."
            elif self.last.held:
                self.status = "holding: the computer's speakers are playing"
            else:
                self.status = "adapting"
            if now - self.last_mode_check >= self.MODE_CHECK_S and self.policy.applied is not None:
                self.last_mode_check = now
                try:
                    m = self.read_mode()
                except Exception:
                    m = None
                self.observe(m, now)
        return 0.0

    def run(self):
        while True:
            try:
                wait = self.step()
            except Exception as e:     # never let the loop die
                self.error = repr(e)
                self._close()
                wait = 5.0
            if wait:
                self.wake.wait(wait)
                self.wake.clear()

    def start(self):
        if self.thread is None:
            self.thread = threading.Thread(target=self.run, daemon=True, name="crusher-adaptive")
            self.thread.start()
        return self
