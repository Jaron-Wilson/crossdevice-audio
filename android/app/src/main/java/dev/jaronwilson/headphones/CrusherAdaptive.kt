package dev.jaronwilson.headphones

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Crusher Adaptive: an "Adaptive" noise mode for the Skullcandy Crusher ANC 2, done by the phone.
 *
 * Port of the platform-free parts of shared/crusher_adaptive.py (same constants, same decisions). The
 * Crusher has no adaptive ANC, so the phone listens to the room with a microphone and moves the Crusher
 * between ANC / Transparency (with a level) / Off over its AT channel. Only numbers are kept: a loudness
 * in dB and a few speech features per window. Each block of samples is dropped once it has been measured.
 *
 * No Android imports here, so the JVM unit tests cover all of it. [CrusherAdaptiveService] does the audio
 * capture, the Bluetooth sending and the notification.
 */
object CA {
    const val RATE = 16000          // Hz
    const val WINDOW_S = 1.0        // loudness window
    const val HOP_S = 0.5           // a reading every half second
    const val FRAME = 320           // 20 ms frames for the speech envelope
    const val ENV_FRAMES = 100      // 2 s of envelope for the syllable-rate check
    const val FLOOR_DB = -120.0
    const val PITCH_N = 2 * FRAME   // 40 ms for the pitch check

    const val QUIET_MAX = -50.0     // dBFS(A) at sensitivity 50: above this the room is no longer quiet
    const val LOUD_MIN = -36.0      // and above this it is loud
    const val HYST = 4.0            // dB between the up and the down threshold of each boundary
    const val CONV_ENTER = 2        // consecutive speech readings (~1 s) to call it a conversation

    /** Seconds without media playing on the phone before the headset mic may be used. */
    const val HEADSET_IDLE_S = 10.0

    /** IEC 61672 A-weighting in dB at frequency f (Hz); 0 dB at 1 kHz. */
    fun aWeightDb(f: Double): Double {
        val ff = max(f, 1e-3)
        val f2 = ff * ff
        val ra = (12194.0.pow(2) * f2 * f2) / ((f2 + 20.6.pow(2)) * sqrt((f2 + 107.7.pow(2)) * (f2 + 737.9.pow(2)))
            * (f2 + 12194.0.pow(2)))
        return 20.0 * log10(ra) + 2.0
    }
}

// ---------------------------------------------------------------------------------------------------
// FFT (any length: radix-2, or Bluestein's chirp-z on top of it)

/** Forward complex DFT of length [n], in place. Plans are reusable but not thread-safe. */
class Fft(val n: Int) {
    private val pow2 = n > 0 && (n and (n - 1)) == 0
    private val m: Int = if (pow2) n else Integer.highestOneBit(2 * n - 1).let { if (it < 2 * n - 1) it shl 1 else it }
    private val cosT = DoubleArray(m / 2) { cos(2 * PI * it / m) }
    private val sinT = DoubleArray(m / 2) { -sin(2 * PI * it / m) }
    private val rev = IntArray(m).also { r ->
        val bits = Integer.numberOfTrailingZeros(m)
        for (i in 0 until m) r[i] = if (bits == 0) 0 else Integer.reverse(i) ushr (32 - bits)
    }
    private lateinit var wRe: DoubleArray
    private lateinit var wIm: DoubleArray
    private lateinit var bRe: DoubleArray
    private lateinit var bIm: DoubleArray
    private lateinit var aRe: DoubleArray
    private lateinit var aIm: DoubleArray

    init {
        if (!pow2) {
            wRe = DoubleArray(n); wIm = DoubleArray(n)
            for (k in 0 until n) {
                val ang = PI * ((k.toLong() * k) % (2L * n)) / n
                wRe[k] = cos(ang); wIm[k] = -sin(ang)
            }
            bRe = DoubleArray(m); bIm = DoubleArray(m)
            bRe[0] = wRe[0]; bIm[0] = -wIm[0]
            for (k in 1 until n) {
                bRe[k] = wRe[k]; bIm[k] = -wIm[k]
                bRe[m - k] = wRe[k]; bIm[m - k] = -wIm[k]
            }
            radix2(bRe, bIm)
            aRe = DoubleArray(m); aIm = DoubleArray(m)
        }
    }

