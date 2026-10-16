package app.folio.tts.piper

import app.folio.tts.EngineCatalog
import app.folio.tts.EngineVoice
import app.folio.tts.VoicePack
import app.folio.tts.VoiceStore
import java.io.File
import java.util.Locale

/** One downloadable Piper voice (sherpa-onnx `tts-models` release, int8: ~20 MB, quality close to fp32). */
data class PiperVoice(
    /** Piper name, e.g. `ru_RU-irina-medium`; also the folder under `files/voices/`. */
    override val id: String,
    val speaker: String,
    override val bytes: Long,
) : VoicePack {
    val locale: Locale get() = Locale.forLanguageTag(id.substringBefore('-').replace('_', '-'))
    override val title: String get() = "$speaker · ${locale.toLanguageTag()}"
    override val group: String get() = locale.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    override val url: String get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$id-int8.tar.bz2"
    /** The Android voice name apps select it by. */
    val voiceName: String get() = "piper-$id"

    override fun complete(dir: File): Boolean = voiceFiles(dir) != null
}

/** The files sherpa-onnx needs from an unpacked voice, or null when it isn't complete. */
internal data class VoiceFiles(val model: File, val tokens: File, val espeakData: File)

internal fun voiceFiles(dir: File): VoiceFiles? {
    val model = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.firstOrNull() ?: return null
    val tokens = File(dir, "tokens.txt").takeIf { it.isFile } ?: return null
    val espeak = File(dir, "espeak-ng-data").takeIf { it.isDirectory } ?: return null
    return VoiceFiles(model, tokens, espeak)
}

object PiperCatalog : EngineCatalog {
    /** Single-speaker voices, medium quality where Piper has one. */
    override val packs: List<PiperVoice> = listOf(
        PiperVoice("ru_RU-irina-medium", "Irina", 21_149_417),
        PiperVoice("ru_RU-denis-medium", "Denis", 21_058_905),
        PiperVoice("ru_RU-dmitri-medium", "Dmitri", 21_129_441),
        PiperVoice("ru_RU-ruslan-medium", "Ruslan", 21_127_907),
        PiperVoice("uk_UA-lada-x_low", "Lada", 13_332_976),
        PiperVoice("en_US-amy-medium", "Amy", 21_028_122),
        PiperVoice("en_US-lessac-medium", "Lessac", 20_969_179),
        PiperVoice("en_US-ryan-medium", "Ryan", 21_083_446),
        PiperVoice("en_GB-alan-medium", "Alan", 21_103_831),
        PiperVoice("en_GB-jenny_dioco-medium", "Jenny", 20_950_036),
        PiperVoice("de_DE-thorsten-medium", "Thorsten", 20_949_833),
        PiperVoice("fr_FR-siwis-medium", "Siwis", 20_914_888),
        PiperVoice("es_ES-davefx-medium", "Davefx", 21_171_632),
        PiperVoice("it_IT-paola-medium", "Paola", 21_143_212),
        PiperVoice("pl_PL-gosia-medium", "Gosia", 21_109_262),
    )

    override fun voices(store: VoiceStore): List<EngineVoice> =
        packs.filter { store.installed(it.id) }.map { EngineVoice(it.voiceName, it.locale, it.speaker) }

    fun voice(name: String): PiperVoice? = packs.firstOrNull { it.voiceName == name }
}
