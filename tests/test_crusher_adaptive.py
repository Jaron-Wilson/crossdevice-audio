"""Tests for shared/crusher_adaptive.py: the measuring on generated signals, the policy on synthetic
level/speech sequences, the AT commands and the rate limiting. No hardware, no microphone.

    python3 -m unittest discover -s tests -v

The meter tests need numpy (they are skipped without it). If android/testdata/librispeech is present
(android/tools/fetch_testdata.py), real speech is checked too.
"""
import glob
import math
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "shared"))
import crusher_adaptive as ca   # noqa: E402

try:
    import numpy as np
except Exception:
    np = None

R = ca.RATE


# ---------------------------------------------------------------------------------------------------
# signals

def _t(secs):
    return np.arange(int(R * secs)) / R


def sine(f, amp, secs):
    return (amp * np.sin(2 * np.pi * f * _t(secs))).astype(np.float32)


def shaped_noise(secs, slope=0.0, seed=1, lo=20, hi=8000):
    """Noise with a 1/f^slope power spectrum (0 white, 1 pink, 2 brown), unit RMS."""
    rng = np.random.default_rng(seed)
    n = int(R * secs)
    X = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / R)
    X = X / np.maximum(f, 20) ** (slope / 2)
    X[(f < lo) | (f > hi)] = 0
    x = np.fft.irfft(X, n)
    return (x / x.std()).astype(np.float32)


def syllables(secs, seed=3):
    """A syllable-rate envelope: 120-250 ms bursts with 40-120 ms gaps (about 4-5 a second)."""
    rng = np.random.default_rng(seed)
    n = int(R * secs)
    env = np.zeros(n)
    pos = 0
    while pos < n:
        L = min(int(R * rng.uniform(0.12, 0.25)), n - pos)
        env[pos:pos + L] = np.hanning(L)
        pos += L + int(R * rng.uniform(0.04, 0.12))
    return env


def voice(secs, f0=130.0, seed=3):
    """A crude talker: a gliding pitch, formant-shaped harmonics (600 / 1200 / 2500 Hz), syllable bursts."""
    t = _t(secs)
    ph = 2 * np.pi * np.cumsum(f0 * (1 + 0.05 * np.sin(2 * np.pi * 0.7 * t))) / R
    v = np.zeros_like(t)
    for k in range(1, 40):
        fk = f0 * k
        if fk > 3800:
            break
        g = sum(math.exp(-((fk - F) / B) ** 2) for F, B in ((600, 150), (1200, 200), (2500, 300))) + 0.05
        v += g * np.sin(k * ph)
    return (v / v.std() * syllables(secs, seed)).astype(np.float32)


def clicks(secs, per_s=6, seed=5):
    """Keyboard-like clicks: short decaying broadband bursts at typing rate."""
    rng = np.random.default_rng(seed)
    n = int(R * secs)
    x = np.zeros(n)
    for p in rng.integers(0, n - 200, int(per_s * secs)):
        x[p:p + 200] += rng.standard_normal(200) * np.exp(-np.arange(200) / 30)
    return (0.2 * x + 0.001 * rng.standard_normal(n)).astype(np.float32)


def measure(x, block=4000):
    m = ca.AmbientMeter()
    out = []
    for i in range(0, len(x), block):
        out += m.feed(x[i:i + block])
    return out


def speech_share(x, skip=4):
    rs = measure(x)[skip:]
    return sum(r.speech for r in rs) / max(1, len(rs))


# ---------------------------------------------------------------------------------------------------
# measuring

class AWeightingTest(unittest.TestCase):
    def test_reference_points(self):
        self.assertAlmostEqual(ca.a_weight_db(1000), 0.0, delta=0.1)
        self.assertAlmostEqual(ca.a_weight_db(100), -19.1, delta=0.2)
        self.assertAlmostEqual(ca.a_weight_db(10000), -2.5, delta=0.2)
        self.assertAlmostEqual(ca.a_weight_db(50), -30.2, delta=0.3)


