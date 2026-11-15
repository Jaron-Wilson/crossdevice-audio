package dev.jaronwilson.headphones

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The phone's ONE AAP link to the AirPods (classic L2CAP PSM 0x1001), shared by everything in the app.
 *
 * The AirPods expect one AAP link per host: a second link from the same phone gets in the way (seen: the
 * listening link then received nothing at all). So instead of each feature opening its own socket, they
 * all [acquire] this one, by name:
 *  - "ear#..": [EarService], for as long as the AirPods are on this phone and ear detection is on;
 *  - "mic#..": [HeadMic], while the AirPods' microphone stream runs;
 *  - "session": a short read or setting from [PhoneDirect] (a mode tap, the widget, Read back).
 * The first holder opens it (handshake, features, notifications on); the last one to [release] closes it.
 * One reader thread reads every packet: it feeds the Packets log, keeps what the AirPods report (battery,
 * settings, mode, ear detection, the IRK), then hands the packet to each holder's [Listener] on that thread.
 *
 * All opening and closing happens under [PhoneDirect.aapLock].
 */
object AapLink {
    private const val TAG = "AapLink"

    interface Listener {
        /** Every packet the AirPods send, on the reader thread. [p] is reused: copy what you keep. */
        fun onPacket(p: ByteArray, n: Int) {}
        /** The link closed under the holder (the AirPods went away or dropped it). Not called on [release]. */
        fun onClosed() {}
    }

    private val lock get() = PhoneDirect.aapLock
    @Volatile private var sock: BluetoothSocket? = null
    private val holders = LinkedHashSet<String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    /** When the current link opened (0 while closed). */
    @Volatile var openedAt = 0L; private set
    @Volatile private var cycling = false

    val isOpen get() = sock != null

    fun holders(): Set<String> = synchronized(lock) { holders.toSet() }

    /**
     * Join the link as [who], opening it if nobody has it open yet. [l] gets every packet from now on
     * (registered before the link opens, so it also sees the burst the AirPods send right after the
     * notification request). Throws if the link can't be opened.
     */
    fun acquire(context: Context, who: String, l: Listener?) = synchronized(lock) {
        if (l != null && l !in listeners) listeners += l
        try {
            if (sock == null) open(context.applicationContext)
            holders += who
        } catch (e: Throwable) {
            if (l != null) listeners -= l
            throw e
        }
        Log.d(TAG, "acquire $who -> holders $holders")
    }

    /** Leave the link; it closes when the last holder leaves. */
    fun release(who: String, l: Listener?) = synchronized(lock) {
        holders -= who
        if (l != null) listeners -= l
        Log.d(TAG, "release $who -> holders $holders")
        if (holders.isEmpty()) close()
    }

    /** Send one AAP packet on the open link (logged in Packets). False if it isn't open or the write failed. */
    fun send(context: Context, pkt: ByteArray): Boolean {
        val s = sock ?: return false
        return runCatching { PhoneDirect.write(context, s.outputStream, pkt) }.isSuccess
    }

    /**
     * Close and reopen the link for its current holders (they stay registered and aren't told), so the
     * AirPods send their full settings report again. Not while the mic stream runs.
     */
    fun reopen(context: Context): Boolean = synchronized(lock) {
        if (sock == null || holders.any { it.startsWith("mic") }) return false
        cycling = true
        try {
            close()
            Thread.sleep(800)               // a link that was just closed can refuse the next connect for a moment
            open(context.applicationContext)
            true
        } catch (e: Throwable) {
            // couldn't reopen: the holders lost the link, as if the AirPods had dropped it
            holders.clear()
            val dropped = listeners.toList(); listeners.clear()
            for (l in dropped) runCatching { l.onClosed() }
            throw e
        } finally { cycling = false }
    }

    private fun open(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("no Bluetooth")
        val mac = Config.airpodsMac(context).ifEmpty { error("Pick your AirPods in the Headphones app") }
        val s = PhoneDirect.socket(adapter.getRemoteDevice(mac))
        try {
            s.connect()
            sock = s
            openedAt = System.currentTimeMillis()
            startReader(context, s)
            PhoneDirect.hello(context, s.outputStream)
        } catch (e: Throwable) {
            if (sock === s) { sock = null; openedAt = 0 }
            runCatching { s.close() }
            throw e
        }
        Log.d(TAG, "open")
    }

    private fun close() {
        val s = sock ?: return
        sock = null; openedAt = 0
        runCatching { s.close() }
        Ears.onLinkClosed()
        Log.d(TAG, "closed")
    }

    private fun startReader(context: Context, s: BluetoothSocket) = thread(name = "aap-rx", isDaemon = true) {
        val buf = ByteArray(maxOf(8192, runCatching { s.maxReceivePacketSize }.getOrDefault(0)))
        runCatching {
            val input = s.inputStream
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                PacketLog.aap(context, false, buf, n)
                if (!AapMic.isAudio(buf, n)) runCatching { PhoneDirect.onReport(context, buf, n) }
                for (l in listeners) runCatching { l.onPacket(buf, n) }.onFailure { Log.w(TAG, "listener", it) }
            }
        }
        // The link ended. If it is still the current one, the AirPods (or Bluetooth) dropped it: tell the holders.
        val dropped = synchronized(lock) {
            if (sock !== s || cycling) return@synchronized emptyList()
            sock = null; openedAt = 0
            holders.clear()
            Ears.onLinkClosed()
            listeners.toList().also { listeners.clear() }
        }
        if (dropped.isNotEmpty()) Log.d(TAG, "link dropped (${dropped.size} holder(s) told)")
        runCatching { s.close() }
        for (l in dropped) runCatching { l.onClosed() }
    }
}
