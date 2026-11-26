package dev.jaronwilson.headphones

import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * The last [MAX] Bluetooth packets this app sent to or read from the AirPods (AAP) and the Crusher (AT), for
 * the Packets screen. In memory only: nothing is written to disk, and it is gone when the app process ends.
 * Every entry is masked (see [PacketDecode]) before it is stored, so no address, name, serial or key is kept.
 *
 * Fed from the few places packets cross the socket: [PhoneDirect.write] (every AAP packet sent), the shared
 * AAP link's one reader ([AapLink]), and [CrusherDirect]'s session. Mic-stream audio (~130 packets a second) is folded
 * into one "mic audio x N/s" line, updated at most once a second.
 */
object PacketLog {
    const val MAX = 500
    const val AIRPODS = "AirPods"
    const val CRUSHER = "Crusher"

    class Entry(val t: Long, val sent: Boolean, val device: String, val hex: String, val text: String?, val meaning: String)

    private val buf = ArrayDeque<Entry>()
    @Volatile var paused = false
    /** Bumped on every change; the screen redraws when it moves. */
    @Volatile var version = 0L; private set

    // mic audio folding
    private var micSecond = 0L
    private var micInSecond = 0
    private var micLast: ByteArray? = null

    @Volatile private var secretsAt = 0L
    @Volatile private var aapSecrets: List<ByteArray> = emptyList()
    @Volatile private var textSecrets: List<String> = emptyList()

    fun entries(): List<Entry> = synchronized(this) { buf.toList() }

    fun clear() { synchronized(this) { buf.clear(); micInSecond = 0; micLast = null; version++ } }

    private fun add(e: Entry): Unit = synchronized(this) {
        if (paused) return
        buf.addLast(e)
        while (buf.size > MAX) buf.removeFirst()
        version++
    }

    /** A packet to (sent) or from the AirPods. Never throws. */
    fun aap(context: Context, sent: Boolean, p: ByteArray, n: Int = p.size) {
        if (paused) return
        runCatching {
            val pkt = if (n == p.size) p.copyOf() else p.copyOf(n)
            if (!sent && PacketDecode.isMicAudio(pkt)) { micAudio(pkt); return }
            val s = PacketDecode.aap(pkt, sent, secrets(context).first)
            add(Entry(System.currentTimeMillis(), sent, AIRPODS, s.hex, s.text, s.meaning))
        }
    }

    /** An AT command (with its trailing CR) or a reply, to or from the Crusher. Never throws. */
    fun crusher(context: Context, sent: Boolean, p: ByteArray) {
        if (paused || p.isEmpty()) return
        runCatching {
            val s = PacketDecode.crusher(p, sent, secrets(context).second)
            add(Entry(System.currentTimeMillis(), sent, CRUSHER, s.hex, s.text, s.meaning))
        }
    }

    /** One line per stretch of audio: replaced in place while it is still the newest entry, once a second. */
    private fun micAudio(p: ByteArray): Unit = synchronized(this) {
        val now = System.currentTimeMillis()
        if (now - micSecond > 3000) { micSecond = now; micInSecond = 1; micLast = p; return }   // a new stretch
        micInSecond++
        if (micLast == null) micLast = p
        if (now - micSecond < 1000) return
        val rate = (micInSecond * 1000 / (now - micSecond)).toInt()
        val shown = PacketDecode.aap(micLast!!, false)
        val e = Entry(now, false, AIRPODS, shown.hex, null, "Mic audio x $rate/s (AAC-ELD, folded into one line)")
        val last = buf.lastOrNull()
        if (last != null && last.meaning.startsWith("Mic audio x ") && now - last.t < 3000) {
            if (!paused) { buf.removeLast(); buf.addLast(e); version++ }
        } else add(e)
        micSecond = now; micInSecond = 0; micLast = null
    }

    /** What to hide wherever it turns up: the AirPods' and Crusher's addresses, the IRK, their names. */
    @Suppress("MissingPermission")
    private fun secrets(context: Context): Pair<List<ByteArray>, List<String>> {
        if (System.currentTimeMillis() - secretsAt > 60_000) {
            val bytes = mutableListOf<ByteArray>()
            val texts = mutableListOf<String>()
            fun addMac(mac: String?) {
                if (mac.isNullOrEmpty()) return
                runCatching { bytes += mac.split(':').map { it.toInt(16).toByte() }.toByteArray() }
                texts += mac; texts += mac.replace(":", "")
            }
            addMac(Config.airpodsMac(context))
            runCatching { Tracker.identity(context).irk }.getOrNull()?.let { bytes += it }
            runCatching {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                val ap = Config.airpodsMac(context).takeIf { it.isNotEmpty() }?.let { adapter?.getRemoteDevice(it) }
                ap?.name?.let { texts += it }
                CrusherDirect.device(context)?.let { addMac(it.address); it.name?.let { n -> texts += n } }
            }
            aapSecrets = bytes
            // a name that is only a protocol word ("Crusher" is in AT.CRUSHER) would hide the commands themselves
            textSecrets = texts.filter { it.length >= 5 && !it.equals("crusher", true) && !it.equals("AirPods", true) }
            secretsAt = System.currentTimeMillis()
        }
        return aapSecrets to textSecrets
    }

    /** The whole log as plain text (already masked), for the Copy button. */
    fun asText(entries: List<Entry>): String = entries.joinToString("\n") { e ->
        "${time(e.t)} ${if (e.sent) "sent" else "recv"} ${e.device}: ${e.meaning}\n  " +
            (e.text?.let { "\"$it\"  " } ?: "") + e.hex
    }

    fun time(t: Long): String = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(t))
}