@unittest.skipIf(np is None, "numpy not installed")
class LevelTest(unittest.TestCase):
    def test_sine_1k_reads_its_rms(self):
        r = measure(sine(1000, 0.1, 3))[-1]
        self.assertAlmostEqual(r.db, 20 * math.log10(0.1 / math.sqrt(2)), delta=0.2)    # -23.0 dB

    def test_low_tone_is_a_weighted_down(self):
        a = measure(sine(1000, 0.1, 3))[-1].db
        b = measure(sine(100, 0.1, 3))[-1].db
        self.assertAlmostEqual(a - b, 19.1, delta=0.4)

    def test_six_db_per_doubling(self):
        a = measure(sine(1000, 0.05, 3))[-1].db
        b = measure(sine(1000, 0.1, 3))[-1].db
        self.assertAlmostEqual(b - a, 6.02, delta=0.1)

    def test_silence_is_the_floor(self):
        r = measure(np.zeros(R * 2, dtype=np.float32))[-1]
        self.assertEqual(r.db, ca.FLOOR_DB)

    def test_dc_offset_is_ignored(self):
        x = sine(1000, 0.1, 3) + 0.3
        self.assertAlmostEqual(measure(x)[-1].db, -23.0, delta=0.2)

    def test_reading_every_half_second(self):
        rs = measure(sine(1000, 0.1, 5))
        self.assertEqual(len(rs), 9)                  # first at 1 s, then every 0.5 s up to 5 s
        self.assertAlmostEqual(rs[1].t - rs[0].t, 0.5)

    def test_block_size_does_not_matter(self):
        x = 0.05 * shaped_noise(4) + voice(4) * 0.05
        a = [(round(r.db, 6), r.speech) for r in measure(x, block=4000)]
        b = [(round(r.db, 6), r.speech) for r in measure(x, block=777)]
        self.assertEqual(a, b)

    def test_smoothing_follows_a_step(self):
        x = np.concatenate([sine(1000, 0.001, 4), sine(1000, 0.1, 6)])
        rs = measure(x)
        first_loud = next(i for i, r in enumerate(rs) if r.db > -30)
        self.assertLess(rs[first_loud].level, rs[first_loud].db - 3)   # one loud window doesn't jump it all
        self.assertAlmostEqual(rs[-1].level, -23.0, delta=0.5)        # but it gets there

    def test_steady_noise_is_steady(self):
        self.assertTrue(measure(0.05 * shaped_noise(10, 1))[-1].steady)

    def test_varying_noise_is_not_steady(self):
        n = shaped_noise(12, 1)
        gain = np.repeat(np.tile([0.02, 0.1], 6), R)
        self.assertFalse(measure((n * gain).astype(np.float32))[-1].steady)


