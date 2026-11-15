package dev.jaronwilson.headphones

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Filtered hearing, the neural part. Pure Kotlin around two small models behind interfaces, so the logic is
 * unit-tested on a PC (OrtModels.kt runs the real models there with the desktop ONNX Runtime).
 *
 *   AirPods 64 kHz -> 48 kHz -> DeepFilterNet3 (streaming, 10 ms hops, 30 ms delay) -> speech estimate e
 *                              \-> the input, delayed to match -> x
 *   x and e go on at 32 kHz into HearingProcessor, which forms   out = x - c*e - v*(x - e)
 *   (c: how much speech to take out, v: how much of the rest), plus the classical stages after it.
 *   e at 16 kHz -> speaker embedding (CAM++) over the current talk spurt -> is it an enrolled voice? -> c.
 */

/** A streaming denoiser: [hop] samples in, [hop] enhanced samples out, [delay] samples late. */
interface Denoiser {
    val hop: Int
    val delay: Int
    /** Returns the model's local SNR estimate (dB) for the frame. */
    fun process(x: FloatArray, out: FloatArray, attenLimDb: Float = 0f): Float
    fun close() {}
}

/** A speaker-embedding model: 16 kHz audio -> a unit-length vector; same voice = high cosine similarity. */
interface Embedder {
    fun embed(x: FloatArray, n: Int): FloatArray
    fun close() {}
}

/** Rational resampler (up/down), polyphase windowed sinc. Cut-off at [cutoff] of the lower Nyquist. */
class PolyResampler(val up: Int, val down: Int, tapsPerPhase: Int = 24, cutoff: Double = 0.9) {
    private val t = tapsPerPhase
    private val h: DoubleArray
    private val hist = DoubleArray(t)
    private var pos = 0
    private var ph = 0
    /** Group delay in output samples. */
    val delay: Double

    init {
        val len = up * t
        val fc = cutoff * 0.5 / max(up, down)                // cycles per upsampled sample
        val m = len - 1
        h = DoubleArray(len) { i ->
            val x = i - m / 2.0
            val s = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            s * (0.42 - 0.5 * cos(2 * PI * i / m) + 0.08 * cos(4 * PI * i / m))
        }
        val sum = h.sum(); for (i in h.indices) h[i] = h[i] / sum * up
        delay = m / 2.0 / down
    }

    /** Resamples x[xOff, xOff+n) into out from outOff; returns how many samples were written. */
    fun process(x: DoubleArray, xOff: Int, n: Int, out: DoubleArray, outOff: Int): Int {
        var k = outOff
        for (i in xOff until xOff + n) {
            hist[pos] = x[i]; pos = if (pos + 1 == t) 0 else pos + 1
            while (ph < up) {
                var acc = 0.0; var j = pos; var tap = ph
                for (q in 0 until t) { j--; if (j < 0) j = t - 1; acc += h[tap] * hist[j]; tap += up }
                out[k++] = acc
                ph += down
            }
            ph -= up
        }
        return k - outOff
    }

    fun maxOut(n: Int) = n * up / down + 2
}

/**
 * Log mel filterbank energies exactly as Kaldi / torchaudio.compliance.kaldi.fbank compute them with their
 * defaults (25 ms Povey window, 10 ms shift, pre-emphasis 0.97, DC removal, power spectrum, 80 bins from
 * 20 Hz to Nyquist, snip_edges), then the mean over time subtracted (what 3D-Speaker's CAM++ expects).
 */
class KaldiFbank(val rate: Int = 16_000, val bins: Int = 80) {
    val frameLen = rate * 25 / 1000
    val shift = rate * 10 / 1000
    private val nfft = 512
    private val win = DoubleArray(frameLen) { (0.5 - 0.5 * cos(2 * PI * it / (frameLen - 1))).pow(0.85) }
    private val mel: Array<DoubleArray>
    private val melLo: IntArray
    private val re = DoubleArray(nfft); private val im = DoubleArray(nfft)

    init {
        fun m(f: Double) = 1127.0 * ln(1 + f / 700.0)
        val lo = m(20.0); val hi = m(rate / 2.0); val d = (hi - lo) / (bins + 1)
        val bw = rate.toDouble() / nfft
        val w = Array(bins) { b ->
            val l = lo + b * d; val c = lo + (b + 1) * d; val r = lo + (b + 2) * d
            DoubleArray(nfft / 2) { i -> val mm = m(bw * i); max(0.0, min((mm - l) / (c - l), (r - mm) / (r - c))) }
        }
        melLo = IntArray(bins) { b -> w[b].indexOfFirst { it > 0 }.coerceAtLeast(0) }
        mel = Array(bins) { b -> val e = w[b].indexOfLast { it > 0 }.coerceAtLeast(melLo[b]); w[b].copyOfRange(melLo[b], e + 1) }
    }

