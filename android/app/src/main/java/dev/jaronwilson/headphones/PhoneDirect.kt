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
 */
object PhoneDirect {
    private const val PSM = 0x1001
    private const val TYPE_L2CAP = 3
    private val HANDSHAKE = hex("00000400010002000000000000000000")
    private val FEATURES = hex("040004004D00FF00000000000000")
    private val MODE = mapOf("off" to 1, "anc" to 2, "transparency" to 3, "adaptive" to 4)
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
    private fun socket(dev: BluetoothDevice): BluetoothSocket {
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
    private val NAMES = mapOf(1 to "off", 2 to "anc", 3 to "transparency", 4 to "adaptive")

    /**
     * One short AAP session from the phone: handshake, ask for notifications, optionally set a mode,
     * then listen [listenMs] for the AirPods' noise-mode report (04 00 04 00 09 00 0D <mode>).
     * Returns the mode the AirPods report, or null if they said nothing in time.
     */
    private fun session(context: Context, setTo: String?, listenMs: Long): String? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("no Bluetooth")
        val mac = Config.airpodsMac(context).ifEmpty { error("Pick your AirPods in the Headphones app") }
        val sock = socket(adapter.getRemoteDevice(mac))
        var seen: String? = null
        try {
            sock.connect()
            val out = sock.outputStream
            val reader = Thread {
                val buf = ByteArray(1024)
                runCatching {
                    while (true) {
                        val n = sock.inputStream.read(buf)
                        if (n <= 0) break
                        for (i in 0..n - 8) {
                            if (buf[i] == 0x04.toByte() && buf[i + 4] == 0x09.toByte() && buf[i + 6] == 0x0D.toByte())
                                NAMES[buf[i + 7].toInt()]?.let { seen = it }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            out.write(HANDSHAKE); out.flush(); Thread.sleep(250)
            out.write(FEATURES); out.flush(); Thread.sleep(150)
            out.write(NOTIFY); out.flush()
            if (setTo != null) {
                Thread.sleep(150)
                out.write(hex("0400040009000D") + byteArrayOf(MODE.getValue(setTo).toByte(), 0, 0, 0)); out.flush()
            }
            val deadline = System.currentTimeMillis() + listenMs
            while (System.currentTimeMillis() < deadline) {
                // a set is confirmed once the AirPods echo the new mode
                if (seen != null && (setTo == null || seen == setTo)) break
                Thread.sleep(50)
            }
            runCatching { sock.close() }
            reader.join(300)
        } finally {
            runCatching { sock.close() }
        }
        return seen
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rememberMode(context: Context, mode: String?) {
        if (mode != null) prefs(context).edit().putString("mode", mode).putLong("mode_t", System.currentTimeMillis()).apply()
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
        val done = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val m = r.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
                // any AirPods "proximity pairing" broadcast; the strongest (closest) one wins
                if (m.size >= 8 && m[0] == 0x07.toByte()) {
                    if (best == null || r.rssi > best!!.first) best = r.rssi to m
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