@unittest.skipIf(np is None, "numpy not installed")
class SpeechTest(unittest.TestCase):
    def test_talker_is_speech(self):
        self.assertGreater(speech_share(0.05 * voice(12)), 0.8)

    def test_higher_voice_is_speech(self):
        self.assertGreater(speech_share(0.05 * voice(12, f0=220, seed=8)), 0.8)

    def test_talker_over_fan_noise_is_speech(self):
        x = 0.05 * voice(12) + 0.02 * shaped_noise(12, 1)
        self.assertGreater(speech_share(x.astype(np.float32)), 0.7)

    def test_noise_is_not_speech(self):
        for slope in (0, 1, 2):
            with self.subTest(slope=slope):
                self.assertEqual(speech_share(0.05 * shaped_noise(12, slope)), 0.0)

    def test_hum_is_not_speech(self):
        self.assertEqual(speech_share(sine(120, 0.05, 12)), 0.0)

    def test_syllable_rate_rumble_is_not_speech(self):
        x = 0.05 * shaped_noise(12, 0, lo=20, hi=200) * (0.55 + 0.45 * np.sin(2 * np.pi * 4 * _t(12)))
        self.assertEqual(speech_share(x.astype(np.float32)), 0.0)

    def test_syllable_rate_noise_is_not_speech(self):
        """Hiss chopped at 4 Hz has the rhythm but no pitch."""
        x = 0.05 * shaped_noise(12, 0) * (0.55 + 0.45 * np.sin(2 * np.pi * 4 * _t(12)))
        self.assertEqual(speech_share(x.astype(np.float32)), 0.0)

    def test_typing_is_not_speech(self):
        self.assertEqual(speech_share(clicks(12)), 0.0)

    def test_slow_swell_is_not_speech(self):
        x = 0.05 * voice(12) * 0 + 0.05 * shaped_noise(12, 1) * (0.55 + 0.45 * np.sin(2 * np.pi * 0.5 * _t(12)))
        self.assertEqual(speech_share(x.astype(np.float32)), 0.0)

    def test_faint_talker_far_below_the_floor_is_not_speech(self):
        self.assertEqual(speech_share(1e-5 * voice(12)), 0.0)

    @unittest.skipUnless(glob.glob(os.path.join(ROOT, "android", "testdata", "librispeech", "*.pcm")),
                         "LibriSpeech excerpts not fetched (android/tools/fetch_testdata.py)")
    def test_librispeech(self):
        for f in sorted(glob.glob(os.path.join(ROOT, "android", "testdata", "librispeech", "*.pcm"))):
            x = np.fromfile(f, dtype="<i2")[:R * 30].astype(np.float32) / 32768
            with self.subTest(file=os.path.basename(f)):
                self.assertGreater(speech_share(x), 0.55)


# ---------------------------------------------------------------------------------------------------
# deciding

def rd(level, speech=False, steady=False, held=False):
    return ca.Reading(0.0, level, level, steady, speech, held=held)


class Sim:
    """Drives a Policy with a sequence of readings, every dt seconds, committing what it asks for (as the
    runner does when its rate limiter allows)."""

    def __init__(self, policy, dt=0.5, limiter=None):
        self.p, self.dt, self.now, self.sent = policy, dt, 0.0, []
        self.limiter = limiter

    def run(self, readings):
        for r in readings:
            self.now += self.dt
            t = self.p.tick(r, self.now)
            if t is not None and (self.limiter is None or self.limiter.ready(self.now)):
                if self.limiter:
                    self.limiter.mark(self.now)
                self.p.committed(t, self.now)
                self.sent.append((self.now, t))
        return self

    def hold(self, reading, secs):
        return self.run([reading] * int(round(secs / self.dt)))

    def modes(self):
        return [(t.mode, t.level) for _, t in self.sent]


QUIET, MODERATE, LOUD = -60.0, -43.0, -25.0


