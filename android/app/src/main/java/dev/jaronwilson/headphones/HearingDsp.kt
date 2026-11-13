package dev.jaronwilson.headphones

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Filtered hearing: the DSP. Pure Kotlin (no Android), so all of it is unit-tested offline with synthetic
 * signals. The Android side (AirPods mic stream in, AudioTrack out) is FilteredHearing.kt.
 *
 * Signal path, at 32 kHz mono (the AirPods' 64 kHz stream halved by [Decimator2]):
 *   notches (learned hum / fan-blade tones, re-tuned as they drift; low "blocked bands")
 *   -> STFT (512-sample sqrt-Hann, hop 128: 16 ms window, 4 ms frames) with one real gain per bin:
 *        learned steady noise (decision-directed Wiener against the learned profile)
 *        x blocked bands x speech removal (all speech, or only frames where the enrolled voice is talking)
 *        x a howl mask
 *   -> overlap-add -> Mix x on/off fade x howl duck -> hard limiter (ceiling well below full scale).
 */

/** 64 kHz -> 32 kHz: a 47-tap windowed-sinc low-pass (about 14.7 kHz) and every second sample. */
class Decimator2(taps: Int = 47) {
    private val h: DoubleArray
    private val hist = DoubleArray(taps)
    private var pos = 0
    private var phase = 0

    init {
        val m = taps - 1
        val fc = 0.23
        h = DoubleArray(taps) { i ->
            val t = i - m / 2.0
            val sinc = if (t == 0.0) 2 * fc else sin(2 * PI * fc * t) / (PI * t)
            sinc * (0.42 - 0.5 * cos(2 * PI * i / m) + 0.08 * cos(4 * PI * i / m))
        }
        val s = h.sum(); for (i in h.indices) h[i] /= s
    }

    /** Writes about n/2 samples to [out]; returns how many. */
    fun process(x: DoubleArray, n: Int, out: DoubleArray): Int {
        var k = 0
        for (i in 0 until n) {
            hist[pos] = x[i]; pos = (pos + 1) % hist.size
            if (phase == 0) {
                var acc = 0.0; var j = pos
                for (t in h.indices) { j--; if (j < 0) j = hist.size - 1; acc += h[t] * hist[j] }
                out[k++] = acc
            }
            phase = phase xor 1
        }
        return k
    }
}

/** A second-order section (transposed direct form II). Re-tuning keeps its state, so moving a notch a few
 *  hertz doesn't click. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var z1 = 0.0; private var z2 = 0.0

    fun notch(f: Double, q: Double, rate: Int): Biquad {
        val w = 2 * PI * f / rate
        val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al
        b0 = 1 / a0; b1 = -2 * c / a0; b2 = 1 / a0; a1 = -2 * c / a0; a2 = (1 - al) / a0
        return this
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/** Notches in series. Adaptive ones (learned tones) follow their tone when it drifts (fan speed). */
class NotchBank(private val rate: Int) {
    class Notch(var hz: Double, val q: Double, val adaptive: Boolean) { val bq = Biquad() }
    val notches = mutableListOf<Notch>()

    fun add(hz: Double, q: Double, adaptive: Boolean) {
        notches += Notch(hz, q, adaptive).also { it.bq.notch(hz, q, rate) }
    }

    fun retune(i: Int, hz: Double) { val n = notches[i]; n.hz = hz; n.bq.notch(hz, n.q, rate) }
    fun clear() = notches.clear()

    fun process(x: Double): Double {
        var v = x
        for (n in notches) v = n.bq.process(v)
        return v
    }

    companion object {
        /** A narrow notch for a tone: about 5 Hz wide (Q 8..60). */
        fun toneQ(hz: Double) = (hz / 5.0).coerceIn(8.0, 60.0)
    }
}

/** Streaming STFT with weighted overlap-add. [push] one sample, [pop] one sample, in lockstep; when [push]
 *  returns true a frame is in [re]/[im] (bins 0..n/2) and [synth] must be called with its gains. The output is
 *  the input delayed by [LATENCY] samples when all gains are 1. */
class StftChannel(val n: Int = HearingProcessor.N, val hop: Int = HearingProcessor.HOP) {
    val win = DoubleArray(n) { sqrt(0.5 - 0.5 * cos(2 * PI * it / n)) }
    private val norm: Double
    private val inBuf = DoubleArray(n)
    private val acc = DoubleArray(n)
    private val outBlock = DoubleArray(hop)
    private var outPos = 0
    private var cnt = 0
    val re = DoubleArray(n)
    val im = DoubleArray(n)

    init {
        var s = 0.0; var k = 0
        while (k < n) { s += win[k] * win[k]; k += hop }
        norm = s
    }

    fun push(x: Double): Boolean {
        inBuf[n - hop + cnt] = x
        if (++cnt < hop) return false
        cnt = 0
        for (i in 0 until n) { re[i] = inBuf[i] * win[i]; im[i] = 0.0 }
        SoundAnalyzer.fft(re, im)
        System.arraycopy(inBuf, hop, inBuf, 0, n - hop)
        return true
    }

    fun pop(): Double = if (outPos < hop) outBlock[outPos++] else 0.0

    fun synth(g: DoubleArray) {
        val h = n / 2
        for (k in 0..h) { re[k] *= g[k]; im[k] *= g[k] }
        for (k in 1 until h) { re[n - k] = re[k]; im[n - k] = -im[k] }
        for (k in 0 until n) im[k] = -im[k]
        SoundAnalyzer.fft(re, im)                       // inverse via conjugation; only the real part is used
        for (i in 0 until n) acc[i] += re[i] / n * win[i] / norm
        System.arraycopy(acc, 0, outBlock, 0, hop)
        System.arraycopy(acc, hop, acc, 0, n - hop)
        java.util.Arrays.fill(acc, n - hop, n, 0.0)
        outPos = 0
    }

    companion object { const val LATENCY = HearingProcessor.N }
}

/** Hard limiter: instant attack (so the output never passes [ceiling]), 100 ms release. */
class Limiter(rate: Int, var ceiling: Double) {
    private var env = 0.0
    private val rel = exp(-1.0 / (0.1 * rate))
    /** Gain applied to the last sample (1 = untouched). */
    var gain = 1.0; private set

    fun process(x: Double): Double {
        env = max(abs(x), env * rel)
        gain = if (env > ceiling) ceiling / env else 1.0
        return (x * gain).coerceIn(-ceiling, ceiling)
    }
}

/** What "Learn this sound" found: tones to notch and the steady remainder's spectrum (power per STFT bin). */
class NoiseLearn(val tonesHz: List<Double>, val profile: DoubleArray, val levelDb: Double) {
    fun describe(): String {
        val t = tonesHz.sorted()
        return "steady noise at ${"%.0f".format(levelDb)} dBFS" +
            if (t.isEmpty()) "" else ", plus tones at " + t.joinToString(", ") { "${"%.0f".format(it)} Hz" }
    }
}

/**
 * A voice, as the statistics of short-time features over the frames where that voice was heard: 12 mel
 * cepstra (timbre: vocal tract) and the pitch (log f0). A diagonal Gaussian; a frame's distance is its mean
 * squared z-score, pitch weighted [PITCH_W] times. This is a classical speaker model, not a neural speaker
 * embedding: it separates voices that differ in pitch range or timbre (most pairs of adults), not two
 * similar voices.
 */
class SpeakerModel(val mean: DoubleArray, val std: DoubleArray, val threshold: Double, val voicedSec: Double) {
    fun distance(x: DoubleArray): Double {
        var s = 0.0
        for (j in 0 until x.size - 1) { val z = (x[j] - mean[j]) / std[j]; s += z * z }
        val j = x.size - 1
        val z = (x[j] - mean[j]) / std[j]
        s += PITCH_W * z * z
        return s / (x.size - 1 + PITCH_W)
    }

    companion object {
        const val PITCH_W = 4.0

        fun fit(frames: List<DoubleArray>, frameSec: Double): SpeakerModel? {
            if (frames.size < 100) return null
            val d = frames[0].size
            val mean = DoubleArray(d) { j -> frames.sumOf { it[j] } / frames.size }
            val std = DoubleArray(d) { j ->
                val v = frames.sumOf { (it[j] - mean[j]).let { e -> e * e } } / frames.size
                // floors: cepstra 0.3, log2 pitch 0.06 (about a semitone)
                max(sqrt(v), if (j == d - 1) 0.06 else 0.3)
            }
            val m = SpeakerModel(mean, std, 0.0, frames.size * frameSec)
            // smoothed distances on the enrollment itself, as the processor smooths them
            val sm = ArrayList<Double>(frames.size)
            var e = Double.NaN
            for (f in frames) { val x = m.distance(f); e = if (e.isNaN()) x else e + SMOOTH * (x - e); sm += e }
            sm.sort()
            val p90 = sm[(sm.size * 0.9).toInt().coerceAtMost(sm.size - 1)]
            return SpeakerModel(mean, std, max(1.5, p90 * 1.35), m.voicedSec)
        }

        const val SMOOTH = 0.08
    }
}

enum class VoiceMode { OFF, TARGET, ALL_SPEECH }

/**
 * The filtered-hearing processor. Feed 32 kHz samples with [process]; it outputs as many, [LATENCY] samples
 * late. Settings change through [post] (applied on the processing thread between frames), so the UI never
 * races the audio thread. [processShadow] (tests) applies the very same gains to separate parts of a mix, to
 * measure how much of each part gets through.
 */
class HearingProcessor(val rate: Int = 32_000, shadows: Int = 0) {
    companion object {
        const val N = 512
        const val HOP = 128
        const val BINS = N / 2 + 1
        const val LATENCY = StftChannel.LATENCY
        /** Full-scale sine power in one bin of the analysis window, for dBFS readings. */
        private val FS_BIN = (StftChannel().win.sum() / 2).let { it * it }

        /** Find tones (local peaks 10 dB above the surrounding median, 40 Hz..4 kHz) and the steady
         *  remainder's spectrum, from a few seconds of the sound alone. */
        fun analyzeNoise(raw: DoubleArray, rate: Int = 32_000, maxTones: Int = 8): NoiseLearn {
            val nfft = 8192
            val half = nfft / 2
            val w = DoubleArray(nfft) { 0.5 - 0.5 * cos(2 * PI * it / nfft) }
            val pw = DoubleArray(half + 1)
            var segs = 0; var off = 0
            val re = DoubleArray(nfft); val im = DoubleArray(nfft)
            while (off + nfft <= raw.size) {
                for (i in 0 until nfft) { re[i] = raw[off + i] * w[i]; im[i] = 0.0 }
                SoundAnalyzer.fft(re, im)
                for (k in 0..half) pw[k] += re[k] * re[k] + im[k] * im[k]
                segs++; off += nfft / 2
            }
            val tones = mutableListOf<Pair<Double, Double>>()        // hz to prominence dB
            if (segs > 0) {
                val db = DoubleArray(half + 1) { 10 * log10(pw[it] / segs + 1e-20) }
                val top = db.maxOrNull() ?: 0.0
                val k0 = max(2, (40.0 * nfft / rate).toInt()); val k1 = min(half - 30, (4000.0 * nfft / rate).toInt())
                for (k in k0..k1) {
                    if (db[k] < db[k - 1] || db[k] < db[k + 1]) continue
                    val around = (max(1, k - 25)..min(half, k + 25)).filter { abs(it - k) > 3 }.map { db[it] }.sorted()
                    val prom = db[k] - around[around.size / 2]
                    if (prom < 10 || db[k] < top - 50) continue
                    val y0 = db[k - 1]; val y1 = db[k]; val y2 = db[k + 1]
                    val f = (k + 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2 - 1e-12)) * rate / nfft
                    tones += f to prom
                }
            }
            val picked = mutableListOf<Double>()
            for ((f, _) in tones.sortedByDescending { it.second }) {
                if (picked.size >= maxTones) break
                if (picked.none { abs(it - f) < 4 }) picked += f
            }
            // the steady remainder, with those tones notched, through the same analysis window as the processor
            val bank = NotchBank(rate).apply { for (f in picked) add(f, NotchBank.toneQ(f), true) }
            val ch = StftChannel()
            val prof = DoubleArray(BINS)
            var frames = 0
            var sq = 0.0
            for ((i, x) in raw.withIndex()) {
                val v = bank.process(x)
                sq += x * x
                if (ch.push(v) && i > N * 4) {             // skip the notches' start-up
                    for (k in 0 until BINS) prof[k] += ch.re[k] * ch.re[k] + ch.im[k] * ch.im[k]
                    frames++
                }
            }
            if (frames > 0) for (k in 0 until BINS) prof[k] /= frames
            return NoiseLearn(picked, prof, 10 * log10(sq / max(1, raw.size) + 1e-12))
        }
    }

    /** What the screen shows (written by the processing thread). */
    data class Status(
        val inDb: Double = -120.0, val outDb: Double = -120.0, val removedDb: Double = 0.0,
        val speech: Boolean = false, val target: Boolean = false, val pitchHz: Double = 0.0,
        val howls: Int = 0, val learning: Double = -1.0, val enrolling: Double = -1.0)

    interface Events {
        fun onLearned(r: NoiseLearn) {}
        fun onEnrolled(m: SpeakerModel?, voicedSec: Double) {}
        fun onHowl(hz: Double) {}
    }

    @Volatile var events: Events? = null
    /** Tests: each voiced frame's smoothed distance to the enrolled voice. */
    internal var trace: ((Double) -> Unit)? = null
    @Volatile var status = Status(); private set
    private val cmds = ConcurrentLinkedQueue<() -> Unit>()
    /** Change a setting from any thread; applied before the next frame. */
    fun post(c: HearingProcessor.() -> Unit) { cmds.add { c() } }

    // ---------------- settings (processing thread only; use post{} from elsewhere) ----------------
    var noise: NoiseLearn? = null; private set
    var noiseOn = true
    var bands: List<Pair<Double, Double>> = emptyList(); private set
    var voiceMode = VoiceMode.OFF
    var speaker: SpeakerModel? = null
    /** Linear gain of the room sound in the output (the Mix slider). */
    var mix = 0.5
    var limiterCeiling: Double
        get() = limiter.ceiling
        set(v) { limiter.ceiling = v }
    /** Output on (fades in over 1 s) or off (fades out over 0.3 s). Processing and learning run either way. */
    var enabled = false
    var howlGuard = true
    /** Analyse a learned sound on its own thread (the app: the audio thread must not stall for it). */
    var learnInBackground = false
    /** The output-side stages (Mix, fade, duck, limiter); off only in tests that measure the filters alone. */
    var outputStage = true

    fun setNoise(n: NoiseLearn?) {
        noise = n
        for (c in chains) { c.notch.notches.removeAll { it.adaptive }; n?.tonesHz?.forEach { c.notch.add(it, NotchBank.toneQ(it), true) } }
        java.util.Arrays.fill(ddPrev, 0.0)
    }

    fun addBand(lo: Double, hi: Double) {
        bands = bands + (lo to hi)
        val c = sqrt(lo * hi)
        if (c < 400) for (ch in chains) {                     // below ~400 Hz the STFT is too coarse: IIR band-stop
            // three notches across the band, each a third of its width
            val q = (3 * c / (hi - lo)).coerceIn(2.0, 60.0)
            val r = sqrt(hi / lo).pow(2.0 / 3)
            for (fc in listOf(c / r, c, c * r)) ch.notch.add(fc, q, false)
        }
        rebuildBandGain()
    }

    fun clearBands() {
        bands = emptyList()
        for (c in chains) c.notch.notches.removeAll { !it.adaptive }
        rebuildBandGain()
    }

    fun learnNoise(seconds: Double = 3.0) { capture = DoubleArray((seconds * rate).toInt()); capPos = 0 }
    fun startEnroll() { enrollFrames = mutableListOf() }
    fun finishEnroll() {
        val f = enrollFrames ?: return
        enrollFrames = null
        val m = SpeakerModel.fit(f, HOP.toDouble() / rate)
        if (m != null) speaker = m
        events?.onEnrolled(m, f.size * HOP.toDouble() / rate)
    }

    // ---------------- state ----------------
    private inner class Chain { val notch = NotchBank(rate); val stft = StftChannel() }
    private val main = Chain()
    private val shadowChains = List(shadows) { Chain() }
    private val chains = listOf(main) + shadowChains

    private val limiter = Limiter(rate, 10.0.pow(-12.0 / 20))
    private var fade = 0.0
    private var duck = 1.0
    private var duckHold = 0
    private val gain = DoubleArray(BINS) { 1.0 }
    private val bandGain = DoubleArray(BINS) { 1.0 }
    private val howlMask = DoubleArray(BINS) { 1.0 }
    private val ddPrev = DoubleArray(BINS)
    private val pow1 = DoubleArray(BINS)
    private val floorN = DoubleArray(BINS)
    private val psm = DoubleArray(BINS)
    private var floorInit = false
    private val speechG = DoubleArray(BINS) { 1.0 }
    private var capture: DoubleArray? = null
    private var capPos = 0
    private var enrollFrames: MutableList<DoubleArray>? = null
    // raw (pre-notch) history for re-tuning the notches, post-notch history for pitch
    private val raw = DoubleArray(8192); private var rawPos = 0; private var sinceRetune = 0
    private val hist = DoubleArray(PITCH_N); private var histPos = 0
    // speech presence and speaker
    private var hang = 0
    private var dSmooth = Double.NaN
    private var voicedRun = 0
    private var targetNow = false
    private var pitchHz = 0.0
    // howl
    private var howlBin = -1; private var howlRun = 0; private val howlLv = ArrayDeque<Double>(); private var hotRun = 0
    private var howls = 0
    // meters
    private var inSq = 0.0; private var outSq = 0.0; private var meterN = 0
    private var pIn = 0.0; private var pOut = 0.0
    private var removedDb = 0.0

    // mel filterbank for the speaker features
    private val mel: List<List<Pair<Int, Double>>> = run {
        fun m(f: Double) = 2595 * log10(1 + f / 700)
        fun f(mm: Double) = 700 * (10.0.pow(mm / 2595) - 1)
        val lo = m(150.0); val hi = m(5000.0); val nb = 18
        val pts = DoubleArray(nb + 2) { f(lo + (hi - lo) * it / (nb + 1)) }
        List(nb) { b ->
            val l = pts[b]; val c = pts[b + 1]; val r = pts[b + 2]
            val ws = (1 until BINS).mapNotNull { k ->
                val fk = k.toDouble() * rate / N
                val w = if (fk in l..c) (fk - l) / (c - l) else if (fk in c..r) (r - fk) / (r - c) else 0.0
                if (w > 0) k to w else null
            }
            ws.ifEmpty { listOf((c * N / rate).toInt().coerceIn(1, BINS - 1) to 1.0) }
        }
    }
    private val nCep = 12

    // pitch analysis: 1024 samples (32 ms), Hann, autocorrelation from the (denoised) power spectrum <= 1.2 kHz
    private val pWin = DoubleArray(PITCH_N) { 0.5 - 0.5 * cos(2 * PI * it / PITCH_N) }
    private val pWinAc: DoubleArray = run {
        val re = DoubleArray(PITCH_N * 2); val im = DoubleArray(PITCH_N * 2)
        for (i in 0 until PITCH_N) re[i] = pWin[i]
        SoundAnalyzer.fft(re, im)
        for (k in re.indices) { re[k] = re[k] * re[k] + im[k] * im[k]; im[k] = 0.0 }
        SoundAnalyzer.fft(re, im)
        DoubleArray(PITCH_N) { re[it] / re[0] }
    }
    private val pRe = DoubleArray(PITCH_N * 2); private val pIm = DoubleArray(PITCH_N * 2)

    fun process(x: DoubleArray, n: Int, out: DoubleArray) = runBlock(x, n, out, null, null)

    /** Tests: [mix] through the processor, and each of [parts] through the same filters with the gains the
     *  mix produced. Returns the mix's output and each part's (parts' outputs skip the output stage). */
    fun processShadow(mix: DoubleArray, parts: List<DoubleArray>, block: Int = 256): Pair<DoubleArray, List<DoubleArray>> {
        require(parts.size == shadowChains.size)
        val out = DoubleArray(mix.size); val pOut = parts.map { DoubleArray(it.size) }
        var off = 0
        while (off < mix.size) {
            val n = min(block, mix.size - off)
            val xin = mix.copyOfRange(off, off + n)
            val o = DoubleArray(n)
            val pin = parts.map { it.copyOfRange(off, off + n) }
            val po = parts.map { DoubleArray(n) }
            runBlock(xin, n, o, pin, po)
            System.arraycopy(o, 0, out, off, n)
            for (s in parts.indices) System.arraycopy(po[s], 0, pOut[s], off, n)
            off += n
        }
        return out to pOut
    }

    private fun drain() { while (true) (cmds.poll() ?: break).invoke() }

    private fun runBlock(x: DoubleArray, n: Int, out: DoubleArray, parts: List<DoubleArray>?, partsOut: List<DoubleArray>?) {
        drain()
        val fadeIn = 1.0 / rate; val fadeOut = 1.0 / (0.3 * rate)
        for (i in 0 until n) {
            val xi = x[i]
            capture?.let { c ->
                c[capPos++] = xi
                if (capPos >= c.size) {
                    capture = null
                    val apply = { r: NoiseLearn -> post { setNoise(r); noiseOn = true; events?.onLearned(r) } }
                    if (learnInBackground) Thread { apply(analyzeNoise(c, rate)) }.apply { isDaemon = true }.start()
                    else { apply(analyzeNoise(c, rate)); drain() }
                }
            }
            raw[rawPos] = xi; rawPos = (rawPos + 1) and (raw.size - 1)
            val v = main.notch.process(xi)
            hist[histPos] = v; histPos = (histPos + 1) % PITCH_N
            var y = main.stft.pop()
            val ready = main.stft.push(v)
            if (parts != null && partsOut != null) for (s in shadowChains.indices) {
                val c = shadowChains[s]
                partsOut[s][i] = c.stft.pop()
                c.stft.push(c.notch.process(parts[s][i]))
            }
            if (ready) {
                drain()
                frame()
                for (c in chains) c.stft.synth(gain)
            }
            if (++sinceRetune >= rate / 2) { sinceRetune = 0; retune() }
            inSq += xi * xi
            if (outputStage) {
                fade = if (enabled) min(1.0, fade + fadeIn) else max(0.0, fade - fadeOut)
                if (duckHold > 0) { duckHold--; duck = max(0.0, duck - 1.0 / 160) }
                else duck = min(1.0, duck + 1.0 / rate)
                y = limiter.process(y * mix * fade * duck)
            }
            out[i] = y
            outSq += y * y
            if (++meterN >= rate / 8) {
                status = status.copy(inDb = 10 * log10(inSq / meterN + 1e-12), outDb = 10 * log10(outSq / meterN + 1e-12),
                    removedDb = removedDb, speech = hang > 0, target = targetNow && hang > 0, pitchHz = pitchHz, howls = howls,
                    learning = capture?.let { capPos.toDouble() / it.size } ?: -1.0,
                    enrolling = enrollFrames?.let { it.size * HOP.toDouble() / rate } ?: -1.0)
                inSq = 0.0; outSq = 0.0; meterN = 0
            }
        }
    }

    private fun frame() {
        val re = main.stft.re; val im = main.stft.im
        val prof = if (noiseOn) noise?.profile else null
        var sIn = 0.0; var sOut = 0.0
        // 1) learned steady noise: decision-directed Wiener against the profile (over-subtracted 1.5x, floor -24 dB)
        for (k in 0 until BINS) {
            val p = re[k] * re[k] + im[k] * im[k]
            var g = 1.0
            if (prof != null && prof[k] > 0) {
                val gamma = p / (1.5 * prof[k])
                val xi = 0.92 * ddPrev[k] + 0.08 * max(gamma - 1, 0.0)
                g = max(xi / (1 + xi), 0.06)
                ddPrev[k] = g * g * gamma
            }
            gain[k] = g
            pow1[k] = g * g * p
            sIn += p; sOut += pow1[k]
        }
        if (sIn > 0) removedDb += 0.02 * (10 * log10(sIn / (sOut + 1e-20)) - removedDb)
        // 2) background floor of what remains (continuous minimum tracking; rises 3 dB/s, 0.5 dB/s in speech)
        val rise = if (hang > 0) RISE_SPEECH else RISE
        //    on power smoothed over ~25 ms; the minimum of that sits ~3 dB under the mean, so x2
        if (!floorInit) { System.arraycopy(pow1, 0, psm, 0, BINS); floorInit = true; for (k in 0 until BINS) floorN[k] = 2 * psm[k] }
        for (k in 0 until BINS) {
            psm[k] += 0.15 * (pow1[k] - psm[k])
            val m = 2 * psm[k]
            floorN[k] = if (m < floorN[k]) m else floorN[k] * rise
        }
        // 3) speech: voiced (pitch) and above the floor in the speech band
        var sb = 0.0; var nb = 0.0
        for (k in BIN_300..BIN_3400) { sb += pow1[k]; nb += floorN[k] }
        val snr = 10 * log10(sb / (nb + 1e-20) + 1e-12)
        val (f0, conf) = pitch(prof)
        val voiced = conf > 0.55 && snr > 5
        if (voiced) {
            hang = HANG; voicedRun++; pitchHz = f0
            val feat = features(f0)
            enrollFrames?.add(feat)
            speaker?.let { s ->
                val d = s.distance(feat)
                dSmooth = if (dSmooth.isNaN()) d else dSmooth + SpeakerModel.SMOOTH * (d - dSmooth)
                if (voicedRun >= 3) targetNow = dSmooth < s.threshold
                trace?.invoke(dSmooth)
            }
        } else if (hang > 0) {
            hang--
            if (hang == 0) { dSmooth = Double.NaN; voicedRun = 0; targetNow = false }
        }
        // 4) remove speech: in the speech band, cut what stands above the background floor
        val sp = speaker
        val remove = hang > 0 && when (voiceMode) {
            VoiceMode.ALL_SPEECH -> true
            VoiceMode.TARGET -> sp != null && targetNow
            VoiceMode.OFF -> false
        }
        for (k in 0 until BINS) {
            val tgt = if (remove && k in BIN_80..BIN_8000) sqrt(min(1.0, 2.0 * floorN[k] / (pow1[k] + 1e-20))).coerceAtLeast(0.05) else 1.0
            speechG[k] = if (tgt < speechG[k]) tgt else speechG[k] + 0.12 * (tgt - speechG[k])
            gain[k] *= speechG[k] * bandGain[k] * howlMask[k]
        }
        // 5) howl guard on the output spectrum
        if (howlGuard && outputStage) howlCheck(re, im)
        pIn = sIn; pOut = sOut
    }

    /** Pitch (Hz) and confidence (normalised autocorrelation 0..1), from the last 32 ms after the notches,
     *  with the learned noise and the background floor subtracted, band-limited to 60..1200 Hz. */
    private fun pitch(prof: DoubleArray?): Pair<Double, Double> {
        val n2 = PITCH_N * 2
        for (i in 0 until PITCH_N) { pRe[i] = hist[(histPos + i) % PITCH_N] * pWin[i]; pIm[i] = 0.0 }
        java.util.Arrays.fill(pRe, PITCH_N, n2, 0.0); java.util.Arrays.fill(pIm, PITCH_N, n2, 0.0)
        SoundAnalyzer.fft(pRe, pIm)
        val scale = PITCH_N * 0.375 / (N * 0.5) * 2          // Hann(1024) vs sqrt-Hann(512) noise power; zero-padded x2
        val kHi = (1200.0 * n2 / rate).toInt(); val kLo = (60.0 * n2 / rate).toInt()
        for (k in 0..n2 / 2) {
            var p = pRe[k] * pRe[k] + pIm[k] * pIm[k]
            if (k < kLo || k > kHi) p = 0.0 else {
                val b = (k * N / n2).coerceAtMost(BINS - 1)
                val noiseP = ((prof?.get(b) ?: 0.0) * 1.5 + floorN[b]) * scale
                p = max(0.0, p - 2 * noiseP)
            }
            pRe[k] = p; pIm[k] = 0.0
            if (k in 1 until n2 / 2) { pRe[n2 - k] = p; pIm[n2 - k] = 0.0 }
        }
        SoundAnalyzer.fft(pRe, pIm)
        val r0 = pRe[0]
        if (r0 <= 0) return 0.0 to 0.0
        val tMin = rate / 400; val tMax = rate / 70
        var best = 0.0
        val r = DoubleArray(tMax + 2)
        for (t in tMin - 1..tMax + 1) r[t] = pRe[t] / r0 / pWinAc[t]
        for (t in tMin..tMax) best = max(best, r[t])
        if (best <= 0) return 0.0 to 0.0
        for (t in tMin..tMax) {                               // the shortest lag near the best (no octave errors)
            if (r[t] >= 0.85 * best && r[t] >= r[t - 1] && r[t] >= r[t + 1]) {
                val y0 = r[t - 1]; val y1 = r[t]; val y2 = r[t + 1]
                val tt = t + 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2 - 1e-12)
                return rate / tt to min(1.0, y1)
            }
        }
        return 0.0 to 0.0
    }

    private fun features(f0: Double): DoubleArray {
        val m = mel.size
        val logE = DoubleArray(m) { b -> ln(mel[b].sumOf { (k, w) -> w * max(pow1[k] - floorN[k], 0.1 * pow1[k]) } + 1e-6) }
        val f = DoubleArray(nCep + 1)
        for (j in 1..nCep) {
            var s = 0.0
            for (b in 0 until m) s += logE[b] * cos(PI * j * (b + 0.5) / m)
            f[j - 1] = s * sqrt(2.0 / m)
        }
        f[nCep] = log2(f0 / 100.0)
        return f
    }

    private fun howlCheck(re: DoubleArray, im: DoubleArray) {
        var kMax = -1; var yMax = 0.0
        for (k in 4 until BINS - 9) {
            val g = gain[k]; val y = g * g * (re[k] * re[k] + im[k] * im[k])
            if (y > yMax) { yMax = y; kMax = k }
        }
        if (kMax < 0) return
        var side = 0.0; var ns = 0
        for (d in 3..8) for (k in intArrayOf(kMax - d, kMax + d)) if (k in 1 until BINS) {
            val g = gain[k]; side += g * g * (re[k] * re[k] + im[k] * im[k]); ns++
        }
        val ratio = 10 * log10(yMax / (side / ns + 1e-20) + 1e-12)
        val lv = 10 * log10(yMax / FS_BIN + 1e-20) + 20 * log10(mix + 1e-9)
        if (abs(kMax - howlBin) <= 1 && ratio > 20) howlRun++ else { howlRun = 0; howlLv.clear() }
        howlBin = kMax
        howlLv.addLast(lv); while (howlLv.size > 13) howlLv.removeFirst()
        val growing = howlRun >= 12 && howlLv.size == 13 && howlLv.last() - howlLv.first() > 4 && lv > -70
        hotRun = if (ratio > 25 && limiter.gain < 0.5) hotRun + 1 else 0
        if (growing || hotRun > 75) {
            duckHold = (1.5 * rate).toInt()
            for (k in kMax - 1..kMax + 1) if (k in 0 until BINS) howlMask[k] = 0.05
            howls++; howlRun = 0; hotRun = 0; howlLv.clear()
            events?.onHowl(kMax.toDouble() * rate / N)
        }
    }

    /** Learned tones drift with fan speed: every 0.5 s move each to the strongest frequency within +-4 %. */
    private fun retune() {
        val ns = main.notch.notches
        if (ns.none { it.adaptive }) return
        val len = raw.size
        for ((i, nt) in ns.withIndex()) {
            if (!nt.adaptive) continue
            val fs = DoubleArray(9) { nt.hz * (0.96 + 0.01 * it) }
            val e = fs.map { goertzel(it, len) }
            val j = e.indices.maxByOrNull { e[it] }!!
            if (j == 0 || j == 8) continue
            if (10 * log10(e[j] / (min(e[0], e[8]) + 1e-20)) < 6) continue
            val y0 = ln(e[j - 1] + 1e-20); val y1 = ln(e[j] + 1e-20); val y2 = ln(e[j + 1] + 1e-20)
            val f = fs[j] + 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2 - 1e-12) * nt.hz * 0.01
            if (abs(f - nt.hz) > 0.3) for (c in chains) c.notch.retune(i, f)
        }
    }

    private fun goertzel(f: Double, len: Int): Double {
        val w = 2 * PI * f / rate; val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until len) {
            val wv = 0.5 - 0.5 * cos(2 * PI * i / len)
            val s = raw[(rawPos + i) and (raw.size - 1)] * wv + c * s1 - s2
            s2 = s1; s1 = s
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }

    private fun rebuildBandGain() {
        java.util.Arrays.fill(bandGain, 1.0)
        for ((lo, hi) in bands) {
            if (sqrt(lo * hi) < 400) continue
            for (k in 0 until BINS) {
                val f = k.toDouble() * rate / N
                val edge = rate.toDouble() / N                    // one bin of raised-cosine skirt each side
                val g = when {
                    f in lo..hi -> 0.03
                    f in (lo - edge)..lo -> 0.03 + 0.97 * (0.5 + 0.5 * cos(PI * (f - lo + edge) / edge))
                    f in hi..(hi + edge) -> 0.03 + 0.97 * (0.5 - 0.5 * cos(PI * (f - hi) / edge))
                    else -> 1.0
                }
                bandGain[k] = min(bandGain[k], g)
            }
        }
    }

    private val BIN_80 = (80 * N / rate).coerceAtLeast(1)
    private val BIN_300 = 300 * N / rate
    private val BIN_3400 = 3400 * N / rate
    private val BIN_8000 = min(BINS - 1, 8000 * N / rate)
    private val RISE = 10.0.pow(0.3 / (rate.toDouble() / HOP))
    private val RISE_SPEECH = 10.0.pow(0.05 / (rate.toDouble() / HOP))
    private val HANG = (0.25 * rate / HOP).toInt()
}

