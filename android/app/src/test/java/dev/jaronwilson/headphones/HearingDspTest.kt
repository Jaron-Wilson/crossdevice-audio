package dev.jaronwilson.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Filtered hearing DSP, offline, with synthetic signals only (no recordings of anyone): a fan (shaped noise,
 * blade tones, mains hum) and formant-synthesised "speakers" (glottal pulses through vowel formants, with
 * fricatives and pauses). Numbers are printed as "FH ..." lines.
 */
class HearingDspTest {
    private val rate = 32_000

    // ---------------------------------------------------------------- synthetic signals
    /** Speech-like: syllables of vowels (formants scaled by [vtl], vocal tract length) on a glottal pulse
     *  train around [f0] Hz, some with a fricative, words separated by pauses. */
    private fun speech(secs: Double, f0: Double, vtl: Double, seed: Int, pauses: Boolean = true): DoubleArray {
        val rnd = Random(seed)
        val n = (secs * rate).toInt()
        val out = DoubleArray(n)
        val vowels = listOf(doubleArrayOf(730.0, 1090.0, 2440.0), doubleArrayOf(270.0, 2290.0, 3010.0),
            doubleArrayOf(300.0, 870.0, 2240.0), doubleArrayOf(530.0, 1840.0, 2480.0), doubleArrayOf(570.0, 840.0, 2410.0))
        val bw = doubleArrayOf(80.0, 100.0, 140.0)
        val y1 = DoubleArray(3); val y2 = DoubleArray(3)
        var fric = 0.0; var fricLp = 0.0
        var glot1 = 0.0; var glot2 = 0.0
        var phase = 0.0
        var i = 0
        var syl = 0
        val cur = vowels[0].copyOf()
        while (i < n) {
            if (rnd.nextDouble() < 0.4) {                       // fricative
                val len = ((0.04 + 0.05 * rnd.nextDouble()) * rate).toInt()
                for (j in 0 until len) {
                    if (i >= n) break
                    val wn = rnd.nextDouble() * 2 - 1
                    fric = wn - 0.6 * fricLp; fricLp = wn            // crude high-pass
                    out[i++] = 0.08 * fric * sin(PI * j / len)
                }
            }
            val target = vowels[rnd.nextInt(vowels.size)].map { it * vtl }
            val len = ((0.12 + 0.16 * rnd.nextDouble()) * rate).toInt()
            val fStart = f0 * (0.9 + 0.25 * rnd.nextDouble()); val fEnd = fStart * (0.85 + 0.15 * rnd.nextDouble())
            for (j in 0 until len) {
                if (i >= n) break
                val t = j.toDouble() / len
                for (k in 0..2) cur[k] += (target[k] - cur[k]) * 0.004
                val ff = fStart + (fEnd - fStart) * t
                phase += ff / rate
                var src = 0.0
                if (phase >= 1) { phase -= 1; src = 1.0 }
                // glottal low-pass (two poles at ~ 400 Hz)
                val a = exp(-2 * PI * 400 / rate)
                glot1 = src + a * glot1; glot2 = glot1 * (1 - a) + a * glot2
                var v = glot2 - 0.97 * 0.0
                for (k in 0..2) {
                    val r = exp(-PI * bw[k] / rate); val a1 = 2 * r * cos(2 * PI * cur[k] / rate); val a2 = -r * r
                    val yk = (1 - a1 - a2) * v + a1 * y1[k] + a2 * y2[k]
                    y2[k] = y1[k]; y1[k] = yk; v = yk
                }
                val env = sin(PI * t).let { e -> sqrt(e) }
                out[i++] = v * env
            }
            syl++
            if (pauses && syl % (2 + rnd.nextInt(3)) == 0) i += ((0.15 + 0.3 * rnd.nextDouble()) * rate).toInt()
        }
        // remove DC, normalise to RMS 0.05 over active samples
        val m = out.average(); for (k in out.indices) out[k] -= m
        return scale(out, 0.05)
    }

