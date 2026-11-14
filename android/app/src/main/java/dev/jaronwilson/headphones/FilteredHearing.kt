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
import java.io.File
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
 * Nothing is recorded. What was learned (a sound's spectrum, voice embeddings: numbers, no audio) is kept in
 * the app's private storage ([HearingStore]) until you tap Forget.
 *
 * Two engines: Neural (DeepFilterNet3 + a speaker embedding, [NeuralFront]; used only while a voice mode
 * needs it) and Light (the classical DSP alone, for battery).
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
    private var e32 = DoubleArray(2048)
    private var g32 = DoubleArray(2048)

    // ---- neural engine
    /** Neural (true) or Light. Saved. */
    @Volatile var neuralEngine = true; private set
    @Volatile var front: NeuralFront? = null; private set
    @Volatile var neuralNote = ""; private set
    @Volatile private var loading = false
    /** Is the front in the path right now. */
    @Volatile var usingFront = false; private set
    /** Remove the user's own (airborne) voice. */
    @Volatile var ownVoice = false; private set
    @Volatile var enrollSlot: String? = null; private set
    /** The voice mode we asked for (settings reach the processor a moment later). */
    @Volatile var mode = VoiceMode.OFF; private set
    private var store = HearingStore()
    @Volatile private var loaded = false

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
            override fun onLearned(r: NoiseLearn) {
                lastEvent = "Learned: ${r.describe()}. Removing it."
                store = store.withNoise(r); persist()
            }
            override fun onEnrolled(m: SpeakerModel?, voicedSec: Double) {
                if (m != null) { store = store.withSpeaker(m); persist() }
                if (front != null && neuralEngine) return                   // the neural enrollment reports
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
        load(context.applicationContext)
        if (neuralEngine) loadNeural(context.applicationContext)
        mic = HeadMic(context.applicationContext, this).also { it.pcmSink = ::feed; it.start() }
    }

    /** Set by [restart]: turn filtered hearing on once the stream is flowing. */
    @Volatile private var autoOn = false

    /** Ear detection: a bud went back in after it stopped listening. Plain listening only runs while its
     *  screen is open, so that is restarted only then; filtered hearing also with the screen off. */
    fun restart(context: Context, filtered: Boolean) {
        if (mic != null) return
        if (!filtered && ui == null) return
        autoOn = filtered
        startMic(context)
    }

    fun stopMic(reason: String = "Stopped") {
        autoOn = false
        if (on) turnOff()
        mic?.stop(reason)
    }

    @Synchronized private fun feed(pcm: ShortArray, nanos: Long) {
        val n = pcm.size
        if (x64.size < n) x64 = DoubleArray(n)
        for (i in 0 until n) x64[i] = pcm[i] / 32768.0
        val f = front
        val want = f != null && neuralEngine && needsFront()
        if (want != usingFront) { usingFront = want; proc.post { dip() } }
        val m: Int
        if (want && f != null) {
            val cap = f.maxOut(n)
            if (x32.size < cap) { x32 = DoubleArray(cap); e32 = DoubleArray(cap); g32 = DoubleArray(cap); y32 = DoubleArray(cap) }
            m = f.process(x64, n, x32, e32, g32)
            proc.process(x32, e32, g32, m, y32)
        } else {
            if (x32.size < n) { x32 = DoubleArray(n); e32 = DoubleArray(n); g32 = DoubleArray(n); y32 = DoubleArray(n) }
            m = dec.process(x64, n, x32)
            proc.process(x32, m, y32)
        }
        playout?.push(y32, m, nanos)
    }

    /** The neural front runs only when a mode needs it (battery). */
    private fun needsFront(): Boolean = enrollSlot != null || ownVoice ||
        mode == VoiceMode.ALL_SPEECH || mode == VoiceMode.VOICES_ONLY ||
        (mode == VoiceMode.TARGET && front?.gate?.voices?.containsKey("that") == true)

    // ---------------------------------------------------------------- engines, modes, learning
    private fun prefs(c: Context) = c.getSharedPreferences("filtered_hearing", Context.MODE_PRIVATE)

    fun setEngine(context: Context, neural: Boolean) {
        neuralEngine = neural
        prefs(context).edit().putBoolean("neural", neural).apply()
        if (neural) loadNeural(context.applicationContext)
        applyRemoving()
    }

    /** Loads the two models (once, in the background): about 45 MB of weights, a second or so. */
    fun loadNeural(c: Context) {
        if (front != null || loading) return
        loading = true
        neuralNote = "Loading the neural models..."
        thread(name = "fh-models", isDaemon = true) {
            try {
                val t0 = System.nanoTime()
                val dfn = OrtModels.Dfn(c.assets.open(OrtModels.DFN_ASSET).use { it.readBytes() })
                val spk = OrtModels.CamPlus(c.assets.open(OrtModels.SPK_ASSET).use { it.readBytes() })
                val gate = VoiceGate(spk)
                store.voices.forEach { (k, v) -> gate.voices[k] = v }
                front = NeuralFront(dfn, gate)
                applyRemoving()
                neuralNote = "Neural engine ready (loaded in ${(System.nanoTime() - t0) / 1_000_000} ms)."
            } catch (e: Throwable) {
                Log.w(TAG, "models", e)
                neuralNote = "Neural engine unavailable (${e.javaClass.simpleName}: ${e.message}); using Light."
                neuralEngine = false
            } finally { loading = false }
        }
    }

    fun setMode(m: VoiceMode) {
        mode = m
        proc.post { voiceMode = m }
        applyRemoving()
    }

    fun setAmount(a: Double) { proc.post { amount = a } }

    fun setOwnVoice(on: Boolean) { ownVoice = on; applyRemoving() }

    /** Which enrolled voices the gate removes. */
    private fun applyRemoving() {
        val g = front?.gate ?: return
        g.removing = buildSet {
            if (mode == VoiceMode.TARGET && g.voices.containsKey("that")) add("that")
            if (ownVoice && g.voices.containsKey("me")) add("me")
        }
    }

    fun hasVoice(slot: String): Boolean = if (slot == "that" && !(neuralEngine && front != null)) proc.speaker != null
        else front?.gate?.voices?.containsKey(slot) ?: store.voices.containsKey(slot)

    /** Hold-to-learn: "that" (someone else) or "me". */
    fun startEnroll(slot: String) {
        enrollSlot = slot
        if (slot == "that") proc.post { startEnroll() }
        front?.gate?.post { startEnroll(slot) }
    }

    fun finishEnroll() {
        val slot = enrollSlot ?: return
        if (slot == "that") proc.post { finishEnroll() }
        val g = front?.gate
        if (g == null || !neuralEngine) { enrollSlot = null; return }
        g.post {
            finishEnroll { v, sec ->
                enrollSlot = null
                val who = if (slot == "me") "your voice" else "this voice"
                lastEvent = if (v == null) "Heard only ${"%.1f".format(sec)} s of speech. Hold longer while " +
                    (if (slot == "me") "you talk" else "they talk") + " (about 10 s)."
                else "Learned $who (${"%.1f".format(sec)} s of speech)." +
                    if (slot == "me") " Tap \"Remove my voice\" to use it." else " Tap \"Remove that voice\" to use it."
                if (v != null) { store = store.withVoice(slot, v); persist() }
                applyRemoving()
            }
        }
    }

    fun forgetSound() {
        proc.post { setNoise(null) }
        store = store.withNoise(null); persist()
        lastEvent = "Forgot the learned sound."
    }

    fun forgetVoices() {
        proc.post { speaker = null; if (voiceMode == VoiceMode.TARGET) voiceMode = VoiceMode.OFF }
        if (mode == VoiceMode.TARGET) mode = VoiceMode.OFF
        ownVoice = false
        front?.gate?.voices?.clear()
        store = store.withSpeaker(null).withVoice("that", null).withVoice("me", null); persist()
        applyRemoving()
        lastEvent = "Forgot the learned voices (yours too)."
    }

    /** Reads the saved engine choice and what was learned (once). */
    fun load(c: Context) {
        if (app == null) app = c.applicationContext
        if (loaded) return
        loaded = true
        neuralEngine = prefs(c).getBoolean("neural", true)
        store = HearingStore.load(File(c.filesDir, "hearing.txt"))
        val s = store
        proc.post { s.noise?.let { setNoise(it); noiseOn = true }; s.speaker?.let { speaker = it } }
        front?.gate?.let { g -> s.voices.forEach { (k, v) -> g.voices[k] = v } }
    }

    private fun persist() {
        val c = app ?: return
        val s = store
        thread(isDaemon = true) { runCatching { HearingStore.save(File(c.filesDir, "hearing.txt"), s) }.onFailure { Log.w(TAG, "save", it) } }
    }

    override fun onStatus(text: String) { ui?.onStatus(text) }
    override fun onFrame(analyzer: SoundAnalyzer) {
        if (autoOn) { autoOn = false; app?.let { c -> turnOn(c) } }
        ui?.onFrame(analyzer)
    }
    override fun onEnded(reason: String) {
        mic = null
        autoOn = false
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
                    val fd = if (usingFront) front?.delay32?.toInt() ?: 0 else 0
                    po.arrival(idx - HearingProcessor.LATENCY - fd)?.let { arr ->
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
        val f = front
        lines += if (!neuralEngine) "Engine: Light (classical filters, least battery)." else
            "Engine: Neural" + (if (usingFront) " (running)." else if (f != null) " (idle: no voice mode on)." else "") +
                (if (neuralNote.isNotEmpty() && (f == null || loading)) " $neuralNote" else "")
        if (enrollSlot != null) {
            val sec = f?.gate?.enrollingSec ?: -1.0
            if (sec >= 0) lines += "Learning ${if (enrollSlot == "me") "your voice" else "the voice"}... heard ${"%.1f".format(sec)} s of speech (aim for 8+)."
        } else if (s.enrolling >= 0) lines += "Learning the voice... heard ${"%.1f".format(s.enrolling)} s of speech (aim for 5+)."
        if (proc.noise != null && proc.noiseOn) lines += "Removing the learned sound: about ${"%.0f".format(s.removedDb)} dB taken out now."
        if (proc.clicksOn) lines += "Click remover on: ${s.clicks} clicks taken out so far."
        if (proc.bands.isNotEmpty()) lines += "Blocked bands: " + proc.bands.joinToString(", ") { "${it.first.toInt()}-${it.second.toInt()} Hz" }
        val neural = usingFront && f != null
        val gate = f?.gate
        when (proc.voiceMode) {
            VoiceMode.ALL_SPEECH -> lines += if (neural) "Removing all speech (neural)" + if (gate?.speaking == true) ": speech heard, taking it out." else "." else
                if (s.speech) "Speech heard: removing it." else "Removing all speech (none right now)."
            VoiceMode.VOICES_ONLY -> lines += if (neural) "Voices only: everything but speech is taken out." else "Voices only needs the Neural engine."
            VoiceMode.TARGET -> lines += if (neural && gate != null) {
                val sim = gate.lastSim.takeIf { !it.isNaN() }?.let { " (match ${"%.2f".format(it)}, needs ${VoiceGate.THR_ON})" } ?: ""
                when {
                    !gate.voices.containsKey("that") -> "Learn the voice again with the Neural engine (hold the button while they talk)."
                    s.gate > 0.5 -> "That voice is talking: removing it.$sim"
                    gate.speaking -> "Someone else is talking: letting them through.$sim"
                    else -> "Removing the learned voice (not heard right now)."
                }
            } else when {
                s.target -> "That voice is talking: removing it."
                s.speech -> "Someone else is talking: letting them through."
                else -> "Removing the learned voice (not heard right now)."
            }
            VoiceMode.OFF -> {}
        }
        if (ownVoice) lines += if (neural && gate?.voices?.containsKey("me") == true)
            (if (s.gate > 0.5 && gate.speaking) "You're talking: your voice is taken out of the playback." else "Removing your voice from the playback when you talk.")
        else "Remove my voice needs the Neural engine and your voice learned."
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