private const val PITCH_N = 1024

/**
 * Between the processor and the audio output: a jitter buffer that hides the AirPods stream's bursts and
 * stalls. It starts playing only once [target] samples are queued, fades in over 30 ms, fades out over the
 * last queued samples when the stream stops (never a click), and if the queue grows (the AirPods' clock runs
 * a little faster than the phone's) it skips ahead with a crossfade. Each underrun makes the target a little
 * longer (up to 150 ms). Times of arrival are kept to estimate the delay.
 */
class Playout(val rate: Int, val block: Int = 256) {
    private val cap = rate * 2
    private val ring = FloatArray(cap)
    private var w = 0L
    private var r = 0L
    var target = rate * 48 / 1000; private set
    private val maxTarget = rate * 150 / 1000
    private val fadeLen = rate * 8 / 1000
    private var playing = false
    private var gain = 0.0
    private val arrIdx = LongArray(256); private val arrNs = LongArray(256); private var arrN = 0L
    var underruns = 0; private set
    var skips = 0; private set

    @Synchronized fun push(x: DoubleArray, n: Int, nanos: Long) {
        if (n <= 0) return
        arrIdx[(arrN % 256).toInt()] = w; arrNs[(arrN % 256).toInt()] = nanos; arrN++
        for (i in 0 until n) ring[((w + i) % cap).toInt()] = x[i].toFloat()
        w += n
        if (w - r > cap) r = w - cap
    }

