package dev.jaronwilson.headphones

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Listens through the AirPods' own microphones with NO call mode: the AAP microphone stream ([AapMic],
 * message 0x58, AAC-ELD) on the phone's own AAP link, decoded with the phone's AAC decoder and fed to a
 * [SoundAnalyzer]. Music keeps playing over A2DP. Nothing is recorded, stored or sent: each decoded block
 * is analysed and dropped.
 *
 * Like LibrePods, Conversation Awareness is turned off while the stream runs (it is put back afterwards).
 * The AirPods hold one AAP link per host, so this joins the app's one link ([AapLink], as "mic"): opened for
 * it if nothing else has it open, otherwise shared with ear detection and the app's other AirPods controls.
 *
 * Experimental: the stream is documented by LibrePods, not by Apple, and hasn't been confirmed on every
 * firmware. If no audio arrives within [NO_AUDIO_MS] the session ends and says so. It never falls back to
 * the phone's or a laptop's microphone.
 */
class HeadMic(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onStatus(text: String)
        fun onFrame(analyzer: SoundAnalyzer)
        fun onEnded(reason: String)
    }

    @Volatile var analyzer = SoundAnalyzer(); private set
    /** Holding the shared AAP link right now. */
    @Volatile private var linked = false
    /** This session's name on the shared link. */
    private val who = "mic#" + Integer.toHexString(System.identityHashCode(this))
    @Volatile private var lastAudio = 0L
    @Volatile private var gotAudio = false
    @Volatile private var running = false
    @Volatile private var caBefore: Int? = null           // 0x28 as the AirPods reported it (1 on, 2 off)
    @Volatile var ear: Pair<Int, Int>? = null; private set
    @Volatile var mode: Int? = null; private set
    /** Gets every decoded block (mono 16-bit at [AapMic.RATE]) and when it arrived (System.nanoTime), on the
     *  receive thread: filtered hearing ([FilteredHearing]) processes it there. */
    @Volatile var pcmSink: ((ShortArray, Long) -> Unit)? = null
    /** How long a session may run (filtered hearing raises it). */
    @Volatile var maxMs = MAX_MS
    /** Times the stream stalled and was asked for again. */
    @Volatile var restarts = 0; private set
    private var decoder: MediaCodec? = null
    private var outRate = AapMic.RATE
    private var outCh = 1

    fun start() {
        if (running) return
        running = true
        thread(name = "headmic", isDaemon = true) { run() }
    }

    fun stop(reason: String = "Stopped") {
        if (!running) return
        running = false
        thread(isDaemon = true) { finish(reason) }       // Bluetooth writes and short waits: off the UI thread
    }

    /** Send an AAP packet on this session's link (mode changes while listening). */
    fun send(pkt: ByteArray): Boolean = linked && AapLink.send(context, pkt)

    private fun run() {
        // During a call (phone or VoIP) the AirPods' microphones belong to it (HFP), and they don't answer the
        // stream request at all (seen). Don't touch the link then.
        val am = context.getSystemService(AudioManager::class.java)
        if (am != null && am.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION,
                AudioManager.MODE_CALL_SCREENING, 5 /* MODE_CALL_REDIRECT */, 6 /* MODE_COMMUNICATION_REDIRECT */)) {
            running = false
            Log.d(TAG, "not starting: audio mode ${am.mode} (a call)")
            listener.onEnded("A call is using the AirPods' microphones right now. Try again after it.")
            return
        }
        try {
            listener.onStatus("Connecting to the AirPods...")
            // Join the app's one AAP link (waits for any short exchange of this app to finish). A link that was
            // just closed (by this app or another host) can refuse the next connect for a moment: try a few times.
            var tries = 0
            while (true) {
                val r = runCatching { AapLink.acquire(context, who, rx) }
                if (r.isSuccess) break
                Log.w(TAG, "AAP connect failed (try ${tries + 1})", r.exceptionOrNull())
                if (++tries >= 3 || !running) throw r.exceptionOrNull()!!
                Thread.sleep(1200)
            }
            linked = true
        } catch (e: Throwable) {
            running = false
            listener.onEnded("Couldn't open the AirPods' control channel: " + ((e.cause ?: e).message ?: e.javaClass.simpleName))
            return
        }
        if (!running) { Log.d(TAG, "stopped while connecting"); finish("Stopped"); return }
        try {
            decoder = makeDecoder()
        } catch (e: Throwable) {
            running = false
            synchronized(PhoneDirect.aapLock) { linked = false; AapLink.release(who, rx) }
            listener.onEnded("This phone has no AAC-ELD decoder: ${e.message}")
            return
        }
        Log.d(TAG, "on the AAP link with ${AapLink.holders()} decoder=${runCatching { decoder?.name }.getOrNull()}")
        val started = System.currentTimeMillis()
        var startSent = 0L
        try {
            Thread.sleep(700)                         // the settings burst after the notification request
            // The link was already open (ear detection holds it): the burst came earlier, and the app kept it.
            if (caBefore == null) caBefore = PhoneDirect.ctlValue(context, 0x28)
            if (mode == null) mode = PhoneDirect.ctlValue(context, 0x0D)
            // If an earlier session ended without putting Conversation Awareness back (the app was killed),
            // it still reads "off" here: put back what the user had, from what that session wrote down.
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (caBefore == 2 && prefs.getBoolean(KEY_CA, false)) caBefore = 1
            if (caBefore == 1) {
                prefs.edit().putBoolean(KEY_CA, true).commit()
                send(PhoneDirect.controlPacket(0x28, 2))
            }
            Thread.sleep(150)
            Log.d(TAG, "Conversation Awareness before: $caBefore; sending START ${hexHead(AapMic.START, AapMic.START.size)}")
            send(AapMic.START); startSent = System.currentTimeMillis()
            val firstStart = startSent
            listener.onStatus("Asked the AirPods for their microphone stream...")
            while (running) {
                Thread.sleep(250)
                val now = System.currentTimeMillis()
                if (!gotAudio && now - firstStart > NO_AUDIO_MS) {
                    running = false
                    finish("No microphone audio came from the AirPods within ${NO_AUDIO_MS / 1000} s" +
                        (if (!acked) ": they didn't answer the request at all (seen while a call was using their " +
                            "microphones). " else ". ") +
                        "Nothing else was used instead.")
                    break
                }
                if (!gotAudio && now - startSent > STALL_MS) {
                    restarts++
                    Log.d(TAG, "no audio yet: asking again #$restarts")
                    send(AapMic.STOP); Thread.sleep(200); send(AapMic.START); startSent = System.currentTimeMillis()
                }
                if (gotAudio && now - lastAudio > STALL_MS && now - startSent > STALL_MS) {
                    // The stream stalls now and then; asking again (STOP, then START) resumes it, as LibrePods does.
                    listener.onStatus("The AirPods' stream paused, asking again...")
                    restarts++
                    Log.d(TAG, "stall ${now - lastAudio} ms: restart #$restarts (mic=$micCount)")
                    send(AapMic.STOP); Thread.sleep(200); send(AapMic.START); startSent = System.currentTimeMillis()
                }
                if (now - started > maxMs) { running = false; finish("Stopped after ${maxMs / 60000} minutes"); break }
            }
        } catch (e: Throwable) {
            if (running) { running = false; finish("Error: ${e.message ?: e.javaClass.simpleName}") }
        } finally {
            if (linked) finish("Stopped")            // stop() raced with the start: never leave the stream running
        }
    }

    @Synchronized private fun finish(reason: String) {
        Log.d(TAG, "finish: $reason (rx=$rxCount mic=$micCount au=$auCount pcm=$pcmCount)")
        if (!linked) { listener.onEnded(reason); return }
        synchronized(PhoneDirect.aapLock) {             // the link closes only if nothing else holds it
            linked = false
            AapLink.send(context, AapMic.STOP)
            if (caBefore == 1) runCatching {
                Thread.sleep(150); AapLink.send(context, PhoneDirect.controlPacket(0x28, 1))
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CA).apply()
            }
            runCatching { Thread.sleep(150) }
            AapLink.release(who, rx)
        }
        runCatching { decoder?.stop(); decoder?.release() }
        decoder = null
        listener.onEnded(reason)
    }

    /** Every packet on the shared link, on its reader thread: settings first (to learn Conversation Awareness),
     *  then audio. */
    private val rx = object : AapLink.Listener {
        override fun onPacket(p: ByteArray, n: Int) {
            if (!running) return
            val buf = p
            rxCount++
            val isMic = n >= 6 && buf[4] == 0x58.toByte() && buf[5] == 0.toByte()
            // Packet dumps (they can hold device addresses): adb shell setprop log.tag.HeadMic VERBOSE
            if (dump && (!isMic || micCount < 20 || micCount % 1000 == 0))
                Log.v(TAG, "rx #$rxCount len=$n ${hexHead(buf, n)}")
            if (isMic) micCount++
            if (isMic && n >= 8 && buf[6] == 0.toByte() && buf[7] == 0.toByte() && !acked) {
                acked = true; Log.d(TAG, "the AirPods answered the stream request: ${hexHead(buf, n)}")
            }
            if (n > 6 && buf[4] == 0x1D.toByte() && buf[5] == 0.toByte()) {
                // Device information: log only the model and firmware-looking strings (no serials)
                val parts = String(buf, 6, n - 6, Charsets.ISO_8859_1).split('\u0000')
                    .map { it.filter { c -> c in ' '..'~' } }.filter { it.length >= 3 }
                Log.d(TAG, "AirPods info: " + parts.filter { PacketDecode.NOT_PERSONAL.matches(it) })
            }
            if (AapMic.isAudio(buf, n)) {
                lastAudio = System.currentTimeMillis(); gotAudio = true
                for (au in AapMic.demux(buf, n)) decode(au)
                return
            }
            // (the ear report itself is kept by AapLink for the whole app; this copy is for the Listen screen)
            PhoneDirect.earFrom(buf, n)?.let {
                if (it != ear) Log.d(TAG, "ear: ${PhoneDirect.earText(it)} (mic packets so far $micCount)")
                ear = it
            }
            for (i in 0..n - 8) if (buf[i] == 0x04.toByte() && buf[i + 2] == 0x04.toByte() &&
                buf[i + 4] == 0x09.toByte() && buf[i + 5] == 0.toByte()) {
                val id = buf[i + 6].toInt() and 0xFF; val v = buf[i + 7].toInt() and 0xFF
                if (id == 0x28 && caBefore == null) caBefore = v
                if (id == 0x0D) mode = v
            }
        }

        override fun onClosed() {
            linked = false
            if (running) { running = false; finish("The AirPods closed the link") }
        }
    }

    private fun makeDecoder(): MediaCodec {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AapMic.RATE, 1)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(AapMic.ASC))
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(f, null, null, 0); start() }
    }

    private val info = MediaCodec.BufferInfo()
    @Volatile private var rxCount = 0
    private val dump = Log.isLoggable(TAG, Log.VERBOSE)
    @Volatile private var micCount = 0
    @Volatile private var acked = false                     // a 0x58 control reply (subtype 0) came back
    private var auCount = 0
    private var firstAu = 0L
    private var secStart = 0L
    private var secAu = 0
    private var secPeak = 0
    private var pcmCount = 0

    private fun hexHead(b: ByteArray, n: Int, max: Int = 48) =
        (0 until minOf(n, max)).joinToString("") { "%02x".format(b[it]) } + if (n > max) "..." else ""

    private fun decode(au: ByteArray) {
        val c = decoder ?: return
        val arrived = System.nanoTime()
        auCount++
        if (auCount == 1) firstAu = System.currentTimeMillis()
        if (auCount <= 5 || auCount % 1000 == 0) Log.d(TAG, "AU #$auCount len=${au.size} ${hexHead(au, au.size, 16)} " +
            "rate=${"%.1f".format(auCount * 1000.0 / maxOf(1L, System.currentTimeMillis() - firstAu))} AU/s")
        runCatching {
            val i = c.dequeueInputBuffer(10_000)
            if (i >= 0) {
                c.getInputBuffer(i)?.apply { clear(); put(au) }
                c.queueInputBuffer(i, 0, au.size, 0, 0)
            }
            while (true) {
                val o = c.dequeueOutputBuffer(info, 0)
                when {
                    o >= 0 -> {
                        val b = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val raw = ShortArray(info.size / 2); b.asShortBuffer().get(raw)
                        c.releaseOutputBuffer(o, false)
                        pcmCount++
                        if (pcmCount <= 5 || pcmCount % 1000 == 0)
                            Log.d(TAG, "PCM #$pcmCount bytes=${info.size} rate=$outRate ch=$outCh peak=${raw.maxOfOrNull { kotlin.math.abs(it.toInt()) }}")
                        val ch = outCh.coerceAtLeast(1)
                        val pcm = if (ch == 1) raw else ShortArray(raw.size / ch) { i -> raw[i * ch] }
                        secAu++; secPeak = maxOf(secPeak, pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0)
                        val tNow = System.currentTimeMillis()
                        if (tNow - secStart >= 1000) {
                            if (dump) Log.v(TAG, "level: $secAu frames in ${tNow - secStart} ms, peak $secPeak")
                            secStart = tNow; secAu = 0; secPeak = 0
                        }
                        pcmSink?.let { sink -> runCatching { sink(pcm, arrived) }.onFailure { Log.w(TAG, "filter", it) } }
                        if (analyzer.feedPcm16(pcm)) {
                            listener.onStatus("Listening through the AirPods' microphones (${outRate / 1000} kHz, no call mode)")
                            listener.onFrame(analyzer)
                        }
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outCh = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        Log.d(TAG, "decoder output format: ${c.outputFormat}")
                        // The decoder reports 48 kHz (the ASC's rate index), but the AirPods send 480-sample
                        // frames at 133 per second (measured), so the audio is 64 kHz: keep the analyzer there.
                        outRate = AapMic.RATE
                    }
                    else -> break
                }
            }
        }.onFailure { Log.w(TAG, "decode failed on AU #$auCount", it) }
    }

    companion object {
        const val TAG = "HeadMic"
        private const val PREFS = "headmic"
        private const val KEY_CA = "ca_to_restore"
        const val NO_AUDIO_MS = 12_000L
        const val STALL_MS = 2_000L
        const val MAX_MS = 3 * 60_000L
    }
}
