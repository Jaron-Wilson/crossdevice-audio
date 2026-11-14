package dev.jaronwilson.headphones

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Things the phone does with the AirPods by itself, no laptop and no Tailscale:
 *  - noise mode over AAP (classic L2CAP PSM 0x1001), via Android's hidden L2CAP socket. Works only if
 *    this phone's Bluetooth stack accepts the AirPods' channel (Android 16 QPR3+); Samsung on QPR2 may
 *    refuse, in which case we remember that and the widget hides the buttons.
 *  - battery from the AirPods' public BLE broadcast.
 *  - AirPods settings (adaptive noise level, Conversational Awareness, ...) as AAP control commands
 *    (04 00 04 00 09 00 <id> <4 bytes>, ids from LibrePods), read back from the values the AirPods
 *    push after the notification request. Same names and encoding as the laptop panel.
 */
object PhoneDirect {
    private const val PSM = 0x1001
    private const val TYPE_L2CAP = 3
    private val HANDSHAKE = hex("00000400010002000000000000000000")
    private val FEATURES = hex("040004004D00FF00000000000000")
    private val MODE = mapOf("off" to 1, "anc" to 2, "transparency" to 3, "adaptive" to 4)
    /** 0x0D value -> mode name. */
    val MODE_NAMES: Map<Int, String> = MODE.entries.associate { (k, v) -> v to k }
    private const val PREFS = "direct"

    /** "ok", "fail" or "" (not tried yet). */
    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("aap", "") ?: ""

