package dev.jaronwilson.headphones

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Filtered hearing 1.7: the neural engine (DeepFilterNet3 + CAM++), the click remover and the old classical
 * engine, on the same mixtures, offline. Real speech is LibriSpeech test-clean (CC BY 4.0), fetched by
 * android/tools/fetch_testdata.py into android/testdata/ (gitignored); room sounds are synthetic. Tests that need
 * the models or the speech are skipped when those are missing. Numbers are printed as "FHN ..." lines.
 *
 * How "how much of each part gets through" is measured: the classical engine applies one real gain per STFT
 * bin, so its parts go through the very same gains (shadow chains, exact). The neural engine is not a per-bin
 * gain of its input, so there the gain is estimated per time-frequency bin as H = Y / X (output over input,
 * same 512-point STFT) and applied to each part (an estimate; exact for any per-bin processing). Both engines
 * are reported with the H estimate too, so the old and new numbers are compared like for like. SI-SDR is
 * against the part that should remain (the room when removing speech; the speech for Voices only).
 */
class NeuralHearingTest {
    private val rate = 32_000
    private val lat = HearingProcessor.LATENCY

    companion object {
        private val dfnFile = File(System.getProperty("fh.dfn") ?: "src/main/assets/models/dfn3_stream.onnx")
        private val spkFile = File(System.getProperty("fh.models") ?: "build/generated/fh-models/models", "campplus_en_voxceleb.onnx")
        private val dataDir = File(System.getProperty("fh.testdata") ?: "../testdata", "librispeech")
        val dfnBytes: ByteArray? by lazy { dfnFile.takeIf { it.exists() }?.readBytes() }
        val spk: Embedder? by lazy { spkFile.takeIf { it.exists() }?.let { OrtModels.CamPlus(it.readBytes()) } }
        fun dfn(): Denoiser? = dfnBytes?.let { OrtModels.Dfn(it) }

        /** 16 kHz speech of one LibriSpeech speaker, or null. */
        fun speaker(id: String): DoubleArray? {
            val f = File(dataDir, "$id.pcm"); if (!f.exists()) return null
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return DoubleArray(b.remaining()) { b.get(it) / 32768.0 }
        }
    }

    /** Pass-through denoiser: shows where every sample lands in the neural chain. */
    private class Identity : Denoiser {
        override val hop = 480; override val delay = 1440
        private val buf = FloatArray(1440 + 480)
        override fun process(x: FloatArray, out: FloatArray, attenLimDb: Float): Float {
            System.arraycopy(buf, 480, buf, 0, 1440); System.arraycopy(x, 0, buf, 1440, 480)
            System.arraycopy(buf, 0, out, 0, 480); return 0f
        }
    }

    // ---------------------------------------------------------------- signals
    private fun up(x: DoubleArray, u: Int, d: Int): DoubleArray {
        val r = PolyResampler(u, d); val o = DoubleArray(r.maxOut(x.size)); val n = r.process(x, 0, x.size, o, 0); return o.copyOf(n)
    }
    private fun rms(x: DoubleArray, from: Int = 0, to: Int = x.size) = sqrt((from until to).sumOf { x[it] * x[it] } / max(1, to - from))
    private fun scale(x: DoubleArray, target: Double): DoubleArray { val r = rms(x); return DoubleArray(x.size) { x[it] * target / (r + 1e-12) } }
    private fun add(vararg xs: DoubleArray) = DoubleArray(xs.minOf { it.size }) { i -> xs.sumOf { it[i] } }
    private fun db(a: Double, b: Double) = 20 * log10(a / (b + 1e-15))
    private fun seg(x: DoubleArray, from: Double, secs: Double, r: Int = 16_000) = x.copyOfRange((from * r).toInt(), ((from + secs) * r).toInt())
    private fun at(len: Int, x: DoubleArray, off: Int) = DoubleArray(len).also { System.arraycopy(x, 0, it, off, min(x.size, len - off)) }

