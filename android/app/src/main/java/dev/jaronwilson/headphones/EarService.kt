package dev.jaronwilson.headphones

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList

/** The ear-detection switches (Settings, "Ear detection"). */
object EarPrefs {
    private fun p(c: Context) = c.getSharedPreferences("ears", Context.MODE_PRIVATE)

    /** name -> (title, default) */
    val SWITCHES = linkedMapOf(
        "on" to ("Ear detection features (keeps one quiet link to the AirPods while they're on this phone)" to true),
        "pause" to ("Pause media when you take an AirPod out" to true),
        "resume" to ("Resume when you put it back (within 2 minutes, only if this app paused it)" to true),
        "listen_stop" to ("Stop listening / filtered hearing when no AirPod is in an ear" to true),
        "listen_restart" to ("Start filtered hearing again when one goes back in (within 2 minutes)" to false),
    )

    fun get(c: Context, k: String) = p(c).getBoolean(k, SWITCHES[k]?.second ?: false)
    fun set(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    fun enabled(c: Context) = get(c, "on")

    /** The last BLE advert's "primary is left" bit for your AirPods, and when (see [SideTracker]). */
    fun rememberAdvertSide(c: Context, primaryLeft: Boolean) =
        p(c).edit().putBoolean("ble_pl", primaryLeft).putLong("ble_pl_t", System.currentTimeMillis()).apply()

    fun advertSide(c: Context, maxAgeMs: Long = 10 * 60_000): Boolean? {
        val t = p(c).getLong("ble_pl_t", 0)
        return if (System.currentTimeMillis() - t <= maxAgeMs) p(c).getBoolean("ble_pl", true) else null
    }
}

/**
 * What the app knows right now about the buds, for the whole app: the last raw ear report, which bud is
 * left ([SideTracker]), and who wants to hear about changes (the open screens).
 */
object Ears {
    private const val TAG = "Ears"
    private val side = SideTracker()
    private val watchers = CopyOnWriteArrayList<() -> Unit>()

    fun watch(w: () -> Unit) { watchers += w }
    fun unwatch(w: () -> Unit) { watchers -= w }
    internal fun changed() { for (w in watchers) runCatching { w() } }

    /** Raw report from the link (reader thread). */
    fun onRaw(c: Context, r: EarReport) {
        val pl = synchronized(side) {
            if (side.primaryIsLeft == null) EarPrefs.advertSide(c)?.let { side.onAdvert(it) }
            side.onEar(r, System.currentTimeMillis())
            side.primaryIsLeft
        }
        Log.d(TAG, "ear report: ${r.primary.words} / ${r.secondary.words}, primary is " +
            (when (pl) { true -> "left"; false -> "right"; null -> "not known" }) + " (${side.source})")
        PhoneDirect.rememberEar(c, r.primary.code to r.secondary.code, pl)
    }

    /** A battery report on the link said which bud is primary. */
    fun onBattery(c: Context, primaryLeft: Boolean) {
        val changed = synchronized(side) {
            val was = side.primaryIsLeft
            side.onBattery(primaryLeft)
            was != primaryLeft
        }
        if (!changed) return
        Log.d(TAG, "primary is ${if (primaryLeft) "left" else "right"} (battery report order)")
        // re-label the last report with the sides it really had
        PhoneDirect.ear(c)?.let { (pair, _) ->
            c.getSharedPreferences("direct", Context.MODE_PRIVATE).edit().putInt("ear_pl", if (primaryLeft) 1 else 0).apply()
            Log.d(TAG, "now: ${PhoneDirect.earText(pair, primaryLeft)}")
        }
        changed()
    }

    /** One of the app's adverts scans heard your AirPods. */
    fun onAdvert(c: Context, primaryLeft: Boolean) {
        EarPrefs.rememberAdvertSide(c, primaryLeft)
        synchronized(side) { side.onAdvert(primaryLeft) }
    }

    fun onLinkClosed() = synchronized(side) { side.reset() }

    /** Just the words ("Left: in ear, Right: in case") while the link is live, else "" (for the widget). */
    fun liveText(c: Context): String {
        val (pair, t) = PhoneDirect.ear(c) ?: return ""
        if (!AapLink.isOpen || t < AapLink.openedAt) return ""
        return PhoneDirect.earText(pair, PhoneDirect.earPrimaryLeft(c))
    }

    /** "Left: in ear, Right: in case (live, since 14:02)", or "" if the AirPods never reported. */
    fun text(c: Context): String {
        val (pair, t) = PhoneDirect.ear(c) ?: return ""
        val live = AapLink.isOpen && t >= AapLink.openedAt
        return PhoneDirect.earText(pair, PhoneDirect.earPrimaryLeft(c)) + " " +
            EarText.freshness(t, System.currentTimeMillis(), live) {
                android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(it))
            }
    }
}

/**
 * Ear detection, live: while the AirPods are connected to this phone (and "Ear detection features" is on),
 * holds the app's one AAP link ([AapLink], as "ear") so the AirPods push their in-ear / in-case report the
 * moment it changes. No polling: the service sleeps until a packet comes. On a change (held 400 ms, so a
 * brush against the ear doesn't count) it pauses or resumes media and stops filtered hearing, as the
 * switches say. Started by [BtReceiver] on connect, stopped on disconnect.
 */