    private fun radix2(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until m) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= m) {
            val half = len / 2
            val step = m / len
            var i = 0
            while (i < m) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** X[k] = sum x[j] e^(-2 pi i jk / n), in place; re and im have at least n entries. */
    fun forward(re: DoubleArray, im: DoubleArray) {
        if (pow2) { radix2(re, im); return }
        java.util.Arrays.fill(aRe, 0.0); java.util.Arrays.fill(aIm, 0.0)
        for (k in 0 until n) {
            aRe[k] = re[k] * wRe[k] - im[k] * wIm[k]
            aIm[k] = re[k] * wIm[k] + im[k] * wRe[k]
        }
        radix2(aRe, aIm)
        for (k in 0 until m) {                      // multiply by the chirp's spectrum, then inverse via conjugation
            val r = aRe[k] * bRe[k] - aIm[k] * bIm[k]
            val i = aRe[k] * bIm[k] + aIm[k] * bRe[k]
            aRe[k] = r; aIm[k] = -i
        }
        radix2(aRe, aIm)
        for (k in 0 until n) {
            val cr = aRe[k] / m; val ci = -aIm[k] / m
            re[k] = cr * wRe[k] - ci * wIm[k]
            im[k] = cr * wIm[k] + ci * wRe[k]
        }
    }
}

private fun hanning(n: Int) = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

/** The last [cap] values, oldest first. */
private class Ring(private val cap: Int) {
    private val a = DoubleArray(cap)
    private var head = 0
    var size = 0; private set
    fun add(v: Double) {
        if (size < cap) { a[(head + size) % cap] = v; size++ } else { a[head] = v; head = (head + 1) % cap }
    }
    fun get(i: Int) = a[(head + i) % cap]
    fun clear() { head = 0; size = 0 }
    fun toArray() = DoubleArray(size) { get(it) }
}

// ---------------------------------------------------------------------------------------------------
// Measuring

/** One measurement. [level] is the smoothed A-weighted dBFS (RMS; a full-scale sine reads -3 dB). */
class Reading(val t: Double, val db: Double, val level: Double, val steady: Boolean = false, val speech: Boolean = false,
              val bandRatio: Double = 0.0, val modRatio: Double = 0.0, val depth: Double = 0.0, var held: Boolean = false) {
    override fun toString() = "Reading(t=%.1f, db=%.1f, level=%.1f, steady=%b, speech=%b, held=%b)".format(t, db, level, steady, speech, held)
}

/** What the policy loop needs from a meter (the tests swap in a scripted one). */
interface Meter {
    fun feed(x: FloatArray): List<Reading>
    fun reset()
}

/**
 * Turns mono float samples (-1..1) into a [Reading] every HOP_S seconds.
 *
 * Loudness: A-weighted RMS over a 1 s window (the A-weighted share of the window's spectrum times its plain
 * RMS power), then an exponential smoothing so one door slam doesn't swing the level.
 *
 * Speech nearby: in 20 ms frames, the energy in 300-3400 Hz (the voice band). Speech is when, over the last
 * 2 s, that band holds most of the energy, its level swings by several dB, the swings come at syllable rate
 * (2-8 Hz) and the louder frames have a voice-like pitch. No voice is recognised, only that pattern.
 */
class AmbientMeter(val rate: Int = CA.RATE, val alpha: Double = 0.35) : Meter {
    companion object {
        const val SPEECH_RATIO = 0.30    // share of 80-8000 Hz energy that is in 300-3400 Hz
        const val SPEECH_DEPTH = 3.5     // dB, standard deviation of the voice-band envelope
        const val SPEECH_MOD = 0.32      // share of envelope modulation at 2-8 Hz (of 0.5-25 Hz)
        const val VOICED_R = 0.5         // autocorrelation at the pitch lag for a frame to count as voiced
        const val SPEECH_VOICED = 0.2    // share of the louder frames (top 20 dB) that must be voiced
        const val ENV_RANGE = 30.0       // dB below the loudest frame that the envelope is clipped at
        const val SPEECH_MIN_DB = -70.0  // the loud frames of the voice band must be at least this (dBFS)
        const val STEADY_STD = 2.0       // dB over the last STEADY_N readings: at or under this it becomes steady,
        const val UNSTEADY_STD = 3.0     // and only over this does it stop being steady (hysteresis)
        const val STEADY_N = 12          // 6 s at a 0.5 s hop
    }