class PolicyZoneTest(unittest.TestCase):
    def test_quiet_room_waits_for_hold_then_transparency(self):
        s = Sim(ca.Policy())
        s.hold(rd(QUIET), 3.5)
        self.assertEqual(s.sent, [])
        s.hold(rd(QUIET), 1.0)
        self.assertEqual(s.modes(), [("transparency", 50)])

    def test_zones_map_to_targets(self):
        for level, steady, want in ((QUIET, False, ("transparency", 50)), (MODERATE, False, ("transparency", 20)),
                                    (MODERATE, True, ("anc", None)), (LOUD, False, ("anc", None))):
            with self.subTest(level=level, steady=steady):
                s = Sim(ca.Policy()).hold(rd(level, steady=steady), 10)
                self.assertEqual(s.modes(), [want])

    def test_quiet_steady_hum_stays_transparency(self):
        s = Sim(ca.Policy()).hold(rd(QUIET, steady=True), 10)
        self.assertEqual(s.modes(), [("transparency", 50)])

    def test_firmware_without_a_level(self):
        p = ca.Policy(has_level=False)
        s = Sim(p).hold(rd(QUIET), 6)
        self.assertEqual(s.modes(), [("transparency", None)])
        s.hold(rd(MODERATE), 20)
        self.assertEqual(s.modes()[-1], ("off", None))

    def test_hysteresis_near_a_threshold_never_flaps(self):
        p = ca.Policy()
        q, _ = p.thresholds()
        s = Sim(p).hold(rd(q - 5), 6)                 # settle in quiet
        wobble = [rd(q + (1.9 if i % 6 < 3 else -1.9)) for i in range(1200)]   # 10 min within +-HYST/2
        s.run(wobble)
        self.assertEqual(s.modes(), [("transparency", 50)])

    def test_crossing_the_band_switches_once(self):
        p = ca.Policy()
        q, _ = p.thresholds()
        s = Sim(p).hold(rd(q - 5), 6)
        s.hold(rd(q + 2.5), 30)
        self.assertEqual(s.modes(), [("transparency", 50), ("transparency", 20)])
        s.hold(rd(q - 1.5), 60)                       # back below the threshold, but inside the band
        self.assertEqual(len(s.sent), 2)
        s.hold(rd(q - 2.5), 30)
        self.assertEqual(s.modes()[-1], ("transparency", 50))

    def test_short_spike_is_ignored(self):
        s = Sim(ca.Policy()).hold(rd(QUIET), 20)
        s.hold(rd(LOUD), 2)                           # a door slam
        s.hold(rd(QUIET), 30)
        self.assertEqual(s.modes(), [("transparency", 50)])

    def test_dwell_between_switches(self):
        p = ca.Policy()
        s = Sim(p).hold(rd(QUIET), 5)
        first = s.sent[0][0]
        s.hold(rd(LOUD), 30)
        second = s.sent[1][0]
        self.assertGreaterEqual(second - first, p.s.dwell_s)
        s.hold(rd(QUIET), 30)                         # right back: the hold applies, and the dwell
        third = s.sent[2][0]
        self.assertGreaterEqual(third - second, p.s.dwell_s)
        self.assertEqual(s.modes(), [("transparency", 50), ("anc", None), ("transparency", 50)])

    def test_alternating_every_few_seconds_switches_at_most_once_per_dwell(self):
        p = ca.Policy()
        seq = []
        for i in range(60):                           # 5 minutes, loud and quiet every 5 s
            seq += [rd(LOUD if i % 2 else QUIET)] * 10
        s = Sim(p).run(seq)
        times = [t for t, _ in s.sent]
        self.assertTrue(all(b - a >= p.s.dwell_s for a, b in zip(times, times[1:])))
        self.assertLessEqual(len(times), 300 / p.s.dwell_s + 1)

    def test_sensitivity_moves_the_thresholds(self):
        lo, hi = ca.Policy(ca.Settings({"sensitivity": 0})), ca.Policy(ca.Settings({"sensitivity": 100}))
        self.assertEqual(lo.thresholds()[0] - hi.thresholds()[0], 20.0)
        s = Sim(hi).hold(rd(-55), 6)                  # quiet at 50, already "some noise" at 100
        self.assertEqual(s.modes(), [("transparency", 20)])
        s = Sim(lo).hold(rd(-45), 6)
        self.assertEqual(s.modes(), [("transparency", 50)])

    def test_held_readings_are_ignored(self):
        s = Sim(ca.Policy()).hold(rd(QUIET), 6)
        s.hold(rd(LOUD, held=True), 60)               # the laptop's speakers are playing
        self.assertEqual(s.modes(), [("transparency", 50)])


