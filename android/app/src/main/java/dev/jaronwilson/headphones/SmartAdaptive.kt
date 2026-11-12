package dev.jaronwilson.headphones

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * "Smart adaptive": switch the headphones' noise mode from what the phone's motion sensors say you are
 * doing. Phone sensors only: no microphone, no headset mic, no SCO / call mode.
 *
 * Why the Activity Recognition Transition API (Google Play services): Android has no public offline
 * "are you walking" API, and polling accelerometer data ourselves would cost battery and need a
 * foreground service. The Transition API is the system-wide, already-running, low-power recogniser; it
 * wakes a manifest receiver only when the activity changes (no polling, no service, no notification).
 * Without Play services the request fails and the settings screen says so.
 *
 * The decision itself (20 s hold, one switch per 30 s) is [SmartMachine]; this object feeds it events,
 * persists its state across process death, sets a (battery-friendly, inexact) alarm for the hold timer,
 * and sends the result to the Crusher or AirPods.
 *
 * Permissions: ACTIVITY_RECOGNITION only. The optional "call -> transparency" sub-option listens for the
 * phone-call state broadcast and needs READ_PHONE_STATE, which is asked for only when you turn that
 * sub-option on. It sees ordinary phone calls, not app calls.
 */
object SmartAdaptive {
    const val CRUSHER = "crusher"
    const val AIRPODS = "airpods"
    val DEVICES = listOf(CRUSHER, AIRPODS)
    val ACTS = Act.values().toList()

    const val ACTION_TRANSITION = "dev.jaronwilson.headphones.SMART_TRANSITION"
    const val ACTION_TICK = "dev.jaronwilson.headphones.SMART_TICK"

    private fun p(c: Context) = c.getSharedPreferences("smart", Context.MODE_PRIVATE)

    // ---- the user's choices (everything off by default) ----
    fun enabled(c: Context, dev: String) = p(c).getBoolean("on_$dev", false)
    fun setEnabled(c: Context, dev: String, on: Boolean) { p(c).edit().putBoolean("on_$dev", on).apply() }
    fun callAware(c: Context) = p(c).getBoolean("call_aware", false)
    fun setCallAware(c: Context, on: Boolean) { p(c).edit().putBoolean("call_aware", on).apply() }
    fun anyEnabled(c: Context) = DEVICES.any { enabled(c, it) }

    fun defaultChoice(a: Act) = when (a) {
        Act.STILL, Act.IN_VEHICLE -> "anc"
        else -> "transparency"
    }
    fun choice(c: Context, dev: String, a: Act): String = p(c).getString("map_${dev}_${a.name}", null) ?: defaultChoice(a)
    fun setChoice(c: Context, dev: String, a: Act, v: String) { p(c).edit().putString("map_${dev}_${a.name}", v).apply() }

    /** The actions a device can be given: value to label. AirPods can also use Adaptive with a level. */
    fun options(dev: String): List<Pair<String, String>> =
        listOf("anc" to "Noise cancelling", "transparency" to "Transparency", "off" to "Off") +
            (if (dev == AIRPODS) listOf("adaptive:100" to "Adaptive, block the most", "adaptive:50" to "Adaptive, balanced",
                "adaptive:0" to "Adaptive, hear the most") else emptyList())

    fun describe(dev: String, v: String) = options(dev).firstOrNull { it.first == v }?.second?.lowercase() ?: v

    fun status(c: Context, dev: String): String = p(c).getString("status_$dev", "") ?: ""
    fun registration(c: Context): String = p(c).getString("registration", "") ?: ""

    // ---- machine state, kept across process death ----
    private fun machine(c: Context, dev: String) = SmartMachine({ SystemClock.elapsedRealtime() },
        { choice(c, dev, it) }, state = load(c, dev))

    private fun load(c: Context, dev: String): SmartMachine.State = runCatching {
        val j = JSONObject(p(c).getString("state_$dev", "{}")!!)
        SmartMachine.State(
            activity = j.optString("a").takeIf { it.isNotEmpty() }?.let { Act.valueOf(it) },
            since = j.optLong("s"), call = j.optBoolean("c"), callSince = j.optLong("cs"),
            lastSwitch = if (j.has("l")) j.getLong("l") else null,
            applied = j.optString("p").takeIf { it.isNotEmpty() })
    }.getOrDefault(SmartMachine.State())

    private fun save(c: Context, dev: String, m: SmartMachine) {
        val s = m.state
        val j = JSONObject().put("a", s.activity?.name ?: "").put("s", s.since).put("c", s.call).put("cs", s.callSince)
            .put("p", s.applied ?: "")
        s.lastSwitch?.let { j.put("l", it) }
        p(c).edit().putString("state_$dev", j.toString()).putString("status_$dev", m.status { describe(dev, it) }).apply()
    }