    private val win = (rate * CA.WINDOW_S).toInt()
    private val hop = (rate * CA.HOP_S).toInt()
    private val winFft = Fft(win)
    private val aw = DoubleArray(win / 2 + 1) { k ->
        val f = k.toDouble() * rate / win
        if (f > 0) 10.0.pow(CA.aWeightDb(f) / 10) else 0.0
    }
    private val hann = hanning(win)
    private val frameFft = Fft(CA.FRAME)
    private val fhann = hanning(CA.FRAME)
    private val bandLo: Int
    private val bandHi: Int
    private val totLo: Int
    private val totHi: Int
    private val pitchFft = Fft(2 * CA.PITCH_N)
    private val pband = DoubleArray(2 * CA.PITCH_N) { k ->
        val f = min(k, 2 * CA.PITCH_N - k).toDouble() * rate / (2 * CA.PITCH_N)
        if (f in 300.0..3400.0) 1.0 else 0.0
    }
    private val unbias = DoubleArray(CA.PITCH_N) { CA.PITCH_N.toDouble() / max(1, CA.PITCH_N - it) }
    private val lagLo = rate / 500
    private val lagHi = rate / 80
    private val envFft = Fft(CA.ENV_FRAMES)
    private val mhann = hanning(CA.ENV_FRAMES)
    private val sylLo: Int
    private val sylHi: Int
    private val modHi: Int

    init {
        val nb = CA.FRAME / 2 + 1
        fun bins(lo: Double, hi: Double, n: Int, df: Double): IntRange {
            val ks = (0 until n).filter { it * df >= lo && it * df <= hi }
            return ks.first()..ks.last()
        }
        val ff = rate.toDouble() / CA.FRAME
        bins(300.0, 3400.0, nb, ff).let { bandLo = it.first; bandHi = it.last }
        bins(80.0, 8000.0, nb, ff).let { totLo = it.first; totHi = it.last }
        val mf = CA.FRAME.toDouble() / rate        // seconds per envelope frame
        val df = 1.0 / (CA.ENV_FRAMES * mf)
        bins(2.0, 8.0, CA.ENV_FRAMES / 2 + 1, df).let { sylLo = it.first; sylHi = it.last }
        bins(0.5, 25.0, CA.ENV_FRAMES / 2 + 1, df).let { modHi = it.last }
    }

    // scratch
    private val wRe = DoubleArray(win)
    private val wIm = DoubleArray(win)
    private val fRe = DoubleArray(CA.FRAME)
    private val fIm = DoubleArray(CA.FRAME)
    private val pRe = DoubleArray(2 * CA.PITCH_N)
    private val pIm = DoubleArray(2 * CA.PITCH_N)
    private val eRe = DoubleArray(CA.ENV_FRAMES)
    private val eIm = DoubleArray(CA.ENV_FRAMES)

    private var buf = FloatArray(win * 2)
    private var bufLen = 0
    private val pend = FloatArray(CA.FRAME)
    private var pendLen = 0
    private var prev = DoubleArray(CA.FRAME)
    private val voiced = Ring(CA.ENV_FRAMES)       // 1.0 where a voice-like pitch was found in the frame
    private val env = Ring(CA.ENV_FRAMES)          // voice-band dB per 20 ms frame
    private val eb = Ring(CA.ENV_FRAMES)           // voice-band energy per frame
    private val et = Ring(CA.ENV_FRAMES)           // 80-8000 Hz energy per frame
    private val hist = Ring(STEADY_N)
    private var level: Double? = null
    var steady = false; private set
    private var samples = 0L

    override fun reset() {
        bufLen = 0; pendLen = 0
        prev = DoubleArray(CA.FRAME)
        voiced.clear(); env.clear(); eb.clear(); et.clear(); hist.clear()
        level = null; steady = false; samples = 0
    }

