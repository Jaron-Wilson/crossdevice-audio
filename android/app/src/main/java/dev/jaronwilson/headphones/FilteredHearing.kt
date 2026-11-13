package dev.jaronwilson.headphones

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.pow

/**
 * Filtered hearing: the room, through the AirPods, minus one chosen sound.
 *
 * The AirPods go to Noise Cancellation; their own microphones (the AAP stream, [HeadMic]) feed
 * [HearingProcessor] on the phone, and the cleaned room sound plays back to them over A2DP as an AudioTrack
 * (USAGE_MEDIA). The app never requests audio focus, so music from another app keeps playing and Android mixes
 * the two; the room sound is held well under the music by a limiter (-12 dBFS) and a track volume cap.
 *
 * The microphone session lives here, not in the screen, so filtered hearing keeps going with the screen off
 * (a media-playback foreground service, [HearingService], with a Stop button). Turning it off puts back the
 * AirPods mode it found; ending the microphone session puts back Conversation Awareness ([HeadMic]).
 * Nothing is recorded or saved: the learned sound and voice are kept in memory only, until the app closes.
 */
object FilteredHearing : HeadMic.Listener {
    private const val TAG = "FilteredHearing"
    const val RATE = 32_000
    /** Track volume: with the limiter's -12 dBFS ceiling the room sound peaks about 15 dB under full-scale music. */
    private const val VOLUME_CAP = 0.7f
    /** Filtered hearing may run this long before the session ends by itself. */
    private const val MAX_ON_MS = 30 * 60_000L

    val proc = HearingProcessor(RATE).apply { learnInBackground = true }
    private val dec = Decimator2()
    private var x64 = DoubleArray(4096)
    private var x32 = DoubleArray(2048)
    private var y32 = DoubleArray(2048)

    @Volatile var mic: HeadMic? = null; private set
    /** The screen, while it is open. */
    @Volatile var ui: HeadMic.Listener? = null
    @Volatile var on = false; private set
    @Volatile private var app: Context? = null
    @Volatile private var modeBefore: Int? = null
    @Volatile private var playout: Playout? = null
    /** Measured on the phone: from a block's arrival from the AirPods to Android's estimate of when it is
     *  heard (decoder, filter, jitter buffer, audio output, Bluetooth). -1 until measured. */
    @Volatile var delayMs = -1.0; private set
    /** The last thing worth telling (learned, enrolled, a howl). */
    @Volatile var lastEvent = ""; private set
    fun clearEvent() { lastEvent = "" }
    @Volatile var trackNote = ""; private set

    init {
        proc.mix = mixGain(50)
        proc.events = object : HearingProcessor.Events {
            override fun onLearned(r: NoiseLearn) { lastEvent = "Learned: ${r.describe()}. Removing it." }
            override fun onEnrolled(m: SpeakerModel?, voicedSec: Double) {
                lastEvent = if (m == null) "Heard only ${"%.1f".format(voicedSec)} s of voice. Hold longer while they talk (about 10 s)."
                else "Learned this voice (${"%.1f".format(m.voicedSec)} s of speech). Tap \"Remove that voice\" to use it."
            }
            override fun onHowl(hz: Double) {
                lastEvent = "Whistling (feedback) at ${"%.0f".format(hz)} Hz: muted for a moment, and that pitch is blocked now."
            }
        }
    }

    // ---------------------------------------------------------------- the microphone session
    fun startMic(context: Context) {
        if (mic != null) return
        app = context.applicationContext
        mic = HeadMic(context.applicationContext, this).also { it.pcmSink = ::feed; it.start() }
    }

    fun stopMic(reason: String = "Stopped") {
        if (on) turnOff()
        mic?.stop(reason)
    }

    @Synchronized private fun feed(pcm: ShortArray, nanos: Long) {
        val n = pcm.size
        if (x64.size < n) { x64 = DoubleArray(n); x32 = DoubleArray(n); y32 = DoubleArray(n) }
        for (i in 0 until n) x64[i] = pcm[i] / 32768.0
        val m = dec.process(x64, n, x32)
        proc.process(x32, m, y32)
        playout?.push(y32, m, nanos)
    }

    override fun onStatus(text: String) { ui?.onStatus(text) }
    override fun onFrame(analyzer: SoundAnalyzer) { ui?.onFrame(analyzer) }
    override fun onEnded(reason: String) {
        mic = null
        if (on) turnOff()
        ui?.onEnded(reason)
    }

    // ---------------------------------------------------------------- on / off
    /** Returns null, or why it can't start. */
    fun turnOn(context: Context): String? {
        val m = mic ?: return "Start listening first: filtered hearing uses the AirPods' microphones."
        if (on) return null
        app = context.applicationContext
        modeBefore = m.mode
        m.maxMs = MAX_ON_MS
        if (m.mode != 2) m.send(PhoneDirect.modePacket("anc"))
        on = true
        delayMs = -1.0
        val po = Playout(RATE)
        playout = po
        proc.post { enabled = true }
        thread(name = "fh-play", isDaemon = true) { play(po) }
        runCatching { context.startForegroundService(Intent(context, HearingService::class.java)) }
            .onFailure { Log.w(TAG, "service", it) }
        return null
    }