    /** A fan: low-passed noise, 120 Hz blade tone and harmonics, 60 Hz hum. */
    private fun fan(secs: Double, seed: Int): DoubleArray {
        val rnd = Random(seed); val n = (secs * rate).toInt()
        var lp = 0.0; var lp2 = 0.0; val a = exp(-2 * PI * 900 / rate)
        return DoubleArray(n) { i ->
            val wn = rnd.nextDouble() * 2 - 1
            lp = (1 - a) * wn + a * lp; lp2 = 0.7 * lp2 + 0.3 * wn
            val t = i.toDouble() / rate
            0.3 * lp + 0.01 * lp2 + 0.04 * sin(2 * PI * 120 * t) + 0.02 * sin(4 * PI * 120 * t + 1) + 0.025 * sin(2 * PI * 60 * t)
        }
    }

    /** A keyboard: key presses about 5 a second, each a sharp broadband click with a few ms of resonance
     *  (2-5 kHz) and a body thump (150-300 Hz), and a quieter release click 80-150 ms later; very quiet hiss. */
    private fun keyboard(secs: Double, seed: Int): DoubleArray {
        val rnd = Random(seed); val n = (secs * rate).toInt(); val out = DoubleArray(n)
        var t = (0.1 * rate).toInt()
        fun click(at: Int, amp: Double) {
            val f1 = 2000 + 3000 * rnd.nextDouble(); val f2 = 150 + 150 * rnd.nextDouble()
            var prev = 0.0
            for (j in 0 until (0.04 * rate).toInt()) {
                if (at + j >= n) break
                val w = rnd.nextDouble() * 2 - 1; val hp = w - prev; prev = w
                out[at + j] += amp * (0.6 * hp * exp(-j / (0.003 * rate)) + 0.3 * sin(2 * PI * f1 * j / rate) * exp(-j / (0.004 * rate)) +
                    0.4 * sin(2 * PI * f2 * j / rate) * exp(-j / (0.012 * rate)))
            }
        }
        while (t < n) {
            val a = 0.6 + 0.4 * rnd.nextDouble()
            click(t, a); click(t + ((0.08 + 0.07 * rnd.nextDouble()) * rate).toInt(), 0.5 * a)
            t += ((0.12 + 0.25 * rnd.nextDouble()) * rate).toInt()
        }
        for (i in 0 until n) out[i] += 0.002 * (rnd.nextDouble() * 2 - 1)
        return out
    }

    // ---------------------------------------------------------------- the two engines, as FilteredHearing runs them
    /** Runs [x64] through an engine in 480-sample blocks (the AirPods' AAC frames). */
    private inner class Engine(val neural: Boolean, val gate: VoiceGate? = null, denoiser: Denoiser? = null) {
        val proc = HearingProcessor(rate).apply { outputStage = false; enabled = true }
        val front = if (neural) NeuralFront(denoiser ?: dfn()!!, gate ?: VoiceGate(null, async = false)) else null
        private val dec = Decimator2()
        val gates = ArrayList<Double>()

        fun run(x64: DoubleArray): DoubleArray {
            val out = ArrayList<Double>(x64.size / 2 + 2000)
            val b = DoubleArray(480)
            val xo = DoubleArray(4096); val eo = DoubleArray(4096); val go = DoubleArray(4096); val y = DoubleArray(4096)
            var off = 0
            while (off < x64.size) {
                val n = min(480, x64.size - off)
                System.arraycopy(x64, off, b, 0, n)
                val m = if (front != null) front.process(b, n, xo, eo, go).also { proc.process(xo, eo, go, it, y) }
                else dec.process(b, n, xo).also { proc.process(xo, it, y) }
                for (i in 0 until m) { out += y[i]; if (front != null) gates += go[i] }
                off += n
            }
            return out.toDoubleArray()
        }

        /** Where each 32 kHz reference sample lands: the part through the same resampling, no filtering. */
        fun reference(part32: DoubleArray): DoubleArray {
            val x64 = up(part32, 2, 1)
            val r = ArrayList<Double>(part32.size + 2000)
            val b = DoubleArray(480); val xo = DoubleArray(4096); val eo = DoubleArray(4096); val go = DoubleArray(4096)
            val f = if (neural) NeuralFront(Identity(), VoiceGate(null, async = false)) else null
            val d = Decimator2()
            var off = 0
            while (off < x64.size) {
                val n = min(480, x64.size - off); System.arraycopy(x64, off, b, 0, n)
                val m = if (f != null) f.process(b, n, xo, eo, go) else d.process(b, n, xo)
                for (i in 0 until m) r += xo[i]
                off += n
            }
            return r.toDoubleArray()
        }
    }

