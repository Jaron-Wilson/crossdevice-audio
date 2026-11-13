package dev.jaronwilson.headphones

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ln
import kotlin.math.pow

/**
 * "What your AirPods hear": the laptop panel's sound viewer, on the phone, fed by the AirPods' own
 * microphones over AAP ([HeadMic]) instead of a laptop or phone mic, and with no call mode. Tap the
 * spectrum to ask about one band. The block buttons use the AirPods' own noise control (Adaptive at its
 * strongest, or Noise Cancellation), sent on the same link.
 *
 * Runs only while this screen is open (it stops in onPause), at most a few minutes. Nothing is recorded.
 */
class ListenActivity : Activity(), HeadMic.Listener {
    private var mic: HeadMic? = null
    private lateinit var status: TextView
    private lateinit var level: TextView
    private lateinit var look: TextView
    private lateinit var startBtn: Button
    private lateinit var spectrum: SpectrumView
    private var band: Pair<Double, Double>? = null
    private var lastLook = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(t: String, size: Float = 13f, color: String = "#8AA6B4") = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(6), 0, dp(4))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(22))
            setBackgroundColor(Color.parseColor("#0F1419"))
        }
        col.addView(text("What your AirPods hear", 22f, "#E8EDF2"))
        col.addView(text("Uses the AirPods' own microphones through their control channel, so there is no call mode " +
            "and music keeps its quality. No phone or laptop microphone is used, nothing is recorded or saved, and it " +
            "stops when you leave this screen. Experimental: if your AirPods' firmware doesn't send the stream, it says " +
            "so and stops. Conversation Awareness is paused while it listens and put back after.", 12f))
        startBtn = Button(this).apply { text = "Start listening"; isAllCaps = false; setOnClickListener { toggle() } }
        col.addView(startBtn)
        status = text("", 12f, "#99FFBB"); col.addView(status)
        level = text("", 14f, "#E8EDF2"); col.addView(level)
        spectrum = SpectrumView(this) { lo, hi -> band = if (lo == null || hi == null) null else lo to hi; showLook(force = true) }
        col.addView(spectrum, LinearLayout.LayoutParams(MATCH_PARENT, dp(200)))
        col.addView(text("Tap the spectrum to ask about one band; tap the same spot again for the whole range.", 11f))
        look = text("", 13f, "#E8EDF2"); col.addView(look)
        col.addView(text("Reduce it with the AirPods themselves", 13f, "#E8EDF2"))
        val row = LinearLayout(this)
        for ((t, act) in listOf<Pair<String, () -> Unit>>(
            "Adaptive, block max" to { block(adaptive = true) },
            "Noise Cancellation" to { block(adaptive = false) },
            "Transparency" to { sendMode("transparency") })) {
            row.addView(Button(this).apply { text = t; isAllCaps = false; textSize = 12f; setOnClickListener { act() } },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        col.addView(row)
        col.addView(text("ANC lowers outside sound as a whole, steady low sounds most. It can't remove one chosen " +
            "frequency: AirPods expose no way to steer their noise filter.", 11f))
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#0F1419")) })
        if (!Handoff.local(this)) status.text = "The AirPods aren't on this phone right now. Connect them to the phone first."
    }

    override fun onPause() {
        super.onPause()
        mic?.stop("Stopped (left the screen)"); mic = null
        startBtn.text = "Start listening"
    }

    private fun toggle() {
        mic?.let { it.stop(); mic = null; startBtn.text = "Start listening"; return }
        if (!Handoff.local(this)) { status.text = "Connect the AirPods to this phone first."; return }
        mic = HeadMic(applicationContext, this).also { it.start() }
        startBtn.text = "Stop"
    }

    private fun sendMode(mode: String) {
        val m = mic
        if (m != null) { if (!m.send(PhoneDirect.modePacket(mode))) status.text = "Couldn't send" ; return }
        Thread { runCatching { PhoneDirect.setMode(applicationContext, mode) }
            .onFailure { e -> runOnUiThread { status.text = "AirPods: ${e.message}" } } }.start()
    }

    private fun block(adaptive: Boolean) {
        val m = mic
        if (m == null) {
            Thread { runCatching { if (adaptive) PhoneDirect.setAdaptive(applicationContext, 100) else PhoneDirect.setMode(applicationContext, "anc") }
                .onFailure { e -> runOnUiThread { status.text = "AirPods: ${e.message}" } } }.start()
            return
        }
        m.send(PhoneDirect.modePacket(if (adaptive) "adaptive" else "anc"))
        if (adaptive) m.send(PhoneDirect.controlPacket(0x2E, 100))
        status.text = if (adaptive) "Sent: Adaptive, blocking the most" else "Sent: Noise Cancellation"
    }

    private fun showLook(force: Boolean = false) {
        val m = mic ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastLook < 1000) return
        lastLook = now
        val b = band
        val l = (if (b == null) m.analyzer.inspect() else m.analyzer.inspect(b.first, b.second))
        look.text = if (l == null) "Listening..." else
            (if (b == null) "Loudest: " else "${b.first.toInt()}-${b.second.toInt()} Hz: ") +
                "${"%.0f".format(l.peakHz)} Hz, ${l.label}" +
                (if (l.tonal) ", a tone" else "") + (if (l.steady) ", steady" else ", changing") +
                "\n" + l.advice
    }

    // ---- HeadMic.Listener (called on its threads) ----
    override fun onStatus(text: String) = runOnUiThread { status.text = text }

    override fun onFrame(analyzer: SoundAnalyzer) = runOnUiThread {
        spectrum.set(analyzer.bins, analyzer.binCenters())
        level.text = "Level at the AirPods' mics: ${"%.0f".format(analyzer.levelDb)} dBFS (relative, not calibrated)" +
            (mic?.ear?.let { "\nEars: " + PhoneDirect.earText(it) } ?: "")
        showLook()
    }

    override fun onEnded(reason: String) = runOnUiThread {
        status.text = reason; mic = null; startBtn.text = "Start listening"
    }
}