    fun frames(n: Int) = if (n < frameLen) 0 else 1 + (n - frameLen) / shift

    /** Features as [frames][bins], mean-normalised over time. */
    fun compute(x: FloatArray, n: Int): Array<FloatArray> {
        val t = frames(n)
        val out = Array(t) { FloatArray(bins) }
        val mean = DoubleArray(bins)
        val f = DoubleArray(frameLen)
        for (fr in 0 until t) {
            val o = fr * shift
            var s = 0.0
            for (i in 0 until frameLen) { f[i] = x[o + i].toDouble(); s += f[i] }
            s /= frameLen
            for (i in 0 until frameLen) f[i] -= s
            for (i in frameLen - 1 downTo 1) f[i] -= 0.97 * f[i - 1]
            f[0] -= 0.97 * f[0]
            for (i in 0 until nfft) { re[i] = if (i < frameLen) f[i] * win[i] else 0.0; im[i] = 0.0 }
            SoundAnalyzer.fft(re, im)
            for (k in 0 until nfft / 2) re[k] = re[k] * re[k] + im[k] * im[k]
            for (b in 0 until bins) {
                var e = 0.0; val w = mel[b]; val k0 = melLo[b]
                for (j in w.indices) e += w[j] * re[k0 + j]
                val v = ln(max(e, 1.1920929e-7))
                out[fr][b] = v.toFloat(); mean[b] += v
            }
        }
        if (t > 0) for (b in 0 until bins) { val m = (mean[b] / t).toFloat(); for (fr in 0 until t) out[fr][b] -= m }
        return out
    }
}

/**
 * Is an enrolled voice talking right now? Fed the denoiser's speech estimate one 10 ms hop at a time (48 kHz).
 *
 * Speech activity is the estimate's level against its own floor (the denoiser has already removed the room).
 * A "talk spurt" ends after 250 ms without speech. Within a spurt, every 250 ms (once there is 0.5 s of it) the
 * last <= 1 s of the spurt's speech is embedded and compared (cosine) with each enrolled voice that is being
 * removed; the decision has hysteresis (on above [THR_ON], off below [THR_OFF]). A spurt that starts within
 * 0.8 s of one that was the enrolled voice starts as "still them" (people pause mid-sentence). The returned
 * gate follows the decision with a 30 ms attack and a 200 ms release, and only while speech is active.
 *
 * Honest limits: the first ~0.5 s of each new spurt from them passes (nothing to decide on yet, unless it is a
 * continuation); with two people talking at once the embedding is a blend, so it tends to let both through.
 */
