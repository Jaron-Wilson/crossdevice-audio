package dev.jaronwilson.headphones

/** What to do with the AirPods' noise control during a translation session. */
enum class AncChoice(val label: String) {
    KEEP("Don't change"),
    ANC("Noise cancelling"),
    BLOCK_MAX("Adaptive, block max"),
}

/** The AirPods' noise state before we touched it: [mode] "off"/"anc"/"transparency"/"adaptive",
 *  [level] the adaptive level (0-100) if known. */
data class AncState(val mode: String?, val level: Int?)

/** The phone's audio routing, as the session needs it (Android's AudioManager in the app, a fake in tests). */
interface AudioRoute {
    /** A headset that can be the call/communication device (the AirPods over Bluetooth SCO), or null. */
    fun findHeadset(): Headset?
    fun currentMode(): Int
    fun setMode(mode: Int)
    /** Make [h] the communication device and wait until it is; false if Android wouldn't. */
    fun routeTo(h: Headset): Boolean
    fun clearRoute()
    fun requestFocus(): Boolean
    fun abandonFocus()

    data class Headset(val id: Int, val name: String, val isConfiguredAirPods: Boolean)

    companion object {
        // the same values as AudioManager.MODE_NORMAL / MODE_IN_COMMUNICATION
        const val MODE_NORMAL = 0
        const val MODE_IN_COMMUNICATION = 3
    }
}

/** The AirPods' noise control (PhoneDirect over AAP in the app). Calls are slow and may throw. */
interface NoiseControl {
    fun available(): Boolean
    fun read(): AncState
    fun apply(choice: AncChoice)
    /** Put back [prev]; [applied] is what the session set (so BLOCK_MAX also restores the adaptive level). */
    fun restore(prev: AncState, applied: AncChoice)
}

/**
 * The session's start/stop, with no Android in it:
 *  start: find the AirPods -> MODE_IN_COMMUNICATION -> AirPods as the communication device (their mic
 *         and the call-audio path) -> transient audio focus (pauses music) -> optional ANC.
 *  stop:  the reverse; the audio mode goes back to MODE_NORMAL so music returns to A2DP quality, and the
 *         AirPods go back to the noise mode they had.
 * Both are idempotent, and a failed start leaves nothing changed. Call from a worker thread: the noise
 * control talks Bluetooth.
 */
class TranslateSession(private val audio: AudioRoute, private val anc: NoiseControl, private val ancChoice: () -> AncChoice) {
    enum class Phase { IDLE, ACTIVE }

    sealed class Start {
        data class Ok(val headset: AudioRoute.Headset, val ancNote: String) : Start()
        object NoHeadset : Start()
        object RouteFailed : Start()
    }

    var phase = Phase.IDLE; private set
    var headset: AudioRoute.Headset? = null; private set
    private var focus = false
    private var savedAnc: AncState? = null
    private var ancApplied: AncChoice? = null

    @Synchronized fun start(): Start {
        headset?.let { if (phase == Phase.ACTIVE) return Start.Ok(it, "already running") }
        val h = audio.findHeadset() ?: return Start.NoHeadset
        audio.setMode(AudioRoute.MODE_IN_COMMUNICATION)
        if (!audio.routeTo(h)) {
            audio.clearRoute(); audio.setMode(AudioRoute.MODE_NORMAL)
            return Start.RouteFailed
        }
        focus = audio.requestFocus()
        headset = h
        phase = Phase.ACTIVE
        return Start.Ok(h, applyAnc(h))
    }

    private fun applyAnc(h: AudioRoute.Headset): String {
        val choice = ancChoice()
        if (choice == AncChoice.KEEP) return ""
        if (!h.isConfiguredAirPods || !anc.available()) return "Noise control isn't reachable from this phone; left as is."
        val before = runCatching { anc.read() }.getOrNull()
        // nothing to restore to if we can't read it, so don't change it either
        if (before?.mode == null) return "Couldn't read the AirPods' noise mode; left as is."
        if (choice == AncChoice.ANC && before.mode == "anc") return ""
        if (choice == AncChoice.BLOCK_MAX && before.mode == "adaptive" && before.level == 100) return ""
        return runCatching { anc.apply(choice) }.fold(
            onSuccess = { savedAnc = before; ancApplied = choice; "AirPods: ${choice.label.lowercase()} (restored when you stop)." },
            onFailure = { "AirPods noise control failed: ${it.message}" })
    }

    /** Returns a note for the user if something couldn't be put back. */
    @Synchronized fun stop(): String {
        if (phase == Phase.IDLE) return ""
        var note = ""
        val prev = savedAnc; val applied = ancApplied
        if (prev != null && applied != null) {
            runCatching { anc.restore(prev, applied) }.onFailure { note = "Couldn't restore the AirPods' noise mode: ${it.message}" }
        }
        savedAnc = null; ancApplied = null
        if (focus) audio.abandonFocus()
        focus = false
        audio.clearRoute()
        audio.setMode(AudioRoute.MODE_NORMAL)
        headset = null
        phase = Phase.IDLE
        return note
    }
}
