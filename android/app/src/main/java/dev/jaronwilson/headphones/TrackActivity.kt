package dev.jaronwilson.headphones

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.concurrent.thread

/**
 * Last seen: where and when the AirPods / Crusher left this phone, on an OpenStreetMap map (no key, no
 * account), the history, a Navigate button, the hotter/colder finder, and Clear history.
 * Everything shown here is stored on this phone only.
 */
class TrackActivity : Activity() {
    private var device = Tracker.AIRPODS
    private lateinit var map: MapView
    private lateinit var mapBox: LinearLayout
    private lateinit var info: TextView
    private lateinit var laptop: TextView
    private lateinit var history: LinearLayout
    private lateinit var navBtn: Button
    private lateinit var locNote: TextView
    private lateinit var bgBtn: Button
    private lateinit var fgBtn: Button
    private lateinit var keyNote: TextView
    private val tabs = LinkedHashMap<String, Button>()
    private var selected: SeenEvent? = null
    private var marker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid: tiles cached in the app's private storage (viewed areas stay available offline)
        Configuration.getInstance().apply {
            load(this@TrackActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osm-tiles")
            tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 160L * 1024 * 1024
            expirationOverrideDuration = 30L * 24 * 3600 * 1000     // keep cached tiles a month: fewer downloads
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        refresh()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(t: String, size: Float = 13f, color: String = "#8AA6B4") = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, dp(6), 0, dp(6))
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1A2230")); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#2B3647"))
        }
    }
    private fun lp(top: Int = 10) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(22))
            setBackgroundColor(Color.parseColor("#0F1419"))
        }
        col.addView(text("Last seen", 24f, "#E8EDF2"))
        val tabRow = LinearLayout(this)
        for (d in Tracker.DEVICES) {
            tabs[d] = btn(Tracker.label(d)) { device = d; selected = null; refresh() }
                .also { tabRow.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }
        col.addView(tabRow)

        val top = card()
        info = text("", 15f, "#E8EDF2")
        top.addView(info)
        mapBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            controller.setZoom(17.0)
            overlays.add(CopyrightOverlay(this@TrackActivity))
            // let the map pan inside the scrolling page
            setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
        }
        mapBox.addView(map, LinearLayout.LayoutParams(MATCH_PARENT, dp(300)))
        mapBox.addView(text("Map © OpenStreetMap contributors. Areas you have viewed are kept on the phone for offline use.", 11f))
        top.addView(mapBox)
        val actions = LinearLayout(this)
        navBtn = btn("Navigate") { navigate() }
        actions.addView(navBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(btn("Find (hotter / colder)") {
            startActivity(Intent(this, FinderActivity::class.java).putExtra("device", device))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f))
        top.addView(actions)
        laptop = text("", 12f)
        top.addView(laptop)
        col.addView(top, lp(8))

        col.addView(text("History (newest first, up to 50 per device)", 15f, "#E8EDF2"))
        history = card()
        col.addView(history, lp(0))

        // ---- settings and permissions ----
        col.addView(text("Settings", 15f, "#E8EDF2"))
        val set = card()
        set.addView(Switch(this).apply {
            text = "Save Last seen events"; textSize = 15f; setTextColor(Color.parseColor("#E8EDF2"))
            isChecked = Tracker.enabled(this@TrackActivity)
            setOnCheckedChangeListener { _, on -> Tracker.setEnabled(this@TrackActivity, on) }
        })
        set.addView(text("Everything stays on this phone: no Apple or Google finding network, no account, no upload. " +
            "Map tiles are the only thing downloaded (from OpenStreetMap), and only for the area you look at."))
        locNote = text("")
        set.addView(locNote)
        fgBtn = btn("Use location for Last seen") { askLocation() }
        set.addView(fgBtn)
        bgBtn = btn("Also allow it when the app is closed (optional)") { askBackground() }
        set.addView(bgBtn)
        keyNote = text("")
        set.addView(keyNote)
        set.addView(btn("Forget the AirPods' key") {
            Tracker.forgetKey(this); refresh(); toast("Key removed. It is asked for again while the AirPods are on this phone.")
        })
        set.addView(btn("Clear history") { confirmClear() })
        col.addView(set, lp(0))

        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#0F1419")) })
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    private fun refresh() {
        for ((d, b) in tabs) b.alpha = if (d == device) 1f else 0.55f
        val events = Tracker.events(this, device)
        val sel = selected?.takeIf { it in events } ?: events.firstOrNull { it.hasLocation } ?: events.firstOrNull()
        selected = sel
        info.text = when {
            Tracker.connected(this, device) -> "${Tracker.label(device)}: connected to this phone now." +
                (events.firstOrNull()?.let { "\nBefore that: ${Tracker.kindText(it).lowercase()} ${Tracker.timeText(it.t)}" } ?: "")
            sel == null -> "Nothing saved yet. Events are saved when your ${Tracker.label(device)} disconnect from this phone " +
                "or are heard nearby."
            else -> describe(sel)
        }
        showOnMap(sel)
        history.removeAllViews()
        if (events.isEmpty()) history.addView(text("No history."))
        for (e in events) {
            history.addView(TextView(this).apply {
                text = "${Tracker.timeText(e.t)}  ·  ${Tracker.kindText(e)}" + (if (e.hasLocation) "  ·  map" else "") +
                    (if (e.battery.isNotEmpty()) "\n${e.battery}" else "")
                textSize = 13f
                setTextColor(Color.parseColor(if (e == sel) "#5FD0FF" else "#E8EDF2"))
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { selected = e; refresh() }
            })
        }
        // permissions and key
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val loc = Tracker.hasLocationPermission(this)
        val bg = Tracker.hasBackgroundLocation(this)
        locNote.text = when {
            !loc -> "Location: off. Only the time and battery are saved."
            bg -> "Location: allowed all the time. The spot is saved whenever they disconnect."
            else -> "Location: allowed while using the app${if (fine) "" else " (approximate)"}. " +
                "Android may not give a location right after a disconnect while the app is closed; " +
                "then only the time and battery are saved."
        }
        fgBtn.visibility = if (fine) View.GONE else View.VISIBLE
        bgBtn.visibility = if (loc && !bg) View.VISIBLE else View.GONE
        keyNote.text = if (Tracker.hasKey(this))
            "AirPods key: saved. The finder recognises your AirPods exactly, even though their Bluetooth address changes."
        else "AirPods key: not yet. It is read from the AirPods over this phone's own connection the next time they are " +
            "on this phone and the widget refreshes. Until then the finder guesses by model and battery."
        loadLaptop()
    }

    private fun describe(e: SeenEvent): String {
        val parts = mutableListOf("${Tracker.kindText(e)} · ${Tracker.timeText(e.t)}")
        if (e.battery.isNotEmpty()) parts += e.battery
        if (e.buds.isNotEmpty()) parts += e.buds.replaceFirstChar { it.uppercase() }
        if (e.rssi != null) parts += "Signal ${e.rssi} dBm"
        when {
            e.hasLocation -> parts += "Within about ${(e.acc ?: 0f).toInt().coerceAtLeast(5)} m of the pin"
            e.note.contains("unavailable") -> parts += "No location: Android didn't give one at that moment"
        }
        return parts.joinToString("\n")
    }

    private fun showOnMap(e: SeenEvent?) {
        val withLoc = e?.takeIf { it.hasLocation }
        mapBox.visibility = if (withLoc != null) View.VISIBLE else View.GONE
        navBtn.isEnabled = withLoc != null
        if (withLoc == null) return
        val pt = GeoPoint(withLoc.lat!!, withLoc.lon!!)
        marker?.let { map.overlays.remove(it) }
        marker = Marker(map).apply {
            position = pt
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "${Tracker.label(withLoc.device)}: ${Tracker.kindText(withLoc).lowercase()}"
            snippet = Tracker.timeText(withLoc.t)
        }
        map.overlays.add(marker)
        map.controller.setCenter(pt)
        map.invalidate()
    }

    private fun navigate() {
        val e = selected?.takeIf { it.hasLocation } ?: return
        val label = Uri.encode("${Tracker.label(e.device)} ${Tracker.kindText(e).lowercase()} ${Tracker.timeText(e.t)}")
        val uri = Uri.parse("geo:${e.lat},${e.lon}?q=${e.lat},${e.lon}($label)")
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { toast("No maps app found") }
    }

    /** The laptop panel's own log ("last connected to laptop at ..."), only if it is set up and reachable. */
    private fun loadLaptop() {
        if (!Config.hasPanel(this)) { laptop.visibility = View.GONE; return }
        laptop.visibility = View.VISIBLE
        laptop.text = "Laptop: checking..."
        val d = device
        val app = applicationContext
        thread {
            val txt = runCatching {
                val j = JSONObject(HeadphonesWidget.get(app, "/api/track")).optJSONObject(d) ?: error("no data")
                when {
                    j.optBoolean("connected") -> "Connected to the laptop now."
                    j.optDouble("last_connected", 0.0) > 0 ->
                        "Last connected to the laptop at ${Tracker.timeText((j.optDouble("last_connected") * 1000).toLong())}."
                    else -> "Not seen on the laptop yet."
                }
            }.getOrElse { "Laptop not reachable (Tailscale off or laptop asleep)." }
            runOnUiThread { if (device == d) laptop.text = txt }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear history?")
            .setMessage("Deletes every Last seen event for both devices from this phone" +
                (if (Config.hasPanel(this)) ", and asks the laptop panel to clear its log too." else "."))
            .setPositiveButton("Clear") { _, _ ->
                Tracker.clear(this)
                selected = null
                HeadphonesWidget.redraw(this)
                if (Config.hasPanel(this)) { val app = applicationContext; thread { runCatching { HeadphonesWidget.post(app, "/api/track/clear", "") } } }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- permissions, explained plainly ----
    private fun askLocation() {
        AlertDialog.Builder(this)
            .setTitle("Location for Last seen")
            .setMessage("When your headphones disconnect from this phone, the app saves where the phone was, so you " +
                "can see on a map where you probably left them. The location is stored only on this phone, never sent " +
                "anywhere, and you can clear it any time. Without it, only the time and battery are saved.")
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun askBackground() {
        AlertDialog.Builder(this)
            .setTitle("Location when the app is closed")
            .setMessage("Headphones usually disconnect while this app is closed. Android only lets an app read the " +
                "location at that moment if you pick \"Allow all the time\" on the next screen. The app asks for one " +
                "location fix per disconnect (no tracking in between) and keeps it on this phone.")
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        refresh()
    }

    private companion object {
        const val REQ_LOC = 11
        const val REQ_BG = 12
    }
}