    /** Output energy of each part (dB of attenuation) over [from, to) of the reference timeline, by H = Y/X. */
    private fun throughH(mix: DoubleArray, y: DoubleArray, parts: List<DoubleArray>, from: Int, to: Int): List<Double> {
        val n = 512; val hop = 128
        val w = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
        val pin = DoubleArray(parts.size); val pout = DoubleArray(parts.size)
        val mr = DoubleArray(n); val mi = DoubleArray(n); val yr = DoubleArray(n); val yi = DoubleArray(n)
        val pr = Array(parts.size) { DoubleArray(n) }; val pi = Array(parts.size) { DoubleArray(n) }
        var meanP = 0.0; var cnt = 0
        for (i in from until to) { meanP += mix[i] * mix[i]; cnt++ }
        val eps = 1e-6 * meanP / max(1, cnt) * n
        var s = from
        while (s + n <= to && s + n + lat <= y.size) {
            for (i in 0 until n) { mr[i] = mix[s + i] * w[i]; mi[i] = 0.0; yr[i] = y[s + lat + i] * w[i]; yi[i] = 0.0 }
            SoundAnalyzer.fft(mr, mi); SoundAnalyzer.fft(yr, yi)
            for (j in parts.indices) { for (i in 0 until n) { pr[j][i] = parts[j][s + i] * w[i]; pi[j][i] = 0.0 }; SoundAnalyzer.fft(pr[j], pi[j]) }
            for (k in 0..n / 2) {
                val den = mr[k] * mr[k] + mi[k] * mi[k] + eps
                var hr = (yr[k] * mr[k] + yi[k] * mi[k]) / den; var hi = (yi[k] * mr[k] - yr[k] * mi[k]) / den
                val mag = sqrt(hr * hr + hi * hi); if (mag > 2) { hr *= 2 / mag; hi *= 2 / mag }
                for (j in parts.indices) {
                    val a = pr[j][k]; val b = pi[j][k]
                    pin[j] += a * a + b * b
                    val o1 = hr * a - hi * b; val o2 = hr * b + hi * a
                    pout[j] += o1 * o1 + o2 * o2
                }
            }
            s += hop
        }
        return parts.indices.map { 10 * log10(pin[it] / (pout[it] + 1e-30)) }
    }

    private fun siSdr(est: DoubleArray, estOff: Int, ref: DoubleArray, from: Int, to: Int): Double {
        var dot = 0.0; var rr = 0.0
        for (i in from until to) { dot += est[i + estOff] * ref[i]; rr += ref[i] * ref[i] }
        val a = dot / (rr + 1e-30)
        var t = 0.0; var e = 0.0
        for (i in from until to) { val tt = a * ref[i]; t += tt * tt; val d = est[i + estOff] - tt; e += d * d }
        return 10 * log10(t / (e + 1e-30))
    }

    private fun needModels() { assumeTrue("DeepFilterNet3 model missing", dfnBytes != null) }
    private fun needSpeech(vararg ids: String) { for (i in ids) assumeTrue("LibriSpeech excerpt $i missing (run android/tools/fetch_testdata.py)", speaker(i) != null) }
    private fun f1(v: Double) = "%.1f".format(v)