    fun turnOff() {
        if (!on) return
        on = false
        proc.post { enabled = false }                    // 0.3 s fade-out; the player stops after it
        val prev = modeBefore?.let { v -> PhoneDirect.MODE_NAMES[v] }
        modeBefore = null
        val m = mic
        val ctx = app
        if (prev != null && prev != "anc") {
            if (m != null) m.send(PhoneDirect.modePacket(prev))
            else if (ctx != null) thread(isDaemon = true) { runCatching { PhoneDirect.setMode(ctx, prev) } }
        }
        ctx?.let { c -> runCatching { c.stopService(Intent(c, HearingService::class.java)) } }
    }

    private fun play(po: Playout) {
        val track = runCatching {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                // Media, so it goes to the AirPods with the music; no audio focus is requested, so music keeps playing.
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(max(min, po.block * 4 * 4))
                .build()
        }.getOrElse { e ->
            Log.w(TAG, "AudioTrack", e); lastEvent = "Couldn't open the audio output: ${e.message}"
            turnOff(); return
        }
        trackNote = "buffer ${track.bufferSizeInFrames * 1000 / RATE} ms" +
            (if (track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) ", low-latency path" else "")
        Log.d(TAG, "track: $trackNote")
        track.setVolume(VOLUME_CAP)
        track.play()
        val buf = FloatArray(po.block)
        val ts = AudioTimestamp()
        var written = 0L
        var lastTs = 0L
        var stopAt = Long.MAX_VALUE
        try {
            while (playout === po) {
                val now = System.nanoTime()
                if (!on && stopAt == Long.MAX_VALUE) stopAt = now + 450_000_000L
                if (now > stopAt) break
                val idx = po.pull(buf)
                if (idx >= 0 && now - lastTs > 250_000_000L && track.getTimestamp(ts)) {
                    lastTs = now
                    val present = ts.nanoTime + (written - ts.framePosition) * 1_000_000_000L / RATE
                    po.arrival(idx - HearingProcessor.LATENCY)?.let { arr ->
                        val d = (present - arr) / 1e6
                        if (d in 0.0..5000.0) delayMs = if (delayMs < 0) d else delayMs + 0.2 * (d - delayMs)
                    }
                }
                val w = track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                if (w < 0) { Log.w(TAG, "write $w"); break }
                written += w
            }
        } finally {
            runCatching { track.pause(); track.flush() }
            runCatching { track.release() }
            if (playout === po) playout = null
        }
    }

    // ---------------------------------------------------------------- what to show
    /** Mix slider 0..100 -> linear gain: 0 is silent, 50 is the AirPods' level as is, 100 is +20 dB. */
    fun mixGain(slider: Int): Double = if (slider <= 0) 0.0 else 10.0.pow((-20 + 0.4 * slider) / 20)

    fun describe(): String {
        val s = proc.status
        val lines = mutableListOf<String>()
        lines += if (on) "On: you hear the room through the AirPods (they're in Noise Cancellation)." else
            "Off." + if (mic == null) " Start listening first." else ""
        if (s.learning >= 0) lines += "Learning the sound... ${(s.learning * 100).toInt()} %. Keep quiet so only it is heard."
        if (s.enrolling >= 0) lines += "Learning the voice... heard ${"%.1f".format(s.enrolling)} s of speech (aim for 5+)."
        if (proc.noise != null && proc.noiseOn) lines += "Removing the learned sound: about ${"%.0f".format(s.removedDb)} dB taken out now."
        if (proc.bands.isNotEmpty()) lines += "Blocked bands: " + proc.bands.joinToString(", ") { "${it.first.toInt()}-${it.second.toInt()} Hz" }
        when (proc.voiceMode) {
            VoiceMode.ALL_SPEECH -> lines += if (s.speech) "Speech heard: removing it." else "Removing all speech (none right now)."
            VoiceMode.TARGET -> lines += when {
                s.target -> "That voice is talking: removing it."
                s.speech -> "Someone else is talking: letting them through."
                else -> "Removing the learned voice (not heard right now)."
            }
            VoiceMode.OFF -> {}
        }
        if (on) {
            lines += if (delayMs >= 0) "Delay: about ${"%.0f".format(delayMs)} ms on the phone (filter, buffer, Bluetooth out) " +
                "+ roughly 20-40 ms in the AirPods' mic link." else "Delay: measuring..."
            playout?.let { if (it.underruns > 0) lines += "The AirPods' stream paused ${it.underruns}x (faded, buffer now ${it.target * 1000 / RATE} ms)." }
        }
        if (lastEvent.isNotEmpty()) lines += lastEvent
        return lines.joinToString("\n")
    }
}

/** Keeps filtered hearing running with the screen off; its notification has a Stop button. */
class HearingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FilteredHearing.stopMic("Stopped from the notification")
            stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Filtered hearing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, ListenActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HearingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Filtered hearing is on")
            .setContentText("The room through your AirPods, minus the sound you chose")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTE_ID, n)
        // turned off again before the service got going: foreground first (Android requires it), then stop
        if (!FilteredHearing.on) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // the user swiped the app away or the system stopped the service: never leave the stream running
        if (FilteredHearing.on) FilteredHearing.stopMic("Stopped")
        super.onDestroy()
    }

    companion object {
        private const val CH = "filtered_hearing"
        private const val NOTE_ID = 7
        const val ACTION_STOP = "dev.jaronwilson.headphones.FH_STOP"
    }
}
