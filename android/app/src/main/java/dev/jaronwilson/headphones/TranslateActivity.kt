package dev.jaronwilson.headphones

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live Translation: start/stop, languages, the reply shown big for the other person, live captions both
 * ways, and the on-device models. Opened from the app or from the widget's Translate button (the widget
 * opens this screen because Android only lets a visible app start using the microphone).
 */
class TranslateActivity : Activity() {
    private lateinit var startBtn: Button
    private lateinit var status: TextView
    private lateinit var caption: TextView
    private lateinit var replyBox: LinearLayout
    private lateinit var replyText: TextView
    private lateinit var meBtn: Button
    private lateinit var themSpin: Spinner
    private lateinit var meSpin: Spinner
    private lateinit var models: LinearLayout
    private lateinit var speech: TextView
    private lateinit var lines: LinearLayout
    private var shownEntries = -1
    private val langs by lazy { LiveTranslate.languages() }
    private var binding = false
    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val onChange: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (intent?.action == ACTION_TOGGLE) toggle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_TOGGLE) toggle()
    }

    override fun onResume() { super.onResume(); LiveTranslate.listen(onChange); refresh(); refreshModels() }
    override fun onPause() { LiveTranslate.unlisten(onChange); super.onPause() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(t: String, size: Float = 13f, color: String = "#8AA6B4") = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(6), 0, dp(4))
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1A2230")); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#2B3647"))
        }
    }
    private fun lp(top: Int = 10) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(22))
            setBackgroundColor(Color.parseColor("#0F1419"))
        }
        col.addView(text("Live Translation", 24f, "#E8EDF2"))
        col.addView(text("Your AirPods' microphones hear the other person and you hear the translation in your AirPods. " +
            "What you say is shown here big and spoken on the phone speaker in their language. " +
            "Everything runs on this phone: no cloud, nothing saved unless you tap Save."))

        startBtn = Button(this).apply {
            textSize = 22f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            setOnClickListener { toggle() }
        }
        col.addView(startBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(84)).apply { topMargin = dp(10) })
        status = text("", 13f, "#99FFBB")
        col.addView(status)

        // the reply, big, for the other person to read
        replyBox = card().apply { visibility = View.GONE }
        replyText = TextView(this).apply { textSize = 30f; setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(6)) }
        replyBox.addView(text("For them", 12f))
        replyBox.addView(replyText)
        replyBox.addView(LinearLayout(this).apply {
            addView(button("Speak again") { LiveTranslateService.speakAgain() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("Full screen") { fullScreenReply() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        col.addView(replyBox, lp(8))

        // hold while I talk: forces my language for that phrase
        meBtn = Button(this).apply {
            text = "Hold while you talk"; isAllCaps = false; textSize = 16f
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> LiveTranslate.trigger(this@TranslateActivity, LiveTranslate.TriggerSource.SCREEN, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        LiveTranslate.trigger(this@TranslateActivity, LiveTranslate.TriggerSource.SCREEN, false)
                        if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                false
            }
        }
        col.addView(meBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(60)).apply { topMargin = dp(6) })
        col.addView(text("Optional: it tells the app the next phrase is yours. Without it, the app works out who spoke from the language.", 11f))

        caption = text("", 16f, "#5FD0FF")
        col.addView(caption)

        // transcript
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Transcript", 15f, "#E8EDF2"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(button("Clear") { LiveTranslate.clear() })
        head.addView(button("Save") { save() })
        col.addView(head, lp(12))
        lines = card()
        col.addView(lines, lp(4))

        // languages
        col.addView(text("Languages", 15f, "#E8EDF2"), lp(14))
        val lc = card()
        val names = langs.map { LiveTranslate.name(it) }
        themSpin = Spinner(this).apply { adapter = ArrayAdapter(this@TranslateActivity, android.R.layout.simple_spinner_dropdown_item, names) }
        meSpin = Spinner(this).apply { adapter = ArrayAdapter(this@TranslateActivity, android.R.layout.simple_spinner_dropdown_item, names) }
        lc.addView(text("They speak", 12f)); lc.addView(themSpin)
        lc.addView(text("You speak (and hear)", 12f)); lc.addView(meSpin)
        lc.addView(button("Swap") {
            LiveTranslate.setLangs(this, LiveTranslate.me(this), LiveTranslate.them(this)); bindLangs(); refreshModels()
        })
        val pick = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (binding) return
                LiveTranslate.setLangs(this@TranslateActivity, langs[themSpin.selectedItemPosition], langs[meSpin.selectedItemPosition])
                refreshModels()
                if (LiveTranslate.running) toast("Language changes apply the next time you start.")
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        themSpin.onItemSelectedListener = pick; meSpin.onItemSelectedListener = pick
        col.addView(lc, lp(4))
        bindLangs()

        // options
        col.addView(text("Options", 15f, "#E8EDF2"), lp(14))
        val oc = card()
        oc.addView(text("AirPods noise control while translating (put back when you stop)", 12f))
        val ancSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@TranslateActivity, android.R.layout.simple_spinner_dropdown_item, AncChoice.values().map { it.label })
            setSelection(LiveTranslate.anc(this@TranslateActivity).ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    LiveTranslate.setAnc(this@TranslateActivity, AncChoice.values()[pos])
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        oc.addView(ancSpin)
        oc.addView(Switch(this).apply {
            text = "Speak my words on the phone speaker"; textSize = 15f; setTextColor(Color.parseColor("#E8EDF2"))
            isChecked = LiveTranslate.speakReplies(this@TranslateActivity)
            setOnCheckedChangeListener { _, on -> LiveTranslate.setSpeakReplies(this@TranslateActivity, on) }
        })
        col.addView(oc, lp(4))

        // models
        col.addView(text("On-device models", 15f, "#E8EDF2"), lp(14))
        val mc = card()
        mc.addView(text("Translation models download over Wi-Fi only, ${LiveTranslate.MODEL_SIZE} each (ML Kit doesn't report exact " +
            "sizes or progress). Language detection is built into the app.", 12f))
        models = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mc.addView(models)
        speech = text("", 12f)
        mc.addView(speech)
        col.addView(mc, lp(4))

        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#0F1419")) })
    }

    private fun bindLangs() {
        binding = true
        themSpin.setSelection(langs.indexOf(LiveTranslate.them(this)).coerceAtLeast(0))
        meSpin.setSelection(langs.indexOf(LiveTranslate.me(this)).coerceAtLeast(0))
        binding = false
    }

    private fun refresh() {
        val on = LiveTranslate.running
        val starting = LiveTranslate.starting
        startBtn.text = when { on -> "Stop translating"; starting -> "Starting…  (tap to cancel)"; else -> "Start translating" }
        startBtn.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (on || starting) "#8A2F2F" else "#2F7D4F")); cornerRadius = dp(16).toFloat()
        }
        status.text = LiveTranslate.status
        caption.text = LiveTranslate.caption.let { if (it.isEmpty()) "" else "… $it" }
        meBtn.isEnabled = on
        meBtn.background = GradientDrawable().apply {
            setColor(Color.parseColor(if (LiveTranslate.meHeld) "#2F5F9A" else "#273345")); cornerRadius = dp(14).toFloat()
        }
        meBtn.setTextColor(Color.WHITE)
        val r = LiveTranslate.lastReply
        replyBox.visibility = if (r != null) View.VISIBLE else View.GONE
        replyText.text = r?.translated ?: ""
        val entries = LiveTranslate.transcript.toList()
        if (entries.size != shownEntries) {
            shownEntries = entries.size
            lines.removeAllViews()
            if (entries.isEmpty()) lines.addView(text("Nothing yet.", 12f))
            for (e in entries.takeLast(60).asReversed()) {
                val mine = e.direction == Direction.MINE_TO_THEM
                lines.addView(text("${time.format(Date(e.time))}  ${if (mine) "You" else "Them"} (${e.from})", 11f))
                lines.addView(text(e.heard, 13f, "#8AA6B4").apply { setPadding(0, 0, 0, 0) })
                lines.addView(text(e.translated, 17f, if (mine) "#FFFFFF" else "#5FD0FF").apply { setPadding(0, 0, 0, dp(8)) })
            }
        }
    }

    private fun refreshModels() {
        if (!::models.isInitialized) return
        val want = listOf(LiveTranslate.them(this), LiveTranslate.me(this)).distinct()
        LiveTranslate.downloadedModels { have ->
            models.removeAllViews()
            for (code in (want + have.sortedBy { LiveTranslate.name(it) }).distinct()) {
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val got = code in have
                val busy = LiveTranslate.isDownloading(code)
                row.addView(text(LiveTranslate.name(code) + when { busy -> "  downloading…"; got -> "  downloaded"; else -> "  not downloaded" },
                    14f, if (got) "#E8EDF2" else "#8AA6B4"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                when {
                    busy -> row.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)))
                    got -> row.addView(button("Delete") {
                        LiveTranslate.delete(code) { err -> if (err != null) toast("Couldn't delete: $err"); refreshModels() }
                    })
                    else -> row.addView(button("Download (Wi-Fi)") {
                        toast("Downloading ${LiveTranslate.name(code)} (${LiveTranslate.MODEL_SIZE}). It waits for Wi-Fi if you're not on it.")
                        LiveTranslate.download(code) { err -> if (err != null) toast("Download failed: $err"); refreshModels() }
                        refreshModels()
                    })
                }
                models.addView(row)
            }
        }
        refreshSpeech(want)
    }

    /** Which of the two languages the on-device speech recognizer has installed (Android 13+ can say). */
    private fun refreshSpeech(want: List<String>) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            speech.text = "On-device speech recognition isn't available on this phone; the default recognizer is used (asked to stay offline)."
            return
        }
        if (Build.VERSION.SDK_INT < 33) { speech.text = "Speech recognition: on-device."; return }
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        val tags = want.map { Langs.localeTag(it) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tags.first())
        rec.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(s: RecognitionSupport) {
                val installed = s.installedOnDeviceLanguages.mapNotNull { Langs.base(it) }.toSet()
                val pending = s.pendingOnDeviceLanguages.mapNotNull { Langs.base(it) }.toSet()
                val missing = want.filter { Langs.base(it) !in installed }
                speech.text = "On-device speech: " + want.joinToString(", ") { c ->
                    LiveTranslate.name(c) + when (Langs.base(c)) { in installed -> " ready"; in pending -> " downloading"; else -> " not installed" }
                }
                runCatching { rec.destroy() }
                if (missing.isNotEmpty() && missing.none { Langs.base(it) in pending }) offerSpeechPack(missing)
            }
            override fun onError(error: Int) {
                speech.text = "On-device speech: couldn't check the languages (error $error)."
                runCatching { rec.destroy() }
            }
        })
    }

    private fun offerSpeechPack(missing: List<String>) {
        if (speech.parent == null) return
        val parent = speech.parent as LinearLayout
        parent.findViewWithTag<View>("speechpack")?.let { parent.removeView(it) }
        parent.addView(button("Get on-device speech for ${missing.joinToString(" and ") { LiveTranslate.name(it) }}") {
            for (c in missing) runCatching {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Langs.localeTag(c)))
                speech.postDelayed({ runCatching { r.destroy() } }, 3000)
            }
            toast("Asked Android to download the speech language. It may take a few minutes.")
        }.apply { tag = "speechpack" })
    }

    private fun fullScreenReply() {
        val r = LiveTranslate.lastReply ?: return
        val tv = TextView(this).apply {
            text = r.translated; textSize = 44f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK); setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val d = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).setView(tv).create()
        tv.setOnClickListener { d.dismiss() }
        d.show()
    }

    // ---------------- start / stop and permissions ----------------

    private fun toggle() {
        if (LiveTranslate.running || LiveTranslate.starting) { LiveTranslateService.stop(this); return }
        val need = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT) +
            (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { LiveTranslateService.start(this); return }
        AlertDialog.Builder(this)
            .setTitle("Microphone and notifications")
            .setMessage("Live Translation listens through your AirPods' microphones, so Android asks for the Microphone. " +
                "Speech is recognized and translated on this phone only; nothing is recorded, uploaded or kept unless you tap Save. " +
                "\"Nearby devices\" lets the app find your AirPods, and Notifications shows the Stop button while it runs.")
            .setPositiveButton("Continue") { _, _ -> requestPermissions(missing.toTypedArray(), REQ) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode != REQ) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) LiveTranslateService.start(this)
        else toast("Live Translation needs the microphone permission.")
    }

    private fun save() {
        if (LiveTranslate.transcript.isEmpty()) { toast("Nothing to save."); return }
        val name = "translation-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, name), REQ_SAVE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.openOutputStream(uri)?.use { it.write(LiveTranslate.transcriptText().toByteArray()) } }
            .onSuccess { toast("Saved.") }.onFailure { toast("Couldn't save: ${it.message}") }
    }

    companion object {
        const val ACTION_TOGGLE = "dev.jaronwilson.headphones.TRANSLATE_TOGGLE"
        private const val REQ = 11
        private const val REQ_SAVE = 12
    }
}
