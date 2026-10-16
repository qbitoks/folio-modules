package app.folio.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FolioTtsServiceTest {
    private val ru = EngineVoice("ru-a", Locale.forLanguageTag("ru-RU"), "A")
    private val us = EngineVoice("en-us", Locale.forLanguageTag("en-US"), "US")
    private val gb = EngineVoice("en-gb", Locale.forLanguageTag("en-GB"), "GB")

    @Test fun languagesMatchByThreeLetterCodesAndPreferTheRegion() {
        assertEquals(listOf(gb, us), matching(listOf(ru, us, gb), "eng", "GBR"))
        assertEquals(listOf(ru), matching(listOf(ru, us), "ru", ""))
        assertEquals(emptyList<EngineVoice>(), matching(listOf(ru), "deu", "DEU"))
    }

    @Test fun availabilityTellsCountryFromLanguage() {
        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, availability(listOf(ru), "rus", "RUS"))
        assertEquals(TextToSpeech.LANG_AVAILABLE, availability(listOf(ru), "rus", "UKR"))
        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, availability(listOf(ru), "fra", ""))
    }

    @Test fun floatSamplesBecomeLittleEndian16Bit() {
        assertArrayEquals(byteArrayOf(0, 0, -1, 127, 1, -128), pcm16(floatArrayOf(0f, 1f, -2f)))
    }
}