class VoiceGate(private val embedder: Embedder?, async: Boolean = true) {
    companion object {
        const val RATE = 16_000
        const val HOP16 = 160
        /** Calibrated on LibriSpeech test-clean (10 speakers): 1 s windows, same speaker p10 0.40, others' p99 0.29. */
        const val THR_ON = 0.32
        const val THR_OFF = 0.24
        const val WIN = RATE                       // embed at most the last 1 s of the spurt
        const val MIN_WIN = RATE / 5               // first look after 0.2 s of speech (short looks can only say "them")
        const val MIN_OFF = RATE * 8 / 10          // "not them" needs 0.8 s (short windows score low)
        const val EVERY = 25                       // hops between embeddings (250 ms)
        const val EVERY_EARLY = 10                 // ... 100 ms in a spurt's first second
        const val GAP = 25                         // hops of silence that end a spurt
        const val CONTINUE = 100                   // a new spurt within 1 s of the voice's last one: assume them
        const val HANG = 15                        // hops of activity hangover
        const val ENROLL_MIN_S = 3.0
        fun cos(a: FloatArray, b: FloatArray): Double {
            var s = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { s += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return s / sqrt(na * nb + 1e-20)
        }
        fun unit(v: FloatArray): FloatArray { var n = 0.0; for (x in v) n += x * x; val r = (1 / sqrt(n + 1e-20)).toFloat(); return FloatArray(v.size) { v[it] * r } }
    }

    var thrOn = THR_ON
    var thrOff = THR_OFF
    var minWin = MIN_WIN
    var everyEarly = EVERY_EARLY
    var continueHops = CONTINUE
    var minOff = MIN_OFF
    /** Before the first look at a new spurt: take out this much (it may be them; it costs others ~0.3 s at -6 dB). */
    var provisional = 0.5
    private var undecided = false
    /** Enrolled voices by slot ("that" = the voice to remove, "me" = your own). */
    val voices = ConcurrentHashMap<String, FloatArray>()
    /** Slots being removed now. */
    @Volatile var removing: Set<String> = emptySet()
    private val exec: ExecutorService? = if (async) Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-embed").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    } else null

    private val dec = PolyResampler(1, 3)
    private val d48 = DoubleArray(480); private val d16 = DoubleArray(200)
    private val ring = FloatArray(WIN); private var ringPos = 0; private var spurt = 0
    private var floorDb = -90.0
    private var hang = 0
    private var silent = 1000
    var hop = 0L; private set
    private var sinceEmbed = 0
    private var spurtId = 0
    private var lastVoiceEnd = -1_000_000L
    private var simEma = Double.NaN
    @Volatile private var busy = false
    @Volatile private var result: Triple<Int, Double, Int>? = null
    /** Is the current spurt an enrolled voice being removed? */
    var decision = false; private set
    /** The last similarity to the closest voice being removed (NaN: none yet). */
    @Volatile var lastSim = Double.NaN; private set
    @Volatile var speaking = false; private set
    private var g = 0.0
    /** Tests: each similarity as it is computed. */
    internal var trace: ((Double) -> Unit)? = null
    /** Embeddings computed (tests, benchmark). */
    @Volatile var embeds = 0; private set

    // enrollment
    private var enroll: FloatArray? = null
    private var enrollLen = 0
    private var enrollSlot = ""
    val enrollingSec: Double get() = if (enroll == null) -1.0 else enrollLen.toDouble() / RATE

    private val cmds = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    /** Run on the audio thread before the next hop (enrollment start / finish from the UI). */
    fun post(c: VoiceGate.() -> Unit) { cmds.add { c() } }

    /** One 10 ms hop of the speech estimate (480 samples at 48 kHz); returns the removal gate 0..1. */
    fun onHop(e: FloatArray): Double {
        while (true) (cmds.poll() ?: break).invoke()
        hop++
        var p = 0.0
        for (i in 0 until 480) { d48[i] = e[i].toDouble(); p += d48[i] * d48[i] }
        val n16 = dec.process(d48, 0, 480, d16, 0)
        val db = 10 * log10(p / 480 + 1e-12)
        floorDb = if (db < floorDb) db else floorDb + 0.05            // follows quiet at once, rises 5 dB/s
        val act = db > -62 && db > floorDb + 12
        if (act) hang = HANG else if (hang > 0) hang--
        val talking = hang > 0
        speaking = talking
        if (act) {
            if (silent >= GAP) {                                       // a new spurt
                spurtId++; spurt = 0; simEma = Double.NaN; sinceEmbed = 0
                decision = hop - lastVoiceEnd < continueHops && removing.isNotEmpty()
                undecided = !decision
            }
            silent = 0
            for (i in 0 until n16) { ring[ringPos] = d16[i].toFloat(); ringPos = (ringPos + 1) % WIN }
            spurt += n16
            enroll?.let { b -> val m = min(n16, b.size - enrollLen); for (i in 0 until m) b[enrollLen + i] = d16[i].toFloat(); enrollLen += m }
        } else {
            silent++
            if (silent == GAP) { if (decision) lastVoiceEnd = hop - GAP; decision = false }
        }
        result?.let { (id, s, len) ->
            result = null
            if (id == spurtId && removing.isNotEmpty()) {
                undecided = false
                if (len < minOff) { if (s > thrOn) decision = true }      // short windows: only to say "it's them"
                else {
                    simEma = if (simEma.isNaN()) s else 0.5 * simEma + 0.5 * s
                    decision = if (simEma > thrOn) true else if (simEma < thrOff) false else decision
                }
            }
        }
        if (act && removing.isNotEmpty() && embedder != null && spurt >= minWin &&
            ++sinceEmbed >= (if (spurt < WIN) everyEarly else EVERY) && !busy) {
            sinceEmbed = 0
            request()
        }
        val target = if (!talking || removing.isEmpty()) 0.0 else if (decision) 1.0 else if (undecided) provisional else 0.0
        g += (target - g) * (if (target > g) 0.35 else 0.05)
        return g
    }

    private fun request() {
        val emb = embedder ?: return
        val n = min(spurt, WIN)
        val w = FloatArray(n) { ring[(ringPos - n + it + WIN) % WIN] }
        val id = spurtId
        val slots = removing
        busy = true
        val job = Runnable {
            try {
                val v = emb.embed(w, n); embeds++
                var best = -1.0
                for (s in slots) voices[s]?.let { best = max(best, cos(v, it)) }
                lastSim = best
                trace?.invoke(best)
                result = Triple(id, best, n)
            } finally { busy = false }
        }
        if (exec != null) exec.execute(job) else job.run()
    }

    fun startEnroll(slot: String, maxSec: Double = 30.0) { enrollSlot = slot; enrollLen = 0; enroll = FloatArray((maxSec * RATE).toInt()) }

    /** Ends enrollment; [done] gets the voice (null: too little speech) and the seconds of speech heard. */
    fun finishEnroll(done: (FloatArray?, Double) -> Unit) {
        val b = enroll ?: return
        enroll = null
        val n = enrollLen; val slot = enrollSlot
        val sec = n.toDouble() / RATE
        val emb = embedder
        if (emb == null || sec < ENROLL_MIN_S) { done(null, sec); return }
        val job = Runnable {
            val v = enrollFrom(emb, b, n)
            voices[slot] = v
            done(v, sec)
        }
        if (exec != null) exec.execute(job) else job.run()
    }

    fun close() { exec?.shutdownNow() }

    /** Average of the unit embeddings of 1 s windows (0.5 s apart), made unit length. */
    fun enrollFrom(emb: Embedder, x: FloatArray, n: Int): FloatArray {
        var acc: FloatArray? = null
        var off = 0
        while (off + WIN <= n || (off == 0 && n > 0)) {
            val len = min(WIN, n - off)
            val v = emb.embed(x.copyOfRange(off, off + len), len)
            val a = acc ?: FloatArray(v.size).also { acc = it }
            for (i in v.indices) a[i] += v[i]
            off += WIN / 2
        }
        return unit(acc!!)
    }
}

/**
 * 64 kHz in -> the denoiser at 48 kHz -> the input (delayed to match) and the speech estimate, both at 32 kHz,
 * plus the voice gate per sample. 480 samples at 48 kHz are exactly 320 at 32 kHz, so the streams stay in step.
 */
class NeuralFront(private val dfn: Denoiser, val gate: VoiceGate) {
    private val hop = dfn.hop
    private val out32 = hop * 2 / 3
    private val up = PolyResampler(3, 4)
    private val dx = PolyResampler(2, 3); private val de = PolyResampler(2, 3)
    private var b48 = DoubleArray(8192); private var n48 = 0
    private val frame = FloatArray(hop); private val enh = FloatArray(hop)
    private val dl = FloatArray(dfn.delay + hop)
    private val xd = DoubleArray(hop); private val ed = DoubleArray(hop)
    private var gPrev = 0.0
    @Volatile var attenLimDb = 0f
    @Volatile var lsnr = 0f; private set
    /** Total delay, in 32 kHz samples, from the 64 kHz input to these outputs. */
    val delay32: Double = (up.delay + dfn.delay) * 2.0 / 3.0 + dx.delay

