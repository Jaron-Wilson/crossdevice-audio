package dev.jaronwilson.headphones

/*
 * Live Translation: the decisions, with no Android in them so they can be unit-tested.
 *
 *  - [Langs]: language-code clean-up between the speech recognizer, ML Kit Language ID and ML Kit
 *    Translate (they don't all spell every language the same way).
 *  - [DirectionRouter]: who just spoke. Their language -> translated to mine and spoken in the AirPods;
 *    my language -> translated to theirs, shown big on screen and spoken on the phone speaker.
 *  - [EchoGuard]: drops what the recognizer hears of our own text-to-speech.
 */

/** Which way one recognized phrase goes. */
enum class Direction {
    /** The other person spoke: translate into my language, speak it in the AirPods. */
    THEIRS_TO_ME,
    /** I spoke: translate into their language, show it big and speak it on the phone speaker. */
    MINE_TO_THEM,
    /** Nothing to do (empty, or an echo of our own speech). */
    IGNORE,
}

object Langs {
    // Old/alternate ISO 639 codes that Language ID or a recognizer may report -> ML Kit Translate's code
    private val ALIASES = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "fil" to "tl", "nb" to "no")

    /** "es-US", "zh-Latn", "iw" -> "es", "zh", "he": the plain language, as ML Kit Translate names it. */
    fun base(tag: String?): String? {
        val t = tag?.trim()?.replace('_', '-')?.substringBefore('-')?.lowercase()
        if (t.isNullOrEmpty() || t == "und") return null
        return ALIASES[t] ?: t
    }

    // The region the speech recognizer and text-to-speech get for a bare language (they want a locale).
    private val REGION = mapOf(
        "en" to "en-US", "es" to "es-US", "fr" to "fr-FR", "de" to "de-DE", "it" to "it-IT", "pt" to "pt-BR",
        "zh" to "zh-CN", "ja" to "ja-JP", "ko" to "ko-KR", "ru" to "ru-RU", "ar" to "ar-SA", "hi" to "hi-IN",
        "nl" to "nl-NL", "pl" to "pl-PL", "tr" to "tr-TR", "vi" to "vi-VN", "th" to "th-TH", "id" to "id-ID",
        "sv" to "sv-SE", "uk" to "uk-UA", "he" to "he-IL", "el" to "el-GR", "da" to "da-DK", "fi" to "fi-FI",
        "no" to "nb-NO", "cs" to "cs-CZ", "ro" to "ro-RO", "hu" to "hu-HU", "tl" to "fil-PH",
    )

    /** BCP-47 tag for the recognizer / TTS, e.g. "es" -> "es-US". */
    fun localeTag(code: String): String = REGION[base(code)] ?: (base(code) ?: code)
}

object DirectionRouter {
    /** Language ID is unreliable on one or two words; below this many words the recognizer's language decides. */
    const val MIN_WORDS_FOR_ID = 3

    /**
     * Decide which way [text] goes.
     *
     * @param scores Language ID's confidences (any tags; they are normalized), e.g. {"en": 0.9, "es": 0.05}
     * @param me my language (ML Kit code), @param them theirs
     * @param heardAs the language the recognizer was listening in / says it detected (null = unknown)
     * @param forced set while the "I'm talking" button (or a future AirPods trigger) is held
     */
    fun decide(
        text: String, scores: Map<String, Float>, me: String, them: String,
        heardAs: String? = null, forced: Direction? = null, minConfidence: Float = 0.5f,
    ): Direction {
        if (text.isBlank()) return Direction.IGNORE
        val meB = Langs.base(me); val themB = Langs.base(them)
        if (meB == null || themB == null || meB == themB) return Direction.IGNORE
        if (forced != null) return forced
        val heard = Langs.base(heardAs)
        val fromHeard = when (heard) { meB -> Direction.MINE_TO_THEM; themB -> Direction.THEIRS_TO_ME; else -> null }
        val words = text.trim().split(Regex("\\s+")).size
        if (words < MIN_WORDS_FOR_ID && fromHeard != null) return fromHeard

        // only the two languages in play matter; sum in case a language shows up under two tags
        var sMe = 0f; var sThem = 0f
        for ((tag, c) in scores) when (Langs.base(tag)) { meB -> sMe += c; themB -> sThem += c }
        val best = maxOf(sMe, sThem)
        if (best >= minConfidence && sMe != sThem) return if (sMe > sThem) Direction.MINE_TO_THEM else Direction.THEIRS_TO_ME
        // Language ID can't tell: trust the recognizer, and failing that assume it's the other person
        // (the recognizer listens in their language by default).
        return fromHeard ?: Direction.THEIRS_TO_ME
    }
}

/**
 * The AirPods' mic can pick up the phone speaker saying my reply (and, faintly, the AirPods' own
 * playback). Recognition is paused while we speak, but a tail can still slip through; anything that
 * mostly repeats what we spoke in the last [windowMs] is dropped.
 */
class EchoGuard(private val clock: () -> Long, private val windowMs: Long = 8_000, private val overlap: Double = 0.6) {
    private val spoken = ArrayDeque<Pair<Long, Set<String>>>()

    fun spoke(text: String) {
        prune(); val w = words(text)
        if (w.isNotEmpty()) spoken.addLast(clock() to w)
    }

    /** True when [heard] is most likely our own speech coming back. */
    fun isEcho(heard: String): Boolean {
        prune()
        val h = words(heard)
        if (h.isEmpty()) return false
        return spoken.any { (_, s) -> h.count { it in s }.toDouble() / h.size >= overlap }
    }

    private fun prune() { val now = clock(); while (spoken.isNotEmpty() && now - spoken.first().first > windowMs) spoken.removeFirst() }

    // lower case, accents dropped (recognizers are inconsistent about them)
    private fun words(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
}
