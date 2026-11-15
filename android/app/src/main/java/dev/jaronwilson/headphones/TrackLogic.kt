package dev.jaronwilson.headphones

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.pow

/*
 * The testable parts of tracking (no Android classes here): the AirPods' BLE advert, matching an advert
 * to *your* AirPods, RSSI smoothing, the distance guess, and the on-phone event log with its rotation.
 */

/**
 * One Apple "proximity pairing" advert (manufacturer data 0x004C, type 0x07), the bytes after the company
 * id. Layout as documented by OpenPods and LibrePods:
 *   [0] 0x07  [1] length  [2] paired flag  [3..4] model  [5] status  [6] bud battery nibbles
 *   [7] charging flags (high nibble) + case battery (low nibble)  [8] lid  [9] colour  [10] connection
 * then 16 encrypted bytes (not used here). Battery nibbles are tens of percent; 15 means "unknown".
 */
data class ProxAdvert(
    val model: Int,
    val status: Int,
    val left: Int?, val right: Int?, val case: Int?,
    val leftCharging: Boolean, val rightCharging: Boolean, val caseCharging: Boolean,
    val color: Int,
) {
    /** The bud sending this advert is in the case (status bit 6). */
    val thisInCase get() = status and 0x40 != 0
    /** Exactly one bud is in the case (bit 4). */
    val oneInCase get() = status and 0x10 != 0
    /** Both buds are in the case (bit 2). */
    val bothInCase get() = status and 0x04 != 0
    /** The primary bud (the one holding the link) is the left one (bit 5). */
    val primaryLeft get() = status and 0x20 != 0
    private val xor get() = !primaryLeft xor thisInCase
    val leftInEar get() = if (xor) status and 0x08 != 0 else status and 0x02 != 0
    val rightInEar get() = if (xor) status and 0x02 != 0 else status and 0x08 != 0

    /** What the advert says about where the buds are, in words ("both in the case", "left in ear", ...). */
    fun budsText(): String = when {
        bothInCase -> "both buds in the case"
        leftInEar && rightInEar -> "both buds in ears"
        oneInCase && leftInEar -> "left bud out (in ear), right in the case"
        oneInCase && rightInEar -> "right bud out (in ear), left in the case"
        oneInCase -> "one bud out of the case"
        leftInEar -> "left in ear, right out of the case"
        rightInEar -> "right in ear, left out of the case"
        else -> "both buds out of the case"
    }

    /** "L 80% · R 70% · Case 50%" (only the levels the advert carries). */
    fun batteryText(): String = listOfNotNull(
        left?.let { "L $it%" + if (leftCharging) "+" else "" },
        right?.let { "R $it%" + if (rightCharging) "+" else "" },
        case?.let { "Case $it%" + if (caseCharging) "+" else "" }).joinToString(" · ")

    companion object {
        /** Model ids (bytes 3..4) as LibrePods lists them. */
        val MODELS = mapOf(
            0x0220 to "AirPods 1", 0x0F20 to "AirPods 2", 0x1320 to "AirPods 3", 0x1920 to "AirPods 4",
            0x1B20 to "AirPods 4 (ANC)", 0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro 2",
            0x2420 to "AirPods Pro 2 (USB-C)", 0x0A20 to "AirPods Max", 0x1F20 to "AirPods Max (USB-C)")

        fun modelName(id: Int) = MODELS[id] ?: "AirPods (model %04X)".format(id)

        fun parse(m: ByteArray?): ProxAdvert? {
            if (m == null || m.size < 11 || m[0] != 0x07.toByte()) return null
            val u = { i: Int -> m[i].toInt() and 0xFF }
            val status = u(5)
            // battery nibbles are ordered primary-first; bit 5 says whether the primary is the left bud
            val flip = status and 0x20 == 0
            val hi = u(6) shr 4; val lo = u(6) and 0x0F
            val l = if (flip) hi else lo
            val r = if (flip) lo else hi
            val ch = u(7) shr 4
            val case = u(7) and 0x0F
            fun pct(n: Int) = if (n <= 10) n * 10 else null
            return ProxAdvert(
                model = (u(3) shl 8) or u(4), status = status,
                left = pct(l), right = pct(r), case = pct(case),
                leftCharging = ch and (if (flip) 0b10 else 0b01) != 0,
                rightCharging = ch and (if (flip) 0b01 else 0b10) != 0,
                caseCharging = ch and 0b100 != 0,
                color = u(9))
        }
    }
}

/** Resolvable private addresses (Bluetooth Core spec, "ah" function), the way LibrePods checks them. */
object Rpa {
    /** True if [address] ("AA:BB:CC:DD:EE:FF") was generated from [irk] (16 bytes, as the AirPods send it). */
    fun resolves(address: String, irk: ByteArray): Boolean {
        if (irk.size != 16) return false
        val parts = address.split(":")
        if (parts.size != 6) return false
        val rpa = runCatching { parts.map { it.toInt(16).toByte() }.reversed().toByteArray() }.getOrNull() ?: return false
        if (rpa[5].toInt() and 0xC0 != 0x40) return false          // not a resolvable private address
        return ah(irk, rpa.copyOfRange(3, 6)).contentEquals(rpa.copyOfRange(0, 3))
    }

