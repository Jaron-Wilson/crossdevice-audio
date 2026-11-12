package dev.jaronwilson.headphones

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The shared EQ model (assets/eq_presets.json = shared/eq_presets.json in the repo, the same file the
 * laptop panel and Windows use): six bands (the "sub" band is stored last), presets, and the user's current choice.
 *
 * The curve is drawn from the real filter math (RBJ biquads), so what you see is the response the
 * laptop's PipeWire filter and Equalizer APO produce. On the phone the audiofx effects can't run
 * biquads directly, so [EqService] samples this curve into their bands.
 */
object EqModel {
    data class Band(val key: String, val label: String, val type: String, val freq: Double, val q: Double)
    data class Preset(val key: String, val label: String, val gains: List<Double>)

    @Volatile private var loaded: JSONObject? = null

    private fun model(c: Context): JSONObject = loaded ?: JSONObject(
        c.assets.open("eq_presets.json").bufferedReader().use { it.readText() }).also { m ->
            loaded = m
            m.optJSONObject("subsonic")?.let { subsonicHz = it.optDouble("freq", 25.0); subsonicQ = it.optDouble("q", 0.707) }
        }

    /** High-pass under the "sub" band, used only while that band is boosted (as on the laptop). */
    @Volatile var subsonicHz = 25.0
    @Volatile var subsonicQ = 0.707

