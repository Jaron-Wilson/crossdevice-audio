package dev.jaronwilson.headphones

/** What the phone's sensors say the wearer is doing. [ON_CALL] is a phone call (it wins over the rest). */
enum class Act(val label: String) {
    STILL("still"), WALKING("walking"), RUNNING("running"), ON_BICYCLE("cycling"),
    IN_VEHICLE("in a vehicle"), ON_CALL("on a call")
}

/**
 * The "Smart adaptive" decision, with no Android in it so it can be unit-tested with a fake clock.
 *
 * Hysteresis, so the headphones never flap:
 *  - a new activity must hold [holdMs] (about 20 s) before it is acted on, and
 *  - at most one switch per [minGapMs] (about 30 s).
 * Whatever the activity maps to is looked up at decision time, so edits to the mapping apply at once.
 *
 * Times are plain milliseconds from [clock] (any monotonic source). [State] is a few primitives so the
 * Android side can keep it across process restarts.
 */
class SmartMachine(
    private val clock: () -> Long,
    private val mapping: (Act) -> String,
    private val holdMs: Long = 20_000,
    private val minGapMs: Long = 30_000,
    var state: State = State(),
) {
    /** [activity] = the latest sensor activity, [since] = when it last changed, [call] = on a call,
     *  [lastSwitch] = when we last acted (null = never), [applied] = the action we last sent. */
    data class State(
        val activity: Act? = null, val since: Long = 0, val call: Boolean = false,
        val callSince: Long = 0, val lastSwitch: Long? = null, val applied: String? = null,
    )

    /** What the machine currently goes by: a call beats the activity. */
    val effective: Act? get() = if (state.call) Act.ON_CALL else state.activity

    /** The action [effective] maps to, or null when nothing is known yet. */
    val desired: String? get() = effective?.let(mapping)

    private val effectiveSince get() = if (state.call) state.callSince else state.since

    fun onActivity(a: Act) {
        if (a == Act.ON_CALL || a == state.activity) return
        state = state.copy(activity = a, since = clock())
    }

    fun onCall(active: Boolean) {
        if (active == state.call) return
        state = state.copy(call = active, callSince = clock())
    }

    /** The action to send now, or null. Call it whenever something changed and when [nextCheckInMs] is up. */
    fun poll(): String? {
        val want = desired ?: return null
        if (want == state.applied) return null
        if (nextCheckInMs() != 0L) return null
        state = state.copy(lastSwitch = clock(), applied = want)
        return want
    }

    /** Milliseconds until [poll] could act (0 = now), or null when there is nothing to do. */
    fun nextCheckInMs(): Long? {
        val want = desired ?: return null
        if (want == state.applied) return null
        val now = clock()
        val held = effectiveSince + holdMs - now
        val gap = state.lastSwitch?.let { it + minGapMs - now } ?: 0L
        return maxOf(0L, held, gap)
    }

    /** The settings screen's status line, e.g. "now: walking -> transparency". */
    fun status(describe: (String) -> String = { it }): String {
        val e = effective ?: return "waiting for the first activity reading"
        return "now: ${e.label} -> ${describe(mapping(e))}"
    }
}