class PolicyConversationTest(unittest.TestCase):
    def test_talking_switches_at_once_and_returns_after_5s(self):
        p = ca.Policy()
        s = Sim(p).hold(rd(LOUD), 6)
        self.assertEqual(s.modes(), [("anc", None)])
        t0 = s.now
        s.hold(rd(LOUD, speech=True), 3)
        conv = s.sent[1]
        self.assertTrue(conv[1].conv)
        self.assertEqual((conv[1].mode, conv[1].level), ("transparency", 100))
        self.assertLessEqual(conv[0] - t0, 1.0)       # two speech readings, no hold, no dwell
        stop = s.now
        s.hold(rd(LOUD), 10)
        back = s.sent[2]
        self.assertEqual((back[1].mode, back[1].conv), ("anc", False))
        self.assertAlmostEqual(back[0] - stop, 5.0, delta=0.6)

    def test_a_single_speech_blip_does_nothing(self):
        s = Sim(ca.Policy()).hold(rd(QUIET), 6)
        s.run([rd(QUIET, speech=True), rd(QUIET), rd(QUIET, speech=True), rd(QUIET)] * 10)
        self.assertEqual(len(s.sent), 1)

    def test_pauses_in_speech_shorter_than_5s_keep_it(self):
        s = Sim(ca.Policy()).hold(rd(QUIET), 6)
        for _ in range(5):
            s.hold(rd(QUIET, speech=True), 2)
            s.hold(rd(QUIET), 3)
        self.assertEqual(len(s.sent), 2)              # entered once, never left
        s.hold(rd(QUIET), 6)
        self.assertEqual(len(s.sent), 3)

    def test_voices_do_not_move_the_zone(self):
        p = ca.Policy()
        s = Sim(p).hold(rd(QUIET), 6)
        s.hold(rd(LOUD, speech=True), 20)             # loud because people talk
        self.assertEqual(p.zone, "quiet")
        s.hold(rd(QUIET), 6)
        self.assertEqual(s.modes()[-1], ("transparency", 50))

    def test_off_when_conversation_awareness_is_off(self):
        s = Sim(ca.Policy(ca.Settings({"conversation": False}))).hold(rd(QUIET), 6)
        s.hold(rd(QUIET, speech=True), 20)
        self.assertEqual(len(s.sent), 1)

    def test_duck_flag(self):
        s = Sim(ca.Policy(ca.Settings({"duck": True}))).hold(rd(QUIET), 6)
        s.hold(rd(QUIET, speech=True), 2)
        self.assertTrue(s.sent[-1][1].duck)
        s.hold(rd(QUIET), 6)
        self.assertFalse(s.sent[-1][1].duck)

    def test_turning_awareness_off_mid_conversation_returns(self):
        p = ca.Policy()
        s = Sim(p).hold(rd(LOUD), 6)
        s.hold(rd(LOUD, speech=True), 2)
        p.s.update({"conversation": False})
        s.hold(rd(LOUD, speech=True), 1)
        self.assertEqual(s.modes()[-1], ("anc", None))


class PolicyManualTest(unittest.TestCase):
    def test_manual_change_pauses_for_n_minutes(self):
        p = ca.Policy(ca.Settings({"pause_min": 10}))
        s = Sim(p).hold(rd(QUIET), 6)
        p.manual(s.now)
        s.hold(rd(LOUD), 9 * 60)
        self.assertEqual(len(s.sent), 1)              # nothing while paused
        s.hold(rd(LOUD), 2 * 60)
        self.assertEqual(s.modes()[-1], ("anc", None))
        self.assertGreaterEqual(s.sent[-1][0], 6 + 600)

    def test_resume_starts_fresh_with_the_hold(self):
        p = ca.Policy()
        s = Sim(p).hold(rd(QUIET), 6)
        p.manual(s.now)
        s.hold(rd(QUIET), 30)
        p.resume()
        t0 = s.now
        s.hold(rd(QUIET), 6)
        self.assertEqual(len(s.sent), 2)              # re-applies (the Crusher may be elsewhere now)
        self.assertGreaterEqual(s.sent[-1][0] - t0, p.s.hold_s)

    def test_pause_zero_means_until_turned_back_on(self):
        p = ca.Policy(ca.Settings({"pause_min": 0}))
        s = Sim(p).hold(rd(QUIET), 6)
        p.manual(s.now)
        s.hold(rd(LOUD), 3 * 3600)
        self.assertEqual(len(s.sent), 1)
        self.assertTrue(p.paused(s.now))
        p.resume()
        s.hold(rd(LOUD), 6)
        self.assertEqual(s.modes()[-1], ("anc", None))

    def test_manual_during_conversation(self):
        p = ca.Policy()
        s = Sim(p).hold(rd(QUIET), 6)
        s.hold(rd(QUIET, speech=True), 2)
        p.manual(s.now)
        s.hold(rd(QUIET), 60)
        self.assertEqual(len(s.sent), 2)              # no "return" while the user is in charge


