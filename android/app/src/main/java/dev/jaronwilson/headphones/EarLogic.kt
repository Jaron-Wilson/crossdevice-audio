package dev.jaronwilson.headphones

/*
 * Ear detection, the testable parts (no Android classes): decoding the AirPods' report, telling left from
 * right, debouncing, and the auto-pause / auto-resume and listening rules. The Bluetooth side is [AapLink]
 * and [EarService].
 */

/** Where one bud is, as the AirPods report it in AAP 0x06 (they sense it themselves). */
enum class Bud(val code: Int, val words: String) {
    IN_EAR(0, "in ear"), OUT(1, "out of ear"), IN_CASE(2, "in case"), UNKNOWN(-1, "unknown");

    companion object {
        fun of(b: Int) = entries.firstOrNull { it.code == b } ?: UNKNOWN
    }
}

/**
 * One ear-detection report, `04 00 04 00 06 00 <primary> <secondary>`. The order is primary first: the
 * primary is the bud that holds the link to the phone (and the microphone), not a fixed side. When the
 * primary leaves the ear the AirPods swap roles and send the report again (LibrePods, "AAP Definitions").
 */
data class EarReport(val primary: Bud, val secondary: Bud) {
    /** How many buds are in an ear (0, 1 or 2). */
    val inEar get() = (if (primary == Bud.IN_EAR) 1 else 0) + (if (secondary == Bud.IN_EAR) 1 else 0)
    val bothInCase get() = primary == Bud.IN_CASE && secondary == Bud.IN_CASE
    val known get() = primary != Bud.UNKNOWN && secondary != Bud.UNKNOWN
    /** Neither bud is in an ear (out, in the case, or one of each). */
    val noneInEar get() = known && inEar == 0
    /** The same pair seen from the other bud (roles swapped). */
    fun swapped() = EarReport(secondary, primary)

    companion object {
        /** The first ear report in [n] bytes of [p], or null. */
        fun decode(p: ByteArray, n: Int = p.size): EarReport? {
            for (i in 0..n - 8) if (p[i] == 0x04.toByte() && p[i + 1] == 0.toByte() && p[i + 2] == 0x04.toByte() &&
                p[i + 3] == 0.toByte() && p[i + 4] == 0x06.toByte() && p[i + 5] == 0.toByte())
                return EarReport(Bud.of(p[i + 6].toInt() and 0xFF), Bud.of(p[i + 7].toInt() and 0xFF))
            return null
        }

        fun of(pair: Pair<Int, Int>) = EarReport(Bud.of(pair.first), Bud.of(pair.second))
    }
}

/**
 * Which bud is the primary: left or right. Two sources, in order of trust:
 *  1. The AAP battery report (`04 00 04 00 04 00 <count> [<type> 01 <level> <status> 01]...`, type 2 left,
 *     4 right, 8 case) lists the primary bud first. LibrePods' Linux app reads the primary this way
 *     (`battery.hpp`, "first pod is primary"). It comes on the same link as the ear report, at once when the
 *     link opens and again when a bud's state changes.
 *  2. The BLE proximity advert's status bit 5 ("primary is left", LibrePods and OpenPods), when the app's
 *     scans heard *your* AirPods recently. Used only until a battery report arrives on the link.
 * Plus one inference: the AirPods swap roles when the primary leaves the ear and re-send the report with the
 * pair exchanged; seeing `(a, b)` then `(b, a)` within [SWAP_MS], with no battery report in between, means the
 * roles swapped, so the side flips too.
 * If none of these is known, [primaryIsLeft] is null and the text says "one bud / the other bud".
 */
class SideTracker {
    var primaryIsLeft: Boolean? = null; private set
    /** "battery report", "Bluetooth advert", "role swap" or "" */
    var source = ""; private set
    private var lastEar: EarReport? = null
    private var lastEarT = 0L
    private var batterySinceEar = false

    /** A battery report on the link: [primaryLeft] from [primaryFromBattery]. */
    fun onBattery(primaryLeft: Boolean) {
        primaryIsLeft = primaryLeft; source = "battery report"; batterySinceEar = true
    }

    /** A recent advert's "primary is left" bit; only used while the link hasn't said. */
    fun onAdvert(primaryLeft: Boolean) {
        if (source == "battery report" || source == "role swap") return
        primaryIsLeft = primaryLeft; source = "Bluetooth advert"
    }

    /** A raw (not debounced) ear report at time [t]. */
    fun onEar(r: EarReport, t: Long) {
        val prev = lastEar
        if (prev != null && !batterySinceEar && t - lastEarT <= SWAP_MS && prev.primary != prev.secondary &&
            r == prev.swapped() && r.known) {
            primaryIsLeft?.let { primaryIsLeft = !it; source = "role swap" }
        }
        lastEar = r; lastEarT = t; batterySinceEar = false
    }

    /** The link closed: what it said no longer holds. */
    fun reset() { primaryIsLeft = null; source = ""; lastEar = null; batterySinceEar = false }

    companion object {
        const val SWAP_MS = 2000L

        /** From an AAP battery report: true if the left bud is listed first (primary), false if the right
         *  is, null if this isn't a battery report or lists no bud. */
        fun primaryFromBattery(p: ByteArray, n: Int = p.size): Boolean? {
            if (n < 7 || p[0] != 0x04.toByte() || p[2] != 0x04.toByte() || p[4] != 0x04.toByte() || p[5] != 0.toByte()) return null
            var off = 7
            repeat(p[6].toInt() and 0xFF) {
                if (off + 4 > n) return null
                when (p[off].toInt() and 0xFF) { 2 -> return true; 4 -> return false }
                off += 5
            }
            return null
        }
    }
}