    fun bands(c: Context): List<Band> = model(c).getJSONArray("bands").let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).let {
            Band(it.getString("key"), it.getString("label"), it.getString("type"), it.getDouble("freq"), it.getDouble("q"))
        } }
    }

    fun presets(c: Context): List<Preset> = model(c).getJSONArray("presets").let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).let { p ->
            val g = p.getJSONArray("gains")
            Preset(p.getString("key"), p.getString("label"), (0 until g.length()).map { g.getDouble(it) })
        } }
    }

    fun gainMin(c: Context) = model(c).getDouble("gain_min")
    fun gainMax(c: Context) = model(c).getDouble("gain_max")

    // ---- the user's choice ----
    private fun p(c: Context) = c.getSharedPreferences("eq", Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun preset(c: Context): String = p(c).getString("preset", "flat") ?: "flat"
    fun gains(c: Context): List<Double> {
        val n = bands(c).size
        // a named preset always means the model's current values (Rumble was retuned in 1.3.2)
        presets(c).firstOrNull { it.key == preset(c) }?.let { pr -> return clamp(c, pr.gains) }
        val saved = runCatching { JSONArray(p(c).getString("gains", "[]")) }.getOrNull()
        return (0 until n).map { saved?.optDouble(it, 0.0) ?: 0.0 }
    }

    fun presetLabel(c: Context): String =
        if (!enabled(c)) "EQ off" else presets(c).firstOrNull { it.key == preset(c) }?.label ?: "Custom"

    private fun clamp(c: Context, g: List<Double>): List<Double> {
        val lo = gainMin(c); val hi = gainMax(c)
        return bands(c).indices.map { i -> (Math.round(g.getOrElse(i) { 0.0 }.coerceIn(lo, hi) * 2) / 2.0) }
    }

    fun setEnabled(c: Context, on: Boolean) { p(c).edit().putBoolean("enabled", on).apply() }

    fun setPreset(c: Context, key: String) {
        val pr = presets(c).firstOrNull { it.key == key } ?: return
        p(c).edit().putString("preset", key).putString("gains", JSONArray(clamp(c, pr.gains)).toString())
            .putBoolean("enabled", true).apply()
    }

    fun setGains(c: Context, g: List<Double>) {
        val cg = clamp(c, g)
        val match = presets(c).firstOrNull { clamp(c, it.gains) == cg }?.key ?: "custom"
        p(c).edit().putString("preset", match).putString("gains", JSONArray(cg).toString()).apply()
    }

    // ---- Rumble: the "rumble" preset, and back to whatever was set before ----
    fun rumbleOn(c: Context) = enabled(c) && preset(c) == "rumble"

    fun setRumble(c: Context, on: Boolean) {
        val pr = p(c)
        if (on) {
            if (!rumbleOn(c)) pr.edit().putString("rumble_prev", JSONObject()
                .put("enabled", enabled(c)).put("preset", preset(c)).put("gains", JSONArray(gains(c))).toString()).apply()
            setPreset(c, "rumble")
        } else {
            val prev = runCatching { JSONObject(pr.getString("rumble_prev", null) ?: "") }.getOrNull()
            pr.edit().remove("rumble_prev").apply()
            if (prev == null) { setPreset(c, "flat"); return }
            val key = prev.optString("preset", "flat")
            if (key != "custom" && key != "rumble" && presets(c).any { it.key == key }) setPreset(c, key)
            else prev.optJSONArray("gains")?.let { a -> setGains(c, (0 until a.length()).map { a.getDouble(it) }) }
            setEnabled(c, prev.optBoolean("enabled", true))
        }
    }

    /** Next preset (Flat -> Bass boost -> Vocal -> Treble -> Flat), turning the EQ on. */
    fun cycle(c: Context): String {
        val keys = presets(c).map { it.key }
        val i = keys.indexOf(preset(c))
        val next = keys[if (i < 0) 0 else (i + 1) % keys.size]
        setPreset(c, next)
        return next
    }

    private const val HEADROOM_SHARE = 0.5      // cut by this share of the curve's peak boost (as the laptop does)

    /** Largest boost of the whole EQ curve over the music range (40 Hz - 16 kHz), in dB, never below 0. */
    fun curvePeak(bands: List<Band>, gains: List<Double>): Double {
        var best = 0.0
        for (i in 0..60) best = maxOf(best, responseDb(bands, gains, 40.0 * (16000.0 / 40.0).pow(i / 60.0)))
        return best
    }

    /** Headroom so boosts don't clip: a cut of half the curve's peak, in 0.5 dB steps. The service's
     *  limiter catches whatever the half-cut leaves. Same model as the laptop's hp_eq.preamp. */
    fun preamp(bands: List<Band>, gains: List<Double>): Double =
        -Math.round(HEADROOM_SHARE * curvePeak(bands, gains) * 2) / 2.0

    /** With a limiter after the EQ (DynamicsProcessing on the phone) a big pre-cut only makes boosts
     *  sound quieter ("Rumble sounds muted"): cut a quarter of the peak, at most 2 dB, and let the
     *  limiter catch the rest. */
    fun preampLimited(bands: List<Band>, gains: List<Double>): Double =
        -minOf(2.0, Math.round(0.25 * curvePeak(bands, gains) * 2) / 2.0)

    fun headroomDb(c: Context): Double = preampLimited(bands(c), gains(c))

    /** DynamicsProcessing pre-EQ layout: [n] bands split at log-spaced cutoffs (upper edges) from 20 Hz
     *  to 20 kHz, each band's gain = the biquad curve at the band's log centre. */
    fun dpBands(bands: List<Band>, gains: List<Double>, n: Int): List<Pair<Double, Double>> =
        (0 until n).map { i -> freqAt((i + 1.0) / n) to responseDb(bands, gains, freqAt((i + 0.5) / n)) }

    /** True while the sub band is boosted (Rumble): the low-band compressor runs then. */
    fun subBoosted(bands: List<Band>, gains: List<Double>): Boolean =
        bands.indexOfFirst { it.key == "sub" }.let { it >= 0 && gains.getOrElse(it) { 0.0 } > 0 }

    // ---- filter math ----
    private const val FS = 48000.0

    private fun coef(type: String, f: Double, q: Double, g: Double): DoubleArray {
        val a = 10.0.pow(g / 40); val w = 2 * PI * f / FS; val c = cos(w); val al = sin(w) / (2 * q)
        val r = 2 * sqrt(a) * al
        return when (type) {
            "peaking" -> doubleArrayOf(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
            "highpass" -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
            "lowshelf" -> doubleArrayOf(a * ((a + 1) - (a - 1) * c + r), 2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - r), (a + 1) + (a - 1) * c + r, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - r)
            else -> doubleArrayOf(a * ((a + 1) + (a - 1) * c + r), -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - r), (a + 1) - (a - 1) * c + r, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - r)
        }
    }

    /** Response in dB at [freq] for these bands and gains (without the preamp), including the
     *  subsonic high-pass while the "sub" band is boosted. */
    fun responseDb(bands: List<Band>, gains: List<Double>, freq: Double): Double {
        val w = 2 * PI * freq / FS
        var db = 0.0
        val stages = bands.withIndex().filter { abs(gains.getOrElse(it.index) { 0.0 }) >= 1e-6 }
            .map { coef(it.value.type, it.value.freq, it.value.q, gains[it.index]) }.toMutableList()
        val sub = bands.indexOfFirst { it.key == "sub" }
        if (sub >= 0 && gains.getOrElse(sub) { 0.0 } > 0) stages += coef("highpass", subsonicHz, subsonicQ, 0.0)
        for (k in stages) {
            val nr = k[0] + k[1] * cos(w) + k[2] * cos(2 * w); val ni = -(k[1] * sin(w) + k[2] * sin(2 * w))
            val dr = k[3] + k[4] * cos(w) + k[5] * cos(2 * w); val di = -(k[4] * sin(w) + k[5] * sin(2 * w))
            db += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        return db
    }

    const val FMIN = 20.0
    const val FMAX = 20000.0
    fun freqAt(u: Double) = FMIN * (FMAX / FMIN).pow(u)
    fun uOf(f: Double) = ln(f / FMIN) / ln(FMAX / FMIN)

    /** A small picture of the curve, for the widget's live preview. */
    fun previewBitmap(c: Context, wPx: Int, hPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val bands = bands(c); val g = gains(c); val range = gainMax(c)
        val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33415A"); strokeWidth = hPx / 40f }
        cv.drawLine(0f, hPx / 2f, wPx.toFloat(), hPx / 2f, mid)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (enabled(c)) "#5FD0FF" else "#56708A")
            style = Paint.Style.STROKE; strokeWidth = hPx / 12f; strokeCap = Paint.Cap.ROUND
        }
        val path = Path()
        val pad = hPx / 8f
        for (i in 0..60) {
            val u = i / 60.0
            val db = responseDb(bands, g, freqAt(u)).coerceIn(-range, range)
            val x = (u * wPx).toFloat(); val y = (hPx / 2f - (db / range * (hPx / 2f - pad))).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        cv.drawPath(path, line)
        return bmp
    }
}