    // ---------------------------------------------------------------- building blocks
    @Test fun resamplersKeepTheBandAndRejectAliases() {
        fun gain(u: Int, d: Int, inRate: Int, f: Double): Double {
            val x = DoubleArray(inRate) { sin(2 * PI * f * it / inRate) }
            val y = up(x, u, d); return db(rms(y, y.size / 4, y.size * 3 / 4), rms(x))
        }
        val g1 = gain(3, 4, 64_000, 1000.0); val g2 = gain(3, 4, 64_000, 20_000.0); val a1 = gain(3, 4, 64_000, 30_000.0)
        val g3 = gain(2, 3, 48_000, 12_000.0); val a2 = gain(2, 3, 48_000, 20_000.0)
        println("FHN resample: 64->48 kHz 1 kHz ${"%.2f".format(g1)} dB, 20 kHz ${f1(g2)} dB, 30 kHz (alias) ${f1(a1)} dB; 48->32 kHz 12 kHz ${f1(g3)} dB, 20 kHz (alias) ${f1(a2)} dB")
        assertEquals(0.0, g1, 0.1); assertTrue(g2 > -3); assertTrue(a1 < -60); assertTrue(g3 > -3); assertTrue(a2 < -60)
    }

    @Test fun fbankMatchesTorchaudioKaldi() {
        // reference: torchaudio.compliance.kaldi.fbank(x, num_mel_bins=80, dither=0), minus its mean over time
        val x = FloatArray(4000) { i -> ((0.05 + 0.2 * i / 4000) * sin(2 * PI * (300 + 0.4 * i) * i / 16000) + 0.01 * sin(2 * PI * 5000 * i / 16000 + 0.3)).toFloat() }
        val f = KaldiFbank().compute(x, x.size)
        assertEquals(23, f.size)
        assertArrayEquals(floatArrayOf(2.9376f, 2.4877f, 2.1532f, 2.6922f, 3.3139f, 4.0284f), f[0].copyOfRange(0, 6), 2e-3f)
        assertArrayEquals(floatArrayOf(-0.3269f, 0.4961f, 1.5193f, 2.631f, 4.4509f, 6.8395f), f[10].copyOfRange(30, 36), 2e-3f)
    }

    @Test fun storeRoundTrips() {
        val n = NoiseLearn(listOf(60.0, 120.5), DoubleArray(HearingProcessor.BINS) { it * 1e-6 }, -33.0, DoubleArray(8) { 0.1 * it }, 4.5)
        val sp = SpeakerModel(DoubleArray(13) { it.toDouble() }, DoubleArray(13) { 1.0 + it }, 1.9, 6.0)
        val s = HearingStore(n, sp, mapOf("that" to FloatArray(512) { it / 512f }, "me" to FloatArray(512) { -it / 512f }))
        val r = HearingStore.decode(s.encode())
        assertEquals(listOf(60.0, 120.5), r.noise!!.tonesHz); assertArrayEquals(n.profile, r.noise!!.profile, 0.0)
        assertArrayEquals(n.clickTemplate, r.noise!!.clickTemplate, 0.0); assertEquals(1.9, r.speaker!!.threshold, 0.0)
        assertArrayEquals(s.voices["me"], r.voices["me"], 0f)
        val f = HearingStore.decode(r.withNoise(null).withVoice("me", null).encode())
        assertEquals(null, f.noise); assertEquals(setOf("that"), f.voices.keys)
    }

    @Test fun denoiserDelayIs1440Samples() {
        needModels(); needSpeech("1320")
        val s = up(seg(speaker("1320")!!, 0.0, 6.0), 3, 1)
        val d = dfn()!!; val fr = FloatArray(480); val o = FloatArray(480); val e = DoubleArray(s.size)
        var off = 0
        while (off + 480 <= s.size) { for (i in 0 until 480) fr[i] = s[off + i].toFloat(); d.process(fr, o); for (i in 0 until 480) e[off + i] = o[i].toDouble(); off += 480 }
        var best = 0; var bc = -1.0
        for (lag in 0..3000 step 4) {
            var xy = 0.0; var xx = 0.0; var yy = 0.0
            for (i in 48_000 until s.size - 3000) { xy += s[i] * e[i + lag]; xx += s[i] * s[i]; yy += e[i + lag] * e[i + lag] }
            val c = xy / sqrt(xx * yy); if (c > bc) { bc = c; best = lag }
        }
        println("FHN DFN3 delay: $best samples at 48 kHz (${best / 48.0} ms), correlation with clean speech ${"%.3f".format(bc)}")
        assertEquals(1440, best)
    }

