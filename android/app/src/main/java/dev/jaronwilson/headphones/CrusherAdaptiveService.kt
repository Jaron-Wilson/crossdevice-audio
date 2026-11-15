package dev.jaronwilson.headphones

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Settings, state and start/stop for Crusher Adaptive (the logic is in CrusherAdaptive.kt, the audio and
 * Bluetooth in [CrusherAdaptiveService]). Off by default; the mic is open only while it is on, not paused and
 * the Crusher is connected to this phone.
 */
object CrusherAdaptiveCtl {
    /** Sent by the widget's Adaptive button: opens the app, which turns Adaptive on (Android only lets a visible app start the mic). */
    const val ACTION_ON = "dev.jaronwilson.headphones.CRUSHER_ADAPTIVE_ON"
    const val ACTION_STOP = "dev.jaronwilson.headphones.CRUSHER_ADAPTIVE_STOP"

    private fun p(c: Context) = c.getSharedPreferences("crusher_adaptive", Context.MODE_PRIVATE)

    @Volatile private var cached: Settings? = null

    /** The settings, loaded once; the running service shares this object. */
    @Synchronized fun settings(c: Context): Settings = cached ?: run {
        val m = HashMap<String, Any?>()
        for ((k, v) in p(c).all) m[k] = v
        Settings(m).also { cached = it }
    }

    /** Change settings and keep them. Reaches the running service at once. */
    @Synchronized fun update(c: Context, m: Map<String, Any?>) {
        val s = settings(c)
        val svc = CrusherAdaptiveService.instance
        if (svc != null) svc.core.set(m) else s.update(m)
        val e = p(c).edit()
        for ((k, v) in s.asMap()) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Double -> e.putFloat(k, v.toFloat())
            else -> e.putString(k, v.toString())
        }
        e.apply()
    }

    fun enabled(c: Context) = settings(c).enabled
    fun hasMicPermission(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Turn Adaptive on (also resumes after a pause) or off. On needs the mic permission, asked by the caller first. */
    fun setEnabled(c: Context, on: Boolean) {
        val app = c.applicationContext
        if (on && !hasMicPermission(app)) return
        update(app, mapOf("enabled" to on))
        if (on) {
            // the two would fight over the Crusher's mode
            if (SmartAdaptive.enabled(app, SmartAdaptive.CRUSHER)) { SmartAdaptive.setEnabled(app, SmartAdaptive.CRUSHER, false); SmartAdaptive.sync(app) }
            start(app)
        } else {
            app.startService(Intent(app, CrusherAdaptiveService::class.java).setAction(ACTION_STOP))
        }
    }

    private fun start(app: Context) {
        runCatching { app.startForegroundService(Intent(app, CrusherAdaptiveService::class.java)) }
            .onFailure { status = "Android would not start the microphone service: ${it.message}" }
    }

    /** Start the service if Adaptive is on but it is not running (app opened, after a reboot). */
    fun sync(c: Context) {
        val app = c.applicationContext
        if (enabled(app) && hasMicPermission(app) && CrusherAdaptiveService.instance == null) start(app)
        if (!enabled(app)) CrusherAdaptiveService.instance?.let { setEnabled(app, false) }
    }

    /** A mode was picked by hand (app, widget): Adaptive steps aside for the pause time. */
    fun manual(c: Context, source: String = "changed in the app") {
        CrusherAdaptiveService.instance?.core?.manual(source)
    }

    /** On and not paused: the mode buttons show Adaptive as the choice. */
    fun active(c: Context): Boolean = enabled(c) && CrusherAdaptiveService.instance?.core?.state()?.paused == false

    @Volatile var status = ""            // the live line for the screen and the notification
    @Volatile var state: AdaptiveState? = null

    fun statusLine(c: Context): String = when {
        !enabled(c) -> ""
        CrusherAdaptiveService.instance == null -> status.ifEmpty { "Not running: open the app again to start it" }
        else -> status
    }
}

class CrusherAdaptiveService : Service() {
    companion object {
        @Volatile var instance: CrusherAdaptiveService? = null
        private const val CH = "crusher_adaptive"
        private const val NID = 74
        private const val BLOCK_S = 0.25
        private const val MODE_CHECK_S = 30.0
        private const val CONNECT_CHECK_S = 5.0
        private const val MUSIC_CHECK_S = 1.0
        private const val SCO_WAIT_S = 4.0
        private const val HEADSET_RETRY_S = 120.0
    }