    /** A-weighted RMS of one window, dBFS. */
    fun windowDb(w: FloatArray, off: Int = 0): Double {
        var mean = 0.0
        for (i in 0 until win) mean += w[off + i]
        mean /= win
        var p = 0.0
        for (i in 0 until win) { val v = w[off + i] - mean; wRe[i] = v; p += v * v }
        p /= win
        if (p <= 0) return CA.FLOOR_DB
        for (i in 0 until win) { wRe[i] *= hann[i]; wIm[i] = 0.0 }
        winFft.forward(wRe, wIm)
        var tot = 0.0
        var weighted = 0.0
        for (k in 0..win / 2) {
            val s = wRe[k] * wRe[k] + wIm[k] * wIm[k]
            tot += s; weighted += s * aw[k]
        }
        if (tot <= 0) return CA.FLOOR_DB
        val pa = p * weighted / tot
        return max(CA.FLOOR_DB, 10 * log10(max(pa, 1e-12)))
    }

    private fun frame() {
        var mean = 0.0
        for (i in 0 until CA.FRAME) mean += pend[i]
        mean /= CA.FRAME
        val f = DoubleArray(CA.FRAME) { pend[it] - mean }
        for (i in 0 until CA.FRAME) { fRe[i] = f[i] * fhann[i]; fIm[i] = 0.0 }
        frameFft.forward(fRe, fIm)
        var b = 0.0
        var t = 0.0
        for (k in totLo..totHi) {
            val s = fRe[k] * fRe[k] + fIm[k] * fIm[k]
            t += s
            if (k in bandLo..bandHi) b += s
        }
        eb.add(b); et.add(t)
        env.add(max(CA.FLOOR_DB, 10 * log10(b / (CA.FRAME.toDouble() * CA.FRAME / 4) + 1e-12)))
        voiced.add(if (pitchStrength(prev, f) >= VOICED_R) 1.0 else 0.0)
        prev = f
    }

    /** Peak normalised autocorrelation of the voice band at a pitch lag (80-500 Hz), counting only true local
     *  peaks: voiced speech has one at its pitch period; noise, clicks and rumble decay with no peak. */
    private fun pitchStrength(a: DoubleArray, b: DoubleArray): Double {
        val n2 = 2 * CA.PITCH_N
        for (i in 0 until CA.FRAME) { pRe[i] = a[i]; pRe[CA.FRAME + i] = b[i] }
        for (i in CA.PITCH_N until n2) pRe[i] = 0.0
        java.util.Arrays.fill(pIm, 0.0)
        pitchFft.forward(pRe, pIm)
        for (k in 0 until n2) { pRe[k] = (pRe[k] * pRe[k] + pIm[k] * pIm[k]) * pband[k]; pIm[k] = 0.0 }
        pitchFft.forward(pRe, pIm)                    // real and symmetric: forward = inverse up to 1/n
        val ac0 = pRe[0] / n2
        if (ac0 <= 1e-12) return 0.0
        var best = 0.0
        var found = false
        fun r(l: Int) = pRe[l] / n2 / ac0 * unbias[l]
        for (l in lagLo + 1 until lagHi) {
            val c = r(l)
            if (c > r(l - 1) && c >= r(l + 1)) { if (!found || c > best) best = c; found = true }
        }
        return if (found) best else 0.0
    }

    /** (speech, bandRatio, modRatio, depth) over the last 2 s of frames. */
    private fun speechFeatures(): DoubleArray {
        if (env.size < CA.ENV_FRAMES) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var tot = 0.0
        var band = 0.0
        for (i in 0 until et.size) { tot += et.get(i); band += eb.get(i) }
        val ratio = if (tot > 0) band / tot else 0.0
        val raw = env.toArray()
        val mx = raw.max()
        val e = DoubleArray(raw.size) { max(raw[it], mx - ENV_RANGE) }   // pauses count as quiet, not -120 dB
        val mean = e.average()
        val depth = sqrt(e.sumOf { (it - mean) * (it - mean) } / e.size)
        for (i in e.indices) { eRe[i] = (e[i] - mean) * mhann[i]; eIm[i] = 0.0 }
        envFft.forward(eRe, eIm)
        var allm = 0.0
        var syl = 0.0
        for (k in 1..modHi) {
            val s = eRe[k] * eRe[k] + eIm[k] * eIm[k]
            allm += s
            if (k in sylLo..sylHi) syl += s
        }
        val mod = if (allm > 0) syl / allm else 0.0
        val loud = percentile(e, 90.0)
        var nTop = 0
        var nVoiced = 0
        for (i in e.indices) if (e[i] >= mx - 20.0) { nTop++; if (voiced.get(i) > 0.5) nVoiced++ }
        val v = if (nTop > 0) nVoiced.toDouble() / nTop else 0.0
        val speech = ratio >= SPEECH_RATIO && depth >= SPEECH_DEPTH && mod >= SPEECH_MOD &&
            loud >= SPEECH_MIN_DB && v >= SPEECH_VOICED
        return doubleArrayOf(if (speech) 1.0 else 0.0, ratio, mod, depth)
    }