    /** A fan: low-passed broadband noise, blade tone at [blade] Hz and harmonics, 60 Hz mains hum x1, x3. */
    private fun fan(secs: Double, seed: Int, blade: Double = 120.0, drift: Double = 0.0): DoubleArray {
        val rnd = Random(seed)
        val n = (secs * rate).toInt()
        var lp = 0.0; var lp2 = 0.0
        val a = exp(-2 * PI * 900 / rate)
        var ph = 0.0
        return DoubleArray(n) { i ->
            val wn = rnd.nextDouble() * 2 - 1
            lp = (1 - a) * wn + a * lp; lp2 = 0.7 * lp2 + 0.3 * wn
            val t = i.toDouble() / rate
            val b = blade * (1 + drift * t / secs)
            ph += 2 * PI * b / rate
            val tones = 0.04 * sin(ph) + 0.02 * sin(2 * ph + 1) + 0.01 * sin(3 * ph + 2) +
                0.025 * sin(2 * PI * 60 * t) + 0.008 * sin(2 * PI * 180 * t)
            0.3 * lp + 0.01 * lp2 + tones
        }
    }

    private fun rms(x: DoubleArray, from: Int = 0, to: Int = x.size) =
        sqrt((from until to).sumOf { x[it] * x[it] } / max(1, to - from))

    private fun scale(x: DoubleArray, target: Double): DoubleArray {
        val r = rms(x); return DoubleArray(x.size) { x[it] * target / (r + 1e-12) }
    }

    private fun add(vararg xs: DoubleArray) = DoubleArray(xs.minOf { it.size }) { i -> xs.sumOf { it[i] } }
    private fun db(a: Double, b: Double) = 20 * log10(a / (b + 1e-15))

