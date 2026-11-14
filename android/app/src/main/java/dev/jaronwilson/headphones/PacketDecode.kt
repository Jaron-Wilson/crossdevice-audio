package dev.jaronwilson.headphones

/**
 * Turns one Bluetooth packet into what the Packets screen shows: masked hex and a plain-English meaning.
 * Pure logic (unit-tested in PacketDecodeTest). The parsing itself is the app's own: [PhoneDirect] (modes,
 * settings, ear detection), [AapTrack] (battery, keys), [AapMic] (mic stream) and [CrusherDirect] (AT replies).
 * Formats are in docs/PROTOCOL.md.
 *
 * Privacy, always on (the screen is meant to be filmed): Bluetooth addresses, serial numbers, device names and
 * key bytes are shown as `xx` in the hex and as "(hidden)" in the text. Besides the fields known to hold them,
 * any printable text of 4+ characters inside an AAP packet is hidden (names and serials are text), except
 * model numbers and firmware versions, and so is any byte run equal to a [secrets] value (the AirPods' and
 * the Crusher's addresses, either byte order, and the AirPods' IRK) wherever it turns up.
 */
object PacketDecode {
    /** [hex] is spaced pairs ("04 00 xx"); [text] is the readable form of a text (AT) packet, masked. */
    data class Shown(val hex: String, val text: String?, val meaning: String)

    /** Model numbers and firmware versions: not personal, so the device-info packet may show them. */
    val NOT_PERSONAL = Regex("^(A\\d{4}|\\d+[A-Z]\\d+[a-z]?|\\d+\\.\\d+[0-9.]*)$")

    /** Mic audio SDUs show only their header: the rest is the room's sound. */
    private const val MIC_HEADER = 22

    private val MODE_LABEL = mapOf("off" to "Off", "anc" to "Noise Cancellation", "transparency" to "Transparency",
        "adaptive" to "Adaptive")
    private val SETTING_LABEL = mapOf(
        "adaptive_level" to "Adaptive noise level", "conversational_awareness" to "Conversational Awareness",
        "personalized_volume" to "Personalized Volume", "ear_detection" to "Automatic ear detection",
        "press_hold_modes" to "Press-and-hold modes", "allow_off" to "Offer Off in press-and-hold",
        "one_bud_anc" to "Noise cancelling with one AirPod")

    fun hex(b: ByteArray, hidden: BooleanArray? = null, n: Int = b.size): String =
        (0 until n).joinToString(" ") { if (hidden?.getOrNull(it) == true) "xx" else "%02X".format(b[it]) }

    private fun u(p: ByteArray, i: Int) = p[i].toInt() and 0xFF

    private fun hideRange(h: BooleanArray, from: Int, to: Int) { for (i in from until minOf(to, h.size)) if (i >= 0) h[i] = true }

    /** Hide every place [s] (or [s] reversed: addresses go over the air least-significant byte first) occurs. */
    private fun hideSecret(p: ByteArray, h: BooleanArray, s: ByteArray) {
        if (s.size < 4) return
        for (pat in listOf(s, s.reversedArray())) {
            for (i in 0..p.size - pat.size) {
                if ((pat.indices).all { p[i + it] == pat[it] }) hideRange(h, i, i + pat.size)
            }
        }
    }

    /** Printable runs of 4+ characters from [from] on, unless they are a model number or firmware version. */
    private fun hideText(p: ByteArray, h: BooleanArray, from: Int) {
        var i = from
        while (i < p.size) {
            if (p[i] !in 0x20..0x7E) { i++; continue }
            var j = i
            while (j < p.size && p[j] in 0x20..0x7E) j++
            if (j - i >= 4 && !NOT_PERSONAL.matches(String(p, i, j - i, Charsets.ISO_8859_1))) hideRange(h, i, j)
            i = j
        }
    }

    private fun isAap(p: ByteArray) = p.size >= 6 && u(p, 0) == 0x04 && u(p, 1) == 0 && u(p, 2) == 0x04 && u(p, 3) == 0