    private fun percentile(x: DoubleArray, q: Double): Double {
        val s = x.sortedArray()
        val pos = q / 100 * (s.size - 1)
        val lo = floor(pos).toInt()
        val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /** Add samples; returns the Readings completed by them (usually zero or one). */
    override fun feed(x: FloatArray): List<Reading> {
        val out = ArrayList<Reading>(1)
        for (v in x) {
            pend[pendLen++] = v
            if (pendLen == CA.FRAME) { frame(); pendLen = 0 }
        }
        if (bufLen + x.size > buf.size) buf = buf.copyOf(max(buf.size * 2, bufLen + x.size))
        System.arraycopy(x, 0, buf, bufLen, x.size)
        bufLen += x.size
        while (bufLen >= win) {
            val db = windowDb(buf, 0)
            System.arraycopy(buf, hop, buf, 0, bufLen - hop)
            bufLen -= hop
            samples += hop
            val lv = level?.let { it + alpha * (db - it) } ?: db
            level = lv
            hist.add(db)
            if (hist.size >= STEADY_N) {
                val h = hist.toArray()
                val m = h.average()
                val sd = sqrt(h.sumOf { (it - m) * (it - m) } / h.size)
                steady = sd <= (if (steady) UNSTEADY_STD else STEADY_STD)
            }
            val f = speechFeatures()
            out += Reading(samples.toDouble() / rate, db, lv, steady, f[0] > 0.5, f[1], f[2], f[3])
        }
        return out
    }
}

// ---------------------------------------------------------------------------------------------------
// Deciding

/** What the Crusher should be doing. mode: anc | transparency | off; level: transparency 0-100 or null;
 *  conv: chosen for a conversation. */
data class Target(val mode: String, val level: Int? = null, val conv: Boolean = false) {
    fun label(): String {
        var name = when (mode) { "anc" -> "ANC"; "transparency" -> "Transparency"; else -> "Off" }
        if (mode == "transparency" && level != null) name += " $level"
        return name + if (conv) " (conversation)" else ""
    }
}

/** User settings, with defaults and clamping. Times are seconds unless named _min. */
class Settings(init: Map<String, Any?> = emptyMap()) {
    companion object {
        val DEFAULTS: Map<String, Any> = linkedMapOf(
            "enabled" to false,
            "sensitivity" to 50,         // 0-100: higher reacts to quieter noise
            "conversation" to true,      // conversation awareness
            "pause_min" to 10,           // after a manual change: pause this long; 0 = until turned back on
            "quiet_level" to 50,         // transparency level in a quiet room
            "moderate_level" to 20,      // transparency level in moderate, varying noise
            "conv_level" to 100,         // transparency level while someone talks
            "hold_s" to 4.0,             // a new situation must last this long before it is acted on
            "dwell_s" to 12.0,           // and at least this long since the last switch
            "conv_return_s" to 5.0,      // back to the room's mode this long after the speech stops
            "min_gap_s" to 3.0,          // never more than one command batch per this
        )
        val LIMITS: Map<String, Pair<Double, Double>> = mapOf(
            "sensitivity" to (0.0 to 100.0), "pause_min" to (0.0 to 24.0 * 60), "quiet_level" to (0.0 to 100.0),
            "moderate_level" to (0.0 to 100.0), "conv_level" to (0.0 to 100.0), "hold_s" to (0.5 to 120.0),
            "dwell_s" to (0.0 to 600.0), "conv_return_s" to (1.0 to 120.0), "min_gap_s" to (1.0 to 60.0))
    }

    private val d = LinkedHashMap(DEFAULTS)

    init { update(init) }

