package dev.jaronwilson.headphones

/*
 * Ear detection, the testable parts (no Android classes): decoding the AirPods' report, telling left from
 * right, debouncing, and the auto-pause / auto-resume and listening rules. The Bluetooth side is [AapLink]
 * and [EarService].
 */

/** Where one bud is, as the AirPods report it in AAP 0x06 (they sense it themselves). The last two are the
 *  app's own reading of a bud the report no longer covers (see [SideTracker.resolve]); never on the wire. */
enum class Bud(val code: Int, val words: String) {
    IN_EAR(0, "in ear"), OUT(1, "out of ear"), IN_CASE(2, "in case"), UNKNOWN(-1, "unknown"),
    CASE_CLOSED(-2, "in case (lid closed)"), GONE(-3, "disconnected");

    /** In the case, lid open or closed. */
    val inCase get() = this == IN_CASE || this == CASE_CLOSED

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
    val bothInCase get() = primary.inCase && secondary.inCase
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

/** Per-bud state from an AAP battery report ([AapTrack] types and states), and which bud it listed first. */
data class BudPower(val left: Int?, val right: Int?, val firstIsLeft: Boolean?) {
    companion object {
        /** From an AAP battery report, or null if [p] isn't one. */
        fun from(p: ByteArray, n: Int = p.size): BudPower? {
            if (n < 7 || p[0] != 0x04.toByte() || p[2] != 0x04.toByte() || p[4] != 0x04.toByte() || p[5] != 0.toByte()) return null
            var l: Int? = null; var r: Int? = null; var first: Boolean? = null
            var off = 7
            repeat(p[6].toInt() and 0xFF) {
                if (off + 4 > n) return@repeat
                val st = p[off + 3].toInt() and 0xFF
                when (p[off].toInt() and 0xFF) {
                    AapTrack.TYPE_LEFT -> { l = st; if (first == null) first = true }
                    AapTrack.TYPE_RIGHT -> { r = st; if (first == null) first = false }
                }
                off += 5
            }
            return BudPower(l, r, first)
        }
    }
}

/**
 * Which bud is the primary: left or right. The ear report only says "primary" and "secondary"; the primary is
 * the bud holding the link, and it moves. Sources:
 *  1. **The AAP battery report's charging states** (types 2 right, 4 left; states 1 charging, 2 discharging,
 *     4 disconnected, LibrePods `battery.hpp`). A bud in the case is charging or off the link; a bud out of it
 *     discharges. When the ear report has exactly one bud in the case and the battery report exactly one bud
 *     charging or disconnected, that is the same bud, which says whether the primary is left or right. Used
 *     only when the battery report is no older than [FRESH_MS] before the ear report.
 *  2. **The BLE proximity advert**'s status bit 5 ("left bud is primary", LibrePods Linux `blemanager.cpp`).
 *  3. **The battery report's order**: LibrePods' Linux app takes the first bud listed as the primary. Used only
 *     while nothing else has said (it agreed with source 1 in the one live check, right bud primary and listed
 *     first, but that alone can't tell it from a fixed right-then-left order).
 *  4. **Role swap**: the AirPods hand the primary role over when the primary leaves the ear and re-send the
 *     report with the pair exchanged; `(a, b)` then `(b, a)` within [SWAP_MS] flips the known side.
 * If none of these is known, [primaryIsLeft] is null and the text says "one bud / the other bud".
 */
class SideTracker {
    var primaryIsLeft: Boolean? = null; private set
    /** "battery charging", "Bluetooth advert", "battery order", "role swap" or "" */
    var source = ""; private set
    private var lastEar: EarReport? = null
    private var lastEarT = -1L
    private var power: BudPower? = null
    private var powerT = -1L
    private var setSinceEar = false
    /** Each role's last known place (primary, secondary), and each bud's battery state before the latest. */
    private val lastPlace = arrayOf(Bud.UNKNOWN, Bud.UNKNOWN)
    private var prevPower: BudPower? = null
    /** Per side (true = left), kept across links: the last place known for that bud, and whether it was
     *  last seen charging. So a lid closed before the link opened still reads "in case (lid closed)". */
    val sidePlace = HashMap<Boolean, Bud>()
    val sideCharging = HashMap<Boolean, Boolean>()

    /** A battery report at [t]. */
    fun onBattery(p: BudPower, t: Long) {
        if (power != null && power != p) prevPower = power
        power = p; powerT = t
        p.left?.let { if (it == AapTrack.CHARGING || it == AapTrack.DISCHARGING) sideCharging[true] = it == AapTrack.CHARGING }
        p.right?.let { if (it == AapTrack.CHARGING || it == AapTrack.DISCHARGING) sideCharging[false] = it == AapTrack.CHARGING }
        infer()
        noteSides()
    }