    fun resetStatus(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("aap").apply()

    private fun remember(context: Context, v: String, why: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("aap", v).putString("why", why).apply()

    fun why(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("why", "") ?: ""

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Classic L2CAP socket to the AirPods' AAP channel, through hidden API (no root). */
    internal fun socket(dev: BluetoothDevice): BluetoothSocket {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
        // 1) BluetoothDevice.createL2capSocket(int) if this build still has it
        runCatching {
            return HiddenApiBypass.invoke(BluetoothDevice::class.java, dev, "createL2capSocket", PSM) as BluetoothSocket
        }
        // 2) the BluetoothSocket constructor: (device, type, auth, encrypt, port, uuid, [more booleans...])
        val ctor = HiddenApiBypass.getDeclaredMethods(BluetoothSocket::class.java)
            .filterIsInstance<java.lang.reflect.Constructor<*>>()
            .filter { c -> c.parameterTypes.let { it.size >= 6 && it[0] == BluetoothDevice::class.java && it[1] == Int::class.javaPrimitiveType } }
            .minByOrNull { it.parameterTypes.size } ?: error("no BluetoothSocket constructor")
        ctor.isAccessible = true
        val args = ctor.parameterTypes.mapIndexed { i, t ->
            when {
                i == 0 -> dev
                i == 1 -> TYPE_L2CAP
                i == 2 || i == 3 -> true          // auth, encrypt
                i == 4 -> PSM
                t == ParcelUuid::class.java -> null
                t == Boolean::class.javaPrimitiveType -> false
                t == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }
        return ctor.newInstance(*args.toTypedArray()) as BluetoothSocket
    }

    private val NOTIFY = hex("040004000F00FFFFFEFF")

    /** The opening sequence on a fresh AAP link: handshake, features, notifications on (and, once, the IRK
     *  request for Last seen). [AapLink] sends it; its reader is already running, so the reply burst is seen. */
    internal fun hello(context: Context, out: java.io.OutputStream) {
        write(context, out, HANDSHAKE); Thread.sleep(250)
        write(context, out, FEATURES); Thread.sleep(150)
        write(context, out, NOTIFY)
        // Ask once for the key that resolves the AirPods' rotating BLE address (local only, see Tracker)
        if (Tracker.wantIrk(context)) { Thread.sleep(150); write(context, out, AapTrack.REQUEST_IRK) }
    }

    /** What every packet from the AirPods teaches the app, whoever holds the link (called by [AapLink]). */
    internal fun onReport(context: Context, buf: ByteArray, n: Int) {
        // Last seen / finder: the battery packet and the IRK reply (see Tracker)
        AapTrack.batteryFrom(buf, n)?.let { Tracker.rememberBattery(context, Tracker.AIRPODS, it) }
        if (n >= 7 && buf[4] == 0x04.toByte() && buf[5] == 0.toByte() && buf[0] == 0x04.toByte() && buf[2] == 0x04.toByte())
            android.util.Log.d("Ears", "battery report, component order: " + (0 until (buf[6].toInt() and 0xFF))
                .mapNotNull { i -> (7 + 5 * i).takeIf { it < n }?.let { buf[it].toInt() and 0xFF } }
                .joinToString(",") { when (it) { 2 -> "L"; 4 -> "R"; 8 -> "Case"; else -> "%02x".format(it) } })
        SideTracker.primaryFromBattery(buf, n)?.let { Ears.onBattery(context, it) }
        AapTrack.irkFrom(buf, n)?.let { Tracker.rememberIrk(context, it) }
        EarReport.decode(buf, n)?.let { Ears.onRaw(context, it) }
        if (n >= 8 && buf[0] == 0x04.toByte() && buf[1] == 0.toByte() && buf[2] == 0x04.toByte() &&
            buf[3] == 0.toByte() && buf[4] == 0x09.toByte() && buf[5] == 0.toByte()) {
            val id = buf[6].toInt() and 0xFF
            val v = buf[7].toInt() and 0xFF
            rememberCtl(context, mapOf(id to v))
            if (id == 0x0D) rememberMode(context, NAMES[v])
        }
    }

    /** Every AAP packet this app sends goes through here (and into the Packets screen's log). */
    fun write(context: Context, out: java.io.OutputStream, pkt: ByteArray) {
        out.write(pkt); out.flush()
        PacketLog.aap(context, true, pkt)
    }

    fun modePacket(mode: String): ByteArray = hex("0400040009000D") + byteArrayOf(MODE.getValue(mode).toByte(), 0, 0, 0)
    fun controlPacket(id: Int, v: Int): ByteArray = hex("040004000900") + byteArrayOf(id.toByte(), v.toByte(), 0, 0, 0)

    /** Ear detection report 04 00 04 00 06 00 <primary> <secondary>: 0 in ear, 1 out of ear, 2 in case.
     *  Returns the pair, or null. The AirPods compute this themselves (LibrePods "AAP Definitions"). */
    fun earFrom(p: ByteArray, n: Int): Pair<Int, Int>? = EarReport.decode(p, n)?.let { it.primary.code to it.secondary.code }

    /** In words, with left and right when the app knows which bud is the primary (see [SideTracker]). */
    fun earText(e: Pair<Int, Int>, primaryIsLeft: Boolean? = null): String = EarText.describe(EarReport.of(e), primaryIsLeft)

    fun rememberEar(context: Context, e: Pair<Int, Int>, primaryIsLeft: Boolean? = null) =
        prefs(context).edit().putString("ear", "${e.first},${e.second}").putLong("ear_t", System.currentTimeMillis())
            .putInt("ear_pl", when (primaryIsLeft) { true -> 1; false -> 0; null -> -1 }).apply()

    /** The last ear-detection report and when it came, or null. */
    fun ear(context: Context): Pair<Pair<Int, Int>, Long>? {
        val s = prefs(context).getString("ear", null) ?: return null
        val (a, b) = s.split(",").map { it.toInt() }
        return (a to b) to prefs(context).getLong("ear_t", 0)
    }

    /** Whether the primary was the left bud when the last ear report came (null: not known). */
    fun earPrimaryLeft(context: Context): Boolean? = when (prefs(context).getInt("ear_pl", -1)) { 1 -> true; 0 -> false; else -> null }

    private val NAMES = mapOf(1 to "off", 2 to "anc", 3 to "transparency", 4 to "adaptive")

    /** name -> (control id, kind). kind: "bool" (1 on / 2 off), "level" (0-100), "mask" (modes). */
    val SETTINGS = linkedMapOf(
        "adaptive_level" to (0x2E to "level"),          // 0 lets the most noise in, 100 filters the most
        "conversational_awareness" to (0x28 to "bool"),
        "personalized_volume" to (0x26 to "bool"),
        "ear_detection" to (0x0A to "bool"),
        "press_hold_modes" to (0x1A to "mask"),
        "allow_off" to (0x34 to "bool"),
        "one_bud_anc" to (0x1B to "bool"),
    )
    /** Not echoed back on this link: 0x1A, 0x34 and 0x0A are never reported at all, and 0x2E is applied
     *  silently and only re-reported when the AAP link reopens (checked live). The app remembers what it
     *  last set for these and shows them as "last set from this app". */
    val NO_ECHO = setOf(0x1A, 0x34, 0x0A, 0x2E)
    val NEVER_REPORTED = linkedSetOf("press_hold_modes", "allow_off", "ear_detection")
    val MASK_BITS = linkedMapOf("off" to 1, "anc" to 2, "transparency" to 4, "adaptive" to 8)

    /** The data byte for a setting value (a Boolean, an Int 0-100, or a List of mode names). */
    fun encode(name: String, value: Any?): Int {
        val (_, kind) = SETTINGS[name] ?: error("unknown setting $name")
        return when (kind) {
            "bool" -> if (value == true) 1 else 2
            "level" -> (value as Number).toInt().coerceIn(0, 100)
            else -> {
                @Suppress("UNCHECKED_CAST")
                val m = (value as List<String>).sumOf { MASK_BITS[it] ?: 0 }
                require(Integer.bitCount(m) >= 2) { "Press and hold needs at least two modes" }
                m
            }
        }
    }

    fun decode(kind: String, b: Int): Any? = when (kind) {
        "bool" -> when (b) { 1 -> true; 2 -> false; else -> null }
        "level" -> b
        else -> MASK_BITS.filter { (b and it.value) != 0 }.keys.toList()
    }

    /**
     * One short AAP exchange from the phone: optionally set a mode and controls, then listen up to [listenMs]
     * for the AirPods to confirm (they echo 04 00 04 00 09 00 <id> <value>). Uses the app's one AAP link
     * ([AapLink]): if it is already open (ear detection, the mic stream), the packets go on it; if not, it is
     * opened for this exchange and closed after. Returns the mode the AirPods report, or null.
     */
    private fun session(context: Context, setTo: String?, listenMs: Long,
                        controls: List<Pair<Int, Int>> = emptyList()): String? = synchronized(aapLock) {
        var seen: String? = null
        val ctl = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val listener = object : AapLink.Listener {
            override fun onPacket(p: ByteArray, n: Int) {
                if (n >= 8 && p[0] == 0x04.toByte() && p[2] == 0x04.toByte() && p[4] == 0x09.toByte() && p[5] == 0.toByte()) {
                    val id = p[6].toInt() and 0xFF
                    ctl[id] = p[7].toInt() and 0xFF
                    if (id == 0x0D) NAMES[p[7].toInt()]?.let { seen = it }
                }
            }
        }
        val wasOpen = AapLink.isOpen
        AapLink.acquire(context, "session", listener)
        try {
            // Already open: everything the AirPods report has been kept as it came, so a plain read is memory.
            if (wasOpen && setTo == null && controls.isEmpty()) return prefs(context).getString("mode", null)
            if (setTo != null) {
                Thread.sleep(150)
                AapLink.send(context, modePacket(setTo))
            }
            for (c in controls) {
                Thread.sleep(150)
                AapLink.send(context, controlPacket(c.first, c.second))
            }
            // Settings the AirPods never echo (see NO_ECHO) can't be confirmed; give the bytes a moment to
            // leave before the link closes. The rest are confirmed by the AirPods repeating them.
            val echoed = controls.filter { it.first !in NO_ECHO }
            val start = System.currentTimeMillis()
            val deadline = start + listenMs
            while (System.currentTimeMillis() < deadline) {
                // a set is confirmed once the AirPods echo it; a plain read waits for the settings burst
                val modeOk = (setTo == null && wasOpen) || (seen != null && (setTo == null || seen == setTo))
                val now = System.currentTimeMillis()
                val ctlOk = when {
                    controls.isEmpty() -> wasOpen || (ctl.size >= 3 && now - start > 600)
                    echoed.isEmpty() -> now - start > 500
                    else -> echoed.all { ctl[it.first] == it.second }
                }
                if (modeOk && ctlOk) break
                if (controls.isNotEmpty() && setTo == null && ctlOk) break
                Thread.sleep(50)
            }
        } finally {
            AapLink.release("session", listener)
        }
        return seen ?: if (wasOpen) prefs(context).getString("mode", null) else null
    }

    /** Serialises this app's use of the AAP link (see [AapLink]): one exchange at a time. */
    val aapLock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rememberMode(context: Context, mode: String?) {
        if (mode != null) prefs(context).edit().putString("mode", mode).putLong("mode_t", System.currentTimeMillis()).apply()
    }

    private fun rememberCtl(context: Context, ctl: Map<Int, Int>) {
        val saved = runCatching { JSONObject(prefs(context).getString("ctl", "{}")!!) }.getOrDefault(JSONObject())
        for ((k, v) in ctl) saved.put(k.toString(), v)
        prefs(context).edit().putString("ctl", saved.toString()).putLong("ctl_t", System.currentTimeMillis()).apply()
    }

    /** One control value (0x09 id) as the AirPods last reported it, or null. */
    fun ctlValue(context: Context, id: Int): Int? =
        runCatching { JSONObject(prefs(context).getString("ctl", "{}")!!) }.getOrNull()
            ?.opt(id.toString())?.let { (it as? Number)?.toInt() }

    /** The AirPods' settings as last reported to the phone (re-read if older than [maxAgeMs]).
     *  Same JSON shape as the laptop panel's airpods.settings; a missing value is JSON null. */
    fun settings(context: Context, maxAgeMs: Long = 60_000): JSONObject {
        val p = prefs(context)
        if (System.currentTimeMillis() - p.getLong("ctl_t", 0) > maxAgeMs && status(context) != "fail")
            runCatching { session(context, null, 2000) }.onSuccess { rememberMode(context, it) }
        val ctl = runCatching { JSONObject(p.getString("ctl", "{}")!!) }.getOrDefault(JSONObject())
        val out = JSONObject()
        for ((name, spec) in SETTINGS) {
            val raw = ctl.opt(spec.first.toString())
            val v = if (raw is Number) decode(spec.second, raw.toInt()) else null
            out.put(name, when (v) { null -> JSONObject.NULL; is List<*> -> org.json.JSONArray(v); else -> v })
        }
        return out
    }

    /** Change one AirPods setting straight from the phone. Throws with a readable reason on failure. */
    fun setSetting(context: Context, name: String, value: Any?) =
        setSettings(context, listOf(name to value))

    /** Several settings in one AAP session, in order (e.g. allow-off before the press-and-hold modes). */
    fun setSettings(context: Context, items: List<Pair<String, Any?>>, mode: String? = null) {
        val ctls = items.map { (name, value) -> (SETTINGS[name]?.first ?: error("unknown setting $name")) to encode(name, value) }
        try {
            session(context, mode, 1500, ctls)
            remember(context, "ok", "")
            rememberCtl(context, ctls.toMap())
            if (mode != null) rememberMode(context, mode)
        } catch (e: Throwable) {
            val why = (e.cause ?: e).let { it.message ?: it.javaClass.simpleName }
            remember(context, "fail", why)
            throw RuntimeException(why)
        }
    }

    /** Press-and-hold cycle: sent once, with "allow off" first when Off is among the modes. */
    fun setPressHold(context: Context, modes: List<String>) {
        val items = mutableListOf<Pair<String, Any?>>()
        if ("off" in modes) items += "allow_off" to true
        items += "press_hold_modes" to modes
        setSettings(context, items)
    }

    /** Block-noise slider: [level] is the raw AAP level (100 blocks the most, 0 hears the most). Also
     *  selects Adaptive mode, like Apple's own slider. */
    fun setAdaptive(context: Context, level: Int) =
        setSettings(context, listOf("adaptive_level" to level), mode = "adaptive")

    /** True for a setting the AirPods never report: what the UI shows is the last value set from this app. */
    fun fromApp(name: String) = name in NEVER_REPORTED

    /** Reconnect the AAP L2CAP link (not Bluetooth itself) so the AirPods report their settings again. */
    fun readBack(context: Context): JSONObject {
        // With the link held open (ear detection), reopen it for a fresh report; else a short session does it.
        if (AapLink.isOpen && runCatching { AapLink.reopen(context) }.getOrDefault(false)) {
            Thread.sleep(1500)
            remember(context, "ok", "")
            return settings(context, Long.MAX_VALUE)
        }
        runCatching { session(context, null, 2500) }.onSuccess { rememberMode(context, it) }.onFailure { e ->
            val why = (e.cause ?: e).let { it.message ?: it.javaClass.simpleName }
            remember(context, "fail", why); throw RuntimeException(why)
        }
        remember(context, "ok", "")
        return settings(context, Long.MAX_VALUE)
    }

    /** Set the noise mode straight from the phone. Throws with a readable reason on failure. */
    fun setMode(context: Context, mode: String) {
        try {
            val echoed = session(context, mode, 1500)
            remember(context, "ok", "")
            rememberMode(context, echoed ?: mode)
        } catch (e: Throwable) {
            val why = (e.cause ?: e).let { it.message ?: it.javaClass.simpleName }
            remember(context, "fail", why)
            throw RuntimeException(why)
        }
    }

    /** The AirPods' current noise mode as seen from the phone (re-read if older than [maxAgeMs]). */
    fun mode(context: Context, maxAgeMs: Long = 15_000): String? {
        val p = prefs(context)
        val cached = p.getString("mode", null)
        if (cached != null && System.currentTimeMillis() - p.getLong("mode_t", 0) < maxAgeMs) return cached
        if (status(context) == "fail") return cached
        val read = runCatching { session(context, null, 1500) }.getOrNull()
        rememberMode(context, read)
        return read ?: cached
    }

    /** Battery from one short BLE scan of the AirPods' proximity broadcast, or null. */
    fun scanBattery(context: Context, millis: Long = 4000): JSONObject? {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return null
        var best: Pair<Int, ByteArray>? = null
        var bestAddr = ""
        val done = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val m = r.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
                // any AirPods "proximity pairing" broadcast; the strongest (closest) one wins
                if (m.size >= 8 && m[0] == 0x07.toByte()) {
                    if (best == null || r.rssi > best!!.first) { best = r.rssi to m; bestAddr = r.device.address }
                }
            }
        }
        val filter = ScanFilter.Builder().setManufacturerData(0x004C, byteArrayOf(0x07), byteArrayOf(0xFF.toByte())).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, cb)
            done.await(millis, TimeUnit.MILLISECONDS)
        } catch (e: SecurityException) {
            return null
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
        // Last seen: the strongest advert while the AirPods are on this phone teaches the app their model
        best?.let { (rssi, m) ->
            ProxAdvert.parse(m)?.let { ad ->
                runCatching { Tracker.onAdvert(context, bestAddr, rssi, ad, learnIt = Handoff.local(context) && rssi > -70) }
            }
        }
        return best?.second?.let { decode(it) }
    }

    /** Same decoding as the laptop panel (OpenPods layout). */
    private fun decode(m: ByteArray): JSONObject {
        val h = m.joinToString("") { "%02x".format(it) }
        val flip = (h[10].digitToInt(16) and 0x02) == 0
        val l = h[if (flip) 12 else 13].digitToInt(16)
        val r = h[if (flip) 13 else 12].digitToInt(16)
        val case = h[15].digitToInt(16)
        val ch = h[14].digitToInt(16)
        val out = JSONObject()
        for ((name, v, charging) in listOf(
            Triple("left", l, ch and (if (flip) 0b10 else 0b01)),
            Triple("right", r, ch and (if (flip) 0b01 else 0b10)),
            Triple("case", case, ch and 0b100))) {
            if (v <= 10) out.put(name, JSONObject().put("level", v * 10).put("status", if (charging != 0) "charging" else "discharging"))
        }
        return out
    }
}