    /** Correlation of y against x delayed by [d] samples, over [from, to) of x. */
    private fun corr(x: DoubleArray, y: DoubleArray, d: Int, from: Int, to: Int): Double {
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in from until to - d) { val a = x[i]; val b = y[i + d]; sxy += a * b; sxx += a * a; syy += b * b }
        return sxy / sqrt(sxx * syy + 1e-30)
    }

    private fun proc(shadows: Int) = HearingProcessor(rate, shadows).apply { outputStage = false; enabled = true }

    // ---------------------------------------------------------------- the building blocks
    @Test fun stftIsTransparentWithUnitGains() {
        val x = fan(1.0, 3)
        val p = proc(0)
        val y = DoubleArray(x.size); p.process(x, x.size, y)
        val d = HearingProcessor.LATENCY
        val err = rms(DoubleArray(x.size - d - 2000) { x[it + 1000] - y[it + 1000 + d] })
        println("FH stft identity: latency $d samples (${d * 1000.0 / rate} ms), residual ${"%.1f".format(db(err, rms(x)))} dB")
        assertTrue(db(err, rms(x)) < -80)
    }

    @Test fun decimatorKeepsSpeechBandAndRejectsAliases() {
        val d = Decimator2()
        fun gainAt(f: Double): Double {
            val x = DoubleArray(64_000) { sin(2 * PI * f * it / 64_000) }
            val o = DoubleArray(32_000); val n = d.process(x, x.size, o)
            return db(rms(o, 2000, n), rms(x))
        }
        val g1k = gainAt(1000.0); val g12k = gainAt(12_000.0); val g20k = gainAt(20_000.0)
        println("FH decimator: 1 kHz ${"%.2f".format(g1k)} dB, 12 kHz ${"%.1f".format(g12k)} dB, 20 kHz (alias) ${"%.1f".format(g20k)} dB")
        assertEquals(0.0, g1k, 0.2); assertTrue(g12k > -3); assertTrue(g20k < -50)
    }

    @Test fun limiterNeverPassesTheCeiling() {
        val l = Limiter(rate, 0.25)
        val x = DoubleArray(rate) { 3 * sin(2 * PI * 300 * it / rate) * (it.toDouble() / rate) }
        val peak = x.maxOf { abs(l.process(it)) }
        assertTrue(peak <= 0.25 + 1e-12)
    }

    // ---------------------------------------------------------------- 1. steady noise
    @Test fun learnsAndRemovesAFan() {
        val learnFan = fan(3.0, 1)
        val nl = HearingProcessor.analyzeNoise(learnFan, rate)
        println("FH learned: ${nl.describe()}")
        for (f in listOf(60.0, 120.0, 240.0)) assertTrue("tone $f found", nl.tonesHz.any { abs(it - f) < 2 })

        val secs = 10.0
        val s = speech(secs, 115.0, 1.0, 7)
        val f = scale(fan(secs, 2), rms(s))                       // 0 dB SNR
        val p = proc(2)
        p.setNoise(HearingProcessor.analyzeNoise(scale(learnFan, rms(f)), rate))
        val (out, parts) = p.processShadow(add(s, f), listOf(s, f))
        val d = HearingProcessor.LATENCY
        val a = rate; val b = s.size - d
        val fanAtt = db(rms(f, a, b), rms(parts[1], a + d, b + d))
        val speechKept = db(rms(parts[0], a + d, b + d), rms(s, a, b))
        val cSpeech = corr(s, parts[0], d, a, b)
        val cIn = corr(s, add(s, f), 0, a, b); val cOut = corr(s, out, d, a, b)
        val snrIn = db(rms(s, a, b), rms(f, a, b)); val snrOut = db(rms(parts[0], a + d, b + d), rms(parts[1], a + d, b + d))
        // tones alone
        val toneIn = DoubleArray(2 * rate) { 0.02 * sin(2 * PI * 120 * it / rate) + 0.012 * sin(2 * PI * 60 * it / rate) }
        val pt = proc(0).apply { setNoise(nl); noiseOn = true }
        val toneOut = DoubleArray(toneIn.size); pt.process(toneIn, toneIn.size, toneOut)
        val toneAtt = db(rms(toneIn, rate, 2 * rate - d), rms(toneOut, rate + d, 2 * rate))
        println("FH fan: fan attenuated ${"%.1f".format(fanAtt)} dB; speech level change ${"%.1f".format(speechKept)} dB; " +
            "speech part correlation ${"%.3f".format(cSpeech)}; corr(clean, mix) ${"%.3f".format(cIn)} -> corr(clean, output) " +
            "${"%.3f".format(cOut)}; SNR ${"%.1f".format(snrIn)} -> ${"%.1f".format(snrOut)} dB; hum+blade tones alone ${"%.1f".format(toneAtt)} dB")
        assertTrue(fanAtt > 12); assertTrue(cSpeech > 0.85); assertTrue(cOut > cIn); assertTrue(toneAtt > 25)
    }

    @Test fun notchesFollowADriftingFanTone() {
        val learn = fan(3.0, 11, blade = 120.0)
        val nl = HearingProcessor.analyzeNoise(learn, rate)
        val tones = DoubleArray(8 * rate) { val f = 120.0 * (1 + 0.03 * it / (8.0 * rate)); 0.02 * sin(2 * PI * f * it / rate) }
        // integrate the frequency properly (a chirp)
        var ph = 0.0
        for (i in tones.indices) { ph += 2 * PI * 120.0 * (1 + 0.03 * i / (8.0 * rate)) / rate; tones[i] = 0.02 * sin(ph) }
        val p = proc(0).apply { setNoise(NoiseLearn(nl.tonesHz.filter { abs(it - 120) < 3 }, DoubleArray(HearingProcessor.BINS), -40.0)) }
        val out = DoubleArray(tones.size); p.process(tones, tones.size, out)
        val att = db(rms(tones, 7 * rate, 8 * rate - 600), rms(out, 7 * rate + 512, 8 * rate))
        println("FH drift: blade tone drifting 120 -> 123.6 Hz, attenuation in the last second ${"%.1f".format(att)} dB")
        assertTrue(att > 15)
    }

    @Test fun blockingATappedBand() {
        val x = DoubleArray(3 * rate) { 0.05 * sin(2 * PI * 2000 * it / rate) + 0.05 * sin(2 * PI * 500 * it / rate) + 0.05 * sin(2 * PI * 125 * it / rate) }
        val p = proc(0).apply { addBand(2000 / 1.12, 2000 * 1.12); addBand(125 / 1.12, 125 * 1.12) }
        val y = DoubleArray(x.size); p.process(x, x.size, y)
        fun amp(sig: DoubleArray, f: Double, from: Int, to: Int): Double {
            var c = 0.0; var s = 0.0
            for (i in from until to) { c += sig[i] * cos(2 * PI * f * i / rate); s += sig[i] * sin(2 * PI * f * i / rate) }
            return 2 * sqrt(c * c + s * s) / (to - from)
        }
        val a2k = db(0.05, amp(y, 2000.0, rate, 3 * rate)); val a500 = db(0.05, amp(y, 500.0, rate, 3 * rate))
        val a125 = db(0.05, amp(y, 125.0, rate, 3 * rate))
        println("FH band: blocked 2 kHz band ${"%.1f".format(a2k)} dB, blocked 125 Hz band ${"%.1f".format(a125)} dB, untouched 500 Hz ${"%.1f".format(a500)} dB")
        assertTrue(a2k > 25); assertTrue(a125 > 20); assertTrue(abs(a500) < 1.5)
    }

    // ---------------------------------------------------------------- 2. voices
    private data class VoiceResult(val target: Double, val other: Double, val other2: Double)

    /** Enroll speaker A, then play turns A, B, A, C (+ some overlap) over a quiet fan; shadow attenuations. */
    private fun voiceRun(mode: VoiceMode, overlap: Boolean): VoiceResult {
        val p = proc(3)
        val bg = scale(fan(30.0, 5), 0.004)                        // quiet room, ~22 dB under the voices
        p.setNoise(null)
        // enrollment: 10 s of A
        val enrollSig = add(speech(10.0, 115.0, 1.0, 21), bg.copyOfRange(0, 10 * rate))
        p.post { startEnroll() }
        val sink = DoubleArray(enrollSig.size)
        var enrolled: SpeakerModel? = null; var voicedSec = 0.0
        p.events = object : HearingProcessor.Events {
            override fun onEnrolled(m: SpeakerModel?, voicedSec: Double) { enrolled = m; voicedSec.also { } }
        }
        p.process(enrollSig, enrollSig.size, sink)
        p.finishEnroll()
        assertNotNull("enrolled", enrolled)
        voicedSec = enrolled!!.voicedSec
        p.voiceMode = mode
        // turns: A (seed 22) | B (female) | A | C (another male, a bit higher, slightly shorter tract)
        val seg = 4 * rate
        val n = 4 * seg
        val a = DoubleArray(n); val b = DoubleArray(n); val c = DoubleArray(n)
        val a1 = speech(4.0, 115.0, 1.0, 22); val a2 = speech(4.0, 115.0, 1.0, 23)
        val b1 = speech(4.0, 210.0, 1.17, 31); val c1 = speech(4.0, 150.0, 1.08, 41)
        System.arraycopy(a1, 0, a, 0, seg); System.arraycopy(b1, 0, b, seg, seg)
        System.arraycopy(a2, 0, a, 2 * seg, seg); System.arraycopy(c1, 0, c, 3 * seg, seg)
        if (overlap) {                                              // B also talks over A's second turn
            val b2 = speech(4.0, 210.0, 1.17, 32); for (i in 0 until seg) b[2 * seg + i] += b2[i]
        }
        val noise = bg.copyOfRange(10 * rate, 10 * rate + n)
        val ds = List(4) { ArrayList<Double>() }
        var pos = 0
        p.trace = { ds[minOf(3, pos / seg)] += it }
        val blk = 256
        // (distance trace per turn: A, B, A, C)
        run {
            val q = HearingProcessor(rate, 0).apply { outputStage = false; speaker = enrolled }
            q.trace = { ds[minOf(3, pos / seg)] += it }
            val mixd = add(a, b, c, noise); val o = DoubleArray(blk)
            while (pos < n) { val m = minOf(blk, n - pos); q.process(mixd.copyOfRange(pos, pos + m), m, o); pos += m }
            fun qs(l: List<Double>) = l.sorted().let { if (it.isEmpty()) "-" else listOf(0.1, 0.5, 0.9).joinToString("/") { f -> "%.2f".format(it[(f * (it.size - 1)).toInt()]) } }
            println("FH voice distances (p10/p50/p90), threshold ${"%.2f".format(enrolled!!.threshold)}: A ${qs(ds[0])}, B ${qs(ds[1])}, A' ${qs(ds[2])}, C ${qs(ds[3])}")
        }
        p.trace = null
        val (_, parts) = p.processShadow(add(a, b, c, noise), listOf(a, b, c))
        val d = HearingProcessor.LATENCY
        fun att(x: DoubleArray, y: DoubleArray, from: Int, to: Int) = db(rms(x, from, to - d), rms(y, from + d, to))
        val tA = if (overlap) att(a, parts[0], 2 * seg, 3 * seg) else att(a, parts[0], 0, n)
        val tB = if (overlap) att(b, parts[1], 2 * seg, 3 * seg) else att(b, parts[1], 0, n)
        println("FH voice enroll: ${"%.1f".format(voicedSec)} s of voiced frames, threshold ${"%.2f".format(enrolled!!.threshold)}")
        return VoiceResult(tA, tB, att(c, parts[2], 3 * seg, n))
    }

    @Test fun removesTheEnrolledVoiceNotOthers() {
        val turns = voiceRun(VoiceMode.TARGET, overlap = false)
        println("FH voice (turns): enrolled voice A attenuated ${"%.1f".format(turns.target)} dB; other voices: " +
            "B (higher, female-like) ${"%.1f".format(turns.other)} dB, C (male, 150 Hz) ${"%.1f".format(turns.other2)} dB")
        val ov = voiceRun(VoiceMode.TARGET, overlap = true)
        println("FH voice (A and B at once): A attenuated ${"%.1f".format(ov.target)} dB, B ${"%.1f".format(ov.other)} dB")
        assertTrue(turns.target > 10); assertTrue(turns.other < 3); assertTrue(turns.target - turns.other > 8)
    }

    @Test fun removesAllSpeechKeepsTheRoom() {
        val p = proc(2)
        val s = speech(10.0, 160.0, 1.08, 51)
        val room = scale(fan(10.0, 52), 0.01)
        val (mixOut, parts) = p.run { voiceMode = VoiceMode.ALL_SPEECH; processShadow(add(s, room), listOf(s, room)) }
        val d = HearingProcessor.LATENCY
        val sa = db(rms(s, rate, s.size - d), rms(parts[0], rate + d, s.size))
        val ra = db(rms(room, rate, s.size - d), rms(parts[1], rate + d, s.size))
        val other = HearingProcessor(rate, 2).apply { outputStage = false; voiceMode = VoiceMode.ALL_SPEECH }
        val s2 = speech(10.0, 115.0, 1.0, 53)
        val (_, p2) = other.processShadow(add(s2, room), listOf(s2, room))
        val sa2 = db(rms(s2, rate, s2.size - d), rms(p2[0], rate + d, s2.size))
        // what you hear vs the room alone (speech gone, room kept = 0 dB; the input was 14 dB above the room)
        val heard = db(rms(mixOut, rate + d, s.size), rms(room, rate, s.size - d))
        val heardIn = db(rms(add(s, room), rate, s.size), rms(room, rate, s.size))
        println("FH all speech: speech attenuated ${"%.1f".format(sa)} dB (low voice ${"%.1f".format(sa2)} dB); " +
            "output vs the room alone ${"%.1f".format(heardIn)} dB -> ${"%.1f".format(heard)} dB; the room's own part, " +
            "under the same gains, lost ${"%.1f".format(ra)} dB (it shares bins with the speech)")
        assertTrue(sa > 10); assertTrue(heard < 4)
    }

    // ---------------------------------------------------------------- 3/4. output path and safety
    @Test fun howlIsCaughtAndDucked() {
        // a closed loop: output -> (2 kHz resonance, gain > 1 there, 40 ms later) -> input
        val p = HearingProcessor(rate).apply { enabled = true; mix = 1.0 }
        val n = 6 * rate
        val amb = scale(fan(6.0, 61), 0.003)
        val out = DoubleArray(n)
        val loopDelay = (0.040 * rate).toInt()
        val r = 0.995; val f = 2000.0
        val a1 = 2 * r * cos(2 * PI * f / rate); val a2 = -r * r
        var z1 = 0.0; var z2 = 0.0
        val blk = 128
        val res = DoubleArray(n)
        var firstHowl = -1
        p.events = object : HearingProcessor.Events { override fun onHowl(hz: Double) { if (firstHowl < 0) firstHowl = -2 } }
        var i = 0
        val g = 0.0118                                               // ~ x1.18 loop gain at the resonance
        while (i < n) {
            val x = DoubleArray(blk) { j ->
                val k = i + j
                val fb = if (k - loopDelay >= 0) out[k - loopDelay] else 0.0
                val y = fb * g + a1 * z1 + a2 * z2; z2 = z1; z1 = y; res[k] = y
                amb[k] + y
            }
            val o = DoubleArray(blk); p.process(x, blk, o)
            System.arraycopy(o, 0, out, i, blk)
            if (firstHowl == -2) firstHowl = i
            i += blk
        }
        // same loop without the guard, to compare
        val q = HearingProcessor(rate).apply { enabled = true; mix = 1.0; howlGuard = false }
        val out2 = DoubleArray(n); z1 = 0.0; z2 = 0.0; i = 0
        while (i < n) {
            val x = DoubleArray(blk) { j ->
                val k = i + j
                val fb = if (k - loopDelay >= 0) out2[k - loopDelay] else 0.0
                val y = fb * g + a1 * z1 + a2 * z2; z2 = z1; z1 = y
                amb[k] + y
            }
            val o = DoubleArray(blk); q.process(x, blk, o); System.arraycopy(o, 0, out2, i, blk); i += blk
        }
        val tail = rms(out, 4 * rate, n); val tail2 = rms(out2, 4 * rate, n)
        println("FH howl: caught after ${"%.2f".format(firstHowl.toDouble() / rate)} s; last 2 s output " +
            "${"%.1f".format(20 * log10(tail + 1e-12))} dBFS with the guard vs ${"%.1f".format(20 * log10(tail2 + 1e-12))} dBFS without; " +
            "peak ${"%.3f".format(out.maxOf { abs(it) })} (ceiling 0.251)")
        assertTrue(firstHowl > 0); assertTrue(tail < tail2 / 4)
        assertTrue(out.maxOf { abs(it) } <= 0.2512)
    }

    @Test fun playoutFadesAcrossStallsWithoutClicks() {
        val po = Playout(rate)
        val sig = DoubleArray(rate * 3) { 0.2 * sin(2 * PI * 440 * it / rate) }
        val written = ArrayList<Float>()
        val blk = FloatArray(po.block)
        var fed = 0
        var t = 0L
        // 3 s of 7.5 ms bursts, with a 400 ms stall in the middle; the player pulls at its own pace
        var step = 0
        while (written.size < rate * 3) {
            if (fed < sig.size && !(step in 200..253)) {              // ~ 1.5 s .. 1.9 s: nothing arrives
                val n = minOf(240, sig.size - fed); po.push(sig.copyOfRange(fed, fed + n), n, t); fed += n
            }
            t += 7_500_000; step++
            // the player pulls about as fast as samples should arrive
            while (written.size < step * 240 - po.target) { po.pull(blk); for (v in blk) written += v }
        }
        var maxStep = 0f
        for (k in 1 until written.size) maxStep = maxOf(maxStep, abs(written[k] - written[k - 1]))
        val natural = (0.2 * 2 * PI * 440 / rate).toFloat()
        println("FH playout: underruns ${po.underruns}, largest sample step ${"%.4f".format(maxStep)} " +
            "(the 440 Hz tone itself steps up to ${"%.4f".format(natural)}), target buffer now ${po.target * 1000 / rate} ms")
        assertTrue(po.underruns >= 1); assertTrue(maxStep <= natural * 1.05f)
    }

    @Test fun realTimeFactor() {
        val p = HearingProcessor(rate).apply { enabled = true; voiceMode = VoiceMode.ALL_SPEECH; setNoise(HearingProcessor.analyzeNoise(fan(3.0, 1), rate)) }
        val x = add(speech(20.0, 120.0, 1.0, 71), scale(fan(20.0, 72), 0.02))
        val y = DoubleArray(x.size)
        p.process(x.copyOf(rate), rate, y)                           // warm up the JIT
        val t0 = System.nanoTime(); p.process(x, x.size, y); val ms = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime(); HearingProcessor.analyzeNoise(fan(3.0, 1), rate); val learnMs = (System.nanoTime() - t1) / 1e6
        println("FH speed: 20 s of audio in ${"%.0f".format(ms)} ms on this PC (x${"%.0f".format(20_000 / ms)} real time); learning a 3 s sound ${"%.0f".format(learnMs)} ms")
        assertTrue(ms < 20_000 / 4)
    }
}