object EarText {
    /** (left, right), or null when the sides aren't known. */
    fun leftRight(r: EarReport, primaryIsLeft: Boolean?): Pair<Bud, Bud>? = when (primaryIsLeft) {
        true -> r.primary to r.secondary
        false -> r.secondary to r.primary
        null -> null
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    /** "Left: in ear, Right: in case", "Both in ear", or without sides "One bud in ear, the other in case". */
    fun describe(r: EarReport, primaryIsLeft: Boolean?): String {
        if (r.primary == r.secondary) return "Both ${r.primary.words}"
        val lr = leftRight(r, primaryIsLeft)
        return if (lr != null) "Left: ${lr.first.words}, Right: ${lr.second.words}"
        else {
            // without sides, name the one in the ear (or out) first: it reads naturally either way
            val (a, b) = listOf(r.primary, r.secondary).sortedBy { it.code }
            "One bud ${a.words}, the other ${b.words} (which is left isn't known yet)"
        }
    }

    /** "(live, 14:02)" while the link is open, else "(last seen 5 min ago)". [clock] formats a time. */
    fun freshness(t: Long, now: Long, live: Boolean, clock: (Long) -> String): String {
        if (live) return "(live, since ${clock(t)})"
        val ago = (now - t).coerceAtLeast(0) / 1000
        val s = when {
            ago < 60 -> "just now"
            ago < 3600 -> "${ago / 60} min ago"
            ago < 86_400 -> "${ago / 3600} h ago"
            else -> "${ago / 86_400} days ago"
        }
        return "(last seen $s)"
    }
}

/**
 * Holds a value until it has stayed the same for [holdMs], so a bud brushing the ear (in, out, in within a
 * few hundred ms) doesn't pause the music. Pure: the caller passes the time and asks again at the time
 * [offer] returns.
 */
class Debouncer<T : Any>(private val holdMs: Long) {
    var stable: T? = null; private set
    private var pending: T? = null
    private var since = 0L

    /** A raw value at [now]. Returns when to call [settle], or null if there is nothing new. */
    fun offer(v: T, now: Long): Long? {
        if (v == stable) { pending = null; return null }
        if (v != pending) { pending = v; since = now }
        return since + holdMs
    }

    /** The new stable value if one has held for [holdMs] by [now], else null. */
    fun settle(now: Long): T? {
        val p = pending ?: return null
        if (now - since < holdMs) return null
        stable = p; pending = null
        return p
    }

    fun reset() { stable = null; pending = null }
}

/**
 * Auto-pause and auto-resume, like an iPhone: a bud leaving an ear while media plays pauses it; putting it
 * back within [resumeWindowMs] resumes it, but only if this app paused it. Both buds into the case ends
 * the chance to resume. The first report after the link opens only sets the baseline.
 */
class AutoPause(private val resumeWindowMs: Long = RESUME_WINDOW_MS) {
    enum class Act { NONE, PAUSE, RESUME }

    private var prev: EarReport? = null
    var pausedByUs = false; private set
    private var pausedAt = 0L
    private var wornBefore = 0

    /**
     * A debounced report. [playing]: is media playing right now (as far as the phone can tell).
     * [pauseOn] / [resumeOn]: the user's toggles.
     */
    fun onEars(r: EarReport, playing: Boolean, now: Long, pauseOn: Boolean, resumeOn: Boolean): Act {
        val p = prev
        prev = r
        if (p == null || !p.known || !r.known) return Act.NONE
        if (pausedByUs && now - pausedAt > resumeWindowMs) pausedByUs = false
        if (r.bothInCase) { pausedByUs = false; return Act.NONE }
        val before = p.inEar
        val after = r.inEar
        if (after < before) {
            if (pauseOn && playing) {
                pausedByUs = true; pausedAt = now; wornBefore = before
                return Act.PAUSE
            }
            return Act.NONE
        }
        if (after > before && pausedByUs) {
            if (playing) { pausedByUs = false; return Act.NONE }        // already playing again (you resumed)
            if (after >= wornBefore) {
                pausedByUs = false
                return if (resumeOn) Act.RESUME else Act.NONE
            }
        }
        return Act.NONE
    }

    /** The AirPods left the phone. */
    fun reset() { prev = null; pausedByUs = false }

    companion object { const val RESUME_WINDOW_MS = 120_000L }
}

/**
 * Listening (What your AirPods hear / Filtered hearing) and the ears: stop when no bud is in an ear, and
 * (optionally) start again if a bud goes back in within [resumeWindowMs] of that stop.
 */
class ListenGuard(private val resumeWindowMs: Long = AutoPause.RESUME_WINDOW_MS) {
    enum class Act { NONE, STOP, RESTART }

    private var stoppedAt = -1L
    var stoppedFiltered = false; private set

    /** [listening]: the mic stream runs now; [filtered]: filtered hearing is on. */
    fun onEars(r: EarReport, listening: Boolean, filtered: Boolean, now: Long, stopOn: Boolean, restartOn: Boolean): Act {
        if (!r.known) return Act.NONE
        if (r.noneInEar) {
            if (stopOn && listening) { stoppedAt = now; stoppedFiltered = filtered; return Act.STOP }
            if (r.bothInCase) stoppedAt = -1
            return Act.NONE
        }
        if (stoppedAt >= 0 && r.inEar > 0) {
            val due = now - stoppedAt <= resumeWindowMs && !listening
            stoppedAt = -1
            if (due && restartOn) return Act.RESTART
        }
        return Act.NONE
    }

    fun reset() { stoppedAt = -1; stoppedFiltered = false }
}