    /** True for a mic-stream audio packet (the Packets log folds these into one rate line). */
    fun isMicAudio(p: ByteArray) = AapMic.isAudio(p)

    /** One AAP packet (AirPods, L2CAP 0x1001). [sent] is phone -> AirPods. */
    fun aap(p: ByteArray, sent: Boolean, secrets: List<ByteArray> = emptyList()): Shown {
        val h = BooleanArray(p.size)
        if (AapMic.isAudio(p)) {
            val n = minOf(p.size, MIC_HEADER)
            val units = AapMic.demux(p).size
            return Shown(hex(p, null, n) + if (p.size > n) " ... (+${p.size - n} bytes of audio)" else "", null,
                "Mic audio: $units AAC-ELD frame${if (units == 1) "" else "s"}")
        }
        for (s in secrets) hideSecret(p, h, s)
        hideText(p, h, if (isAap(p)) 6 else 0)
        val meaning = if (!isAap(p)) session(p, sent) else when (val op = u(p, 4) or (u(p, 5) shl 8)) {
            0x09 -> control(p, sent)
            0x04 -> AapTrack.batteryFrom(p)?.let { "Battery: $it" } ?: "Battery report (nothing connected)"
            0x06 -> PhoneDirect.earFrom(p, p.size)?.let { "Ear detection: ${PhoneDirect.earText(it)}" } ?: "Ear detection (short packet)"
            0x0F -> "Turn on notifications"
            0x4D -> if (sent) "Feature flags" else "Feature flags reply"
            0x0E -> {
                hideRange(h, 6, 12)
                val type = if (p.size > 12) mapOf(0 to "none", 1 to "call", 2 to "media")[u(p, 12)] ?: "type ${u(p, 12)}" else "?"
                "Audio source: a device (address hidden), $type"
            }
            0x2E -> {
                val count = if (p.size > 8) u(p, 8) else 0
                for (k in 0 until count) hideRange(h, 9 + k * 8, 9 + k * 8 + 6)
                "Connected devices: $count (addresses hidden)"
            }
            0x4B -> {
                val lvl = if (p.size > 9) u(p, 9) else -1
                "Conversation Awareness: " + when (lvl) {
                    1, 2 -> "you started speaking"; 3 -> "you stopped speaking"; 8, 9 -> "back to normal"
                    else -> "level $lvl"
                }
            }
            0x58 -> when {
                p.contentEquals(AapMic.START) -> "Start the mic stream"
                p.contentEquals(AapMic.STOP) -> "Stop the mic stream"
                else -> if (sent) "Mic stream control" else "Mic stream reply (the AirPods answered)"
            }
            0x30 -> if (p.size > 6 && u(p, 6) == 0x01) "Ask for the IRK (proximity key)" else "Ask for proximity keys"
            0x31 -> keys(p, h)
            0x1D -> info(p, h)
            0x1A -> { hideRange(h, 6, p.size); "Rename (name hidden)" }
            0x17 -> "Head tracking data"
            else -> "unknown opcode 0x%02X".format(op)
        }
        val hidden = h.any { it }
        return Shown(hex(p, h), null, meaning + if (hidden && "hidden" !in meaning) " (some bytes hidden)" else "")
    }

    private fun session(p: ByteArray, sent: Boolean): String = when {
        sent && p.size >= 8 && u(p, 0) == 0 && u(p, 1) == 0 && u(p, 4) == 0x01 -> "Handshake: open the AAP session"
        p.isEmpty() -> "Empty packet"
        else -> "Session packet, header %02X %02X (not decoded)".format(u(p, 0), if (p.size > 1) u(p, 1) else 0)
    }