    fun ah(irk: ByteArray, prand: ByteArray): ByteArray {
        val r = ByteArray(16); prand.copyInto(r, 0, 0, 3)
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(irk.reversedArray(), "AES"))
        return c.doFinal(r.reversedArray()).reversedArray().copyOfRange(0, 3)
    }
}

/** What the phone knows about *your* AirPods, to tell their adverts from anyone else's. */
data class ApIdentity(
    val model: Int? = null,
    val color: Int? = null,
    val irk: ByteArray? = null,
    /** recent battery levels (percent), from the last advert matched while they were on this phone */
    val left: Int? = null, val right: Int? = null, val case: Int? = null,
)

enum class MatchLevel { EXACT, LIKELY, MODEL_ONLY, NO }

object AdvertMatch {
    /**
     * AirPods advertise from a random address that changes about every 15 minutes, so the address alone
     * can't identify them. Best: the IRK the AirPods hand over on the phone's own AAP link resolves the
     * address exactly. Without it: same model and colour as yours, and battery levels within [tolerance]
     * points of the last ones seen from yours. That is a guess: identical AirPods with similar battery
     * levels can't be told apart.
     */
    fun match(address: String, ad: ProxAdvert, id: ApIdentity, tolerance: Int = 20): MatchLevel {
        id.irk?.let { return if (Rpa.resolves(address, it)) MatchLevel.EXACT else MatchLevel.NO }
        if (id.model != null && id.model != ad.model) return MatchLevel.NO
        if (id.color != null && id.color != ad.color) return MatchLevel.NO
        val pairs = listOf(id.left to ad.left, id.right to ad.right, id.case to ad.case)
            .filter { it.first != null && it.second != null }
        if (pairs.any { abs(it.first!! - it.second!!) > tolerance }) return MatchLevel.NO
        if (id.model == null) return MatchLevel.MODEL_ONLY
        return if (pairs.isNotEmpty()) MatchLevel.LIKELY else MatchLevel.MODEL_ONLY
    }
}

/** 1-D Kalman filter for RSSI: steadier than raw readings, quicker than a long average. */
class RssiKalman(private val q: Double = 2.0, private val r: Double = 16.0) {
    var value: Double? = null; private set
    private var p = 1.0
    fun update(z: Double): Double {
        val x = value ?: run { value = z; p = r; return z }
        p += q
        val k = p / (p + r)
        val nx = x + k * (z - x)
        p *= (1 - k)
        value = nx
        return nx
    }
    fun reset() { value = null; p = 1.0 }
}

/** Exponential moving average, the simpler alternative. */
class Ema(private val alpha: Double = 0.25) {
    var value: Double? = null; private set
    fun update(z: Double): Double = (value?.let { it + alpha * (z - it) } ?: z).also { value = it }
}

enum class Heat(val label: String) { HOT("Hot"), WARM("Warm"), COLD("Cold") }

object Proximity {
    /** Log-distance path loss: [txAt1m] is the RSSI at one metre, [n] the environment factor (2 open air,
     *  ~2.5-3 indoors). Very rough: a body, a wall or a pocket changes RSSI by 10 dB or more. */
    fun distanceMeters(rssi: Double, txAt1m: Double = -59.0, n: Double = 2.5): Double =
        10.0.pow((txAt1m - rssi) / (10 * n))

    fun heat(rssi: Double): Heat = when {
        rssi >= -62 -> Heat.HOT
        rssi >= -78 -> Heat.WARM
        else -> Heat.COLD
    }

    /** 0 (far, -100 dBm) .. 1 (touching, -40 dBm), for the ring meter. */
    fun fraction(rssi: Double): Double = ((rssi + 100) / 60).coerceIn(0.0, 1.0)

    /** Time between buzzes: slow when cold, fast when hot. */
    fun buzzIntervalMs(rssi: Double): Long = (1600 - fraction(rssi) * 1450).toLong().coerceIn(150, 1600)

    /** "about 2 m" style text; anything past ~20 m is just "far". */
    fun distanceText(rssi: Double): String {
        val d = distanceMeters(rssi)
        return when {
            d < 0.5 -> "within half a metre"
            d < 10 -> "about %.0f m".format(d.coerceAtLeast(1.0))
            d < 20 -> "about %.0f m".format(Math.round(d / 5.0) * 5.0)
            else -> "far (20 m or more)"
        }
    }
}

/**
 * One thing the phone remembers about a device.
 * kind: "disconnected" (left this phone), "nearby" (its advert was heard), "lost" (adverts stopped).
 */
data class SeenEvent(
    val device: String,
    val kind: String,
    val t: Long,
    val lat: Double? = null, val lon: Double? = null, val acc: Float? = null,
    val battery: String = "",
    val buds: String = "",
    val rssi: Int? = null,
    val note: String = "",
) {
    val hasLocation get() = lat != null && lon != null
}