    @Synchronized fun queued(): Int = (w - r).toInt()

    /** Fill [out] (always [block] samples). Returns the stream index of out[0], or -1 for silence. */
    @Synchronized fun pull(out: FloatArray): Long {
        val avail = (w - r).toInt()
        if (!playing) {
            if (avail < target) { java.util.Arrays.fill(out, 0, block, 0f); return -1 }
            playing = true; gain = 0.0
        }
        val start = r
        if (avail >= block + fadeLen) {
            val over = avail - target - rate * 60 / 1000
            if (over > 0) {
                val drop = avail - target
                for (i in 0 until block) {
                    val a = ring[((r + i) % cap).toInt()]; val b = ring[((r + drop + i) % cap).toInt()]
                    val t = (i + 1).toFloat() / block
                    out[i] = a * (1 - t) + b * t
                }
                r += drop + block; skips++
            } else {
                for (i in 0 until block) out[i] = ring[((r + i) % cap).toInt()]
                r += block
            }
            val step = 1.0 / (0.03 * rate)
            for (i in 0 until block) { gain = min(1.0, gain + step); out[i] = (out[i] * gain).toFloat() }
            return start
        }
        // running dry: fade out over what is left, then silence until the queue refills
        val m = min(avail, block)
        for (i in 0 until m) out[i] = (ring[((r + i) % cap).toInt()] * gain * (1.0 - (i + 1).toDouble() / m)).toFloat()
        java.util.Arrays.fill(out, m, block, 0f)
        r += m; playing = false; underruns++
        target = min(maxTarget, target + rate * 8 / 1000)
        return start
    }

    /** When stream sample [idx] arrived (System.nanoTime), or null if too old. */
    @Synchronized fun arrival(idx: Long): Long? {
        var bi = -1L; var bn = 0L
        val from = max(0L, arrN - 256)
        for (j in from until arrN) {
            val s = (j % 256).toInt()
            if (arrIdx[s] <= idx && arrIdx[s] > bi) { bi = arrIdx[s]; bn = arrNs[s] }
        }
        return if (bi < 0) null else bn + (idx - bi) * 1_000_000_000L / rate
    }
}