    fun update(m: Map<String, Any?>): Settings {
        for ((k, v) in m) {
            val dv = DEFAULTS[k] ?: continue
            if (v == null) continue
            when (dv) {
                is Boolean -> d[k] = v == true || (v is Number && v.toDouble() == 1.0) ||
                    (v is String && v in listOf("1", "on", "true", "yes"))
                is Int, is Double -> {
                    val x = when (v) {
                        is Number -> v.toDouble()
                        is String -> v.toDoubleOrNull() ?: continue
                        is Boolean -> if (v) 1.0 else 0.0
                        else -> continue
                    }
                    val (lo, hi) = LIMITS[k] ?: (-Double.MAX_VALUE to Double.MAX_VALUE)
                    if (dv is Int) d[k] = (x.toInt().toDouble().coerceIn(lo, hi)).toInt()
                    else d[k] = x.coerceIn(lo, hi)
                }
                else -> d[k] = v.toString()
            }
        }
        return this
    }

    fun asMap(): Map<String, Any> = LinkedHashMap(d)

    val enabled get() = d["enabled"] as Boolean
    val sensitivity get() = d["sensitivity"] as Int
    val conversation get() = d["conversation"] as Boolean
    val pauseMin get() = d["pause_min"] as Int
    val quietLevel get() = d["quiet_level"] as Int
    val moderateLevel get() = d["moderate_level"] as Int
    val convLevel get() = d["conv_level"] as Int
    val holdS get() = d["hold_s"] as Double
    val dwellS get() = d["dwell_s"] as Double
    val convReturnS get() = d["conv_return_s"] as Double
    val minGapS get() = d["min_gap_s"] as Double
}

/**
 * The decision, with no I/O and an explicit clock so it can be tested with synthetic sequences.
 *
 * tick(reading, now) returns the [Target] to send now, or null. The caller sends it (when its rate limiter
 * allows) and then calls [committed]; until then tick keeps proposing it.
 *
 * - Zones by the smoothed level with hysteresis (HYST dB), so a level sitting on a threshold never flaps.
 * - A new zone target must persist hold_s, and dwell_s must have passed since the last switch.
 * - Conversation: CONV_ENTER speech readings in a row switch to transparency high at once; conv_return_s
 *   without speech switches back at once (the dwell does not delay the return).
 * - Readings taken while someone speaks don't move the zone, and readings marked held are ignored entirely.
 * - manual(now) pauses everything for pause_min minutes (0 = until [resume]).
 */
class Policy(val s: Settings = Settings(), var hasLevel: Boolean = true) {
    var zone: String? = null; private set
    var steady = false; private set
    var applied: Target? = null; private set
    private var pending: Pair<Target, Double>? = null
    var lastSwitch = Double.NEGATIVE_INFINITY; private set
    var conv = false; private set
    private var speechRun = 0
    private var lastSpeech = Double.NEGATIVE_INFINITY
    private var pausedUntil: Double? = null

    init { reset() }

    fun reset() {
        zone = null; steady = false; applied = null; pending = null
        lastSwitch = Double.NEGATIVE_INFINITY; conv = false; speechRun = 0
        lastSpeech = Double.NEGATIVE_INFINITY; pausedUntil = null
    }

    /** (quiet_max, loud_min) after the sensitivity shift. */
    fun thresholds(): Pair<Double, Double> {
        val shift = (s.sensitivity - 50) * 0.2           // +-10 dB across the slider
        return CA.QUIET_MAX - shift to CA.LOUD_MIN - shift
    }

    fun classify(level: Double): String {
        val (q, l) = thresholds()
        val h = CA.HYST / 2
        return when (zone) {
            null -> if (level < q) "quiet" else if (level < l) "moderate" else "loud"
            "quiet" -> if (level > l + h) "loud" else if (level > q + h) "moderate" else "quiet"
            "moderate" -> if (level > l + h) "loud" else if (level < q - h) "quiet" else "moderate"
            else -> if (level < q - h) "quiet" else if (level < l - h) "moderate" else "loud"
        }
    }

    private fun zoneTarget(): Target = when {
        zone == "quiet" -> Target("transparency", if (hasLevel) s.quietLevel else null)
        zone == "loud" || steady -> Target("anc")
        else -> if (hasLevel) Target("transparency", s.moderateLevel) else Target("off")
    }

    private fun convTarget() = Target("transparency", if (hasLevel) s.convLevel else null, conv = true)

    /** The conversation can no longer be heard (media plays): drop it, so desired() is the room's zone target. */
    fun endConversation() { conv = false; speechRun = 0 }

