package app.folio.tts.silero

import app.folio.tts.EngineCatalog
import app.folio.tts.EngineVoice
import app.folio.tts.VoicePack
import app.folio.tts.VoiceStore
import java.io.File
import java.util.Locale

/**
 * The Silero v5 CIS base model (MIT), converted to ONNX and int8 by `silero/export_onnx.py`: one file for
 * all [SileroSpeakers], 48 kHz. Hosted as a release asset of this repository.
 */
object SileroModel : VoicePack {
    override val id = "silero-v5-cis-base-int8"
    override val title = "Silero v5"
    override val group = "Silero"
    override val url = "https://github.com/qbitoks/folio-modules/releases/download/silero-model-v5/silero-v5-cis-base-int8.onnx"
    override val bytes = 64_693_958L
    override val file = "model.onnx"
    override val sha256 = "278628db177f4182096f8e214279dab535fed8911e00551f4db5ac9fd98b76c6"
    const val SAMPLE_RATE = 48_000

    override fun complete(dir: File): Boolean = File(dir, file).isFile

    /** The model reads Russian only here; its other CIS speakers are not offered. */
    fun speaks(locale: Locale) = locale.language == "ru"
}

/** A Russian speaker of [SileroModel]; [index] is its id in the model. */
data class SileroSpeaker(val key: String, val index: Long) {
    val name: String get() = key.removePrefix("ru_").replaceFirstChar { it.titlecase(Locale.ROOT) }
    /** The Android voice name apps select it by. */
    val voiceName: String get() = "silero-$key"
}

/** The model's `ru_*` speakers (indices from Silero's `v5_cis_base` speaker table). */
val SileroSpeakers = listOf(
    "aigul", "albina", "alexandr", "alfia", "alfia2", "bogdan", "dmitriy", "eduard", "ekaterina", "gamat",
    "igor", "karina", "kejilgan", "kermen", "marat", "miyau", "nurgul", "oksana", "onaoy", "ramilia",
    "roman", "safarhuja", "saida", "sibday", "vika", "zara", "zhadyra", "zhazira", "zinaida",
).mapIndexed { i, name -> SileroSpeaker("ru_$name", 20L + i) }

/** Listed first, so it is the default voice until the user picks another. */
val DefaultSileroSpeaker = SileroSpeakers.first { it.key == "ru_dmitriy" }

object SileroCatalog : EngineCatalog {
    private val russian = Locale.forLanguageTag("ru-RU")

    override val packs: List<VoicePack> = listOf(SileroModel)

    override fun voices(store: VoiceStore): List<EngineVoice> =
        if (!store.installed(SileroModel.id)) emptyList()
        else (listOf(DefaultSileroSpeaker) + (SileroSpeakers - DefaultSileroSpeaker)).map { EngineVoice(it.voiceName, russian, it.name) }

    fun speaker(voiceName: String): SileroSpeaker? = SileroSpeakers.firstOrNull { it.voiceName == voiceName }
}

/** Silero's symbol table: the token id of a character is its index here. */
private const val SYMBOLS = "|||!'+,-.:;?hабвгдежзийклмнопрстуфхцчшщъыьэюяёєіїјўґғҕҗҙқҝҡңҥҫүұҳҷҹһӑӗәӝӟӣӥӧөӯӱӳӵӏ—… "

/** Longest phrase fed to the model at once; longer ones are split (its position table holds ~60 s of audio). */
internal const val SILERO_MAX_CHARS = 350

/**
 * Token ids for [text]: lower-cased, numbers spelled out in Russian, dashes and ellipses unified,
 * characters the model has no symbol for dropped; wrapped in the model's start (2) and end (1) ids.
 */
internal fun sileroTokens(text: String): LongArray {
    val clean = sileroText(text)
    val ids = ArrayList<Long>(clean.length + 2)
    ids += 2L
    for (c in clean) ids += SYMBOLS.indexOf(c).toLong()
    ids += 1L
    return ids.toLongArray()
}

/** [text] as the model reads it (see [sileroTokens]): only characters from its symbol table, single spaces. */
internal fun sileroText(text: String): String = spellNumbers(text.lowercase(Locale.ROOT))
    .replace('–', '—').replace('‑', '-').replace("...", "…")
    .map { c -> if (c.isWhitespace()) ' ' else c }
    .filter { c -> SYMBOLS.indexOf(c) >= 3 }
    .joinToString("")
    .replace(Regex(" {2,}"), " ")
    .trim()

/** Splits a phrase longer than [max] characters at the last comma or space that fits. */
internal fun sileroChunks(text: String, max: Int = SILERO_MAX_CHARS): List<String> {
    val chunks = mutableListOf<String>()
    var rest = text.trim()
    while (rest.length > max) {
        val window = rest.substring(0, max)
        val cut = listOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(" — "))
            .maxOrNull()?.takeIf { it > max / 3 }?.plus(1)
            ?: window.lastIndexOf(' ').takeIf { it > 0 }
            ?: max
        chunks += rest.substring(0, cut).trim()
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) chunks += rest
    return chunks
}
