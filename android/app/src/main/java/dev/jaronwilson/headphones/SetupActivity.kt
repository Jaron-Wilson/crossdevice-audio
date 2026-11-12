package dev.jaronwilson.headphones

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The app screen. On top: the controls that don't fit a widget (the EQ curve, the AirPods' adaptive
 * noise level and features, the Crusher's extras). Below: setup (pick your AirPods from the phone's
 * paired devices, optionally point it at a laptop panel). Nothing device-specific is built into the APK.
 */
class SetupActivity : Activity() {
    private lateinit var spinner: Spinner
    private lateinit var url: EditText
    private lateinit var status: TextView
    private var devices: List<BluetoothDevice> = emptyList()

    // controls
    private val io = Executors.newSingleThreadExecutor()
    private var binding = false                       // true while we set controls from state (no echo)
    private lateinit var eqOn: Switch
    private lateinit var eqCurve: EqCurveView
    private lateinit var eqPresets: LinearLayout
    private lateinit var eqStatus: TextView
    private lateinit var apBox: LinearLayout
    private lateinit var apLevel: SeekBar
    private lateinit var apLevelText: TextView
    private lateinit var eqHeadroom: TextView
    private val switchTitles = LinkedHashMap<String, String>()
    private val smartSw = LinkedHashMap<String, Switch>()
    private val smartSpin = LinkedHashMap<String, LinkedHashMap<Act, Spinner>>()
    private val smartStatus = LinkedHashMap<String, TextView>()
    private lateinit var smartCall: Switch
    private lateinit var smartNote: TextView
    private lateinit var crFeat: TextView
    private lateinit var apNote: TextView
    private val apSwitches = LinkedHashMap<String, Switch>()
    private val phButtons = LinkedHashMap<String, Button>()
    private var phModes: List<String> = emptyList()
    // The AirPods never report the press-and-hold modes (0x1A), so they start unknown: picks are kept
    // here until there are two, then sent.
    private var phDraft: List<String>? = null
    private lateinit var crBox: LinearLayout
    private lateinit var crBassOn: Switch
    private val crSwitches = LinkedHashMap<String, Switch>()
    private var lastEqPush = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneDirect.resetStatus(this)   // opening the app retries direct control from the phone
        buildUi()
        val need = (arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) +
            (if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()))
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else loadDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        val granted = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_ACTIVITY -> {
                if (!granted) { for (dev in SmartAdaptive.DEVICES) SmartAdaptive.setEnabled(this, dev, false); toast("Smart adaptive needs the Physical activity permission") }
                SmartAdaptive.sync(this); refreshSmart()
            }
            REQ_PHONE -> {
                SmartAdaptive.setCallAware(this, granted)
                if (!granted) toast("Call detection needs the Phone permission")
                refreshSmart()
            }
            else -> loadDevices()
        }
    }

    override fun onResume() {
        super.onResume()
        loadControls()
        if (::smartNote.isInitialized) { SmartAdaptive.sync(this); refreshSmart() }
    }

    private companion object {
        const val REQ_ACTIVITY = 2
        const val REQ_PHONE = 3
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, size: Float = 13f, color: String = "#8AA6B4") = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(14), 0, dp(6))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(12))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#1A2230")); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#2B3647"))
        }
    }

    private fun lp(top: Int = 10) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun switch(t: String, onToggle: (Boolean) -> Unit) = Switch(this).apply {
        text = t; textSize = 15f; setTextColor(Color.parseColor("#E8EDF2")); setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, on -> if (!binding) onToggle(on) }
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    private fun ends(l: String, r: String) = LinearLayout(this).apply {
        addView(TextView(this@SetupActivity).apply { text = l; textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(this@SetupActivity).apply { text = r; textSize = 12f; gravity = Gravity.END; setTextColor(Color.parseColor("#8AA6B4")) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun seek(max: Int, onDone: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { if (!binding) onDone(sb.progress) }
        })
    }

    private fun buildControls(col: LinearLayout) {
        // ---- Sound (EQ) ----
        col.addView(label("Sound (EQ on this phone's audio)", 15f, "#E8EDF2"))
        val eq = card()
        eqOn = switch("Equalizer") { on -> EqModel.setEnabled(this, on); EqService.update(this); refreshEq(); HeadphonesWidget.redraw(this) }
        eq.addView(eqOn)
        eqPresets = LinearLayout(this)
        eq.addView(HorizontalScrollView(this).apply { addView(eqPresets); isHorizontalScrollBarEnabled = false }, lp(4))
        eqCurve = EqCurveView(this, onChange = { g ->
            // live while dragging, at most ~6 updates a second
            if (System.currentTimeMillis() - lastEqPush > 160) { lastEqPush = System.currentTimeMillis(); pushEq(g) }
        }, onDone = { g -> pushEq(g); refreshEq() })
        eq.addView(eqCurve, lp(8))
        eqStatus = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")); setPadding(0, dp(8), 0, 0) }
        eq.addView(eqStatus)
        eqHeadroom = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")) }
        eq.addView(eqHeadroom)
        col.addView(eq, lp(0))

        // ---- AirPods ----
        apBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        apBox.addView(label("AirPods", 15f, "#E8EDF2"))
        val ap = card()
        ap.addView(label("Block noise", 13f, "#E8EDF2"))
        ap.addView(TextView(this).apply {
            text = "Your AirPods listen with their own mics and adjust automatically."
            textSize = 12f; setTextColor(Color.parseColor("#8AA6B4"))
        })
        // slider position = 100 - level: left = block more (level 100), right = hear more (level 0)
        apLevelText = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#E8EDF2")); setPadding(0, dp(8), 0, 0) }
        ap.addView(apLevelText)
        apLevel = seek(100) { v -> setAdaptive(100 - v) }
        apLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { apLevelText.text = "Adaptive level: blocks ${100 - v}%" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { if (!binding) setAdaptive(100 - sb.progress) }
        })
        ap.addView(apLevel)
        ap.addView(ends("Block more", "Hear more"))
        val quick = LinearLayout(this)
        for ((t, act) in listOf<Pair<String, () -> Unit>>(
            "Block max" to { setAdaptive(100) }, "Balanced" to { setAdaptive(50) },
            "Hear more" to { setAdaptive(0) }, "Full ANC" to { setApMode("anc") })) {
            quick.addView(Button(this).apply { text = t; isAllCaps = false; textSize = 12f; setOnClickListener { act() } },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        ap.addView(quick)
        ap.addView(TextView(this).apply {
            text = "Adaptive and ANC lower outside sound as a whole, steady low sounds (engines, fans) most. " +
                "They can't pick out one frequency. The level is applied silently: the AirPods report it again " +
                "only when the link reopens (Read back below)."
            textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")); setPadding(0, dp(6), 0, dp(6))
        })
        for ((k, t) in listOf("conversational_awareness" to "Conversational Awareness",
                "personalized_volume" to "Personalized Volume", "ear_detection" to "Automatic ear detection",
                "one_bud_anc" to "Noise cancelling with one AirPod", "allow_off" to "Offer \"Off\" in press and hold")) {
            switchTitles[k] = t
            apSwitches[k] = switch(t) { on -> setAp(k, on) }.also { ap.addView(it) }
        }
        ap.addView(label("Press and hold cycles through (at least two)", 12f))
        val ph = LinearLayout(this)
        for ((k, t) in listOf("off" to "Off", "anc" to "ANC", "transparency" to "Clear", "adaptive" to "Adaptive")) {
            phButtons[k] = Button(this).apply {
                text = t; isAllCaps = false
                setOnClickListener {
                    val cur = phDraft ?: phModes
                    val next = if (k in cur) cur - k else cur + k
                    if (next.size < 2) {
                        phDraft = next; colorPh(next); toast("Pick at least two modes")
                    } else {
                        phDraft = null; phModes = next; colorPh(next)
                        setPressHold(next)
                    }
                }
            }.also { ph.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }
        ap.addView(ph)
        apNote = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")) }
        ap.addView(apNote)
        ap.addView(Button(this).apply {
            text = "Read back from AirPods"; isAllCaps = false
            setOnClickListener { readBack() }
        })
        apBox.addView(ap)
        col.addView(apBox)

        // ---- Crusher ----
        crBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        crBox.addView(label("Crusher ANC 2", 15f, "#E8EDF2"))
        val cr = card()
        crBassOn = switch("Sensory bass (haptic) on") { on -> setCr("bass_on", on) }
        cr.addView(crBassOn)
        for ((k, t) in listOf("at_on_call" to "Hear-through on calls", "at_on_pause" to "Hear-through when paused"))
            crSwitches[k] = switch(t) { on -> setCr(k, on) }.also { cr.addView(it) }
        crFeat = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")); setPadding(0, dp(6), 0, 0) }
        cr.addView(crFeat)
        crBox.addView(cr)
        col.addView(crBox)
        buildSmart(col)
        refreshEq()
    }

    // ---- Smart adaptive ----
    private fun devName(dev: String) = if (dev == SmartAdaptive.CRUSHER) "Crusher" else "AirPods"

    private fun buildSmart(col: LinearLayout) {
        col.addView(label("Smart adaptive", 15f, "#E8EDF2"))
        val c = card()
        c.addView(TextView(this).apply {
            text = "Switches the noise mode by what your phone's motion sensors say you are doing. " +
                "Phone sensors only: no microphone is used. A new activity must hold about 20 seconds, " +
                "and it switches at most once every 30 seconds. Off by default."
            textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")); setPadding(0, dp(8), 0, dp(4))
        })
        for (dev in SmartAdaptive.DEVICES) {
            smartSw[dev] = switch("${devName(dev)}: switch by activity") { on -> toggleSmart(dev, on) }.also { c.addView(it) }
            smartStatus[dev] = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#99FFBB")) }.also { c.addView(it) }
            val spins = LinkedHashMap<Act, Spinner>()
            for (a in SmartAdaptive.ACTS) {
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(this).apply { text = a.label.replaceFirstChar { it.uppercase() }; textSize = 13f
                    setTextColor(Color.parseColor("#E8EDF2")) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val opts = SmartAdaptive.options(dev)
                val sp = Spinner(this).apply {
                    adapter = ArrayAdapter(this@SetupActivity, android.R.layout.simple_spinner_dropdown_item, opts.map { it.second })
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                            if (!binding) { SmartAdaptive.setChoice(this@SetupActivity, dev, a, opts[pos].first); pokeSmart() }
                        }
                        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                    }
                }
                row.addView(sp, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f))
                spins[a] = sp; c.addView(row)
            }
            smartSpin[dev] = spins
        }
        smartCall = switch("Phone calls count (needs phone-state permission)") { on -> toggleCall(on) }.also { c.addView(it) }
        smartNote = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")) }
        c.addView(smartNote)
        col.addView(c, lp(10))
        refreshSmart()
    }

    private fun refreshSmart() {
        binding = true
        for (dev in SmartAdaptive.DEVICES) {
            smartSw.getValue(dev).isChecked = SmartAdaptive.enabled(this, dev)
            smartStatus.getValue(dev).text = if (SmartAdaptive.enabled(this, dev)) SmartAdaptive.status(this, dev) else ""
            for ((a, sp) in smartSpin.getValue(dev)) {
                val i = SmartAdaptive.options(dev).indexOfFirst { it.first == SmartAdaptive.choice(this, dev, a) }
                sp.setSelection(i.coerceAtLeast(0))
            }
        }
        smartCall.isChecked = SmartAdaptive.callAware(this) && SmartAdaptive.hasPhonePermission(this)
        smartNote.text = SmartAdaptive.registration(this)
        binding = false
    }

    /** A choice changed: re-evaluate now (Bluetooth work, off the main thread). */
    private fun pokeSmart() {
        val app = applicationContext
        io.execute { SmartAdaptive.process(app); runOnUiThread { refreshSmart() } }
    }

    private fun toggleSmart(dev: String, on: Boolean) {
        SmartAdaptive.setEnabled(this, dev, on)
        if (on && !SmartAdaptive.hasActivityPermission(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Physical activity")
                .setMessage("Android asks for \"Physical activity\" so the phone can tell when you are still, walking, cycling or in a vehicle. " +
                    "Only the phone's motion sensors are used, nothing is recorded or sent anywhere, and no microphone is touched.")
                .setPositiveButton("Continue") { _, _ -> requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_ACTIVITY) }
                .setNegativeButton("Cancel") { _, _ -> SmartAdaptive.setEnabled(this, dev, false); refreshSmart() }
                .setOnCancelListener { SmartAdaptive.setEnabled(this, dev, false); refreshSmart() }
                .show()
            return
        }
        SmartAdaptive.sync(this)
        if (!on) pokeSmart()
        io.execute { runOnUiThread { refreshSmart() } }
        smartNote.postDelayed({ refreshSmart() }, 1500)
    }

    private fun toggleCall(on: Boolean) {
        if (!on) { SmartAdaptive.setCallAware(this, false); refreshSmart(); return }
        if (SmartAdaptive.hasPhonePermission(this)) { SmartAdaptive.setCallAware(this, true); refreshSmart(); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("Phone state")
            .setMessage("To switch to Transparency during a phone call, Android requires the \"Phone\" permission so this app is told when a call starts and ends. " +
                "It does not read your calls, numbers or contacts, and never opens a microphone. It sees ordinary phone calls, not app calls.")
            .setPositiveButton("Continue") { _, _ -> requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE) }
            .setNegativeButton("Cancel") { _, _ -> refreshSmart() }
            .setOnCancelListener { refreshSmart() }
            .show()
    }

    private fun pushEq(g: List<Double>) {
        EqModel.setGains(this, g)
        if (!EqModel.enabled(this)) EqModel.setEnabled(this, true)
        EqService.update(this)
    }

    private fun refreshEq() {
        binding = true
        eqOn.isChecked = EqModel.enabled(this)
        binding = false
        eqCurve.active = EqModel.enabled(this)
        eqCurve.gains = EqModel.gains(this).toMutableList()
        eqPresets.removeAllViews()
        val cur = EqModel.preset(this)
        for (p in EqModel.presets(this) + EqModel.Preset("custom", "Custom", emptyList())) {
            eqPresets.addView(Button(this).apply {
                text = p.label; isAllCaps = false
                setTextColor(Color.parseColor(if (p.key == cur && EqModel.enabled(this@SetupActivity)) "#5FD0FF" else "#E8EDF2"))
                isEnabled = p.key != "custom"
                setOnClickListener { EqModel.setPreset(this@SetupActivity, p.key); EqService.update(this@SetupActivity); refreshEq(); HeadphonesWidget.redraw(this@SetupActivity) }
            })
        }
        // the service writes its status a moment after it starts
        val hr = EqModel.headroomDb(this)
        eqHeadroom.text = "Headroom " + (if (hr == 0.0) "0" else "%.1f".format(hr)) + " dB (a limiter catches the rest)"
        eqStatus.text = if (EqModel.enabled(this)) EqService.status(this) else "Off"
        eqStatus.postDelayed({ if (EqModel.enabled(this)) eqStatus.text = EqService.status(this) }, 1200)
    }

    /** Read the AirPods' and the Crusher's state (phone first, then the laptop panel) and fill in the controls. */
    private fun loadControls() {
        if (!::apBox.isInitialized) return
        val app = applicationContext
        io.execute {
            val onPhone = Handoff.local(app)
            val panel = runCatching { HeadphonesWidget.panelState(app) }.getOrNull()
            val apSet: JSONObject? = when {
                onPhone && PhoneDirect.status(app) != "fail" -> runCatching { PhoneDirect.settings(app) }.getOrNull()
                panel?.optJSONObject("airpods")?.optBoolean("online") == true -> panel.optJSONObject("airpods")?.optJSONObject("settings")
                else -> null
            }
            val cr: JSONObject? = if (CrusherDirect.connected(app)) runCatching { CrusherDirect.state(app) }.getOrNull()
                else panel?.optJSONObject("crusher")?.takeIf { it.optBoolean("online") }
            runOnUiThread { bindControls(apSet, onPhone, cr) }
        }
    }

    private fun bindControls(ap: JSONObject?, onPhone: Boolean, cr: JSONObject?) {
        binding = true
        apBox.visibility = if (ap != null) View.VISIBLE else View.GONE
        if (ap != null) {
            if (!ap.isNull("adaptive_level")) apLevel.progress = 100 - ap.optInt("adaptive_level")
            apLevelText.text = "Adaptive level: blocks ${100 - apLevel.progress}%"
            for ((k, sw) in apSwitches) {
                sw.isChecked = ap.optBoolean(k, false)
                sw.alpha = if (ap.isNull(k)) 0.5f else 1f
                val base = switchTitles.getValue(k)
                sw.text = if (onPhone && PhoneDirect.fromApp(k) && !ap.isNull(k)) "$base (last set from this app)" else base
            }
            phModes = ap.optJSONArray("press_hold_modes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            colorPh(phDraft ?: phModes)
            apNote.text = (if (onPhone) "Talking to the AirPods directly from this phone." else "Through the laptop panel.") +
                " Dim = never set. The AirPods never report ear detection, \"Off\" in press and hold, or the press-and-hold modes, " +
                "so those show what this app last set."
        }
        crBox.visibility = if (cr != null) View.VISIBLE else View.GONE
        if (cr != null) {
            crBassOn.visibility = if (cr.has("bass_on")) View.VISIBLE else View.GONE
            crBassOn.isChecked = cr.optBoolean("bass_on")
            val x = cr.optJSONObject("extras") ?: JSONObject()
            // no transparency level / ANC strength on this firmware (those commands answer ERROR)
            crFeat.text = x.optJSONArray("features")?.let { a ->
                "Firmware features (read-only): " + (0 until a.length()).joinToString(", ") {
                    a.getJSONObject(it).let { f -> f.getString("name") + (if (f.optBoolean("enabled")) " on" else " off") } }
            } ?: ""
            for ((k, sw) in crSwitches) {
                sw.visibility = if (x.has(k)) View.VISIBLE else View.GONE
                sw.isChecked = x.optBoolean(k)
            }
        }
        binding = false
    }

    private fun colorPh(modes: List<String>) {
        for ((k, b) in phButtons) b.setTextColor(Color.parseColor(if (k in modes) "#5FD0FF" else "#8AA6B4"))
    }

    private fun setAp(name: String, value: Any) {
        val app = applicationContext
        io.execute {
            val r = runCatching {
                if (Handoff.local(app)) PhoneDirect.setSetting(app, name, value)
                else {
                    val v: Any = if (value is List<*>) JSONArray(value) else value
                    HeadphonesWidget.post(app, "/api/airpods/setting", JSONObject().put("name", name).put("value", v).toString())
                }
            }
            r.exceptionOrNull()?.let { toast("AirPods: ${it.message}") }
            loadControls()
        }
    }

    private fun setPressHold(modes: List<String>) {
        val app = applicationContext
        io.execute {
            val r = runCatching {
                if (Handoff.local(app)) PhoneDirect.setPressHold(app, modes)
                else {
                    if ("off" in modes) HeadphonesWidget.post(app, "/api/airpods/setting",
                        JSONObject().put("name", "allow_off").put("value", true).toString())
                    HeadphonesWidget.post(app, "/api/airpods/setting",
                        JSONObject().put("name", "press_hold_modes").put("value", JSONArray(modes)).toString())
                }
            }
            r.exceptionOrNull()?.let { toast("AirPods: ${it.message}") }
            loadControls()
        }
    }

    /** Raw AAP level (100 = blocks the most) and Adaptive mode, in one go. */
    private fun setAdaptive(level: Int) {
        val app = applicationContext
        io.execute {
            val r = runCatching {
                if (Handoff.local(app)) PhoneDirect.setAdaptive(app, level)
                else {
                    HeadphonesWidget.post(app, "/api/airpods/setting",
                        JSONObject().put("name", "adaptive_level").put("value", level).toString())
                    HeadphonesWidget.post(app, "/api/airpods/mode", "adaptive")
                }
            }
            r.exceptionOrNull()?.let { toast("AirPods: ${it.message}") }
            loadControls()
        }
    }

    private fun setApMode(mode: String) {
        val app = applicationContext
        io.execute {
            val r = runCatching {
                if (Handoff.local(app)) PhoneDirect.setMode(app, mode)
                else HeadphonesWidget.post(app, "/api/airpods/mode", mode)
            }
            r.exceptionOrNull()?.let { toast("AirPods: ${it.message}") }
            loadControls()
        }
    }

    /** Reopens the AAP link (not Bluetooth) so the AirPods report their settings again. */
    private fun readBack() {
        val app = applicationContext
        io.execute {
            if (!Handoff.local(app)) { toast("Only works while the AirPods are on this phone"); return@execute }
            val r = runCatching { PhoneDirect.readBack(app) }
            toast(if (r.isSuccess) "Read back from the AirPods" else "AirPods: ${r.exceptionOrNull()?.message}")
            loadControls()
        }
    }

    private fun setCr(name: String, value: Any) {
        val app = applicationContext
        io.execute {
            val r = runCatching {
                if (CrusherDirect.connected(app)) CrusherDirect.setSetting(app, name, value)
                else HeadphonesWidget.post(app, "/api/crusher/setting", JSONObject().put("name", name).put("value", value).toString())
            }
            r.exceptionOrNull()?.let { toast("Crusher: ${it.message}") }
            loadControls()
        }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(22))
            setBackgroundColor(Color.parseColor("#0F1419"))
        }
        col.addView(label("Headphones", 24f, "#E8EDF2"))
        col.addView(Button(this).apply {
            text = "Live Translation"; isAllCaps = false; textSize = 20f; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#2F7D4F")); cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { startActivity(Intent(this@SetupActivity, TranslateActivity::class.java)) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(72)).apply { bottomMargin = dp(6) })
        buildControls(col)
        col.addView(label("Setup", 15f, "#E8EDF2"))
        col.addView(label("Control your AirPods noise modes and see their battery from a home-screen widget. " +
            "The laptop panel is optional: it adds control while your AirPods are on a computer."))
        col.addView(label("Your AirPods (from paired devices)"))
        spinner = Spinner(this)
        col.addView(spinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(label("Laptop panel URL (optional)"))
        url = EditText(this).apply {
            hint = "https://my-laptop.my-tailnet.ts.net:8970"
            setText(Config.panelUrl(this@SetupActivity))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#55708A"))
        }
        col.addView(url, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val save = Button(this).apply { text = "Save"; setOnClickListener { save() } }
        col.addView(save, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        val open = Button(this).apply {
            text = "Open laptop panel"
            setOnClickListener {
                if (Config.hasPanel(this@SetupActivity))
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.panelUrl(this@SetupActivity))))
                else Toast.makeText(this@SetupActivity, "No laptop panel set", Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(open, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = label("", 13f, "#99FFBB")
        col.addView(status)
        col.addView(label("Then long-press your home screen, open Widgets and add Headphones.", 12f))
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#0F1419")) })
    }

    @Suppress("MissingPermission")
    private fun loadDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Allow \"Nearby devices\" so the app can find your AirPods."
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        devices = adapter?.bondedDevices?.sortedBy { if ((it.name ?: "").contains("AirPods", true)) 0 else 1 } ?: emptyList()
        val names = devices.map { "${it.name ?: "Unknown"}  (${it.address})" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val saved = devices.indexOfFirst { it.address.equals(Config.airpodsMac(this), true) }
        if (saved >= 0) spinner.setSelection(saved)
        status.text = if (devices.isEmpty()) "Pair your AirPods in Bluetooth settings first." else ""
        if (Config.hasAirPods(this)) syncHandoff()
    }

    private fun save() {
        val dev = devices.getOrNull(spinner.selectedItemPosition)
        if (dev == null) { status.text = "Pick your AirPods first."; return }
        Config.save(this, url.text.toString(), dev.address)
        status.text = "Saved."
        syncHandoff()
    }

    /** Who has the AirPods right now: tell the laptop (if any) and redraw the widget. */
    @Suppress("MissingPermission")
    private fun syncHandoff() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val onPhone = runCatching {
                    (proxy as BluetoothA2dp).connectedDevices.any { it.address.equals(Config.airpodsMac(this@SetupActivity), true) }
                }.getOrDefault(false)
                adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                Handoff.setLocal(this@SetupActivity, onPhone)
                val app = applicationContext
                thread { Handoff.tell(app, onPhone); HeadphonesWidget.refresh(app) }
                runOnUiThread {
                    status.text = (status.text.toString() + "\n" +
                        (if (onPhone) "Your AirPods are on this phone." else "Your AirPods aren't on this phone right now.")).trim()
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }
}