    fun desired(): Target? = if (zone == null) null else if (conv) convTarget() else zoneTarget()

    // ---- manual override ----
    fun manual(now: Double) { pausedUntil = if (s.pauseMin <= 0) Double.POSITIVE_INFINITY else now + s.pauseMin * 60.0 }

    /** Clear a pause; the next decision starts fresh (it doesn't assume the Crusher is where we left it). */
    fun resume(): Boolean { val was = pausedUntil != null; reset(); return was }

    fun paused(now: Double): Boolean {
        val p = pausedUntil ?: return false
        if (now >= p) { resume(); return false }
        return true
    }

    fun pausedFor(now: Double): Double = if (!paused(now)) 0.0 else pausedUntil!! - now

    // ---- the decision ----
    fun tick(r: Reading, now: Double): Target? {
        if (paused(now)) return null
        if (!r.held) {
            if (s.conversation) {
                if (r.speech) {
                    speechRun++
                    if (conv || speechRun >= CA.CONV_ENTER) lastSpeech = now
                } else speechRun = 0
                if (!conv && speechRun >= CA.CONV_ENTER) conv = true
            } else { conv = false; speechRun = 0 }
            if (!(r.speech && zone != null)) {
                zone = classify(r.level)
                steady = r.steady && zone != "quiet"
            }
        }
        if (conv && now - lastSpeech >= s.convReturnS) conv = false
        val want = desired() ?: return null
        if (want == applied) { pending = null; return null }
        if (want.conv) return want                       // someone is talking: now
        if (applied?.conv == true) return want           // the talking stopped: back now
        if (pending == null || pending!!.first != want) pending = want to now
        if (now - pending!!.second >= s.holdS && now - lastSwitch >= s.dwellS) return want
        return null
    }