    // ---------------------------------------------------------------- 1. all speech / voices only
    private data class Res(val speechAtt: Double, val roomAtt: Double, val siIn: Double, val siOut: Double)

    /** speech + room through an engine; attenuation of each, SI-SDR against [keep] ("room" or "speech"). */
    private fun runMix(e: Engine, speech32: DoubleArray, room32: DoubleArray, keep: String, from: Int = 3 * rate): Res {
        val sR = e.reference(speech32); val rR = e.reference(room32); val mR = add(sR, rR)
        val y = e.run(up(add(speech32, room32), 2, 1))
        val to = min(mR.size, y.size - lat) - 512
        val (sa, ra) = throughH(mR, y, listOf(sR, rR), from, to)
        val ref = if (keep == "room") rR else sR
        return Res(sa, ra, siSdr(mR, 0, ref, from, to), siSdr(y, lat, ref, from, to))
    }

    private fun speech32(vararg parts: DoubleArray) = up(parts.reduce { a, b -> a + b }, 2, 1).let { scale(it, 0.05) }

    @Test fun removeAllSpeech() {
        needModels(); needSpeech("1320", "2094")
        val s = speech32(seg(speaker("1320")!!, 0.0, 12.0), seg(speaker("2094")!!, 0.0, 12.0))
        for ((label, roomDb) in listOf("quiet room (speech 14 dB over it)" to -14.0, "loud room (speech 5 dB over it)" to -5.0)) {
            val room = scale(fan(s.size.toDouble() / rate, 3), 0.05 * Math.pow(10.0, roomDb / 20))
            val light = runMix(Engine(false).apply { proc.voiceMode = VoiceMode.ALL_SPEECH }, s, room, "room")
            val neural = runMix(Engine(true).apply { proc.voiceMode = VoiceMode.ALL_SPEECH }, s, room, "room")
            val half = runMix(Engine(true).apply { proc.voiceMode = VoiceMode.ALL_SPEECH; proc.amount = 0.5 }, s, room, "room")
            println("FHN all speech, $label: speech attenuated Light ${f1(light.speechAtt)} dB -> Neural ${f1(neural.speechAtt)} dB " +
                "(amount 50 %: ${f1(half.speechAtt)} dB); the room lost Light ${f1(light.roomAtt)} dB, Neural ${f1(neural.roomAtt)} dB; " +
                "SI-SDR of the room ${f1(light.siIn)} dB in -> Light ${f1(light.siOut)} dB, Neural ${f1(neural.siOut)} dB")
            assertTrue(neural.speechAtt > light.speechAtt); assertTrue(neural.siOut > light.siOut)
        }
    }

