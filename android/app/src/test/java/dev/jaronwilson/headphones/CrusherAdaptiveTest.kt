package dev.jaronwilson.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tests for CrusherAdaptive.kt, the port of shared/crusher_adaptive.py: the meter on generated signals (its dB
 * checked against the Python implementation's numbers), the policy on synthetic level/speech sequences, the AT
 * commands, the rate limiter and the loop's logic with a scripted meter.
 */
class CrusherAdaptiveTest {
    private val R = CA.RATE

    // ---- signals ----
    private fun sine(f: Double, amp: Double, secs: Double) =
        FloatArray((R * secs).toInt()) { (amp * sin(2 * PI * f * it / R)).toFloat() }

    /** The same generator the Python reference numbers were taken with (uniform, an LCG). */
    private fun lcgNoise(n: Int, amp: Double): FloatArray {
        var s = 12345L
        return FloatArray(n) { s = (s * 1103515245L + 12345L) and 0x7fffffffL; ((s / 2147483648.0 * 2 - 1) * amp).toFloat() }
    }

    /** Noise with a 1/f^slope power spectrum (0 white, 1 pink, 2 brown), unit RMS. */
    private fun shapedNoise(secs: Double, slope: Double = 0.0, seed: Long = 1, lo: Double = 20.0, hi: Double = 8000.0): FloatArray {
        val n = (R * secs).toInt()
        val rnd = java.util.Random(seed)
        val re = DoubleArray(n) { rnd.nextGaussian() }
        val im = DoubleArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        for (k in 0 until n) {
            val f = minOf(k, n - k).toDouble() * R / n
            val g = if (f < lo || f > hi) 0.0 else 1.0 / Math.pow(max(f, 20.0), slope / 2)
            re[k] *= g; im[k] *= -g                      // conjugate for the inverse
        }
        fft.forward(re, im)
        val x = DoubleArray(n) { re[it] / n }
        val mean = x.average()
        val sd = sqrt(x.sumOf { (it - mean) * (it - mean) } / n)
        return FloatArray(n) { (x[it] / sd).toFloat() }
    }

    private fun scale(x: FloatArray, g: Double) = FloatArray(x.size) { (x[it] * g).toFloat() }
    private fun plus(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] + b[it] }
    private fun times(a: FloatArray, env: (Int) -> Double) = FloatArray(a.size) { (a[it] * env(it)).toFloat() }

    /** Syllable-rate envelope: 120-250 ms bursts with 40-120 ms gaps (about 4-5 a second). */
    private fun syllables(secs: Double, seed: Long = 3): DoubleArray {
        val rnd = java.util.Random(seed)
        val n = (R * secs).toInt()
        val env = DoubleArray(n)
        var pos = 0
        while (pos < n) {
            val len = minOf((R * (0.12 + 0.13 * rnd.nextDouble())).toInt(), n - pos)
            for (i in 0 until len) env[pos + i] = 0.5 - 0.5 * Math.cos(2 * PI * i / (len - 1).coerceAtLeast(1))
            pos += len + (R * (0.04 + 0.08 * rnd.nextDouble())).toInt()
        }
        return env
    }

    /** A crude talker: a gliding pitch, formant-shaped harmonics (600 / 1200 / 2500 Hz), syllable bursts. */
    private fun voice(secs: Double, f0: Double = 130.0, seed: Long = 3): FloatArray {
        val n = (R * secs).toInt()
        val ph = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) { acc += f0 * (1 + 0.05 * sin(2 * PI * 0.7 * i / R)); ph[i] = 2 * PI * acc / R }
        val v = DoubleArray(n)
        for (k in 1 until 40) {
            val fk = f0 * k
            if (fk > 3800) break
            val g = listOf(600.0 to 150.0, 1200.0 to 200.0, 2500.0 to 300.0).sumOf { (F, B) -> exp(-Math.pow((fk - F) / B, 2.0)) } + 0.05
            for (i in 0 until n) v[i] += g * sin(k * ph[i])
        }
        val mean = v.average()
        val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / n)
        val env = syllables(secs, seed)
        return FloatArray(n) { (v[it] / sd * env[it]).toFloat() }
    }

    /** Keyboard-like clicks: short decaying broadband bursts at typing rate. */
    private fun clicks(secs: Double, perS: Int = 6, seed: Long = 5): FloatArray {
        val rnd = java.util.Random(seed)
        val n = (R * secs).toInt()
        val x = DoubleArray(n)
        repeat((perS * secs).toInt()) {
            val p = rnd.nextInt(n - 200)
            for (i in 0 until 200) x[p + i] += rnd.nextGaussian() * exp(-i / 30.0)
        }
        return FloatArray(n) { (0.2 * x[it] + 0.001 * rnd.nextGaussian()).toFloat() }
    }

    private fun measure(x: FloatArray, block: Int = 4000): List<Reading> {
        val m = AmbientMeter()
        val out = ArrayList<Reading>()
        var i = 0
        while (i < x.size) { out += m.feed(x.copyOfRange(i, minOf(x.size, i + block))); i += block }
        return out
    }

    private fun speechShare(x: FloatArray, skip: Int = 4): Double {
        val rs = measure(x).drop(skip)
        return rs.count { it.speech }.toDouble() / max(1, rs.size)
    }

    private fun near(want: Double, got: Double, delta: Double) =
        assertTrue("want $want, got $got", abs(want - got) <= delta)

    // ---- measuring ----
    @Test fun aWeightingReferencePoints() {
        near(0.0, CA.aWeightDb(1000.0), 0.1)
        near(-19.1, CA.aWeightDb(100.0), 0.2)
        near(-2.5, CA.aWeightDb(10000.0), 0.2)
        near(-30.2, CA.aWeightDb(50.0), 0.3)
    }

    @Test fun fftMatchesADirectDft() {
        for (n in listOf(8, 100, 320, 1280)) {
            val re = DoubleArray(n) { sin(it * 0.7) + 0.3 * Math.cos(it * 2.1) }
            val im = DoubleArray(n)
            val want = re.copyOf()
            Fft(n).forward(re, im)
            for (k in listOf(0, 1, 3, n / 2, n - 1)) {
                var sr = 0.0; var si = 0.0
                for (j in 0 until n) { val a = -2 * PI * ((j.toLong() * k) % n) / n; sr += want[j] * Math.cos(a); si += want[j] * sin(a) }
                near(sr, re[k], 1e-6); near(si, im[k], 1e-6)
            }
        }
    }

    @Test fun fullScaleSineReadsMinusThree() {
        near(-3.01, measure(sine(1000.0, 1.0, 3.0)).last().db, 0.05)
    }

    @Test fun dbMatchesThePythonImplementation() {
        // numbers from shared/crusher_adaptive.py on the same signals
        near(-3.01, measure(sine(1000.0, 1.0, 3.0)).last().db, 0.05)
        near(-17.706, measure(sine(250.0, 0.5, 3.0)).last().db, 0.1)
        near(-16.026, measure(sine(4000.0, 0.2, 3.0)).last().db, 0.1)
        for (r in measure(lcgNoise(48000, 0.3))) near(-15.04, r.db, 0.1)       // Python: -15.06 .. -15.02
        for (r in measure(lcgNoise(48000, 0.01))) near(-44.59, r.db, 0.1)      // Python: -44.62 .. -44.56
    }

    @Test fun sine1kReadsItsRms() {
        near(20 * log10(0.1 / sqrt(2.0)), measure(sine(1000.0, 0.1, 3.0)).last().db, 0.2)
    }

    @Test fun lowToneIsAWeightedDown() {
        val a = measure(sine(1000.0, 0.1, 3.0)).last().db
        val b = measure(sine(100.0, 0.1, 3.0)).last().db
        near(19.1, a - b, 0.4)
    }

    @Test fun sixDbPerDoubling() {
        val a = measure(sine(1000.0, 0.05, 3.0)).last().db
        val b = measure(sine(1000.0, 0.1, 3.0)).last().db
        near(6.02, b - a, 0.1)
    }

    @Test fun silenceIsTheFloor() {
        assertEquals(CA.FLOOR_DB, measure(FloatArray(R * 2)).last().db, 0.0)
    }

    @Test fun dcOffsetIsIgnored() {
        val x = sine(1000.0, 0.1, 3.0).let { FloatArray(it.size) { i -> it[i] + 0.3f } }
        near(-23.0, measure(x).last().db, 0.2)
    }

    @Test fun readingEveryHalfSecond() {
        val rs = measure(sine(1000.0, 0.1, 5.0))
        assertEquals(9, rs.size)
        near(0.5, rs[1].t - rs[0].t, 1e-9)
    }

    @Test fun blockSizeDoesNotChangeTheLevel() {
        val x = plus(scale(shapedNoise(4.0), 0.05), scale(voice(4.0), 0.05))
        val a = measure(x, 4000).map { Math.round(it.db * 1e6) }
        val b = measure(x, 777).map { Math.round(it.db * 1e6) }
        assertEquals(a, b)
    }

    @Test fun smoothingFollowsAStep() {
        val x = sine(1000.0, 0.001, 4.0) + sine(1000.0, 0.1, 6.0)
        val rs = measure(x)
        val first = rs.indexOfFirst { it.db > -30 }
        assertTrue(rs[first].level < rs[first].db - 3)       // one loud window doesn't jump it all
        near(-23.0, rs.last().level, 0.5)                    // but it gets there
    }

    @Test fun steadyNoiseIsSteady() {
        assertTrue(measure(scale(shapedNoise(10.0, 1.0), 0.05)).last().steady)
    }

    @Test fun varyingNoiseIsNotSteady() {
        val n = shapedNoise(12.0, 1.0)
        val x = times(n) { if ((it / R) % 2 == 0) 0.02 else 0.1 }
        assertFalse(measure(x).last().steady)
    }

    @Test fun quietAndLoudNoiseLandInTheirZones() {
        fun zone(amp: Double): String {
            val lvl = measure(lcgNoise(R * 6, amp)).last().level
            return Policy().classify(lvl)
        }
        assertEquals("quiet", zone(0.0005))
        assertEquals("moderate", zone(0.01))
        assertEquals("loud", zone(0.3))
    }

    // ---- speech ----
    @Test fun talkerIsSpeech() { assertTrue(speechShare(scale(voice(12.0), 0.05)) > 0.8) }

    @Test fun higherVoiceIsSpeech() { assertTrue(speechShare(scale(voice(12.0, 220.0, 8), 0.05)) > 0.8) }

    @Test fun talkerOverFanNoiseIsSpeech() {
        assertTrue(speechShare(plus(scale(voice(12.0), 0.05), scale(shapedNoise(12.0, 1.0), 0.02))) > 0.7)
    }

    @Test fun steadyFanNoiseIsNotSpeech() {
        for (slope in listOf(0.0, 1.0, 2.0)) assertEquals("slope $slope", 0.0, speechShare(scale(shapedNoise(12.0, slope), 0.05)), 0.0)
    }

    @Test fun humIsNotSpeech() { assertEquals(0.0, speechShare(sine(120.0, 0.05, 12.0)), 0.0) }

    @Test fun syllableRateRumbleIsNotSpeech() {
        val x = times(scale(shapedNoise(12.0, 0.0, 1, 20.0, 200.0), 0.05)) { 0.55 + 0.45 * sin(2 * PI * 4 * it / R) }
        assertEquals(0.0, speechShare(x), 0.0)
    }

    @Test fun syllableRateNoiseIsNotSpeech() {
        val x = times(scale(shapedNoise(12.0), 0.05)) { 0.55 + 0.45 * sin(2 * PI * 4 * it / R) }
        assertEquals(0.0, speechShare(x), 0.0)
    }

    @Test fun typingIsNotSpeech() { assertEquals(0.0, speechShare(clicks(12.0)), 0.0) }

    @Test fun slowSwellIsNotSpeech() {
        val x = times(scale(shapedNoise(12.0, 1.0), 0.05)) { 0.55 + 0.45 * sin(2 * PI * 0.5 * it / R) }
        assertEquals(0.0, speechShare(x), 0.0)
    }

    @Test fun faintTalkerFarBelowTheFloorIsNotSpeech() {
        assertEquals(0.0, speechShare(scale(voice(12.0), 1e-5)), 0.0)
    }

    // ---- deciding ----
    private fun rd(level: Double, speech: Boolean = false, steady: Boolean = false, held: Boolean = false) =
        Reading(0.0, level, level, steady, speech, held = held)

    /** Drives a Policy with readings, every dt seconds, committing what it asks for (as the runner does when
     *  its rate limiter allows). */
    private class Sim(val p: Policy, val dt: Double = 0.5, val limiter: RateLimiter? = null) {
        var now = 0.0
        val sent = ArrayList<Pair<Double, Target>>()
        fun run(rs: List<Reading>): Sim {
            for (r in rs) {
                now += dt
                val t = p.tick(r, now)
                if (t != null && (limiter == null || limiter.ready(now))) {
                    limiter?.mark(now)
                    p.committed(t, now)
                    sent += now to t
                }
            }
            return this
        }
        fun hold(r: Reading, secs: Double) = run(List(Math.round(secs / dt).toInt()) { r })
        fun modes() = sent.map { it.second.mode to it.second.level }
    }

    private val QUIET = -60.0
    private val MODERATE = -43.0
    private val LOUD = -25.0

    @Test fun quietRoomWaitsForHoldThenTransparency() {
        val s = Sim(Policy())
        s.hold(rd(QUIET), 3.5)
        assertTrue(s.sent.isEmpty())
        s.hold(rd(QUIET), 1.0)
        assertEquals(listOf("transparency" to 50), s.modes())
    }

    @Test fun zonesMapToTargets() {
        val cases = listOf(Triple(QUIET, false, "transparency" to 50), Triple(MODERATE, false, "transparency" to 20),
            Triple(MODERATE, true, "anc" to null), Triple(LOUD, false, "anc" to null))
        for ((level, steady, want) in cases) {
            assertEquals("$level $steady", listOf(want), Sim(Policy()).hold(rd(level, steady = steady), 10.0).modes())
        }
    }

    @Test fun quietSteadyHumStaysTransparency() {
        assertEquals(listOf("transparency" to 50), Sim(Policy()).hold(rd(QUIET, steady = true), 10.0).modes())
    }

    @Test fun firmwareWithoutALevel() {
        val p = Policy(hasLevel = false)
        val s = Sim(p).hold(rd(QUIET), 6.0)
        assertEquals(listOf("transparency" to null), s.modes())
        s.hold(rd(MODERATE), 20.0)
        assertEquals("off" to null, s.modes().last())            // moderate maps to Off without a level
    }

    @Test fun hysteresisNearAThresholdNeverFlaps() {
        val p = Policy()
        val q = p.thresholds().first
        val s = Sim(p).hold(rd(q - 5), 6.0)
        s.run(List(1200) { rd(q + (if (it % 6 < 3) 1.9 else -1.9)) })    // 10 min within +-HYST/2
        assertEquals(listOf("transparency" to 50), s.modes())
    }

    @Test fun crossingTheBandSwitchesOnce() {
        val p = Policy()
        val q = p.thresholds().first
        val s = Sim(p).hold(rd(q - 5), 6.0)
        s.hold(rd(q + 2.5), 30.0)
        assertEquals(listOf("transparency" to 50, "transparency" to 20), s.modes())
        s.hold(rd(q - 1.5), 60.0)                                 // back below the threshold, but inside the band
        assertEquals(2, s.sent.size)
        s.hold(rd(q - 2.5), 30.0)
        assertEquals("transparency" to 50, s.modes().last())
    }

    @Test fun shortSpikeIsIgnored() {
        val s = Sim(Policy()).hold(rd(QUIET), 20.0)
        s.hold(rd(LOUD), 2.0)                                     // a door slam
        s.hold(rd(QUIET), 30.0)
        assertEquals(listOf("transparency" to 50), s.modes())
    }

    @Test fun dwellBetweenSwitches() {
        val p = Policy()
        val s = Sim(p).hold(rd(QUIET), 5.0)
        val first = s.sent[0].first
        s.hold(rd(LOUD), 30.0)
        val second = s.sent[1].first
        assertTrue(second - first >= p.s.dwellS)
        s.hold(rd(QUIET), 30.0)
        val third = s.sent[2].first
        assertTrue(third - second >= p.s.dwellS)
        assertEquals(listOf("transparency" to 50, "anc" to null, "transparency" to 50), s.modes())
    }

    @Test fun alternatingEveryFewSecondsSwitchesAtMostOncePerDwell() {
        val p = Policy()
        val seq = ArrayList<Reading>()
        for (i in 0 until 60) repeat(10) { seq += rd(if (i % 2 == 1) LOUD else QUIET) }   // 5 minutes
        val s = Sim(p).run(seq)
        val times = s.sent.map { it.first }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= p.s.dwellS })
        assertTrue(times.size <= 300 / p.s.dwellS + 1)
    }

    @Test fun sensitivityMovesTheThresholds() {
        val lo = Policy(Settings(mapOf("sensitivity" to 0)))
        val hi = Policy(Settings(mapOf("sensitivity" to 100)))
        assertEquals(20.0, lo.thresholds().first - hi.thresholds().first, 1e-9)
        assertEquals(listOf("transparency" to 20), Sim(hi).hold(rd(-55.0), 6.0).modes())   // quiet at 50, some noise at 100
        assertEquals(listOf("transparency" to 50), Sim(lo).hold(rd(-45.0), 6.0).modes())
    }

    @Test fun heldReadingsAreIgnored() {
        val s = Sim(Policy()).hold(rd(QUIET), 6.0)
        s.hold(rd(LOUD, held = true), 60.0)
        assertEquals(listOf("transparency" to 50), s.modes())
    }

    // ---- conversation ----
    @Test fun talkingSwitchesAtOnceAndReturnsAfter5s() {
        val s = Sim(Policy()).hold(rd(LOUD), 6.0)
        assertEquals(listOf("anc" to null), s.modes())
        val t0 = s.now
        s.hold(rd(LOUD, speech = true), 3.0)
        val conv = s.sent[1]
        assertTrue(conv.second.conv)
        assertEquals("transparency" to 100, conv.second.mode to conv.second.level)
        assertTrue(conv.first - t0 <= 1.0)                        // two speech readings, no hold, no dwell
        val stop = s.now
        s.hold(rd(LOUD), 10.0)
        val back = s.sent[2]
        assertEquals("anc" to false, back.second.mode to back.second.conv)
        near(5.0, back.first - stop, 0.6)
    }

    @Test fun aSingleSpeechBlipDoesNothing() {
        val s = Sim(Policy()).hold(rd(QUIET), 6.0)
        repeat(10) { s.run(listOf(rd(QUIET, speech = true), rd(QUIET), rd(QUIET, speech = true), rd(QUIET))) }
        assertEquals(1, s.sent.size)
    }

    @Test fun pausesInSpeechShorterThan5sKeepIt() {
        val s = Sim(Policy()).hold(rd(QUIET), 6.0)
        repeat(5) { s.hold(rd(QUIET, speech = true), 2.0); s.hold(rd(QUIET), 3.0) }
        assertEquals(2, s.sent.size)                              // entered once, never left
        s.hold(rd(QUIET), 6.0)
        assertEquals(3, s.sent.size)
    }

    @Test fun voicesDoNotMoveTheZone() {
        val p = Policy()
        val s = Sim(p).hold(rd(QUIET), 6.0)
        s.hold(rd(LOUD, speech = true), 20.0)                     // loud because people talk
        assertEquals("quiet", p.zone)
        s.hold(rd(QUIET), 6.0)
        assertEquals("transparency" to 50, s.modes().last())
    }

    @Test fun offWhenConversationAwarenessIsOff() {
        val s = Sim(Policy(Settings(mapOf("conversation" to false)))).hold(rd(QUIET), 6.0)
        s.hold(rd(QUIET, speech = true), 20.0)
        assertEquals(1, s.sent.size)
    }

    @Test fun duckFlag() {
        val s = Sim(Policy(Settings(mapOf("duck" to true)))).hold(rd(QUIET), 6.0)
        s.hold(rd(QUIET, speech = true), 2.0)
        assertTrue(s.sent.last().second.duck)
        s.hold(rd(QUIET), 6.0)
        assertFalse(s.sent.last().second.duck)
    }

    @Test fun turningAwarenessOffMidConversationReturns() {
        val p = Policy()
        val s = Sim(p).hold(rd(LOUD), 6.0)
        s.hold(rd(LOUD, speech = true), 2.0)
        p.s.update(mapOf("conversation" to false))
        s.hold(rd(LOUD, speech = true), 1.0)
        assertEquals("anc" to null, s.modes().last())
    }

    // ---- manual override ----
    @Test fun manualChangePausesForNMinutes() {
        val p = Policy(Settings(mapOf("pause_min" to 10)))
        val s = Sim(p).hold(rd(QUIET), 6.0)
        p.manual(s.now)
        s.hold(rd(LOUD), 9 * 60.0)
        assertEquals(1, s.sent.size)                              // nothing while paused
        s.hold(rd(LOUD), 2 * 60.0)
        assertEquals("anc" to null, s.modes().last())
        assertTrue(s.sent.last().first >= 6 + 600)
    }

    @Test fun resumeStartsFreshWithTheHold() {
        val p = Policy()
        val s = Sim(p).hold(rd(QUIET), 6.0)
        p.manual(s.now)
        s.hold(rd(QUIET), 30.0)
        p.resume()
        val t0 = s.now
        s.hold(rd(QUIET), 6.0)
        assertEquals(2, s.sent.size)                              // re-applies (the Crusher may be elsewhere now)
        assertTrue(s.sent.last().first - t0 >= p.s.holdS)
    }

    @Test fun pauseZeroMeansUntilTurnedBackOn() {
        val p = Policy(Settings(mapOf("pause_min" to 0)))
        val s = Sim(p).hold(rd(QUIET), 6.0)
        p.manual(s.now)
        s.hold(rd(LOUD), 3 * 3600.0)
        assertEquals(1, s.sent.size)
        assertTrue(p.paused(s.now))
        p.resume()
        s.hold(rd(LOUD), 6.0)
        assertEquals("anc" to null, s.modes().last())
    }

    @Test fun manualDuringConversation() {
        val p = Policy()
        val s = Sim(p).hold(rd(QUIET), 6.0)
        s.hold(rd(QUIET, speech = true), 2.0)
        p.manual(s.now)
        s.hold(rd(QUIET), 60.0)
        assertEquals(2, s.sent.size)                              // no "return" while the user is in charge
    }

    // ---- settings ----
    @Test fun settingsClampingAndTypes() {
        val st = Settings(mapOf("sensitivity" to 500, "pause_min" to -3, "conversation" to "off", "duck" to "on",
            "mic" to 7, "unknown" to 1, "hold_s" to 0.0, "headset_mic" to "yes"))
        assertEquals(100, st.sensitivity)
        assertEquals(0, st.pauseMin)
        assertFalse(st.conversation)
        assertTrue(st.duck)
        assertEquals("7", st.mic)
        assertEquals(0.5, st.holdS, 0.0)
        assertTrue(st.headsetMic)
        assertFalse(st.asMap().containsKey("unknown"))
    }

    @Test fun settingsDefaults() {
        val st = Settings()
        assertFalse(st.enabled); assertFalse(st.headsetMic); assertFalse(st.duck); assertTrue(st.conversation)
        assertEquals(50, st.sensitivity); assertEquals(10, st.pauseMin)
        assertEquals(4.0, st.holdS, 0.0); assertEquals(12.0, st.dwellS, 0.0)
        assertEquals(10.0, CA.HEADSET_IDLE_S, 0.0)
    }

    // ---- sending ----
    @Test fun commandsAnc() {
        assertEquals(listOf("AT.UIAUDIO=transparency,off", "AT.UIAUDIO=anc,on"), commandsFor(null, Target("anc")))
        assertEquals(emptyList<String>(), commandsFor(Target("anc"), Target("anc")))
    }

    @Test fun commandsOff() {
        assertEquals(listOf("AT.UIAUDIO=transparency,off", "AT.UIAUDIO=anc,off"), commandsFor(Target("anc"), Target("off")))
    }

    @Test fun commandsTransparencyWithLevel() {
        assertEquals(listOf("AT.UIAUDIO=transparency,on", "AT.UIAUDIO=transparency,,50"),
            commandsFor(Target("anc"), Target("transparency", 50)))
        assertEquals(listOf("AT.UIAUDIO=transparency,,20"),
            commandsFor(Target("transparency", 50), Target("transparency", 20)))
        assertEquals(emptyList<String>(), commandsFor(Target("transparency", 50), Target("transparency", 50, conv = true)))
    }

    @Test fun noLevelOnFirmwareWithoutIt() {
        assertEquals(listOf("AT.UIAUDIO=transparency,on"), commandsFor(null, Target("transparency", 50), hasLevel = false))
    }

    @Test fun modeOfState() {
        assertEquals("anc", modeOf(true, null))
        assertEquals("transparency", modeOf(false, true))
        assertEquals("off", modeOf(null, null))
    }

    @Test fun targetLabels() {
        assertEquals("Transparency 50", Target("transparency", 50).label())
        assertEquals("Transparency 100 (conversation)", Target("transparency", 100, conv = true).label())
        assertEquals("ANC", Target("anc").label())
    }

    @Test fun rateLimiterOneBatchPerGap() {
        val rl = RateLimiter(3.0)
        assertTrue(rl.ready(0.0))
        rl.mark(0.0)
        assertFalse(rl.ready(2.9))
        assertTrue(rl.ready(3.0))
    }

    @Test fun policyNeverSendsFasterThanTheLimiter() {
        val st = Settings(mapOf("conv_return_s" to 1.0))
        val seq = ArrayList<Reading>()
        repeat(40) { seq += List(2) { rd(LOUD, speech = true) } + List(3) { rd(LOUD) } }
        val s = Sim(Policy(st), limiter = RateLimiter(3.0)).hold(rd(LOUD), 6.0).run(seq)
        val times = s.sent.map { it.first }
        assertTrue(times.size > 5)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 3.0 })
    }

    // ---- the loop's logic, with a scripted meter ----
    private class FakeMeter(rs: List<Reading>) : Meter {
        val readings = ArrayDeque(rs)
        var resets = 0
        override fun feed(x: FloatArray) = listOfNotNull(readings.removeFirstOrNull())
        override fun reset() { resets++ }
    }

    private inner class Rig(rs: List<Reading> = emptyList(), val fail: Boolean = false, settings: Map<String, Any?> = emptyMap()) {
        var t = 1000.0
        val sent = ArrayList<Pair<Double, List<String>>>()
        val ducks = ArrayList<Boolean>()
        val meter = FakeMeter(rs)
        val core = AdaptiveCore(Settings(settings), send = { c -> if (fail) throw RuntimeException("not reachable"); sent += t to c },
            duck = { ducks += it }, clock = { t }, meter = meter)
        fun steps(n: Int, dt: Double = 0.5) = repeat(n) { t += dt; core.process(FloatArray(1)) }
    }

    @Test fun coreEnabledAdaptsAndSendsAt() {
        val r = Rig(List(12) { rd(QUIET) }, settings = mapOf("enabled" to true))
        r.steps(12)
        assertEquals(listOf("AT.UIAUDIO=transparency,on", "AT.UIAUDIO=transparency,,50"), r.sent[0].second)
        assertEquals("transparency", r.core.state().target?.mode)
    }

    @Test fun coreRateLimits() {
        val r = Rig(settings = mapOf("enabled" to true))
        val now = r.t
        assertTrue(r.core.apply(Target("anc"), now))
        assertFalse(r.core.apply(Target("off"), now + 1))
        assertTrue(r.core.apply(Target("off"), now + 3))
        assertEquals(2, r.sent.size)
    }

    @Test fun coreFailedSendIsNotCommittedAndBacksOff() {
        val r = Rig(fail = true, settings = mapOf("enabled" to true))
        val now = r.t
        assertFalse(r.core.apply(Target("anc"), now))
        assertNull(r.core.policy.applied)
        assertTrue(r.core.error.contains("not reachable"))
        assertFalse(r.core.limiter.ready(now + 5))
        assertTrue(r.core.limiter.ready(now + 10))
    }

    @Test fun coreHasLevelDecidesTheCommands() {
        val sent = ArrayList<List<String>>()
        val core = AdaptiveCore(Settings(), send = { sent += it }, hasLevel = { false }, clock = { 0.0 }, meter = FakeMeter(emptyList()))
        core.apply(Target("transparency", 50), 0.0)
        assertEquals(listOf(listOf("AT.UIAUDIO=transparency,on")), sent)
    }

    @Test fun coreManualPausesAndResumes() {
        val r = Rig(List(40) { rd(QUIET) }, settings = mapOf("enabled" to true, "pause_min" to 10))
        r.steps(12)
        assertTrue(r.core.manual("test"))
        assertEquals(1, r.meter.resets)
        assertTrue(r.core.state().paused)
        assertTrue(r.core.state().line().startsWith("Paused"))
        r.core.set(mapOf("enabled" to true))                  // picking Adaptive again resumes at once
        assertFalse(r.core.state().paused)
    }

    @Test fun coreManualIsIgnoredWhenOff() {
        assertFalse(Rig().core.manual("test"))
    }

    @Test fun coreAChangeOnTheHeadsetIsNoticed() {
        val r = Rig(List(200) { rd(QUIET) }, settings = mapOf("enabled" to true))
        r.steps(12)
        assertEquals("transparency", r.core.policy.applied?.mode)
        r.t += 30
        assertFalse(r.core.observe("transparency", r.t))
        assertTrue(r.core.observe("anc", r.t))                // someone pressed the Crusher's button
        assertTrue(r.core.state().paused)
    }

    @Test fun coreOurOwnChangeIsNotTakenAsManual() {
        val r = Rig(settings = mapOf("enabled" to true))
        val now = r.t
        r.core.apply(Target("anc"), now)
        assertFalse(r.core.observe("off", now + 2))           // within the grace time after our own send
        assertTrue(r.core.observe("off", now + 20))
    }

    @Test fun coreDucksOnConversationAndBack() {
        val seq = List(12) { rd(QUIET) } + List(4) { rd(QUIET, speech = true) } + List(16) { rd(QUIET) }
        val r = Rig(seq, settings = mapOf("enabled" to true, "duck" to true))
        r.steps(seq.size)
        assertEquals(listOf(true, false), r.ducks)
    }

    @Test fun coreTurningOffRestoresTheVolume() {
        val seq = List(12) { rd(QUIET) } + List(4) { rd(QUIET, speech = true) }
        val r = Rig(seq, settings = mapOf("enabled" to true, "duck" to true))
        r.steps(seq.size)
        assertEquals(listOf(true), r.ducks)
        r.core.set(mapOf("enabled" to false))
        assertEquals(listOf(true, false), r.ducks)
    }

    @Test fun coreSourceChangeResetsTheMeterButKeepsTheTarget() {
        val r = Rig(List(12) { rd(QUIET) }, settings = mapOf("enabled" to true))
        r.steps(12)
        val applied = r.core.policy.applied
        assertNotNull(applied)
        r.core.sourceChanged()
        assertEquals(1, r.meter.resets)
        assertEquals(applied, r.core.policy.applied)
        assertNull(r.core.last)
    }

    @Test fun coreSettingsAreClamped() {
        val r = Rig()
        r.core.set(mapOf("sensitivity" to 500, "conversation" to false))
        assertEquals(100, r.core.settings.sensitivity)
        assertFalse(r.core.settings.conversation)
    }

    @Test fun stateLine() {
        val s = AdaptiveState(-48.2, "moderate", true, true, false, Target("anc"), false, null, "")
        assertEquals("Room -48 dB, moderate, steady, voices: chose ANC", s.line())
        assertEquals("Paused after a manual change (5 min left)",
            AdaptiveState(null, null, false, false, false, null, true, 290, "").line())
    }
}