    fun committed(target: Target, now: Double) { applied = target; lastSwitch = now; pending = null }
}

// ---------------------------------------------------------------------------------------------------
// Sending

/** At most one command batch per minGap seconds (a failed send can push the next try further out). */
class RateLimiter(var minGap: Double = 3.0) {
    private var nextOk = Double.NEGATIVE_INFINITY
    fun ready(now: Double) = now >= nextOk
    fun mark(now: Double, extra: Double = 0.0) { nextOk = now + minGap + extra }
}

/** AT commands to go from [prev] (null = unknown) to [t]. Same commands as the mode buttons; the transparency
 *  level only when the firmware has it and it changed. */
fun commandsFor(prev: Target?, t: Target, hasLevel: Boolean = true): List<String> {
    val out = ArrayList<String>()
    val same = prev != null && prev.mode == t.mode
    when (t.mode) {
        "anc" -> if (!same) { out += "AT.UIAUDIO=transparency,off"; out += "AT.UIAUDIO=anc,on" }
        "off" -> if (!same) { out += "AT.UIAUDIO=transparency,off"; out += "AT.UIAUDIO=anc,off" }
        else -> {
            if (!same) out += "AT.UIAUDIO=transparency,on"
            if (hasLevel && t.level != null && (!same || prev!!.level != t.level))
                out += "AT.UIAUDIO=transparency,,${t.level.coerceIn(0, 100)}"
        }
    }
    return out
}

/** "anc" | "transparency" | "off" from a Crusher state (anc on, transparency on). */
fun modeOf(anc: Boolean?, transparency: Boolean?) = if (anc == true) "anc" else if (transparency == true) "transparency" else "off"

// ---------------------------------------------------------------------------------------------------
// The loop's logic (no audio, no Bluetooth: those are passed in)

/** A snapshot for the UI and the notification. */
data class AdaptiveState(
    val ambientDb: Double?, val zone: String?, val steady: Boolean, val speech: Boolean, val conversation: Boolean,
    val target: Target?, val paused: Boolean, val pausedForS: Long?, val error: String,
) {
    /** "Room -48 dB, quiet, steady, voices -> Transparency 50" */
    fun line(): String {
        if (paused) return "Paused after a manual change" + (pausedForS?.let { " (${(it + 59) / 60} min left)" } ?: "")
        val parts = ArrayList<String>()
        ambientDb?.let { parts += "Room %.0f dB".format(it) }
        zone?.let { parts += it }
        if (steady) parts += "steady"
        if (speech) parts += "voices"
        val head = parts.joinToString(", ")
        val tail = target?.label()?.let { "chose $it" } ?: "no choice yet"
        return if (head.isEmpty()) tail else "$head: $tail"
    }
}

/**
 * Meter, policy, rate limit and read-back, with the platform's glue passed in:
 *   send(cmds)  sends AT commands; throws on failure
 *   hasLevel()  does this firmware answer the transparency level
 * The caller feeds audio blocks to [process] and mode read-backs to [observe].
 */
class AdaptiveCore(
    val settings: Settings,
    private val send: (List<String>) -> Unit,
    private val hasLevel: () -> Boolean = { true },
    private val clock: () -> Double,
    private val log: (String) -> Unit = {},
    val meter: Meter = AmbientMeter(),
) {
    companion object {
        const val MANUAL_GRACE_S = 8.0     // a mode read this soon after our own send isn't taken as a manual change
    }

    val policy = Policy(settings)
    val limiter = RateLimiter(settings.minGapS)
    @Volatile var last: Reading? = null; private set
    @Volatile var error = ""
    private var lastSend = Double.NEGATIVE_INFINITY

    /** Settings changed (from the UI). Picking Adaptive again clears a pause. */
    fun set(m: Map<String, Any?>) {
        val was = settings.enabled
        settings.update(m)
        limiter.minGap = settings.minGapS
        if ("enabled" in m) {
            if (settings.enabled) policy.resume()
            else if (was) { stopListening(); policy.reset() }
        }
    }

    /** A mode was changed by hand: step aside for a while. */
    fun manual(source: String = "manual"): Boolean {
        if (!settings.enabled) return false
        policy.manual(clock())
        stopListening()
        log("crusher adaptive: paused ($source)")
        return true
    }

    /** The mic is closed or the Crusher went away: forget the room. */
    fun stopListening() {
        meter.reset()
        last = null
    }

    /** Different mic, different levels: restart the meter but keep what was applied. */
    fun sourceChanged() { meter.reset(); last = null }

    /**
     * Media plays, so nothing is heard: hold the last decided mode. Forget the meter; a conversation can no
     * longer be heard, so it ends and the room's zone target is sent. Without a conversation nothing is sent.
     */
    fun holdForMedia(now: Double) {
        meter.reset()
        last = null
        if (policy.conv || policy.applied?.conv == true) {     // (the second: a send that was rate limited or failed is retried)
            policy.endConversation()
            policy.desired()?.let { apply(it, now) }
        }
    }

    /** Send one target (rate limited). Returns true if it was sent and committed. */
    fun apply(target: Target, now: Double): Boolean {
        if (!limiter.ready(now)) return false
        val hl = hasLevel()
        policy.hasLevel = hl
        val cmds = commandsFor(policy.applied, target, hl)
        try {
            if (cmds.isNotEmpty()) send(cmds)
        } catch (e: Exception) {
            error = "Crusher: ${e.message}"
            limiter.mark(now, extra = 7.0)
            return false
        }
        limiter.mark(now)
        error = ""
        lastSend = now
        policy.committed(target, now)
        log(last?.let { "crusher adaptive: ${target.label()} (room %.0f dB, ${policy.zone})".format(it.level) }
            ?: "crusher adaptive: ${target.label()}")
        return true
    }

    /** Measure one block of samples and act on the readings it completes. */
    fun process(block: FloatArray, now: Double = clock()) {
        for (r in meter.feed(block)) {
            last = r
            policy.tick(r, now)?.let { apply(it, now) }
        }
    }

    /** The Crusher's mode as read back; a change we didn't make pauses Adaptive. */
    fun observe(mode: String?, now: Double): Boolean {
        val a = policy.applied
        if (mode == null || a == null || now - lastSend < MANUAL_GRACE_S) return false
        if (mode != a.mode) { manual("changed on the headset or elsewhere"); return true }
        return false
    }

    fun state(): AdaptiveState {
        val now = clock()
        val r = last
        val pf = if (settings.enabled) policy.pausedFor(now) else 0.0
        return AdaptiveState(r?.level, policy.zone, policy.steady, r?.speech == true, policy.conv, policy.applied,
            pf > 0, if (pf.isInfinite()) null else Math.round(pf), error)
    }
}