    @Test fun voicesOnly() {
        needModels(); needSpeech("260")
        val s = speech32(seg(speaker("260")!!, 0.0, 20.0))
        val secs = s.size.toDouble() / rate
        for ((label, room) in listOf(
            "fan" to scale(fan(secs, 5), 0.05),
            "keyboard" to scale(keyboard(secs, 6), 0.05),
            "fan + keyboard" to add(scale(fan(secs, 7), 0.035), scale(keyboard(secs, 8), 0.035)))) {
            val n = runMix(Engine(true).apply { proc.voiceMode = VoiceMode.VOICES_ONLY }, s, room, "speech")
            // the classical way to the same end: learn the sound alone first (3 s), then remove it
            val learn = when (label) { "fan" -> scale(fan(3.0, 50), 0.05); "keyboard" -> scale(keyboard(3.0, 51), 0.05)
                else -> add(scale(fan(3.0, 52), 0.035), scale(keyboard(3.0, 53), 0.035)) }
            val l = runMix(Engine(false).apply { proc.setNoise(HearingProcessor.analyzeNoise(learn, rate)) }, s, room, "speech")
            println("FHN voices only, speech + $label at 0 dB: $label attenuated Light (learned) ${f1(l.roomAtt)} dB -> Neural ${f1(n.roomAtt)} dB; " +
                "speech lost Light ${f1(l.speechAtt)} dB, Neural ${f1(n.speechAtt)} dB; SI-SDR of the speech ${f1(n.siIn)} dB in -> " +
                "Light ${f1(l.siOut)} dB, Neural ${f1(n.siOut)} dB")
            assertTrue(n.roomAtt > 8); assertTrue(n.siOut > n.siIn + 2)
        }
    }

    // ---------------------------------------------------------------- 2. clicks (keyboard)
    @Test fun keyboardClicks() {
        needSpeech("3575")
        val secs = 16.0
        val learnSig = scale(keyboard(3.0, 22), 0.03)
        val learned = HearingProcessor.analyzeNoise(learnSig, rate)
        val oldLearn = HearingProcessor.analyzeNoise(learnSig, rate, clicks = false)       // 1.6.0: clicks averaged in
        println("FHN keyboard learned: ${learned.describe()}")
        assertNotNull("click shape learned", learned.clickTemplate)
        for ((label, speechLevel) in listOf("speech 4 dB over the keyboard" to 0.05, "speech 6 dB under the keyboard" to 0.015)) {
            val kb = scale(keyboard(secs, 21), 0.03)
            val s = speech32(seg(speaker("3575")!!, 0.0, secs)).copyOf(kb.size).let { x -> DoubleArray(x.size) { x[it] * speechLevel / 0.05 } }
            fun shadow(noise: NoiseLearn?, clicks: Boolean): Pair<Double, Double> {
                val p = HearingProcessor(rate, 2).apply { outputStage = false; enabled = true; setNoise(noise); clicksOn = clicks }
                val (_, parts) = p.processShadow(add(s, kb), listOf(s, kb))
                val a = 2 * rate; val b = s.size - lat
                return db(rms(kb, a, b), rms(parts[1], a + lat, b + lat)) to db(rms(s, a, b), rms(parts[0], a + lat, b + lat))
            }
            val (old, sOld) = shadow(oldLearn, false)
            val (new, sNew) = shadow(learned, true)
            val (alone, sAlone) = shadow(null, true)
            val n = if (dfnBytes != null) runMix(Engine(true).apply { proc.voiceMode = VoiceMode.VOICES_ONLY }, s, kb, "speech") else null
            println("FHN keyboard, $label: keyboard attenuated: learned sound (1.6.0) ${f1(old)} dB -> learned + click remover " +
                "${f1(new)} dB (click remover alone ${f1(alone)} dB" + (n?.let { ", Neural voices only ${f1(it.roomAtt)} dB" } ?: "") +
                "); speech lost ${f1(sOld)} / ${f1(sNew)} / ${f1(sAlone)}" + (n?.let { " / ${f1(it.speechAtt)}" } ?: "") + " dB")
            assertTrue(new > old + 3); assertTrue(sNew < 4)
        }
    }

    // ---------------------------------------------------------------- 3. one voice (and your own)
    data class VoiceRes(val a: Double, val b: Double, val a2: Double, val c: Double, val ovA: Double, val ovB: Double)

