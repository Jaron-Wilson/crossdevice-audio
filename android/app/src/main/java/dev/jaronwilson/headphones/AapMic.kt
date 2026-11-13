package dev.jaronwilson.headphones

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The AirPods' own microphone stream over AAP, with no call (HFP/SCO) mode. Pure logic only, so it can be
 * unit-tested; the Bluetooth and decoder side is [HeadMic].
 *
 * AirPods Pro 2 can send a mono AAC-ELD stream (64 kHz, 480-sample frames) from their microphones on the
 * same AAP channel (L2CAP PSM 0x1001) as the control commands, as message type 0x58, while A2DP music keeps
 * playing at full quality. Apple's own devices use it for "high quality two-way audio". The packets below
 * are the ones LibrePods sends (Linux: librepods PR #655, `linux-rust/src/bluetooth/aacp.rs` and
 * `aacp_audio.rs`; Android: the `android/rewrite` branch, `AACPManager.requestMicrophoneStream` and
 * `MicrophoneFrame.parsePacket`). Two independent implementations agree on the bytes. It is not yet
 * confirmed on every firmware, so the app treats it as experimental and says so when nothing arrives.
 */
object AapMic {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Start the microphone stream (0x58, length 9). */
    val START: ByteArray = hex("04000400580000000900000182000000049600")
    /** Stop it (0x58, length 2). */
    val STOP: ByteArray = hex("040004005800000002000301")
    /** AudioSpecificConfig for the decoder: AAC-ELD (object type 39), 64 kHz, mono, 480-sample frames. */
    val ASC: ByteArray = byteArrayOf(0xF8.toByte(), 0xE6.toByte(), 0x30, 0x00)
    const val RATE = 64_000
    private const val HEADER = 22

    /** A 0x58 *audio* SDU (subtype 0x0001), as opposed to the stream's control replies. */
    fun isAudio(p: ByteArray, n: Int = p.size): Boolean =
        n >= 8 && p[0] == 0x04.toByte() && p[2] == 0x04.toByte() && p[4] == 0x58.toByte() && p[5] == 0.toByte() &&
            p[6] == 0x01.toByte() && p[7] == 0.toByte()

    /** The AAC-ELD access units in one audio SDU: a 22-byte header, then [timestamp u32 LE][len u8][AU]... */
    fun demux(p: ByteArray, n: Int = p.size): List<ByteArray> {
        if (!isAudio(p, n) || n < HEADER) return emptyList()
        val out = mutableListOf<ByteArray>()
        var off = HEADER
        while (off + 5 <= n) {
            val len = p[off + 4].toInt() and 0xFF
            val start = off + 5
            val end = start + len
            if (end > n) break
            out += p.copyOfRange(start, end)
            off = end
        }
        return out
    }
}

/**
 * What the AirPods' microphones hear: a spectrum about 8 times a second and "what is that sound" for a
 * band, the same analysis as the laptop panel's sound viewer (laptop/hp_sound.py), fed from the AirPods
 * instead of the laptop mic. Levels are dB relative to full scale of the AirPods' stream, not calibrated
 * sound pressure.
 */
class SoundAnalyzer(val rate: Int = AapMic.RATE, val nfft: Int = 8192, nBins: Int = 120) {
    data class Look(val lo: Double, val hi: Double, val peakHz: Double, val levelDb: Double, val tonal: Boolean,
                    val steady: Boolean, val label: String, val advice: String, val ancHelps: Boolean)

    val fmin = 20.0
    val fmax = 11_000.0
    private val hop = rate / 8
    private val window = DoubleArray(nfft) { 0.5 - 0.5 * cos(2 * PI * it / (nfft - 1)) }
    private val buf = DoubleArray(nfft)                  // ring buffer of the last nfft samples
    private var pos = 0
    private var filled = 0
    private var sinceHop = 0
    private val hist = ArrayDeque<DoubleArray>()          // recent fine spectra (dB), ~3 s
    private val histMax = 24
    private val edges = DoubleArray(nBins + 1) { fmin * (fmax / fmin).pow(it.toDouble() / nBins) }
    private val binIdx = (0 until nBins).map { i ->
        val a = (edges[i] * nfft / rate).toInt(); a to max(a + 1, (edges[i + 1] * nfft / rate).toInt())
    }

    /** Display bins (dB, -120..0) of the latest frame, log-spaced from [fmin] to [fmax]. */
    @Volatile var bins: IntArray = IntArray(nBins) { -120 }; private set
    /** Overall level of the last hop, dBFS. */
    @Volatile var levelDb: Double = -120.0; private set
    @Volatile var frames = 0; private set

    fun binCenters(): List<Double> = (0 until edges.size - 1).map { sqrt(edges[it] * edges[it + 1]) }