class SettingsTest(unittest.TestCase):
    def test_clamping_and_types(self):
        st = ca.Settings({"sensitivity": 500, "pause_min": -3, "conversation": "off", "duck": "on",
                          "mic": 7, "unknown": 1})
        self.assertEqual(st.sensitivity, 100)
        self.assertEqual(st.pause_min, 0)
        self.assertFalse(st.conversation)
        self.assertTrue(st.duck)
        self.assertEqual(st.mic, "7")
        self.assertNotIn("unknown", st.as_dict())


# ---------------------------------------------------------------------------------------------------
# sending

class CommandsTest(unittest.TestCase):
    def test_anc(self):
        self.assertEqual(ca.commands_for(None, ca.T("anc")), ["AT.UIAUDIO=transparency,off", "AT.UIAUDIO=anc,on"])
        self.assertEqual(ca.commands_for(ca.T("anc"), ca.T("anc")), [])

    def test_off(self):
        self.assertEqual(ca.commands_for(ca.T("anc"), ca.T("off")),
                         ["AT.UIAUDIO=transparency,off", "AT.UIAUDIO=anc,off"])

    def test_transparency_with_level(self):
        self.assertEqual(ca.commands_for(ca.T("anc"), ca.T("transparency", 50)),
                         ["AT.UIAUDIO=transparency,on", "AT.UIAUDIO=transparency,,50"])
        self.assertEqual(ca.commands_for(ca.T("transparency", 50), ca.T("transparency", 20)),
                         ["AT.UIAUDIO=transparency,,20"])
        self.assertEqual(ca.commands_for(ca.T("transparency", 50), ca.T("transparency", 50, conv=True)), [])

    def test_no_level_on_firmware_without_it(self):
        self.assertEqual(ca.commands_for(None, ca.T("transparency", 50), has_level=False),
                         ["AT.UIAUDIO=transparency,on"])

    def test_mode_of(self):
        self.assertEqual(ca.mode_of({"anc": True}), "anc")
        self.assertEqual(ca.mode_of({"transparency": True}), "transparency")
        self.assertEqual(ca.mode_of({}), "off")


class RateLimiterTest(unittest.TestCase):
    def test_one_batch_per_gap(self):
        rl = ca.RateLimiter(3.0)
        self.assertTrue(rl.ready(0))
        rl.mark(0)
        self.assertFalse(rl.ready(2.9))
        self.assertTrue(rl.ready(3.0))

    def test_policy_never_sends_faster_than_the_limiter(self):
        """Conversation enter/exit bypass the dwell, so the limiter is what spaces them."""
        st = ca.Settings({"conv_return_s": 1.0})
        rl = ca.RateLimiter(3.0)
        seq = []
        for _ in range(40):
            seq += [rd(LOUD, speech=True)] * 2 + [rd(LOUD)] * 3
        s = Sim(ca.Policy(st), limiter=rl).hold(rd(LOUD), 6).run(seq)
        times = [t for t, _ in s.sent]
        self.assertGreater(len(times), 5)
        self.assertTrue(all(b - a >= 3.0 for a, b in zip(times, times[1:])))