    /** Enroll A (10 s), then turns A | B | A | C with [gap] s pauses, then A and B at once; quiet room. */
    var tuneGate: (VoiceGate) -> Unit = {}
    private fun voiceScene(neural: Boolean, gap: Double = 0.4): VoiceRes {
        val A = speaker("1320")!!; val B = speaker("2094")!!; val C = speaker("7127")!!
        val turn = 5.0
        val ta = speech32(seg(A, 30.0, turn)); val tb = speech32(seg(B, 30.0, turn)); val ta2 = speech32(seg(A, 40.0, turn))
        val tc = speech32(seg(C, 30.0, turn)); val oa = speech32(seg(A, 50.0, turn)); val ob = speech32(seg(B, 40.0, turn))
        val g = (gap * rate).toInt(); val tl = ta.size
        val starts = IntArray(5) { it * (tl + g) }
        val total = starts[4] + tl + rate
        val a = add(at(total, ta, starts[0]), at(total, ta2, starts[2]), at(total, oa, starts[4]))
        val b = add(at(total, tb, starts[1]), at(total, ob, starts[4]))
        val c = at(total, tc, starts[3])
        val room = scale(fan(total.toDouble() / rate, 9), 0.05 * 0.056)                  // 25 dB under the voices
        val gate = VoiceGate(spk, async = false).also(tuneGate)
        val e = Engine(neural, gate)
        // enrollment: 10 s of A in the same room, through the same chain
        val enrollSig = add(speech32(seg(A, 0.0, 10.0)), scale(fan(10.0, 10), 0.05 * 0.056))
        if (neural) gate.startEnroll("that") else e.proc.startEnroll()
        e.run(up(enrollSig, 2, 1))
        if (neural) {
            var v: FloatArray? = null; var sec = 0.0
            gate.post { finishEnroll { x, s -> v = x; sec = s } }
            e.run(DoubleArray(960))                                                       // one hop to run the posted command
            assertNotNull("neural enrollment", v)
            println("FHN voice enroll (neural): ${f1(sec)} s of speech")
            gate.removing = setOf("that")
        } else { e.proc.finishEnroll(); assertNotNull(e.proc.speaker) }
        e.proc.voiceMode = VoiceMode.TARGET
        // fresh chain state for the scene, same models and voice
        val sceneEngine = if (neural) Engine(true, gate) else Engine(false).apply { proc.speaker = e.proc.speaker }
        sceneEngine.proc.voiceMode = VoiceMode.TARGET
        val aR = sceneEngine.reference(a); val bR = sceneEngine.reference(b); val cR = sceneEngine.reference(c); val rR = sceneEngine.reference(room)
        val mR = add(aR, bR, cR, rR)
        val sims = ArrayList<Pair<Int, Double>>()
        var hopNow = 0
        val hop0 = gate.hop
        if (neural) gate.trace = { sims += (gate.hop - hop0).toInt() to it }
        val y = sceneEngine.run(up(add(a, b, c, room), 2, 1))
        gate.trace = null
        if (neural) {
            val gs = sceneEngine.gates
            val names = listOf("A", "B", "A'", "C", "A+B")
            println("FHN gate per turn: " + (0 until 5).joinToString("; ") { k ->
                val f = starts[k] + 960; val t = min(starts[k] + tl + 960, gs.size)
                val on = (f until t).count { gs[it] > 0.5 } * 100.0 / max(1, t - f)
                val first = (f until t).firstOrNull { gs[it] > 0.5 }?.let { "%.2f s".format((it - f) / 32000.0) } ?: "never"
                "${names[k]} on ${"%.0f".format(on)} % (first at $first)"
            })
            if (System.getProperty("fh.debug") != null) println("FHN sims: " + sims.joinToString(" ") { (h, v) -> "%.2f:%.2f".format(h / 100.0, v) })
            println("FHN turn starts (s): " + starts.joinToString(" ") { "%.2f".format(it / 32000.0) })
        }
        fun att(k: Int, part: Int): Double {
            val lag = if (neural) 1440 * 2 / 3 else 0
            val from = starts[k] + lag; val to = min(starts[k] + tl + lag, y.size - lat - 600)
            return throughH(mR, y, listOf(aR, bR, cR), from, to)[part]
        }
        return VoiceRes(att(0, 0), att(1, 1), att(2, 0), att(3, 2), att(4, 0), att(4, 1))
    }

