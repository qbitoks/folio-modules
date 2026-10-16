package app.folio.tts

import java.util.Locale

/** Mono float PCM, as an offline model produces it. */
class Pcm(val samples: FloatArray, val sampleRate: Int)

/** One voice an engine can speak with now; [name] is the Android `Voice` name apps select it by. */
data class EngineVoice(val name: String, val locale: Locale, val title: String)

/**
 * An offline speech model behind [FolioTtsService]. All calls come from the service's single
 * synthesis thread, so implementations need no locking of their native sessions.
 */
interface Synthesizer {
    /** Voices whose files are installed. */
    fun voices(): List<EngineVoice>

    /** Speaks [text] with [voice]; [speed] 1 is normal. [stopped] is polled between chunks of a long phrase. */
    fun synthesize(voice: EngineVoice, text: String, speed: Float, stopped: () -> Boolean): Pcm

    /** Frees native sessions; the next [synthesize] loads them again. */
    fun release()
}