object EventLog {
    const val MAX_PER_DEVICE = 50
    const val NEARBY_GAP_MS = 5 * 60_000L

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun encode(e: SeenEvent): String = listOf(
        e.device, e.kind, e.t.toString(), e.lat?.toString() ?: "", e.lon?.toString() ?: "", e.acc?.toString() ?: "",
        e.battery, e.buds, e.rssi?.toString() ?: "", e.note).joinToString("\t") { clean(it) }

    fun decode(line: String): SeenEvent? {
        val f = line.split("\t")
        if (f.size < 10) return null
        return runCatching {
            SeenEvent(f[0], f[1], f[2].toLong(), f[3].toDoubleOrNull(), f[4].toDoubleOrNull(), f[5].toFloatOrNull(),
                f[6], f[7], f[8].toIntOrNull(), f[9])
        }.getOrNull()
    }

    fun decodeAll(text: String): List<SeenEvent> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { decode(it) }.toList()
    fun encodeAll(events: List<SeenEvent>): String = events.joinToString("\n") { encode(it) } + if (events.isEmpty()) "" else "\n"

    /**
     * Add [e] and keep at most [max] events per device (oldest dropped). A run of "nearby" sightings
     * collapses into one entry (the newest), so the history isn't flooded while you sit next to them.
     */
    fun append(events: List<SeenEvent>, e: SeenEvent, max: Int = MAX_PER_DEVICE): List<SeenEvent> {
        val list = events.toMutableList()
        val lastIdx = list.indexOfLast { it.device == e.device }
        if (e.kind == "nearby" && lastIdx >= 0 && list[lastIdx].kind == "nearby") {
            val prev = list[lastIdx]
            // keep a location from the earlier sighting if this one has none
            list[lastIdx] = if (!e.hasLocation && prev.hasLocation) e.copy(lat = prev.lat, lon = prev.lon, acc = prev.acc) else e
        } else list += e
        val mine = list.filter { it.device == e.device }
        if (mine.size <= max) return list
        val drop = mine.take(mine.size - max).toSet()
        return list.filterNot { it in drop }
    }

    /** Should a "nearby" sighting be written now? At most one every [gapMs] per device. */
    fun nearbyDue(lastWrite: Long, now: Long, gapMs: Long = NEARBY_GAP_MS) = now - lastWrite >= gapMs

    fun forDevice(events: List<SeenEvent>, device: String) = events.filter { it.device == device }.sortedByDescending { it.t }
}

/** AAP packets the tracker reads from the phone's own link to the AirPods (formats from LibrePods). */
object AapTrack {
    /** Ask for the IRK: 04 00 04 00 30 00 <type 0x01> 00. */
    val REQUEST_IRK = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x30, 0x00, 0x01, 0x00)

    /** The IRK from a proximity-keys reply (04 00 04 00 31 00 <count> then <type> 00 <len> 00 <key>...). */
    fun irkFrom(p: ByteArray, n: Int = p.size): ByteArray? {
        if (n < 7 || p[0] != 0x04.toByte() || p[2] != 0x04.toByte() || p[4] != 0x31.toByte()) return null
        var off = 7
        repeat(p[6].toInt() and 0xFF) {
            if (off + 4 > n) return null
            val type = p[off].toInt() and 0xFF
            val len = p[off + 2].toInt() and 0xFF
            off += 4
            if (off + len > n) return null
            if (type == 0x01 && len == 16) return p.copyOfRange(off, off + len)
            off += len
        }
        return null
    }

    /** AAP battery component types (LibrePods `battery.hpp`): 2 is the RIGHT bud, 4 the LEFT, 8 the case,
     *  1 a single-unit headset. Not the other way round: 1.9.1 and earlier had left and right swapped. */
    const val TYPE_RIGHT = 2
    const val TYPE_LEFT = 4
    const val TYPE_CASE = 8
    /** Component states: 1 charging, 2 discharging, 4 disconnected (0 unknown). */
    const val CHARGING = 1
    const val DISCHARGING = 2
    const val DISCONNECTED = 4

    /** Battery packet 04 00 04 00 04 00 <count> then <type> 01 <level> <status> 01 per component (types and
     *  states above). Returns "L 93% · R 91% · Case 40%+" (always left, right, case; + = charging) or null. */
    fun batteryFrom(p: ByteArray, n: Int = p.size): String? {
        if (n < 7 || p[0] != 0x04.toByte() || p[2] != 0x04.toByte() || p[4] != 0x04.toByte() || p[5] != 0.toByte()) return null
        val found = HashMap<Int, String>()
        var off = 7
        repeat(p[6].toInt() and 0xFF) {
            if (off + 4 > n) return@repeat
            val type = p[off].toInt() and 0xFF
            val level = p[off + 2].toInt() and 0xFF
            val st = p[off + 3].toInt() and 0xFF
            if (st != DISCONNECTED && level in 0..100) found[type] = "$level%" + if (st == CHARGING) "+" else ""
            off += 5
        }
        val out = listOf(TYPE_LEFT to "L", TYPE_RIGHT to "R", TYPE_CASE to "Case", 1 to "").mapNotNull { (t, name) ->
            found[t]?.let { if (name.isEmpty()) it else "$name $it" } }
        return out.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
