package app.folio.tts.silero

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import app.folio.tts.EngineVoice
import app.folio.tts.FolioTtsService
import app.folio.tts.Pcm
import app.folio.tts.Synthesizer
import app.folio.tts.VoiceStore
import app.folio.tts.VoicesActivity
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** The Silero model run by onnxruntime; long phrases are synthesized chunk by chunk ([sileroChunks]). */
class SileroSynthesizer(context: Context) : Synthesizer {
    private val store = VoiceStore.get(context, SileroCatalog.packs)
    private var session: OrtSession? = null

    override fun voices(): List<EngineVoice> = SileroCatalog.voices(store)

    /** All chunks of the phrase, joined; a phrase with nothing the model can read is silence. */
    override fun synthesize(voice: EngineVoice, text: String, speed: Float, stopped: () -> Boolean): Pcm {
        val speaker = SileroCatalog.speaker(voice.name) ?: error("Unknown voice ${voice.name}")
        val parts = mutableListOf<FloatArray>()
        for (chunk in sileroChunks(text)) {
            if (stopped()) break
            val tokens = sileroTokens(chunk)
            if (tokens.size > 2) parts += run(tokens, speaker.index, speed)
        }
        val samples = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(samples, at)
            at += p.size
        }
        return Pcm(samples, SileroModel.SAMPLE_RATE)
    }

    override fun release() {
        session?.close()
        session = null
    }

    private fun run(tokens: LongArray, speaker: Long, speed: Float): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val inputs = mapOf(
            "text" to OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())),
            "speaker" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speaker)), longArrayOf(1)),
            "speed" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf()),
        )
        try {
            session().run(inputs).use { result ->
                return (result[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also(b::get) }
            }
        } finally {
            inputs.values.forEach(OnnxTensor::close)
        }
    }

    private fun session(): OrtSession {
        session?.let { return it }
        val model = File(store.dir(SileroModel), SileroModel.file)
        check(model.isFile) { "Silero model is not installed" }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return OrtEnvironment.getEnvironment().createSession(model.path, options).also { session = it }
    }
}

class SileroService : FolioTtsService() {
    override fun createSynthesizer(): Synthesizer = SileroSynthesizer(this)
}

class SileroVoicesActivity : VoicesActivity() {
    override val catalog get() = SileroCatalog
    override val about get() = R.string.silero_about
}