    /** An open AudioRecord and where it listens: the phone's mic or the Crusher's (Bluetooth SCO). */
    private class Capture(val rec: AudioRecord, val headset: Boolean)

    lateinit var core: AdaptiveCore
        private set
    private lateinit var am: AudioManager
    @Volatile private var running = false
    private var worker: Thread? = null
    @Volatile private var capture: Capture? = null
    private var lastNotify = 0.0
    private var lastNotifyText = ""
    private var duckBefore = -1
    private var duckTo = -1

    private fun clock() = SystemClock.elapsedRealtime() / 1000.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        am = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Crusher Adaptive", NotificationManager.IMPORTANCE_LOW))
        val app = applicationContext
        core = AdaptiveCore(CrusherAdaptiveCtl.settings(app),
            send = { cmds -> CrusherDirect.sendCommands(app, cmds) },
            hasLevel = { CrusherDirect.hasLevel(app) },
            duck = { on -> duck(on) },
            clock = { clock() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CrusherAdaptiveCtl.ACTION_STOP) {
            if (CrusherAdaptiveCtl.enabled(this)) CrusherAdaptiveCtl.update(this, mapOf("enabled" to false))
            shutdown(); return START_NOT_STICKY
        }
        if (!CrusherAdaptiveCtl.enabled(this) || !CrusherAdaptiveCtl.hasMicPermission(this)) {
            // a foreground start must still be answered with startForeground
            runCatching { startForegroundNow("Off") }
            shutdown(); return START_NOT_STICKY
        }
        try { startForegroundNow("Starting...") } catch (e: Exception) {
            // e.g. started from the background: Android doesn't allow the mic then
            CrusherAdaptiveCtl.status = "Android would not start the microphone service: ${e.message}"
            shutdown(); return START_NOT_STICKY
        }
        if (!running) {
            running = true
            worker = thread(name = "crusher-adaptive") { loop() }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow(text: String) =
        startForeground(NID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

    private fun shutdown() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        runCatching { worker?.join(1500) }
        closeCapture()
        runCatching { core.unduck() }
        CrusherAdaptiveCtl.status = ""
        CrusherAdaptiveCtl.state = null
        instance = null
        super.onDestroy()
    }

    // ---------------- the loop ----------------

    private fun loop() {
        val app = applicationContext
        var connected = false
        var lastConn = Double.NEGATIVE_INFINITY
        var lastMusic = Double.NEGATIVE_INFINITY
        var musicActive = false
        var idleSince: Double? = null
        var headsetRetryAt = 0.0
        var lastModeCheck = Double.NEGATIVE_INFINITY
        var note = ""
        try {
            while (running) {
                val st = core.settings
                var now = clock()
                if (!st.enabled) break
                if (core.policy.paused(now)) {
                    closeCapture()
                    publish(core.state().line(), now)
                    Thread.sleep(1000); continue
                }
                if (now - lastConn >= CONNECT_CHECK_S) { connected = CrusherDirect.connected(app); lastConn = now }
                if (!connected) {
                    closeCapture()
                    core.stopListening(); core.policy.reset()
                    publish("Waiting for the Crusher to connect", now)
                    Thread.sleep(2000); continue
                }
                if (now - lastMusic >= MUSIC_CHECK_S) {
                    musicActive = am.isMusicActive
                    idleSince = if (musicActive) null else (idleSince ?: now)
                    lastMusic = now
                }
                val wantHeadset = st.headsetMic && !musicActive && idleSince != null &&
                    now - idleSince >= CA.HEADSET_IDLE_S && now >= headsetRetryAt

                // headset mic only while nothing plays; the moment media plays (or the option is off), back to the phone mic
                capture?.let { c ->
                    if (c.headset && (!st.headsetMic || musicActive)) {
                        closeCapture(); core.sourceChanged(); lastModeCheck = now
                        note = ""
                    } else if (!c.headset && wantHeadset) {
                        closeCapture(); core.sourceChanged()
                    }
                }
                if (capture == null) {
                    if (wantHeadset) {
                        capture = openHeadset()
                        if (capture == null) { headsetRetryAt = clock() + HEADSET_RETRY_S; note = "headset mic not available" }
                        else note = ""
                    }
                    if (capture == null) capture = openPhone()
                    if (capture == null) { publish("No microphone available", now); Thread.sleep(5000); continue }
                    core.sourceChanged()
                }
                val cap = capture ?: continue
                val buf = ShortArray((CA.RATE * BLOCK_S).toInt())
                val n = cap.rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    closeCapture(); core.sourceChanged()
                    publish("The microphone stopped", clock()); Thread.sleep(2000); continue
                }
                now = clock()
                core.process(FloatArray(n) { buf[it] / 32768f }, now)

                if (!cap.headset && now - lastModeCheck >= MODE_CHECK_S && core.policy.applied != null) {
                    lastModeCheck = now
                    val m = runCatching { CrusherDirect.modeNow(app) }.getOrNull()
                    core.observe(m, clock())
                }
                val lead = if (core.last == null) "Listening..." else core.state().line()
                val where = if (cap.headset) " (headset mic)" else if (note.isNotEmpty()) " ($note)" else ""
                publish(lead + where + core.error.let { if (it.isEmpty()) "" else " | $it" }, now)
            }
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            CrusherAdaptiveCtl.status = "Stopped: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            closeCapture()
            runCatching { core.stopListening() }
        }
    }

    private fun publish(text: String, now: Double) {
        CrusherAdaptiveCtl.status = text
        CrusherAdaptiveCtl.state = core.state()
        if (text != lastNotifyText && now - lastNotify >= 2.0) {
            lastNotify = now; lastNotifyText = text
            getSystemService(NotificationManager::class.java).notify(NID, notification(text))
        }
    }

    // ---------------- microphones ----------------

    private fun newRecord(source: Int): AudioRecord? {
        if (!CrusherAdaptiveCtl.hasMicPermission(this)) return null
        val min = AudioRecord.getMinBufferSize(CA.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        return runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(CA.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min, CA.RATE * 2))
                .build()
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }

    private fun openPhone(): Capture? {
        val rec = newRecord(MediaRecorder.AudioSource.MIC) ?: return null
        return runCatching { rec.startRecording(); Capture(rec, false) }.getOrElse { rec.release(); null }
    }

    private fun isCrusher(d: AudioDeviceInfo, mac: String?): Boolean =
        (mac != null && d.address.equals(mac, ignoreCase = true)) || (d.productName?.toString() ?: "").contains("crusher", true)

    /**
     * Listen through the Crusher's own mic: it becomes the communication device (Bluetooth SCO / HFP call
     * mode), then a VOICE_COMMUNICATION recording is pinned to its SCO input. Gives up after SCO_WAIT_S.
     */
    private fun openHeadset(): Capture? {
        val mac = CrusherDirect.device(this)?.address
        val out = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && isCrusher(it, mac) }
            ?: return null
        if (!am.setCommunicationDevice(out)) return null
        val deadline = clock() + SCO_WAIT_S
        var input: AudioDeviceInfo? = null
        while (clock() < deadline && running) {
            if (am.communicationDevice?.id == out.id)
                input = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && isCrusher(it, mac) }
            if (input != null) break
            Thread.sleep(100)
        }
        val rec = if (input != null) newRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION) else null
        if (rec == null) { am.clearCommunicationDevice(); return null }
        val ok = runCatching { rec.setPreferredDevice(input); rec.startRecording(); rec.recordingState == AudioRecord.RECORDSTATE_RECORDING }
            .getOrDefault(false)
        if (!ok) { runCatching { rec.release() }; am.clearCommunicationDevice(); return null }
        return Capture(rec, true)
    }

    /** Stop and release the AudioRecord; a headset capture also hands the link back to music (A2DP). */
    private fun closeCapture() {
        val c = capture ?: return
        capture = null
        runCatching { c.rec.stop() }
        runCatching { c.rec.release() }
        if (c.headset) runCatching { am.clearCommunicationDevice() }
    }

    // ---------------- lower the media ----------------

    /** About a third of the music volume while someone talks, put back after unless the user changed it meanwhile. */
    private fun duck(on: Boolean) {
        val s = AudioManager.STREAM_MUSIC
        if (on) {
            val cur = am.getStreamVolume(s)
            duckBefore = cur
            am.setStreamVolume(s, if (cur > 0) maxOf(1, cur / 3) else 0, 0)
            duckTo = am.getStreamVolume(s)
        } else {
            if (duckBefore >= 0 && am.getStreamVolume(s) == duckTo) am.setStreamVolume(s, duckBefore, 0)
            duckBefore = -1
        }
    }

    // ---------------- notification ----------------

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CrusherAdaptiveService::class.java).setAction(CrusherAdaptiveCtl.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Crusher Adaptive")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }
}
