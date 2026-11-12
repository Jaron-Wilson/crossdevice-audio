package dev.jaronwilson.headphones

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Control the Skullcandy Crusher ANC 2 straight from the phone, no laptop needed.
 *
 * The Crusher speaks a plain-text AT protocol over classic Bluetooth RFCOMM channel 9 (the same one
 * the laptop controller uses): `AT.UIAUDIO=anc,on`, `AT.CRUSHER=level,5`, `AT.UIAUDIO?`, etc. Android
 * has no public API to open a numbered RFCOMM channel, so we use the hidden `createRfcommSocket(int)`
 * via HiddenApiBypass — the RFCOMM analogue of what [PhoneDirect] does for the AirPods' L2CAP channel.
 *
 * The Crusher is found by name among the phone's paired devices, so no MAC address is baked in.
 */
object CrusherDirect {
    private const val CH = 9
    private val lock = Any()

    // connected() opens an A2DP proxy (~1s); cache the answer briefly so one tap doesn't check 3x.
    @Volatile private var connCache: Pair<Long, Boolean>? = null

    /** The paired Crusher, if there is one (matched by name, so no hardcoded MAC). */
    fun device(context: Context): BluetoothDevice? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return runCatching {
            adapter.bondedDevices?.firstOrNull { (it.name ?: "").contains("crusher", true) }
        }.getOrNull()
    }

    /** Is the Crusher currently connected to this phone (A2DP link up)? Cached ~3s. */
    fun connected(context: Context): Boolean {
        connCache?.let { (t, v) -> if (System.currentTimeMillis() - t < 3000) return v }
        val v = connectedLive(context)
        connCache = System.currentTimeMillis() to v
        return v
    }

    private fun connectedLive(context: Context): Boolean {
        val dev = device(context) ?: return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val ok = runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runCatching {
                        result.set((proxy as BluetoothA2dp).connectedDevices.any { it.address == dev.address })
                    }
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    latch.countDown()
                }
                override fun onServiceDisconnected(profile: Int) { latch.countDown() }
            }, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (!ok) return false
        runCatching { latch.await(1500, TimeUnit.MILLISECONDS) }
        return result.get()
    }

    private fun socket(dev: BluetoothDevice): BluetoothSocket {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
        // Preferred: the hidden createInsecureRfcommSocket(int) / createRfcommSocket(int) that open a
        // numbered channel directly (channel 9), matching the laptop controller.
        for (name in listOf("createInsecureRfcommSocket", "createRfcommSocket")) {
            runCatching {
                return HiddenApiBypass.invoke(BluetoothDevice::class.java, dev, name, CH) as BluetoothSocket
            }
        }
        error("No usable RFCOMM socket method (createRfcommSocket) on this device")
    }

    /**
     * Open one RFCOMM session, send [cmds] (each gets a trailing CR), and if [read] collect the
     * replies (up to an "OK"/"ERROR" per command). Returns the combined reply text.
     */
    private fun session(context: Context, cmds: List<String>, read: Boolean): String =
        sessionEach(context, cmds, read).joinToString("\n")

    /** Like [session], but one reply string per command. */
    private fun sessionEach(context: Context, cmds: List<String>, read: Boolean): List<String> = synchronized(lock) {
        val dev = device(context) ?: error("No paired Crusher")
        val sock = socket(dev)
        val replies = mutableListOf<String>()
        try {
            sock.connect()
            val out = sock.outputStream
            val input = sock.inputStream
            for (c in cmds) {
                out.write((c + "\r").toByteArray()); out.flush()
                Thread.sleep(350)
                if (!read) continue
                val sb = StringBuilder()
                val deadline = System.currentTimeMillis() + 1500
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    if (input.available() > 0) {
                        val n = input.read(buf)
                        if (n > 0) sb.append(String(buf, 0, n))
                        if (sb.contains("OK") || sb.contains("ERROR")) break
                    } else Thread.sleep(40)
                }
                replies += sb.toString()
            }
        } finally {
            runCatching { sock.close() }
        }
        return replies
    }

    // Extras found in Skull-iQ's AT code (generic Bragi AT commands). Probed live on this firmware:
    // AT.UIAUDIO=transparency|atoncall|atonpause answer with on/off ONLY (there is no transparency
    // level, and anc_filter / samplingrate / AT.VOLUME? / AT.FEATURE=enable are ERROR), so no level
    // control exists and we never send commands we have not seen answered. Read once per connection.
    private val EXTRA_Q = linkedMapOf("at_on_call" to "AT.UIAUDIO=atoncall", "at_on_pause" to "AT.UIAUDIO=atonpause")
    @Volatile private var extras: JSONObject? = null

    private fun answered(r: String) = r.contains("OK") && !r.contains("ERROR")

    private fun readExtras(context: Context): JSONObject {
        extras?.let { return it }
        val replies = runCatching { sessionEach(context, EXTRA_Q.values.toList(), read = true) }.getOrNull()
            ?: return JSONObject()
        val out = JSONObject()
        for ((k, r) in EXTRA_Q.keys.zip(replies)) {
            if (!answered(r)) continue
            Regex(":\\s*(on|off)").find(r)?.let { out.put(k, it.groupValues[1] == "on") }
        }
        // read-only feature flags: AT.FEATURE=count, then AT.FEATURE=info,0,<count> ("name,id,policy,x,enabled|disabled")
        runCatching {
            val cnt = Regex("(\\d+)").find(sessionEach(context, listOf("AT.FEATURE=count"), read = true).first())
                ?.groupValues?.get(1)?.toInt() ?: 0
            if (cnt > 0) {
                val r = sessionEach(context, listOf("AT.FEATURE=info,0,${minOf(16, cnt)}"), read = true).first()
                val arr = org.json.JSONArray()
                for (m in Regex("\\.FEATURE:\\s*([^,|\\r\\n]+),([^,]+),[^,]*,[^,]*,(enabled|disabled)").findAll(r))
                    arr.put(JSONObject().put("name", m.groupValues[1].trim()).put("enabled", m.groupValues[3] == "enabled"))
                if (arr.length() > 0) out.put("features", arr)
            }
        }
        extras = out
        return out
    }

    /** Set "bass_on", "at_on_call" or "at_on_pause" (Boolean). */
    fun setSetting(context: Context, name: String, value: Any) {
        val cmd = when (name) {
            // Skull-iQ's CRUSHER_SET_STATE is "AT.CRUSHER=" + on|off ("AT.CRUSHER=state,on" answers ERROR)
            "bass_on" -> "AT.CRUSHER=" + (if (value == true) "on" else "off")
            "at_on_call" -> "AT.UIAUDIO=atoncall," + (if (value == true) "on" else "off")
            "at_on_pause" -> "AT.UIAUDIO=atonpause," + (if (value == true) "on" else "off")
            else -> error("Unknown Crusher setting $name")
        }
        val r = sessionEach(context, listOf(cmd), read = true).firstOrNull() ?: ""
        if (!answered(r)) error("Crusher refused $cmd")
        if (name != "bass_on") extras?.put(name, value)
    }

    /** Set the noise mode: "anc", "transparency", or "off". */
    fun setMode(context: Context, mode: String) {
        val cmds = when (mode) {
            "anc" -> listOf("AT.UIAUDIO=transparency,off", "AT.UIAUDIO=anc,on")
            "transparency" -> listOf("AT.UIAUDIO=transparency,on")
            "off" -> listOf("AT.UIAUDIO=transparency,off", "AT.UIAUDIO=anc,off")
            else -> error("Unknown Crusher mode $mode")
        }
        session(context, cmds, read = false)
    }

    /** Rumble: Sensory Bass on at 10. Off puts back the level and on/off state from before. */
    fun rumbleOn(context: Context) = rumblePrefs(context).contains("prev")

    private fun rumblePrefs(context: Context) = context.getSharedPreferences("rumble", Context.MODE_PRIVATE)

    fun setRumble(context: Context, on: Boolean) {
        val p = rumblePrefs(context)
        if (on) {
            if (!p.contains("prev")) {
                val s = state(context) ?: error("Crusher not connected to this phone")
                if (!s.optBoolean("online")) error("Crusher not answering")
                p.edit().putString("prev", JSONObject().put("bass_on", s.opt("bass_on")).put("bass", s.opt("bass")).toString()).apply()
            }
            val r = sessionEach(context, listOf("AT.CRUSHER=on", "AT.CRUSHER=level,10"), read = true)
            if (r.any { !answered(it) }) error("Crusher refused Rumble")
        } else {
            val prev = runCatching { JSONObject(p.getString("prev", null) ?: "") }.getOrNull()
            p.edit().remove("prev").apply()
            if (prev == null) return                       // nothing saved: leave it as it is
            val cmds = mutableListOf<String>()
            if (prev.has("bass")) cmds += "AT.CRUSHER=level,${prev.optInt("bass", 5).coerceIn(0, 10)}"
            if (prev.opt("bass_on") == false) cmds += "AT.CRUSHER=off"
            if (cmds.isNotEmpty()) session(context, cmds, read = false)
        }
    }

    /** Set sensory-bass level, 0-10. */
    fun setBass(context: Context, level: Int) {
        session(context, listOf("AT.CRUSHER=level,${level.coerceIn(0, 10)}"), read = false)
    }

    /** Live state read straight from the Crusher, or null if it isn't on this phone. */
    fun state(context: Context): JSONObject? {
        if (!connected(context)) { extras = null; return null }
        val txt = runCatching { session(context, listOf("AT.UIAUDIO?", "AT.CRUSHER?"), read = true) }
            .getOrElse { return JSONObject().put("online", false) }
        val anc = Regex("anc:\\s*(on|off)").find(txt)?.groupValues?.get(1) == "on"
        val transparency = Regex("transparency:\\s*(on|off)").find(txt)?.groupValues?.get(1) == "on"
        val lvl = Regex("level:\\s*(\\d+)\\s*/\\s*(\\d+)").find(txt)
        val bassOn = Regex("(?i)state:\\s*(on|off)").find(txt)?.groupValues?.get(1)?.lowercase()
        return JSONObject()
            .put("online", true)
            .put("anc", anc)
            .put("transparency", transparency)
            .put("extras", readExtras(context))
            .apply {
                if (bassOn != null) put("bass_on", bassOn == "on")
                if (lvl != null) {
                    put("bass", lvl.groupValues[1].toInt())
                    put("bass_max", lvl.groupValues[2].toInt())
                }
            }
    }
}