    fun maxOut(n: Int) = (up.maxOut(n) + n48) / hop * out32 + out32

    /** Returns how many 32 kHz samples were written to xo / eo / go. */
    fun process(x64: DoubleArray, n: Int, xo: DoubleArray, eo: DoubleArray, go: DoubleArray): Int {
        if (b48.size < n48 + up.maxOut(n)) b48 = b48.copyOf(2 * (n48 + up.maxOut(n)))
        n48 += up.process(x64, 0, n, b48, n48)
        var off = 0; var k = 0
        val dly = dfn.delay
        while (n48 - off >= hop) {
            for (i in 0 until hop) frame[i] = b48[off + i].toFloat()
            lsnr = dfn.process(frame, enh, attenLimDb)
            System.arraycopy(dl, hop, dl, 0, dly)
            System.arraycopy(frame, 0, dl, dly, hop)
            for (i in 0 until hop) { xd[i] = dl[i].toDouble(); ed[i] = enh[i].toDouble() }
            val g1 = gate.onHop(enh)
            val m = dx.process(xd, 0, hop, xo, k); de.process(ed, 0, hop, eo, k)
            for (i in 0 until m) go[k + i] = gPrev + (g1 - gPrev) * (i + 1) / m
            gPrev = g1
            k += m; off += hop
        }
        if (off > 0) { System.arraycopy(b48, off, b48, 0, n48 - off); n48 -= off }
        return k
    }
}
