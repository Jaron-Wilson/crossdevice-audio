package dev.jaronwilson.headphones

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Home-screen widget. When the AirPods are on this phone it talks to them directly (PhoneDirect);
 * otherwise, if a laptop panel is set up (panel.py over Tailscale), it controls them through that.
 * A device's row is shown only while that device is connected, so the widget never offers a
 * control nothing can do (no bass for AirPods, no adaptive for the Crusher).
 */
class HeadphonesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        scheduleRefresh(context)
        val pending = goAsync()
        thread {
            try { refresh(context) } finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
        // Show the choice at once (optimistic), then confirm with the panel's real state.
        render(context, optimistic(last, cmd), if (cmd == "refresh") "Refreshing…" else null)
        val pending = goAsync()
        thread {
            try {
                val err = if (cmd == "refresh") null else runCatching { send(context, cmd) }.exceptionOrNull()
                if (cmd == "refresh" || err == null) refresh(context)
                else render(context, withPhoneBattery(context, fetch(context)), failText(context, cmd, err))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION = "dev.jaronwilson.headphones.CMD"
        private const val EXTRA_CMD = "cmd"

        @Volatile private var last: JSONObject? = null

        fun post(context: Context, path: String, body: String) = http(context, "POST", path, body)

        /** Fetch and redraw; if the laptop disagrees about who has the AirPods, correct it first. */
        fun refresh(context: Context) {
            var state = fetch(context)
            val canSee = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val laptopThinks = state?.optJSONObject("airpods")?.optBoolean("on_phone")
            if (state != null && canSee && laptopThinks != null && laptopThinks != Handoff.local(context)) {
                if (Handoff.tell(context, Handoff.local(context))) state = fetch(context)
            }
            render(context, withPhoneBattery(context, state), null)
        }

        /** Refresh about every 5 minutes, only while the phone is awake (no wakeups, no battery cost). */
        private fun scheduleRefresh(context: Context) {
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 300_000, 300_000,
                cmdIntent(context, "refresh", 1))
        }

        /** The state as it will be once [cmd] lands, so the tap shows instantly. */
        private fun optimistic(state: JSONObject?, cmd: String): JSONObject? {
            if (state == null && !cmd.startsWith("ap:")) return null
            val s = state?.let { JSONObject(it.toString()) } ?: JSONObject().put("airpods", JSONObject())
            val (dev, arg) = cmd.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (dev) {
                "ap" -> s.optJSONObject("airpods")?.put("mode", arg)
                "cr" -> s.optJSONObject("crusher")?.let { c ->
                    when (arg) {
                        "anc" -> c.put("anc", true).put("transparency", false)
                        "transparency" -> c.put("anc", false).put("transparency", true)
                        "off" -> c.put("anc", false).put("transparency", false)
                        "bass+" -> c.put("bass", minOf(10, c.optInt("bass", 0) + 2))
                        "bass-" -> c.put("bass", maxOf(0, c.optInt("bass", 0) - 2))
                    }
                }
            }
            return s
        }

        private fun batteryText(ap: JSONObject?): String {
            val b = ap?.optJSONObject("battery") ?: return ""
            return listOf("left" to "L", "right" to "R", "case" to "Case").mapNotNull { (k, short) ->
                b.optJSONObject(k)?.let { e ->
                    "$short ${e.optInt("level")}%" + if (e.optString("status") == "charging") "⚡" else ""
                }
            }.joinToString(" · ")
        }

        private fun http(context: Context, method: String, path: String, body: String? = null): String {
            if (!Config.hasPanel(context)) throw IllegalStateException("No laptop panel set")
            val c = URL(Config.panelUrl(context) + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 5000
            c.readTimeout = 10000
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            try {
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                if (code >= 400) throw RuntimeException(JSONObject(text.ifEmpty { "{}" }).optString("error", "HTTP $code"))
                return text
            } finally {
                c.disconnect()
            }
        }

        /** Panel state, or null when the laptop can't be reached (Tailscale off, laptop asleep). */
        private fun fetch(context: Context): JSONObject? =
            if (!Config.hasPanel(context)) null
            else runCatching { JSONObject(http(context, "GET", "/api/state")) }.getOrNull().also { if (it != null) last = it }

        private fun failText(context: Context, cmd: String, err: Throwable): String =
            if (cmd.startsWith("ap:") && Handoff.local(context))
                "This phone's Bluetooth won't allow it. Hold the AirPods stem instead."
            else "Failed: ${err.message ?: err.javaClass.simpleName}"

        /** When the phone has the AirPods, read mode + battery here (no laptop, no Tailscale). */
        private fun withPhoneBattery(context: Context, state: JSONObject?): JSONObject? {
            if (!Handoff.local(context)) return state
            val s = state?.let { JSONObject(it.toString()) } ?: JSONObject()
            val ap = s.optJSONObject("airpods") ?: JSONObject().also { s.put("airpods", it) }
            PhoneDirect.mode(context)?.let { ap.put("mode", it) }
            if ((ap.optJSONObject("battery")?.length() ?: 0) == 0)
                PhoneDirect.scanBattery(context)?.let { ap.put("battery", it) }
            return s
        }

        private fun send(context: Context, cmd: String) {
            val (dev, arg) = cmd.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (dev) {
                // phone has the AirPods: talk to them directly; otherwise via the laptop panel
                "ap" -> if (Handoff.local(context)) PhoneDirect.setMode(context, arg)
                        else http(context, "POST", "/api/airpods/mode", arg)
                "cr" -> when (arg) {
                    "bass+", "bass-" -> {
                        val cur = last?.optJSONObject("crusher")?.optInt("bass", 0) ?: 0
                        val next = if (arg == "bass+") minOf(10, cur + 2) else maxOf(0, cur - 2)
                        http(context, "POST", "/api/crusher/bass", next.toString())
                    }
                    else -> http(context, "POST", "/api/crusher/mode", arg)
                }
            }
        }

        private fun render(context: Context, state: JSONObject?, note: String?) {
            val v = RemoteViews(context.packageName, R.layout.widget)
            val ap = state?.optJSONObject("airpods")
            val cr = state?.optJSONObject("crusher")
            val apOn = ap?.optBoolean("online") == true
            val crOn = cr?.optBoolean("online") == true

            val onPhone = Handoff.local(context)
            val status = when {
                note != null -> note
                onPhone -> "On this phone" + batteryText(ap).let { if (it.isEmpty()) "" else " · $it" } +
                    (if (PhoneDirect.status(context) == "fail") " · hold stem for modes" else "")
                !Config.hasAirPods(context) && !Config.hasPanel(context) -> "Open the Headphones app to set up"
                state == null && !Config.hasPanel(context) -> "AirPods not connected to this phone"
                state == null -> "Laptop not reachable. Is Tailscale on?"
                !apOn && !crOn -> "No headphones connected"
                else -> listOfNotNull("AirPods".takeIf { apOn }, "Crusher".takeIf { crOn }).joinToString(" · ")
            }
            v.setTextViewText(R.id.status, status)
            // On the phone the mode buttons stay while direct control works (or hasn't been tried).
            val showAp = if (onPhone) PhoneDirect.status(context) != "fail" else apOn
            v.setViewVisibility(R.id.ap_row, if (showAp) View.VISIBLE else View.GONE)
            val bat = batteryText(ap)
            v.setTextViewText(R.id.ap_label, if (bat.isEmpty()) "AirPods" else "AirPods   $bat")
            v.setViewVisibility(R.id.cr_row, if (crOn) View.VISIBLE else View.GONE)

            // AirPods: highlight the mode they last reported (unknown while another host plays).
            val apMode = ap?.optString("mode", "") ?: ""
            for ((id, key) in listOf(R.id.ap_off to "off", R.id.ap_anc to "anc",
                    R.id.ap_tr to "transparency", R.id.ap_ad to "adaptive")) {
                v.setInt(id, "setBackgroundResource", if (apMode == key) R.drawable.btn_on else R.drawable.btn)
                v.setOnClickPendingIntent(id, cmdIntent(context, "ap:$key", id))
            }
            // Crusher: mode + bass level.
            val crMode = when {
                cr?.optBoolean("anc") == true -> "anc"
                cr?.optBoolean("transparency") == true -> "transparency"
                crOn -> "off"
                else -> ""
            }
            for ((id, key) in listOf(R.id.cr_anc to "anc", R.id.cr_tr to "transparency", R.id.cr_off to "off")) {
                v.setInt(id, "setBackgroundResource", if (crMode == key) R.drawable.btn_on else R.drawable.btn)
                v.setOnClickPendingIntent(id, cmdIntent(context, "cr:$key", id))
            }
            val bass = cr?.opt("bass")
            v.setTextViewText(R.id.cr_bass, if (bass is Number) "Bass ${bass.toInt()}/10" else "Bass")
            v.setOnClickPendingIntent(R.id.cr_bdown, cmdIntent(context, "cr:bass-", R.id.cr_bdown))
            v.setOnClickPendingIntent(R.id.cr_bup, cmdIntent(context, "cr:bass+", R.id.cr_bup))
            v.setOnClickPendingIntent(R.id.header, cmdIntent(context, "refresh", R.id.header))

            val mgr = AppWidgetManager.getInstance(context)
            mgr.updateAppWidget(ComponentName(context, HeadphonesWidget::class.java), v)
        }

        private fun cmdIntent(context: Context, cmd: String, requestCode: Int): PendingIntent {
            val i = Intent(context, HeadphonesWidget::class.java).setAction(ACTION).putExtra(EXTRA_CMD, cmd)
            return PendingIntent.getBroadcast(context, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