    /**
     * The report as the app shows and acts on it. When the lid closes, the bud in the case drops off the link:
     * the ear report then says "unknown" for it and the battery report "disconnected" (seen live). Such a bud
     * reads [Bud.CASE_CLOSED] if it was last in the case (or charging) or nothing is known about it (a bud out
     * of the case stays on the link), else [Bud.GONE] (it was in an ear or out, and went away).
     */
    fun resolve(r: EarReport): EarReport {
        fun fix(b: Bud, role: Int): Bud {
            if (b != Bud.UNKNOWN) return b
            val p = power ?: return b
            val pl = primaryIsLeft
            // which bud this role is: by the known side, else the only bud that is off the link
            val isLeft: Boolean = when {
                pl != null -> if (role == 0) pl else !pl
                (p.left == AapTrack.DISCONNECTED) != (p.right == AapTrack.DISCONNECTED) -> p.left == AapTrack.DISCONNECTED
                else -> return b
            }
            val st = if (isLeft) p.left else p.right
            if (st != AapTrack.DISCONNECTED) return b
            val before = prevPower?.let { if (isLeft) it.left else it.right }
            val last = lastPlace[role].takeIf { it != Bud.UNKNOWN } ?: sidePlace[isLeft]
            val wasCharging = before == AapTrack.CHARGING || (before == null && sideCharging[isLeft] == true)
            // nothing known about it: a bud out of the case stays on the link, so off the link means the closed case
            if (last == null || last == Bud.UNKNOWN) return Bud.CASE_CLOSED
            return if (last.inCase || wasCharging) Bud.CASE_CLOSED else Bud.GONE
        }
        return EarReport(fix(r.primary, 0), fix(r.secondary, 1))
    }

    /** An advert's "primary is left" bit (an observation of now: it replaces older ones). */
    fun onAdvert(primaryLeft: Boolean) {
        primaryIsLeft = primaryLeft; source = "Bluetooth advert"; setSinceEar = true
    }

    /** A raw (not debounced) ear report at time [t]. */
    fun onEar(r: EarReport, t: Long) {
        val prev = lastEar
        if (prev != null && !setSinceEar && t - lastEarT <= SWAP_MS && prev.primary != prev.secondary &&
            r == prev.swapped() && r.known) {
            primaryIsLeft?.let { primaryIsLeft = !it; source = "role swap" }
        }
        lastEar = r; lastEarT = t; setSinceEar = false
        if (r.primary != Bud.UNKNOWN) lastPlace[0] = r.primary
        if (r.secondary != Bud.UNKNOWN) lastPlace[1] = r.secondary
        infer()
        noteSides()
    }

    private fun infer() {
        val p = power ?: return
        val r = lastEar
        if (r != null && powerT >= lastEarT - FRESH_MS && listOf(r.primary, r.secondary).count { it == Bud.IN_CASE } == 1) {
            val lCase = p.left == AapTrack.CHARGING || p.left == AapTrack.DISCONNECTED
            val rCase = p.right == AapTrack.CHARGING || p.right == AapTrack.DISCONNECTED
            if (lCase != rCase && p.left != null && p.right != null) {
                // the bud in the case is the left one iff lCase; the primary is that bud iff the primary is in the case
                primaryIsLeft = (r.primary == Bud.IN_CASE) == lCase; source = "battery charging"; setSinceEar = true
                return
            }
        }
        if (primaryIsLeft == null && p.firstIsLeft != null) { primaryIsLeft = p.firstIsLeft; source = "battery order" }
    }

    /** The link closed: what it said no longer holds. */
    fun reset() {
        primaryIsLeft = null; source = ""; lastEar = null; lastEarT = -1; power = null; powerT = -1; setSinceEar = false
        lastPlace.fill(Bud.UNKNOWN); prevPower = null
    }

    private fun noteSides() {
        val r = lastEar ?: return
        val pl = primaryIsLeft ?: return
        if (r.primary != Bud.UNKNOWN) sidePlace[pl] = r.primary
        if (r.secondary != Bud.UNKNOWN) sidePlace[!pl] = r.secondary
    }

    /** The latest raw ear report, or null. */
    fun lastRaw(): EarReport? = lastEar

    companion object {
        const val SWAP_MS = 2000L
        const val FRESH_MS = 5000L
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
