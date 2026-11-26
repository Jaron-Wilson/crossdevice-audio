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
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.ln
import kotlin.math.pow

/**
 * "What your AirPods hear": the laptop panel's sound viewer, on the phone, fed by the AirPods' own
 * microphones over AAP ([HeadMic]) instead of a laptop or phone mic, and with no call mode. Tap the
 * spectrum to ask about one band. The block buttons use the AirPods' own noise control (Adaptive at its
 * strongest, or Noise Cancellation), sent on the same link.
 *
 * Listening runs only while this screen is open (it stops in onPause), at most a few minutes, unless
 * Filtered hearing is on: then the session lives in [FilteredHearing] with a foreground service and a Stop
 * button in its notification. Nothing is recorded.
 */
private var mixSlider = 50
private var amountSlider = 100

class ListenActivity : Activity(), HeadMic.Listener {
    private val mic: HeadMic? get() = FilteredHearing.mic
    private val proc get() = FilteredHearing.proc
    private lateinit var fhBtn: Button
    private lateinit var fhStatus: TextView
    private lateinit var suggestBtn: Button
    private lateinit var voiceBtn: Button
    private lateinit var allSpeechBtn: Button
    private var pendingOn = false
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
        addFilteredHearing(col)
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#0F1419")) })
        if (!Handoff.local(this)) status.text = "The AirPods aren't on this phone right now. Connect them to the phone first."
    }

    override fun onResume() {
        super.onResume()
        FilteredHearing.ui = this
        startBtn.text = if (mic != null) "Stop" else "Start listening"
        refreshFh()
    }

    override fun onPause() {
        super.onPause()
        FilteredHearing.ui = null
        pendingOn = false
        // Filtered hearing keeps going with the screen off (its notification has Stop); plain listening doesn't.
        if (!FilteredHearing.on) FilteredHearing.stopMic("Stopped (left the screen)")
    }

    private fun toggle() {
        if (mic != null) { FilteredHearing.stopMic(); startBtn.text = "Start listening"; return }
        if (!Handoff.local(this)) { status.text = "Connect the AirPods to this phone first."; return }
        FilteredHearing.startMic(this)
        startBtn.text = "Stop"
    }

    // ---------------------------------------------------------------- Filtered hearing card
    private lateinit var engineNeural: Button
    private lateinit var engineLight: Button
    private lateinit var voicesOnlyBtn: Button
    private lateinit var ownBtn: Button
    private lateinit var clicksBtn: Button
    private var clicksShown = false

    private fun addFilteredHearing(col: LinearLayout) {
        FilteredHearing.load(this)
        col.addView(text("Filtered hearing", 20f, "#E8EDF2").apply { setPadding(0, dp(22), 0, dp(2)) })
        col.addView(text("Hear the room through your AirPods with sounds taken out: a fan, a hum, keyboard clicks, a band " +
            "you tap, one person's voice, all speech, or everything but voices. The AirPods go to Noise Cancellation and " +
            "play back what their own mics hear, cleaned on the phone. Music keeps playing. It runs with the screen off " +
            "until you stop it (notification). What it learns (numbers, no audio) is saved on the phone until you tap Forget.", 11f))
        fhBtn = Button(this).apply { isAllCaps = false; textSize = 18f; setOnClickListener { toggleFh() } }
        col.addView(fhBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(64)))
        val mixLabel = text("", 13f, "#E8EDF2"); col.addView(mixLabel)
        fun showMix(v: Int) {
            val g = FilteredHearing.mixGain(v)
            mixLabel.text = "Room sound (Mix): " + if (v == 0) "silent" else "${v} %  (${"%+.0f".format(20 * kotlin.math.log10(g))} dB)"
        }
        col.addView(SeekBar(this).apply {
            max = 100; progress = mixSlider; showMix(mixSlider)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    mixSlider = v; showMix(v); val g = FilteredHearing.mixGain(v); proc.post { mix = g }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        fun row(vararg b: Button) = LinearLayout(this).apply {
            for (x in b) addView(x, LinearLayout.LayoutParams(0, dp(60), 1f))
            col.addView(this)
        }
        fun btn(t: String, act: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; textSize = 14f; setOnClickListener { act() } }
        engineNeural = btn("Neural") { FilteredHearing.setEngine(this, true); refreshFh() }
        engineLight = btn("Light") { FilteredHearing.setEngine(this, false); refreshFh() }
        col.addView(text("Engine: Neural (DeepFilterNet3 + a voice model; better, uses more battery, only while a " +
            "voice mode is on) or Light (the classical filters).", 11f))
        row(engineNeural, engineLight)

        col.addView(text("Sounds", 15f, "#E8EDF2").apply { setPadding(0, dp(12), 0, dp(2)) })
        suggestBtn = Button(this).apply { isAllCaps = false; visibility = View.GONE; setOnClickListener { learnSound() } }
        col.addView(suggestBtn)
        row(btn("Learn this sound (fan, hum, keyboard)") { learnSound() }, btn("Block tapped band") { blockBand() })
        clicksBtn = btn("Remove clicks") {
            clicksShown = !proc.clicksOn
            val on = clicksShown; proc.post { clicksOn = on }; refreshFh()
        }
        row(clicksBtn, btn("Clear filters") {
            proc.post { setNoise(null); clearBands(); voiceMode = VoiceMode.OFF; clicksOn = false }
            FilteredHearing.setMode(VoiceMode.OFF); FilteredHearing.setOwnVoice(false); clicksShown = false
            FilteredHearing.clearEvent(); refreshFh()
        })

        col.addView(text("Voices", 15f, "#E8EDF2").apply { setPadding(0, dp(12), 0, dp(2)) })
        allSpeechBtn = btn("Remove all speech") { toggleMode(VoiceMode.ALL_SPEECH) }
        voicesOnlyBtn = btn("Voices only") {
            if (!FilteredHearing.neuralEngine) { fhStatus.text = "Voices only needs the Neural engine."; return@btn }
            toggleMode(VoiceMode.VOICES_ONLY)
        }
        row(allSpeechBtn, voicesOnlyBtn)
        val amountLabel = text("", 13f, "#E8EDF2"); col.addView(amountLabel)
        fun showAmount(v: Int) { amountLabel.text = "How much to take out (neural): $v %" }
        col.addView(SeekBar(this).apply {
            max = 100; progress = amountSlider; showAmount(amountSlider)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    amountSlider = v; showAmount(v); FilteredHearing.setAmount(v / 100.0)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        voiceBtn = btn("Remove that voice") {
            if (!FilteredHearing.hasVoice("that")) { fhStatus.text = "Learn a voice first: hold the button while they talk."; return@btn }
            toggleMode(VoiceMode.TARGET)
        }
        row(holdButton("Hold to learn a voice", "that"), voiceBtn)
        ownBtn = btn("Remove my voice") {
            if (!FilteredHearing.neuralEngine) { fhStatus.text = "Remove my voice needs the Neural engine."; return@btn }
            if (!FilteredHearing.ownVoice && !FilteredHearing.hasVoice("me")) { fhStatus.text = "Learn your voice first: hold the button and talk for about 10 s."; return@btn }
            FilteredHearing.setOwnVoice(!FilteredHearing.ownVoice); refreshFh()
        }
        row(holdButton("Hold to learn my voice", "me"), ownBtn)
        col.addView(text("Your own voice: you mostly hear it through your skull (bone conduction), and the sealed ear " +
            "tips make that louder (occlusion). No app can remove that part. \"Remove my voice\" only takes out the part " +
            "the AirPods' mics pick up and play back to you.", 11f))
        row(btn("Forget learned sound") { FilteredHearing.forgetSound(); refreshFh() },
            btn("Forget voices") { FilteredHearing.forgetVoices(); refreshFh() })
        fhStatus = text("", 13f, "#99FFBB"); col.addView(fhStatus)
        col.addView(text("Safety: the room sound is limited to well under music level and fades in; if a whistle " +
            "(feedback) builds up it is muted at once. Turning it off puts the AirPods back in the mode they were in; " +
            "Conversation Awareness comes back when listening stops. Expect a delay (Bluetooth both ways, plus 30 ms " +
            "for the neural filter), so it suits background, not lip-sync.", 11f))
        refreshFh()
    }

    private fun holdButton(label: String, slot: String) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (mic == null) fhStatus.text = "Start listening first."
                    else {
                        FilteredHearing.startEnroll(slot)
                        text = if (slot == "me") "Talk now... keep holding (~10 s)" else "Listening to the voice... keep holding (~10 s)"
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    text = label; FilteredHearing.finishEnroll()
                    if (e.action == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    private fun toggleMode(m: VoiceMode) {
        FilteredHearing.setMode(if (FilteredHearing.mode == m) VoiceMode.OFF else m)
        refreshFh()
    }

    private fun refreshFh() {
        if (!::fhBtn.isInitialized) return
        fhBtn.text = when {
            FilteredHearing.on -> "Filtered hearing: ON  (tap to turn off)"
            pendingOn -> "Starting... (waiting for the AirPods' mics)"
            else -> "Turn on filtered hearing"
        }
        val vm = FilteredHearing.mode
        val n = FilteredHearing.neuralEngine
        engineNeural.text = if (n) "Neural  \u2713" else "Neural"
        engineLight.text = if (!n) "Light (battery)  \u2713" else "Light (battery)"
        voiceBtn.text = if (vm == VoiceMode.TARGET) "Removing that voice (tap: stop)" else "Remove that voice"
        allSpeechBtn.text = if (vm == VoiceMode.ALL_SPEECH) "Removing all speech (tap: stop)" else "Remove all speech"
        voicesOnlyBtn.text = if (vm == VoiceMode.VOICES_ONLY) "Voices only (tap: stop)" else "Voices only"
        ownBtn.text = if (FilteredHearing.ownVoice) "Removing my voice (tap: stop)" else "Remove my voice"
        val clicks = clicksShown || proc.clicksOn
        clicksBtn.text = if (clicks) "Removing clicks (tap: stop)" else "Remove clicks"
        fhStatus.text = FilteredHearing.describe()
    }

    private fun toggleFh() {
        if (FilteredHearing.on) { FilteredHearing.turnOff(); refreshFh(); return }
        if (mic == null) {
            if (!Handoff.local(this)) { fhStatus.text = "Connect the AirPods to this phone first."; return }
            FilteredHearing.startMic(this); startBtn.text = "Stop"; pendingOn = true; refreshFh(); return
        }
        FilteredHearing.turnOn(this)?.let { fhStatus.text = it; return }
        refreshFh()
    }

    private fun learnSound() {
        if (mic == null) { fhStatus.text = "Start listening first, then learn the sound while it is the main thing heard."; return }
        proc.post { learnNoise(3.0) }
        clicksShown = false
        suggestBtn.visibility = View.GONE
        fhStatus.text = "Learning the sound for 3 s... keep quiet so only it is heard."
    }

    private fun blockBand() {
        val b = band ?: run { fhStatus.text = "Tap the spectrum on the sound's band first, then Block."; return }
        proc.post { addBand(b.first, b.second) }
        fhStatus.text = "Blocking ${b.first.toInt()}-${b.second.toInt()} Hz."
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
        // Auto-suggest: a steady fan / hum / motor / air sound can be learned and taken out
        if (l != null && l.steady && proc.noise == null && Regex("fan|hum|motor|air|rumble|whine|hiss").containsMatchIn(l.label)) {
            suggestBtn.text = "Block it: ${l.label} (learns it for 3 s: stay quiet)"
            suggestBtn.visibility = View.VISIBLE
        } else if (proc.noise != null) suggestBtn.visibility = View.GONE
    }

    // ---- HeadMic.Listener (called on its threads) ----
    override fun onStatus(text: String) = runOnUiThread { status.text = text }

    override fun onFrame(analyzer: SoundAnalyzer) = runOnUiThread {
        spectrum.set(analyzer.bins, analyzer.binCenters())
        level.text = "Level at the AirPods' mics: ${"%.0f".format(analyzer.levelDb)} dBFS (relative, not calibrated)" +
            (mic?.ear?.let { "\nEars: " + Ears.text(this) } ?: "")
        showLook()
        if (pendingOn) { pendingOn = false; FilteredHearing.turnOn(this)?.let { fhStatus.text = it } }
        refreshFh()
    }

    override fun onEnded(reason: String) = runOnUiThread {
        status.text = reason; startBtn.text = "Start listening"; pendingOn = false; refreshFh()
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