    private fun control(p: ByteArray, sent: Boolean): String {
        if (p.size < 8) return "Control command (short packet)"
        val id = u(p, 6); val v = u(p, 7)
        if (id == 0x0D) {
            val m = PhoneDirect.MODE_NAMES[v]?.let { MODE_LABEL[it] } ?: "value $v"
            return if (sent) "Set noise mode: $m" else "Noise mode is $m"
        }
        val entry = PhoneDirect.SETTINGS.entries.firstOrNull { it.value.first == id }
            ?: return (if (sent) "Set setting 0x%02X to %d" else "Setting 0x%02X is %d").format(id, v)
        val label = SETTING_LABEL[entry.key] ?: entry.key
        val shown = when (val d = PhoneDirect.decode(entry.value.second, v)) {
            true -> "on"; false -> "off"; null -> "value $v"
            is List<*> -> d.joinToString(", ") { MODE_LABEL[it] ?: it.toString() }.ifEmpty { "none" }
            else -> if (entry.key == "adaptive_level") "$d (0 lets the most noise in, 100 the least)" else d.toString()
        }
        return if (sent) "Set $label: $shown" else "$label: $shown"
    }

    /** 04 00 04 00 31 00 <count> then <type> 00 <len> 00 <key> per key: hide every key. */
    private fun keys(p: ByteArray, h: BooleanArray): String {
        if (p.size < 7) return "Proximity keys (short packet)"
        val names = mutableListOf<String>()
        var off = 7
        repeat(u(p, 6)) {
            if (off + 4 > p.size) return@repeat
            val type = u(p, off); val len = u(p, off + 2)
            hideRange(h, off + 4, off + 4 + len)
            names += when (type) { 1 -> "IRK"; 4 -> "encryption key"; else -> "key type $type" }
            off += 4 + len
        }
        // a reply that doesn't parse cleanly: hide everything after the header rather than guess
        if (off < p.size || names.isEmpty()) hideRange(h, 7, p.size)
        if (AapTrack.irkFrom(p) != null && "IRK" !in names) names += "IRK"
        return "Proximity keys: ${names.joinToString(", ").ifEmpty { "?" }} (hidden)"
    }

    /** Device information: NUL-separated strings (name, model, serials, firmware). Only models and firmware show. */
    private fun info(p: ByteArray, h: BooleanArray): String {
        val keep = mutableListOf<String>()
        var i = 6
        while (i < p.size) {
            var j = i
            while (j < p.size && p[j] != 0.toByte()) j++
            val s = String(p, i, j - i, Charsets.ISO_8859_1)
            if (j > i) {
                if (s.all { it in ' '..'~' } && NOT_PERSONAL.matches(s)) keep += s else hideRange(h, i, j)
            }
            i = j + 1
        }
        return "Device info: " + (if (keep.isEmpty()) "" else keep.joinToString(", ") + "; ") + "name and serial numbers (hidden)"
    }

    // ---- Skullcandy Crusher ANC 2: text AT commands over RFCOMM ----

    /** (pattern, group to hide). */
    private val CRUSHER_PRIVATE = listOf(
        Regex("(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}") to 0,                 // an address
        Regex("(?<![0-9A-Za-z])[0-9A-Fa-f]{12}(?![0-9A-Za-z])") to 0,          // an address without separators
        Regex("(?i)(?:name|serial|sn|bdaddr|addr|mac)\\s*[:=,]\\s*([^\\r\\n,]+)") to 1)

    /** One AT command or reply. [sent] is phone -> Crusher. [secrets] are strings to hide wherever they occur. */
    fun crusher(p: ByteArray, sent: Boolean, secrets: List<String> = emptyList()): Shown {
        val raw = String(p, Charsets.ISO_8859_1)
        val h = BooleanArray(p.size)
        for ((r, g) in CRUSHER_PRIVATE) for (m in r.findAll(raw))
            m.groups[g]?.range?.let { hideRange(h, it.first, it.last + 1) }
        for (s in secrets) if (s.length >= 3) {
            var at = raw.indexOf(s, ignoreCase = true)
            while (at >= 0) { hideRange(h, at, at + s.length); at = raw.indexOf(s, at + 1, ignoreCase = true) }
        }
        val text = buildString {
            var i = 0
            while (i < raw.length) {
                if (h[i]) { append("(hidden)"); while (i < raw.length && h[i]) i++; continue }
                when (val c = raw[i]) { '\r' -> append("\\r"); '\n' -> append("\\n"); else -> append(c) }
                i++
            }
        }
        val masked = String(CharArray(raw.length) { if (h[it]) ' ' else raw[it] })
        val meaning = if (sent) atCommand(masked.trim()) else atReply(masked)
        return Shown(hex(p, h), text, meaning + if (h.any { it } && "hidden" !in meaning) " (some text hidden)" else "")
    }