    // ---- registration with the system ----
    fun hasActivityPermission(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasPhonePermission(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun transitionIntent(c: Context) = PendingIntent.getBroadcast(c, 10,
        Intent(c, SmartReceiver::class.java).setAction(ACTION_TRANSITION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // Play services fills in the result

    /** Start or stop listening to match the switches and the permission. Call on app open, on boot and after a toggle. */
    fun sync(c: Context) {
        val app = c.applicationContext
        val client = ActivityRecognition.getClient(app)
        if (anyEnabled(app) && hasActivityPermission(app)) {
            val types = listOf(DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING,
                DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE)
            val req = ActivityTransitionRequest(types.map {
                ActivityTransition.Builder().setActivityType(it)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
            })
            runCatching {
                client.requestActivityTransitionUpdates(req, transitionIntent(app))
                    .addOnSuccessListener { p(app).edit().putString("registration", "listening").apply() }
                    .addOnFailureListener { p(app).edit().putString("registration",
                        "Activity updates unavailable (needs Google Play services): ${it.message}").apply() }
            }.onFailure { p(app).edit().putString("registration", "Activity updates unavailable: ${it.message}").apply() }
        } else {
            runCatching { client.removeActivityTransitionUpdates(transitionIntent(app)) }
            cancelTick(app)
            p(app).edit().putString("registration", if (anyEnabled(app)) "Allow \"Physical activity\" to start" else "").apply()
        }
    }

    // ---- events ----
    private fun actOf(type: Int): Act? = when (type) {
        DetectedActivity.STILL -> Act.STILL
        DetectedActivity.WALKING -> Act.WALKING
        DetectedActivity.RUNNING -> Act.RUNNING
        DetectedActivity.ON_BICYCLE -> Act.ON_BICYCLE
        DetectedActivity.IN_VEHICLE -> Act.IN_VEHICLE
        else -> null
    }

    fun onTransitions(c: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val latest = ActivityTransitionResult.extractResult(intent)?.transitionEvents
            ?.filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            ?.maxByOrNull { it.elapsedRealTimeNanos } ?: return
        val act = actOf(latest.activityType) ?: return
        for (dev in DEVICES.filter { enabled(c, it) }) machine(c, dev).also { it.onActivity(act); save(c, dev, it) }
        process(c)
    }

    fun onCall(c: Context, active: Boolean) {
        if (!callAware(c)) return
        for (dev in DEVICES.filter { enabled(c, it) }) machine(c, dev).also { it.onCall(active); save(c, dev, it) }
        process(c)
    }

    /** Act on whatever is due and schedule the next check. Blocking (Bluetooth), so run off the main thread. */
    fun process(c: Context) {
        var next: Long? = null
        for (dev in DEVICES.filter { enabled(c, it) }) {
            val m = machine(c, dev)
            val ready = if (dev == CRUSHER) CrusherDirect.connected(c)
                else Handoff.local(c) && PhoneDirect.status(c) != "fail"
            if (ready) {
                val before = m.state
                m.poll()?.let { action ->
                    runCatching { send(c, dev, action) }.onFailure { m.state = before }   // retry at the next tick
                }
            }
            save(c, dev, m)
            m.nextCheckInMs()?.let { next = minOf(next ?: Long.MAX_VALUE, if (!ready || it == 0L) 15_000L else it) }
        }
        next?.let { scheduleTick(c, it) } ?: cancelTick(c)
    }

    private fun send(c: Context, dev: String, action: String) {
        if (dev == CRUSHER) CrusherDirect.setMode(c, action)
        else if (action.startsWith("adaptive:")) PhoneDirect.setAdaptive(c, action.substringAfter(':').toInt())
        else PhoneDirect.setMode(c, action)
    }

    private fun tickIntent(c: Context) = PendingIntent.getBroadcast(c, 11,
        Intent(c, SmartReceiver::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // inexact, no special permission: a few seconds of slack is fine for a 20 s hold timer
    private fun scheduleTick(c: Context, inMs: Long) = c.getSystemService(AlarmManager::class.java)
        .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + inMs + 500, tickIntent(c))

    private fun cancelTick(c: Context) = c.getSystemService(AlarmManager::class.java).cancel(tickIntent(c))
}

/** Activity transitions, the hold-timer alarm, boot and (opt-in) phone-call state all land here. */
class SmartReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        val app = c.applicationContext
        thread {
            try {
                when (i.action) {
                    SmartAdaptive.ACTION_TRANSITION -> SmartAdaptive.onTransitions(app, i)
                    SmartAdaptive.ACTION_TICK -> SmartAdaptive.process(app)
                    Intent.ACTION_BOOT_COMPLETED -> SmartAdaptive.sync(app)
                    TelephonyManager.ACTION_PHONE_STATE_CHANGED -> SmartAdaptive.onCall(app,
                        i.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_OFFHOOK)
                }
            } finally { pending.finish() }
        }
    }
}