/** Spectrum bars (dB -100..0, log frequency). Tap picks a third of an octave; tapping it again clears. */
class SpectrumView(c: Context, private val onPick: (Double?, Double?) -> Unit) : View(c) {
    private var bins = IntArray(0)
    private var centers: List<Double> = emptyList()
    private var pick: Pair<Double, Double>? = null
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5FD0FF") }
    private val sel = Paint().apply { color = Color.parseColor("#3340566B") }
    private val grid = Paint().apply { color = Color.parseColor("#33415A"); textSize = 26f }

    init { setBackgroundColor(Color.parseColor("#141B22")) }

    fun set(b: IntArray, c: List<Double>) { bins = b; centers = c; invalidate() }

    private fun xOf(f: Double) = (ln(f / 20.0) / ln(11000.0 / 20.0) * width).toFloat()
    private fun fOf(x: Float) = 20.0 * (11000.0 / 20.0).pow((x / width).toDouble().coerceIn(0.0, 1.0))

    override fun onDraw(cv: Canvas) {
        pick?.let { (lo, hi) -> cv.drawRect(xOf(lo), 0f, xOf(hi), height.toFloat(), sel) }
        for (f in listOf(50.0, 100.0, 1000.0, 10000.0)) {
            val x = xOf(f); cv.drawLine(x, 0f, x, height.toFloat(), grid)
            cv.drawText(if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", x + 4, height - 6f, grid)
        }
        if (bins.isEmpty()) return
        val w = width.toFloat() / bins.size
        for ((i, db) in bins.withIndex()) {
            val h = ((db + 100).coerceIn(0, 100) / 100f) * height
            cv.drawRect(i * w, height - h, (i + 1) * w - 1, height.toFloat(), bar)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val f = fOf(e.x)
        val cur = pick
        pick = if (cur != null && f in cur.first..cur.second) null else (f / 2.0.pow(1.0 / 6)) to (f * 2.0.pow(1.0 / 6))
        onPick(pick?.first, pick?.second)
        invalidate(); performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
