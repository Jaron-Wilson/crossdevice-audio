package dev.jaronwilson.headphones

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Hotter / colder: scans Bluetooth LE at full speed while this screen is open (and only then), smooths
 * the signal strength of your AirPods' (or Crusher's) broadcast and shows how close you are, with a
 * buzz that speeds up as you get closer. Nothing is sent anywhere; no sound is played.
 */
class FinderActivity : Activity() {
    private class Track(val label: String) {
        val kalman = RssiKalman()
        var seen = 0L
        var raw = 0
        var detail = ""
    }

    private var device = Tracker.AIRPODS
    private lateinit var ring: HeatRingView
    private lateinit var title: TextView
    private lateinit var parts: TextView
    private lateinit var matchNote: TextView
    private lateinit var vibrate: Switch
    private val tracks = LinkedHashMap<String, Track>()
    private val sameModel = HashMap<String, Long>()       // address -> last seen, adverts we can't tell apart
    private var matchLevel = MatchLevel.NO
    private var identity = ApIdentity()
    private val ui = Handler(Looper.getMainLooper())
    private var scanning = false
    private var lastBuzz = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra("device") ?: Tracker.AIRPODS
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding((18 * d).toInt(), (24 * d).toInt(), (18 * d).toInt(), (22 * d).toInt())
            setBackgroundColor(Color.parseColor("#0F1419"))
        }
        title = TextView(this).apply { textSize = 22f; setTextColor(Color.parseColor("#E8EDF2")) }
        col.addView(title)
        ring = HeatRingView(this)
        col.addView(ring, LinearLayout.LayoutParams(MATCH_PARENT, (300 * d).toInt()))
        parts = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#E8EDF2")); setPadding(0, (8 * d).toInt(), 0, 0) }
        col.addView(parts, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        vibrate = Switch(this).apply {
            text = "Buzz faster as I get closer"; textSize = 15f; setTextColor(Color.parseColor("#E8EDF2")); isChecked = true
        }
        col.addView(vibrate, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        matchNote = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")); setPadding(0, (10 * d).toInt(), 0, 0) }
        col.addView(matchNote, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(TextView(this).apply {
            textSize = 12f; setTextColor(Color.parseColor("#8AA6B4")); setPadding(0, (10 * d).toInt(), 0, 0)
            text = "Walk slowly and turn around: the signal is strongest with nothing between the phone and them. " +
                "Distances are rough (a wall or a pocket can halve or double them). " +
                (if (device == Tracker.AIRPODS) "AirPods in a closed case mostly stop broadcasting: open the lid if you can, " +
                    "or look where they were last seen. No sound can be played: Apple only offers that through its Find My network."
                else "The Crusher is found only if it sends a Bluetooth LE broadcast with its name or its address; " +
                    "if it is switched off or connected elsewhere it may send none.")
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#0F1419")) })
        title.text = "Find your ${Tracker.label(device)}"
    }

    override fun onResume() {
        super.onResume()
        identity = Tracker.identity(this)
        startScan()
        ui.post(tick)
    }

    override fun onPause() {
        stopScan()
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private val cb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) = runOnUiThread { onResult(r) }
        override fun onBatchScanResults(results: MutableList<ScanResult>) = runOnUiThread { results.forEach { onResult(it) } }
    }

    @Suppress("MissingPermission")
    private fun startScan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            matchNote.text = "Allow \"Nearby devices\" in the main Headphones screen first."
            return
        }
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: run {
            matchNote.text = "Bluetooth is off."; return
        }
        val filters = if (device == Tracker.AIRPODS)
            listOf(ScanFilter.Builder().setManufacturerData(0x004C, byteArrayOf(0x07), byteArrayOf(0xFF.toByte())).build())
        else {
            val c = CrusherDirect.device(this)
            listOfNotNull(c?.let { ScanFilter.Builder().setDeviceAddress(it.address).build() },
                ScanFilter.Builder().setDeviceName(c?.name ?: "Crusher ANC 2").build())
        }
        // full speed, but only while this screen is open (stopped in onPause)
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = runCatching { scanner.startScan(filters, settings, cb) }.isSuccess
    }

    @Suppress("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        scanning = false
    }

    private fun onResult(r: ScanResult) {
        val now = SystemClock.elapsedRealtime()
        if (device == Tracker.CRUSHER) {
            if (!Tracker.isCrusher(r, CrusherDirect.device(this))) return
            feed("crusher", "Crusher", r.rssi, now, "")
            matchLevel = MatchLevel.EXACT
            Tracker.sawNearby(applicationContext, Tracker.CRUSHER, r.rssi, "", "", "finder")
            return
        }
        val ad = ProxAdvert.parse(r.scanRecord?.getManufacturerSpecificData(0x004C)) ?: return
        val m = AdvertMatch.match(r.device.address, ad, identity)
        if (m == MatchLevel.NO) return
        if (m == MatchLevel.MODEL_ONLY) sameModel[r.device.address] = now
        if (m.ordinal < matchLevel.ordinal || matchLevel == MatchLevel.NO) matchLevel = m
        // A bud that is out of the case broadcasts for itself; the case side covers the buds inside it.
        val key = if (ad.thisInCase || ad.bothInCase) "case" else "bud"
        val label = if (key == "case") "Case" else "Bud out of the case"
        feed(key, label, r.rssi, now, listOf(ad.budsText(), ad.batteryText()).filter { it.isNotEmpty() }.joinToString(" · "))
        if (m == MatchLevel.EXACT || m == MatchLevel.LIKELY)
            Tracker.onAdvert(applicationContext, r.device.address, r.rssi, ad)
    }

    private fun feed(key: String, label: String, rssi: Int, now: Long, detail: String) {
        val t = tracks.getOrPut(key) { Track(label) }
        if (now - t.seen > 15_000) t.kalman.reset()        // gone a while: start fresh, don't drag old values
        t.kalman.update(rssi.toDouble()); t.seen = now; t.raw = rssi; t.detail = detail
    }

    /** Redraw ~4 times a second; buzz on the strongest fresh signal. */
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val fresh = tracks.values.filter { now - it.seen < 6000 && it.kalman.value != null }
            val best = fresh.maxByOrNull { it.kalman.value!! }
            if (best == null) {
                ring.set(null, if (tracks.isEmpty()) "Searching..." else "Lost the signal", "")
            } else {
                val v = best.kalman.value!!
                ring.set(v, Proximity.heat(v).label, Proximity.distanceText(v))
                if (vibrate.isChecked && now - lastBuzz >= Proximity.buzzIntervalMs(v)) { lastBuzz = now; buzz() }
            }
            parts.text = tracks.values.joinToString("\n\n") { t ->
                val age = (now - t.seen) / 1000
                val v = t.kalman.value
                "${t.label}: " + (if (v == null || age > 6) "not heard for ${age}s"
                    else "${Proximity.heat(v).label}, ${Proximity.distanceText(v)} (${t.raw} dBm)") +
                    (if (t.detail.isNotEmpty()) "\n${t.detail}" else "")
            }
            sameModel.entries.removeAll { now - it.value > 10_000 }
            matchNote.text = when {
                device == Tracker.CRUSHER -> "Matched by the Crusher's name or Bluetooth address."
                matchLevel == MatchLevel.EXACT -> "Matched by your AirPods' key: these are definitely yours."
                matchLevel == MatchLevel.LIKELY -> "Probably yours: same model and colour, and battery levels like yours. " +
                    "Identical AirPods nearby could be mistaken for them."
                matchLevel == MatchLevel.MODEL_ONLY && identity.model == null -> "Any AirPods nearby count: the app " +
                    "hasn't learned which are yours yet. Put your AirPods on this phone once (with the widget on the home screen)."
                matchLevel == MatchLevel.MODEL_ONLY -> "Same model as yours, but the app can't be sure they are yours" +
                    (if (sameModel.size > 1) " (${sameModel.size} sets of this model nearby)" else "") +
                    ". Put your AirPods on this phone once so it can learn their key."
                else -> ""
            }
            ui.postDelayed(this, 250)
        }
    }

    private fun buzz() {
        val vib = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching { vib.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}

/** The big ring: fills and warms up as the signal gets stronger. */
class HeatRingView(context: Context) : View(context) {
    private var rssi: Double? = null
    private var big = "Searching..."
    private var small = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor("#243042") }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val smallP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8AA6B4"); textAlign = Paint.Align.CENTER }

    fun set(rssi: Double?, big: String, small: String) {
        this.rssi = rssi; this.big = big; this.small = small; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val size = minOf(width, height).toFloat()
        val stroke = size * 0.09f
        val r = RectF((width - size) / 2 + stroke, (height - size) / 2 + stroke, (width + size) / 2 - stroke, (height + size) / 2 - stroke)
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        c.drawArc(r, 0f, 360f, false, track)
        rssi?.let { v ->
            arc.color = when (Proximity.heat(v)) {
                Heat.HOT -> Color.parseColor("#FF5A3C"); Heat.WARM -> Color.parseColor("#FFB13C"); Heat.COLD -> Color.parseColor("#4FA3FF")
            }
            c.drawArc(r, -90f, (Proximity.fraction(v) * 360).toFloat().coerceAtLeast(6f), false, arc)
        }
        bigP.textSize = size * 0.16f; smallP.textSize = size * 0.065f
        c.drawText(big, width / 2f, height / 2f, bigP)
        if (small.isNotEmpty()) c.drawText(small, width / 2f, height / 2f + size * 0.11f, smallP)
    }
}
