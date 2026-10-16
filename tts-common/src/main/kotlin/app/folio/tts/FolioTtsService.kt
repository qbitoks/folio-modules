package app.folio.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.MissingResourceException

/**
 * A system text-to-speech engine over an offline [Synthesizer]: any app (Folio, Android settings) can pick
 * it and its installed voices through the standard `TextToSpeech` API. Android calls every `on*` method
 * except [onStop] on one synthesis thread, and plays the PCM handed to the callback on its own audio thread,
 * so the next phrase is synthesized while this one sounds.
 */
abstract class FolioTtsService : TextToSpeechService() {
    protected abstract fun createSynthesizer(): Synthesizer

    private val synth by lazy { createSynthesizer() }
    private val defaults by lazy { VoiceDefaults(this) }
    private var language: Array<String> = arrayOf("", "", "")

    @Volatile
    private var stopped = false

    override fun onDestroy() {
        runCatching { synth.release() }
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        availability(synth.voices(), lang.orEmpty(), country.orEmpty())

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) language = arrayOf(lang.orEmpty(), country.orEmpty(), "")
        return result
    }

    override fun onGetLanguage(): Array<String> = language

    override fun onGetVoices(): List<Voice> = synth.voices().map { v ->
        Voice(v.name, v.locale, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (synth.voices().any { it.name == voiceName }) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        defaultVoice(lang.orEmpty(), country.orEmpty())?.name

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val voices = synth.voices()
        val voice = voices.firstOrNull { it.name == request.voiceName } ?: defaultVoice(request.language, request.country)
        if (voice == null) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }
        val text = request.charSequenceText?.toString() ?: request.text.orEmpty()
        val pcm = try {
            synth.synthesize(voice, text, request.speechRate / 100f) { stopped }
        } catch (e: Throwable) {
            Log.e("FolioTtsService", "Synthesis failed", e)
            runCatching { synth.release() }
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
            return
        }
        if (stopped) return
        if (callback.start(pcm.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        val bytes = pcm16(pcm.samples)
        val chunk = callback.maxBufferSize
        var offset = 0
        while (offset < bytes.size && !stopped) {
            val n = minOf(chunk, bytes.size - offset)
            if (callback.audioAvailable(bytes, offset, n) != TextToSpeech.SUCCESS) return
            offset += n
        }
        if (!stopped) callback.done()
    }

    /** The voice the user marked as default for the language in this engine's screen, else the first one. */
    private fun defaultVoice(lang: String, country: String): EngineVoice? {
        val matching = matching(synth.voices(), lang, country)
        val language = matching.firstOrNull()?.locale?.language ?: return null
        return matching.firstOrNull { it.name == defaults.get(language) } ?: matching.first()
    }
}

/** ISO 639-2 language of [locale] ("rus"), or its two-letter code when Java has no three-letter one. */
internal fun iso3Language(locale: Locale): String =
    try { locale.isO3Language } catch (_: MissingResourceException) { locale.language }

internal fun iso3Country(locale: Locale): String =
    try { locale.isO3Country } catch (_: MissingResourceException) { locale.country }

/** [voices] that speak [lang] (two- or three-letter), those of [country] first. */
internal fun matching(voices: List<EngineVoice>, lang: String, country: String): List<EngineVoice> {
    val asked = Locale(lang, country)
    val language = iso3Language(asked).lowercase()
    val region = iso3Country(asked).uppercase()
    return voices.filter { iso3Language(it.locale).lowercase() == language }
        .sortedByDescending { region.isNotEmpty() && iso3Country(it.locale).uppercase() == region }
}

internal fun availability(voices: List<EngineVoice>, lang: String, country: String): Int {
    val found = matching(voices, lang, country).firstOrNull() ?: return TextToSpeech.LANG_NOT_SUPPORTED
    val region = iso3Country(Locale(lang, country)).uppercase()
    return if (region.isNotEmpty() && iso3Country(found.locale).uppercase() == region) TextToSpeech.LANG_COUNTRY_AVAILABLE
    else TextToSpeech.LANG_AVAILABLE
}

/** Float samples in [-1, 1] as little-endian 16-bit PCM. */
internal fun pcm16(samples: FloatArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val v = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
        out[2 * i] = v.toByte()
        out[2 * i + 1] = (v shr 8).toByte()
    }
    return out
}