    private fun onOff(s: String) = s.trim().lowercase().let { if (it == "on") "on" else if (it == "off") "off" else it }

    private fun atCommand(c: String): String {
        val eq = c.substringAfter('=', "")
        return when {
            c == "AT.UIAUDIO?" -> "Ask the noise mode"
            c == "AT.CRUSHER?" -> "Ask the Sensory Bass level and state"
            c.startsWith("AT.UIAUDIO=anc,") -> "ANC ${onOff(eq.substringAfter(','))}"
            c == "AT.UIAUDIO=transparency" -> "Ask transparency"
            c.startsWith("AT.UIAUDIO=transparency,,") -> "Set transparency level ${eq.substringAfterLast(',')}"
            c.startsWith("AT.UIAUDIO=transparency,") -> "Transparency ${onOff(eq.substringAfter(','))}"
            c == "AT.UIAUDIO=atoncall" -> "Ask hear-through during calls"
            c.startsWith("AT.UIAUDIO=atoncall,") -> "Hear-through during calls: ${onOff(eq.substringAfter(','))}"
            c == "AT.UIAUDIO=atonpause" -> "Ask hear-through while paused"
            c.startsWith("AT.UIAUDIO=atonpause,") -> "Hear-through while paused: ${onOff(eq.substringAfter(','))}"
            c.startsWith("AT.CRUSHER=level,") -> "Set Sensory Bass level ${eq.substringAfter(',')} of 10"
            c == "AT.CRUSHER=on" || c == "AT.CRUSHER=state,on" -> "Sensory Bass on"
            c == "AT.CRUSHER=off" || c == "AT.CRUSHER=state,off" -> "Sensory Bass off"
            c == "AT.FEATURE=count" -> "Ask how many features there are"
            c.startsWith("AT.FEATURE=info") -> "Ask the feature list"
            c.startsWith("AT.VOLUME=") -> "Volume ${eq.replace(',', ' ')}"
            else -> "AT command (not decoded)"
        }
    }

    private fun atReply(r: String): String {
        val s = CrusherDirect.parseState(r)
        val parts = mutableListOf<String>()
        s.anc?.let { parts += "ANC " + if (it) "on" else "off" }
        s.transparency?.let { parts += "transparency " + if (it) "on" else "off" }
        if (s.bass != null) parts += "Sensory Bass level ${s.bass} of ${s.bassMax}"
        s.bassOn?.let { parts += "Sensory Bass " + if (it) "on" else "off" }
        Regex("(?i)\\b(atoncall|atonpause)\\b[^:\\r\\n]*:\\s*(on|off)").find(r)?.let {
            parts += (if (it.groupValues[1].lowercase() == "atoncall") "hear-through during calls " else "hear-through while paused ") +
                it.groupValues[2].lowercase()
        }
        val features = Regex("\\.FEATURE:").findAll(r).count()
        if (features > 1) parts += "$features features listed"
        else Regex("\\.FEATURE:\\s*(\\d+)\\s*(\\r|\\n|$)").find(r)?.let { parts += "${it.groupValues[1]} features" }
        val verdict = when {
            r.contains("ERROR") -> "refused (ERROR)"
            r.contains("OK") -> "accepted (OK)"
            r.isBlank() -> "no reply"
            else -> ""
        }
        return when {
            parts.isEmpty() && verdict.isEmpty() -> "Reply (not decoded)"
            parts.isEmpty() -> "Reply: $verdict"
            else -> parts.joinToString(", ").replaceFirstChar { it.uppercase() } + if (verdict.isEmpty()) "" else "; $verdict"
        }
    }
}