    var voiceResults: Pair<VoiceRes, VoiceRes>? = null
    @Test fun removeOneVoice() {
        needModels(); needSpeech("1320", "2094", "7127")
        assumeTrue("CAM++ model missing (it is fetched by the build)", spk != null)
        val l = voiceScene(false); val n = voiceScene(true)
        voiceResults = l to n
        println("FHN one voice, turns (A enrolled; B female, C male): A attenuated Light ${f1(l.a)} dB -> Neural ${f1(n.a)} dB " +
            "(second turn ${f1(l.a2)} -> ${f1(n.a2)} dB); B Light ${f1(l.b)} dB, Neural ${f1(n.b)} dB; C Light ${f1(l.c)} dB, Neural ${f1(n.c)} dB")
        println("FHN one voice, A and B at once: A Light ${f1(l.ovA)} dB, Neural ${f1(n.ovA)} dB; B Light ${f1(l.ovB)} dB, Neural ${f1(n.ovB)} dB")
        assertTrue(n.a > 15); assertTrue(n.a2 > 12); assertTrue(n.b < 6); assertTrue(n.c < 6)
    }

    @Test fun speakerEmbeddingSeparatesVoices() {
        needModels(); needSpeech("1320", "2094", "260", "3575", "7127")
        val e = spk; assumeTrue(e != null)
        val ids = listOf("1320", "2094", "260", "3575", "7127")
        val gate = VoiceGate(e, async = false)
        val enr = ids.associateWith { id -> val x = seg(speaker(id)!!, 0.0, 10.0); gate.enrollFrom(e!!, FloatArray(x.size) { x[it].toFloat() }, x.size) }
        val same = ArrayList<Double>(); val diff = ArrayList<Double>()
        for (id in ids) {
            val x = speaker(id)!!
            var t = 20.0
            while (t + 1.0 <= 60.0) {
                val w = seg(x, t, 1.0); val v = e!!.embed(FloatArray(w.size) { w[it].toFloat() }, w.size)
                for (o in ids) (if (o == id) same else diff) += VoiceGate.cos(v, enr[o]!!)
                t += 1.0
            }
        }
        same.sort(); diff.sort()
        fun p(l: List<Double>, q: Double) = l[(q * (l.size - 1)).toInt()]
        val miss = same.count { it < VoiceGate.THR_ON } * 100.0 / same.size; val fa = diff.count { it > VoiceGate.THR_ON } * 100.0 / diff.size
        println("FHN CAM++ on 1 s windows (5 speakers): same voice p10 ${"%.2f".format(p(same, 0.1))} median ${"%.2f".format(p(same, 0.5))}; " +
            "other voices median ${"%.2f".format(p(diff, 0.5))} p99 ${"%.2f".format(p(diff, 0.99))}; at the threshold ${VoiceGate.THR_ON}: " +
            "${f1(miss)} % of windows of the voice missed, ${f1(fa)} % of other voices' windows taken for it")
        assertTrue(fa < 5)
    }

    @Test fun speed() {
        needModels()
        val d = dfn()!!; val fr = FloatArray(480) { (0.05 * sin(it * 0.1)).toFloat() }; val o = FloatArray(480)
        repeat(100) { d.process(fr, o) }
        val t0 = System.nanoTime(); repeat(1000) { d.process(fr, o) }; val ms = (System.nanoTime() - t0) / 1e6 / 1000
        var emb = Double.NaN
        spk?.let { e -> val w = FloatArray(16_000) { (0.05 * sin(it * 0.07)).toFloat() }; repeat(3) { e.embed(w, w.size) }
            val t1 = System.nanoTime(); repeat(10) { e.embed(w, w.size) }; emb = (System.nanoTime() - t1) / 1e6 / 10 }
        println("FHN speed on this PC: DFN3 ${"%.2f".format(ms)} ms per 10 ms hop; CAM++ ${f1(emb)} ms per 1 s window")
    }
}