class EarService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val debounce = Debouncer<EarReport>(DEBOUNCE_MS)
    private val pause = AutoPause()
    private val guard = ListenGuard()
    @Volatile private var linked = false
    /** This instance's name on the shared link (a new instance may join before an old one has left). */
    private val who = "ear#" + Integer.toHexString(System.identityHashCode(this))
    private var tries = 0
    private var connecting = false

    private val rx = object : AapLink.Listener {
        override fun onPacket(p: ByteArray, n: Int) {
            if (AapMic.isAudio(p, n)) return
            val r = EarReport.decode(p, n) ?: return
            main.post { onRaw(r) }
        }
        override fun onClosed() { main.post { linkLost() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CH, "Ear detection", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false)
            description = "Shown while the app listens for the AirPods' in-ear reports. Silent."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        startFg("Connecting to the AirPods...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(Ears.text(this).ifEmpty { "Connecting to the AirPods..." })
        if (intent?.action == ACTION_STOP || !wanted(this)) { stopSelf(); return START_NOT_STICKY }
        if (!linked) { tries = 0; connect() }
        return START_STICKY                    // brought back (and reconnected) if the system kills the app
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        val wasLinked = linked
        linked = false
        Thread {
            if (wasLinked || who in AapLink.holders()) synchronized(PhoneDirect.aapLock) { AapLink.release(who, rx) }
        }.start()
        pause.reset(); guard.reset(); debounce.reset()
        super.onDestroy()
    }

    private fun connect() {
        if (connecting) return
        connecting = true
        Thread {
            val r = runCatching { AapLink.acquire(applicationContext, who, rx) }
            main.post {
                connecting = false
                if (r.isSuccess) {
                    linked = true; tries = 0
                    Log.d(TAG, "holding the AAP link for ear detection")
                    startFg(Ears.text(this).ifEmpty { "Waiting for the AirPods' report" })
                } else {
                    Log.w(TAG, "couldn't open the AAP link: ${r.exceptionOrNull()?.message}")
                    retry()
                }
            }
        }.start()
    }

    /** Back off: 5 s, 20 s, 60 s, then give up until the next connect. */
    private fun retry() {
        if (!wanted(this)) { stopSelf(); return }
        val wait = RETRY_MS.getOrNull(tries++) ?: run {
            Log.d(TAG, "giving up until the AirPods connect again")
            stopSelf(); return
        }
        startFg("Can't reach the AirPods' control channel, trying again")
        main.postDelayed({ if (!linked) connect() }, wait)
    }

    private fun linkLost() {
        linked = false
        debounce.reset(); pause.reset(); guard.reset()
        Ears.changed()
        Log.d(TAG, "link lost")
        retry()
    }

    private fun onRaw(r: EarReport) {
        val now = System.currentTimeMillis()
        val due = debounce.offer(r, now) ?: return
        main.postDelayed({ settle() }, (due - now).coerceAtLeast(0) + 5)
    }

    private fun settle() {
        val r = debounce.settle(System.currentTimeMillis()) ?: return
        act(r)
        startFg(Ears.text(this))
        Ears.changed()
        runCatching { HeadphonesWidget.redraw(this) }
    }

    private fun act(r: EarReport) {
        val now = System.currentTimeMillis()
        val am = getSystemService(AudioManager::class.java)
        val inCall = am.mode in CALL_MODES
        // Filtered hearing plays sound itself, so "music is active" can't tell your media from it: no auto-pause then.
        val playing = !inCall && am.isMusicActive && !FilteredHearing.on
        when (pause.onEars(r, playing, now, EarPrefs.get(this, "pause"), EarPrefs.get(this, "resume") && !inCall)) {
            AutoPause.Act.PAUSE -> { Log.d(TAG, "a bud came out: pausing media"); mediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE) }
            AutoPause.Act.RESUME -> { Log.d(TAG, "back in: resuming what this app paused"); mediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY) }
            AutoPause.Act.NONE -> {}
        }
        when (guard.onEars(r, FilteredHearing.mic != null, FilteredHearing.on, now,
                EarPrefs.get(this, "listen_stop"), EarPrefs.get(this, "listen_restart"))) {
            ListenGuard.Act.STOP -> { Log.d(TAG, "no bud in an ear: stopping listening"); FilteredHearing.stopMic("Stopped: no AirPod in an ear") }
            ListenGuard.Act.RESTART -> { Log.d(TAG, "a bud is back: starting listening again"); FilteredHearing.restart(this, guard.stoppedFiltered) }
            ListenGuard.Act.NONE -> {}
        }
    }

    /** The same key a headset button sends, to whichever app has the media session. */
    private fun mediaKey(am: AudioManager, code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun startFg(text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("AirPods: ${text.ifEmpty { "waiting for the ear report" }}")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTE_ID, n)
    }

    companion object {
        private const val TAG = "EarService"
        private const val CH = "ear_detection"
        private const val NOTE_ID = 9
        const val DEBOUNCE_MS = 400L
        private val RETRY_MS = longArrayOf(5_000, 20_000, 60_000)
        private const val ACTION_STOP = "dev.jaronwilson.headphones.EAR_STOP"
        private val CALL_MODES = setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING, 5 /* MODE_CALL_REDIRECT */, 6 /* MODE_COMMUNICATION_REDIRECT */)

        private fun wanted(c: Context) = EarPrefs.enabled(c) && Handoff.local(c) && Config.hasAirPods(c)

        /** Start or stop to match the switch and whether the AirPods are on this phone. */
        fun sync(c: Context) {
            val i = Intent(c, EarService::class.java)
            if (wanted(c)) runCatching { c.startForegroundService(i) }.onFailure { Log.w(TAG, "start", it) }
            else runCatching { c.stopService(i) }
        }
    }
}
