package dev.jaronwilson.headphones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateLogicTest {
    private fun decide(text: String, scores: Map<String, Float>, heard: String? = null, forced: Direction? = null) =
        DirectionRouter.decide(text, scores, me = "en", them = "es", heardAs = heard, forced = forced)

    // ---- language codes ----
    @Test fun baseCodes() {
        assertEquals("es", Langs.base("es-US"))
        assertEquals("zh", Langs.base("zh-Latn"))
        assertEquals("he", Langs.base("iw"))
        assertEquals("id", Langs.base("in_ID"))
        assertNull(Langs.base("und"))
        assertNull(Langs.base(""))
        assertNull(Langs.base(null))
        assertEquals("es-US", Langs.localeTag("es"))
        assertEquals("sw", Langs.localeTag("sw"))
    }

    // ---- direction ----
    @Test fun englishFromMeGoesToThem() =
        assertEquals(Direction.MINE_TO_THEM, decide("where is the train station", mapOf("en" to 0.97f, "es" to 0.01f), heard = "es-US"))

    @Test fun spanishFromThemComesToMe() =
        assertEquals(Direction.THEIRS_TO_ME, decide("la estación está a la derecha", mapOf("es" to 0.95f), heard = "es-US"))

    @Test fun otherLanguagesDontCount() =
        // Language ID's top pick is Portuguese; between the two languages in play, Spanish wins
        assertEquals(Direction.THEIRS_TO_ME, decide("obrigado por tudo hoje", mapOf("pt" to 0.6f, "es" to 0.55f, "en" to 0.01f)))

    @Test fun lowConfidenceFallsBackToTheRecognizer() {
        assertEquals(Direction.MINE_TO_THEM, decide("okay so then what", mapOf("en" to 0.3f, "es" to 0.2f), heard = "en-US"))
        assertEquals(Direction.THEIRS_TO_ME, decide("okay so then what", mapOf("en" to 0.3f, "es" to 0.2f), heard = "es-US"))
    }

    @Test fun nothingKnownAssumesTheOtherPerson() =
        assertEquals(Direction.THEIRS_TO_ME, decide("mm hmm yes okay", emptyMap()))

    @Test fun shortPhrasesTrustTheRecognizerLanguage() {
        // "sí" alone is too short for language ID to be trusted, even if it says English
        assertEquals(Direction.THEIRS_TO_ME, decide("sí claro", mapOf("en" to 0.9f), heard = "es"))
        assertEquals(Direction.MINE_TO_THEM, decide("thank you", mapOf("es" to 0.9f), heard = "en-GB"))
        // with no recognizer language, language ID still decides
        assertEquals(Direction.MINE_TO_THEM, decide("thanks", mapOf("en" to 0.9f)))
    }

    @Test fun languageTagsAreNormalized() =
        assertEquals(Direction.MINE_TO_THEM, decide("good morning to you all", mapOf("en-Latn" to 0.8f, "es" to 0.1f)))

    @Test fun holdingTheButtonForcesMine() =
        assertEquals(Direction.MINE_TO_THEM, decide("hola amigo como estas", mapOf("es" to 0.99f), forced = Direction.MINE_TO_THEM))

    @Test fun emptyOrSameLanguageIsIgnored() {
        assertEquals(Direction.IGNORE, decide("   ", mapOf("en" to 1f)))
        assertEquals(Direction.IGNORE, DirectionRouter.decide("hello there friend", mapOf("en" to 1f), me = "en", them = "en-US"))
    }

    @Test fun swappedLanguagesSwapDirections() {
        val d = DirectionRouter.decide("where is the station please", mapOf("en" to 0.9f), me = "es", them = "en", heardAs = "en-US")
        assertEquals(Direction.THEIRS_TO_ME, d)
    }

    // ---- echo guard ----
    @Test fun ourOwnSpeechIsAnEchoForAWhile() {
        var now = 0L
        val g = EchoGuard({ now })
        g.spoke("¿Dónde está la estación de tren?")
        assertTrue(g.isEcho("donde esta la estación de tren"))
        assertFalse(g.isEcho("no sé, pregunta allí"))
        now += 9_000
        assertFalse(g.isEcho("donde esta la estación de tren"))
    }
}
