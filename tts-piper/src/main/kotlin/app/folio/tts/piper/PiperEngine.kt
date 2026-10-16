package app.folio.tts.piper

import android.content.Context
import app.folio.tts.EngineVoice
import app.folio.tts.FolioTtsService
import app.folio.tts.Pcm
import app.folio.tts.Synthesizer
import app.folio.tts.VoiceStore
import app.folio.tts.VoicesActivity
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/** Piper models run by sherpa-onnx; one voice is kept loaded at a time. */
class PiperSynthesizer(context: Context) : Synthesizer {
    private val store = VoiceStore.get(context, PiperCatalog.packs)
    private var tts: Pair<String, OfflineTts>? = null

    override fun voices(): List<EngineVoice> = PiperCatalog.voices(store)

    override fun synthesize(voice: EngineVoice, text: String, speed: Float, stopped: () -> Boolean): Pcm {
        val generated = engineFor(voice.name).generate(text, 0, speed)
        return Pcm(generated.samples, generated.sampleRate)
    }

    override fun release() {
        tts?.second?.release()
        tts = null
    }

    private fun engineFor(name: String): OfflineTts {
        tts?.let { (loaded, engine) -> if (loaded == name) return engine else engine.release() }
        tts = null
        val pack = PiperCatalog.voice(name) ?: error("Unknown voice $name")
        val files = voiceFiles(store.dir(pack)) ?: error("Voice $name is not installed")
        val config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = files.model.path, tokens = files.tokens.path, dataDir = files.espeakData.path),
            numThreads = 2,
        ))
        return OfflineTts(config = config).also { tts = name to it }
    }
}

class PiperService : FolioTtsService() {
    override fun createSynthesizer(): Synthesizer = PiperSynthesizer(this)
}

class PiperVoicesActivity : VoicesActivity() {
    override val catalog get() = PiperCatalog
    override val about get() = R.string.piper_about
}