# ---------------------------------------------------------------------------------------------------
# the runner, with fakes for the clock, the mic and the Crusher

class FakeClock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class FakeMeter:
    """Stands in for AmbientMeter: each fed block yields the next scripted reading."""

    def __init__(self, readings):
        self.readings = list(readings)

    def feed(self, block):
        return [self.readings.pop(0)] if self.readings else []

    def reset(self):
        pass


class FakeCap:
    opened = 0

    def __init__(self, mic):
        FakeCap.opened += 1
        self.label, self.closed = mic or "test mic", False

    def read(self, n):
        return [0.0] * n

    def close(self):
        self.closed = True


class RunnerTest(unittest.TestCase):
    def make(self, readings=(), connected=True, fail=False, **settings):
        self.clock = FakeClock()
        self.sent, self.ducks, self.saved = [], [], []
        self.mode = None

        def send(cmds):
            if fail:
                raise OSError("not reachable")
            self.sent.append((self.clock.t, list(cmds)))

        FakeCap.opened = 0
        r = ca.AdaptiveRunner(open_capture=FakeCap, send=send, read_mode=lambda: self.mode,
                              connected=lambda: connected, duck=self.ducks.append, load=lambda: settings,
                              save=self.saved.append, clock=self.clock, log=lambda m: None)
        r.meter = FakeMeter(readings)
        return r

    def steps(self, r, n, dt=0.5):
        for _ in range(n):
            self.clock.t += dt
            r.step()

    def test_disabled_never_opens_the_mic(self):
        r = self.make([rd(QUIET)] * 20)
        self.steps(r, 20)
        self.assertEqual(FakeCap.opened, 0)
        self.assertEqual(self.sent, [])
        self.assertEqual(r.state()["status"], "off")

    def test_no_crusher_no_mic(self):
        r = self.make([rd(QUIET)] * 20, connected=False, enabled=True)
        self.steps(r, 20)
        self.assertEqual(FakeCap.opened, 0)
        self.assertIn("waiting", r.state()["status"])

    def test_enabled_adapts_and_sends_at(self):
        r = self.make([rd(QUIET)] * 12, enabled=True)
        self.steps(r, 12)
        self.assertEqual(FakeCap.opened, 1)
        self.assertEqual(self.sent[0][1], ["AT.UIAUDIO=transparency,on", "AT.UIAUDIO=transparency,,50"])
        self.assertEqual(r.state()["target"]["mode"], "transparency")

    def test_runner_rate_limits(self):
        r = self.make(enabled=True)
        now = self.clock.t
        self.assertTrue(r.apply(ca.T("anc"), now))
        self.assertFalse(r.apply(ca.T("off"), now + 1))
        self.assertTrue(r.apply(ca.T("off"), now + 3))
        self.assertEqual(len(self.sent), 2)

    def test_failed_send_is_not_committed_and_backs_off(self):
        r = self.make(enabled=True, fail=True)
        now = self.clock.t
        self.assertFalse(r.apply(ca.T("anc"), now))
        self.assertIsNone(r.policy.applied)
        self.assertIn("not reachable", r.state()["error"])
        self.assertFalse(r.limiter.ready(now + 5))
        self.assertTrue(r.limiter.ready(now + 10))

    def test_manual_pauses_and_closes_the_mic(self):
        r = self.make([rd(QUIET)] * 40, enabled=True, pause_min=10)
        self.steps(r, 12)
        cap = r.cap
        r.manual("test")
        self.assertTrue(cap.closed)
        self.steps(r, 20)
        self.assertIsNone(r.cap)
        self.assertTrue(r.state()["paused"])
        self.assertIn("paused", r.state()["status"])
        r.set(enabled=True)                          # picking Adaptive again resumes at once
        self.assertFalse(r.state()["paused"])

    def test_a_change_on_the_headset_is_noticed(self):
        r = self.make([rd(QUIET)] * 200, enabled=True)
        self.steps(r, 12)
        self.assertEqual(r.policy.applied.mode, "transparency")
        self.mode = "anc"                            # someone pressed the Crusher's button
        self.steps(r, 70)                            # the mode is read back every 30 s
        self.assertTrue(r.state()["paused"])

    def test_our_own_change_is_not_taken_as_manual(self):
        r = self.make(enabled=True)
        r.policy.zone = "quiet"
        now = self.clock.t
        r.apply(ca.T("anc"), now)
        self.assertFalse(r.observe("off", now + 2))  # within the grace time after our own send
        self.assertTrue(r.observe("off", now + 20))

    def test_duck_on_conversation_and_back(self):
        seq = [rd(QUIET)] * 12 + [rd(QUIET, speech=True)] * 4 + [rd(QUIET)] * 16
        r = self.make(seq, enabled=True, duck=True)
        self.steps(r, len(seq))
        self.assertEqual(self.ducks, [True, False])

    def test_turning_off_restores_the_volume(self):
        seq = [rd(QUIET)] * 12 + [rd(QUIET, speech=True)] * 4
        r = self.make(seq, enabled=True, duck=True)
        self.steps(r, len(seq))
        self.assertEqual(self.ducks, [True])
        r.set(enabled=False)
        self.assertEqual(self.ducks, [True, False])
        self.assertIsNone(r.cap)

    def test_settings_are_clamped_and_saved(self):
        r = self.make()
        st = r.set(sensitivity=500, conversation=False)
        self.assertEqual(st["settings"]["sensitivity"], 100)
        self.assertFalse(self.saved[-1]["conversation"])


