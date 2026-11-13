package dev.jaronwilson.headphones

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
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
 * The AirPods hold one AAP link per host, so this owns it for the session; the app's other AirPods
 * controls go through [send] meanwhile.
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
    @Volatile private var sock: BluetoothSocket? = null
    @Volatile private var running = false
    @Volatile private var caBefore: Int? = null           // 0x28 as the AirPods reported it (1 on, 2 off)
    @Volatile var ear: Pair<Int, Int>? = null; private set
    @Volatile var mode: Int? = null; private set
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
    fun send(pkt: ByteArray): Boolean = runCatching {
        sock?.outputStream?.apply { write(pkt); flush() } ?: return false
    }.isSuccess

    private fun run() {
        val s = try {
            listener.onStatus("Connecting to the AirPods...")
            PhoneDirect.openAap(context)
        } catch (e: Throwable) {
            running = false
            listener.onEnded("Couldn't open the AirPods' control channel: " + ((e.cause ?: e).message ?: e.javaClass.simpleName))
            return
        }
        sock = s
        try {
            decoder = makeDecoder()
        } catch (e: Throwable) {
            running = false; runCatching { s.close() }; sock = null
            listener.onEnded("This phone has no AAC-ELD decoder: ${e.message}")
            return
        }
        val buf = ByteArray(maxOf(8192, runCatching { s.maxReceivePacketSize }.getOrDefault(0)))
        val started = System.currentTimeMillis()
        var startSent = 0L
        var lastAudio = 0L
        var gotAudio = false
        try {
            val input = s.inputStream
            // Reader: settings first (to learn Conversation Awareness), then audio.
            val reader = thread(name = "headmic-rx", isDaemon = true) {
                runCatching {
                    while (running) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (AapMic.isAudio(buf, n)) {
                            lastAudio = System.currentTimeMillis(); gotAudio = true
                            for (au in AapMic.demux(buf, n)) decode(au)
                            continue
                        }
                        PhoneDirect.earFrom(buf, n)?.let { ear = it; PhoneDirect.rememberEar(context, it) }
                        for (i in 0..n - 8) if (buf[i] == 0x04.toByte() && buf[i + 2] == 0x04.toByte() &&
                            buf[i + 4] == 0x09.toByte() && buf[i + 5] == 0.toByte()) {
                            val id = buf[i + 6].toInt() and 0xFF; val v = buf[i + 7].toInt() and 0xFF
                            if (id == 0x28 && caBefore == null) caBefore = v
                            if (id == 0x0D) mode = v
                        }
                    }
                }
                if (running) { running = false; finish("The AirPods closed the link") }
            }
            Thread.sleep(700)                         // the settings burst after the notification request
            if (caBefore == 1) send(PhoneDirect.controlPacket(0x28, 2))
            Thread.sleep(150)
            send(AapMic.START); startSent = System.currentTimeMillis()
            listener.onStatus("Asked the AirPods for their microphone stream...")
            while (running) {
                Thread.sleep(250)
                val now = System.currentTimeMillis()
                if (!gotAudio && now - startSent > NO_AUDIO_MS) {
                    running = false
                    finish("No microphone audio came from the AirPods within ${NO_AUDIO_MS / 1000} s. Their firmware may " +
                        "not offer the stream to this phone. Nothing else was used instead.")
                    break
                }
                if (gotAudio && now - lastAudio > STALL_MS) listener.onStatus("The AirPods' stream paused...")
                if (now - started > MAX_MS) { running = false; finish("Stopped after ${MAX_MS / 60000} minutes"); break }
            }
            reader.join(500)
        } catch (e: Throwable) {
            if (running) { running = false; finish("Error: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    @Synchronized private fun finish(reason: String) {
        val s = sock ?: run { listener.onEnded(reason); return }
        sock = null
        runCatching { s.outputStream.apply { write(AapMic.STOP); flush() } }
        if (caBefore == 1) runCatching { Thread.sleep(150); s.outputStream.apply { write(PhoneDirect.controlPacket(0x28, 1)); flush() } }
        runCatching { Thread.sleep(150); s.close() }
        runCatching { decoder?.stop(); decoder?.release() }
        decoder = null
        listener.onEnded(reason)
    }

    private fun makeDecoder(): MediaCodec {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AapMic.RATE, 1)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(AapMic.ASC))
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(f, null, null, 0); start() }
    }

    private val info = MediaCodec.BufferInfo()

    private fun decode(au: ByteArray) {
        val c = decoder ?: return
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
                        val ch = outCh.coerceAtLeast(1)
                        val pcm = if (ch == 1) raw else ShortArray(raw.size / ch) { i -> raw[i * ch] }
                        if (analyzer.feedPcm16(pcm)) {
                            listener.onStatus("Listening through the AirPods' microphones (${outRate / 1000} kHz, no call mode)")
                            listener.onFrame(analyzer)
                        }
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outRate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outCh = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (outRate != analyzer.rate) analyzer = SoundAnalyzer(outRate)
                    }
                    else -> break
                }
            }
        }
    }

    companion object {
        const val NO_AUDIO_MS = 5_000L
        const val STALL_MS = 2_000L
        const val MAX_MS = 3 * 60_000L
    }
}