    /** Feed mono samples scaled to -1..1. Returns true when a new frame was computed. */
    fun feed(x: DoubleArray, n: Int = x.size): Boolean {
        var newFrame = false
        var sq = 0.0
        for (i in 0 until n) {
            buf[pos] = x[i]; pos = (pos + 1) % nfft
            sq += x[i] * x[i]
            if (filled < nfft) filled++
            if (++sinceHop >= hop) {
                sinceHop = 0
                if (filled >= nfft / 2) { frame(); newFrame = true }
            }
        }
        if (n > 0) levelDb = 10 * log10(sq / n + 1e-12)
        return newFrame
    }

    fun feedPcm16(pcm: ShortArray, n: Int = pcm.size) = feed(DoubleArray(n) { pcm[it] / 32768.0 }, n)

    private fun frame() {
        val re = DoubleArray(nfft) { buf[(pos + it) % nfft] * window[it] }
        val im = DoubleArray(nfft)
        fft(re, im)
        val half = nfft / 2 + 1
        val db = DoubleArray(half) { k -> 20 * log10(sqrt(re[k] * re[k] + im[k] * im[k]) / (nfft / 4.0) + 1e-9) }
        synchronized(hist) { hist.addLast(db); while (hist.size > histMax) hist.removeFirst() }
        bins = IntArray(binIdx.size) { i ->
            val (a, b) = binIdx[i]
            var m = -120.0
            for (k in a until min(b, half)) m = max(m, db[k])
            m.coerceIn(-120.0, 0.0).toInt()
        }
        frames++
    }

    /** What is in lo..hi Hz over the last ~3 s (null until enough has been heard). */
    fun inspect(lo0: Double = fmin, hi0: Double = fmax): Look? {
        val h = synchronized(hist) { hist.toList() }
        if (h.size < 4) return null
        val lo = max(fmin, min(lo0, hi0)); val hi = min(fmax, max(lo0, hi0))
        val a = (lo * nfft / rate).toInt().coerceAtLeast(1)
        val b = max(a + 1, (hi * nfft / rate).toInt())
        val w = b - a
        val avg = DoubleArray(w) { j -> 10 * log10(h.sumOf { 10.0.pow(it[a + j] / 10) } / h.size + 1e-12) }
        val k = avg.indices.maxByOrNull { avg[it] } ?: return null
        var f0 = (a + k).toDouble() * rate / nfft
        if (k in 1 until w - 1) {
            val y0 = avg[k - 1]; val y1 = avg[k]; val y2 = avg[k + 1]
            f0 += 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2 + 1e-9) * rate / nfft
        }
        val level = 10 * log10(avg.sumOf { 10.0.pow(it / 10) } / w + 1e-12)
        val inside = k in 1 until w - 1
        val tonalDb = if (w > 4 && inside) avg[k] - avg.sorted()[w / 2] else 0.0
        val bandT = h.map { s -> 10 * log10((a until b).sumOf { 10.0.pow(s[it] / 10) } / w + 1e-12) }
        val mean = bandT.average()
        val spread = sqrt(bandT.sumOf { (it - mean) * (it - mean) } / bandT.size)
        val tonal = tonalDb > 12; val steady = spread < 3.0
        val (good, advice) = ancAdvice(f0, steady)
        return Look(lo, hi, f0, level, tonal, steady, classify(f0, tonal, steady), advice, good)
    }

    companion object {
        /** Same words as laptop/hp_sound.py classify(). */
        fun classify(f: Double, tonal: Boolean, steady: Boolean): String {
            for (base in listOf(50.0, 60.0)) for (n in 1..3)
                if (tonal && abs(f - base * n) < 3 * n)
                    return "mains hum (${base.toInt()} Hz power" + (if (n == 1) ")" else ", harmonic x$n)")
            return when {
                f < 40 -> "deep rumble (wind, traffic, building vibration)"
                f < 250 -> if (tonal) "motor or transformer hum" else "low rumble (engines, traffic, ventilation)"
                f < 1000 -> if (tonal) "motor / fan tone" else if (steady) "fan or air noise" else "voices (low part) or general noise"
                f < 4000 -> if (tonal) "whine, beep or alarm tone" else if (!steady) "voices / speech" else "fan or air hiss"
                else -> if (tonal) "high whine (electronics)" else "hiss (air, water, sibilance)"
            }
        }

        /** What the AirPods' own noise control can do about it (laptop/hp_sound.py anc_advice()). */
        fun ancAdvice(f: Double, steady: Boolean): Pair<Boolean, String> = when {
            f < 1000 && steady -> true to "Steady and low: the AirPods' Noise Cancellation handles this well. " +
                "Use Noise Cancellation, or Adaptive set to block more."
            f < 1000 -> true to "Low but changing: Noise Cancellation reduces it, though not completely."
            f < 4000 -> false to "ANC only partly reduces mid sounds like voices; the ear tips' seal does most " +
                "of the work. Noise Cancellation still helps a bit."
            else -> false to "High sounds are mostly blocked by the ear tips' seal, not ANC. A good tip fit " +
                "matters more than the mode."
        }

        /** In-place radix-2 FFT; the size must be a power of two. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]; val ui = im[i + k]
                        val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ur + vr; im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                        val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