# ---------------------------------------------------------------------------------------------------
# which microphones may be opened (the Windows daemon's rule; the laptop uses hp_sound.sources())

class WindowsMicChoiceTest(unittest.TestCase):
    def setUp(self):
        sys.path.insert(0, os.path.join(ROOT, "windows"))
        import crusher_adaptive_win as caw         # importable anywhere: winmm is loaded lazily
        self.caw = caw
        self.real = caw.list_mics

    def tearDown(self):
        self.caw.list_mics = self.real

    def fake(self, names):
        self.caw.list_mics = lambda: [(i, n, self.caw.mic_is_safe(n)) for i, n in enumerate(names)]

    def test_headset_mics_are_never_safe(self):
        for n in ("Headset (Crusher ANC 2 Hands-F", "Headset Microphone (2- AirPods", "Hands-Free AG Audio",
                  "Microphone (Bluetooth Audio)", "Skullcandy Crusher ANC 2"):
            with self.subTest(n=n):
                self.assertFalse(self.caw.mic_is_safe(n))
        for n in ("Microphone Array (Realtek(R) Au", "Microphone (C920 HD Pro Webcam)", "Mic in at front panel"):
            with self.subTest(n=n):
                self.assertTrue(self.caw.mic_is_safe(n))

    def test_auto_skips_the_headset_even_when_it_is_first(self):
        self.fake(["Headset (Crusher ANC 2 Hands-F", "Microphone (C920 HD Pro Webcam)"])
        self.assertEqual(self.caw.pick_mic("")[1], "Microphone (C920 HD Pro Webcam)")

    def test_asking_for_the_headset_is_refused(self):
        self.fake(["Headset (Crusher ANC 2 Hands-F", "Microphone (C920 HD Pro Webcam)"])
        with self.assertRaises(RuntimeError):
            self.caw.pick_mic("Crusher")
        self.assertEqual(self.caw.pick_mic("c920")[1], "Microphone (C920 HD Pro Webcam)")

    def test_no_safe_mic(self):
        self.fake(["Headset (Crusher ANC 2 Hands-F"])
        with self.assertRaises(RuntimeError):
            self.caw.pick_mic("")


if __name__ == "__main__":
    unittest.main()
