package app.folio.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Where a downloadable voice stands on this device. */
sealed interface VoiceStatus {
    data object Missing : VoiceStatus
    /** [fraction] null while the size is unknown or the archive is being unpacked. */
    data class Downloading(val fraction: Float?) : VoiceStatus
    data object Failed : VoiceStatus
    data object Installed : VoiceStatus
}

/** Files to download for one or more voices: a `.tar.bz2` to unpack, or a single [file]. */
interface VoicePack {
    /** Unique within the engine; also the folder name under `files/voices/`. */
    val id: String
    val title: String
    /** Group shown in the voices screen, e.g. the language name. */
    val group: String
    val url: String
    val bytes: Long
    /** Name to save a single-file download under; null = a `.tar.bz2` unpacked without its top folder. */
    val file: String? get() = null
    /** Expected SHA-256 of the download (hex), checked when set. */
    val sha256: String? get() = null

    /** Whether [dir] holds everything the engine loads. */
    fun complete(dir: File): Boolean
}

/**
 * Voice packs on disk in `files/voices/<id>/`. A pack counts as installed only once complete and renamed
 * into place, so a crash mid-download leaves only `<id>.part` / `<id>.tmp`, cleaned up by the next attempt.
 * One instance per process ([get]): the voices screen and the TTS service see the same state.
 */
class VoiceStore private constructor(context: Context, val packs: List<VoicePack>) {
    private val root = File(context.filesDir, "voices")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _status = MutableStateFlow(packs.associate { it.id to statusOnDisk(it) })
    val status: StateFlow<Map<String, VoiceStatus>> = _status.asStateFlow()

    fun installed(id: String): Boolean = _status.value[id] == VoiceStatus.Installed

    fun dir(pack: VoicePack) = File(root, pack.id)

    fun download(pack: VoicePack) {
        if (jobs[pack.id]?.isActive == true || installed(pack.id)) return
        set(pack.id, VoiceStatus.Downloading(0f))
        jobs[pack.id] = scope.launch {
            try {
                install(pack)
                set(pack.id, VoiceStatus.Installed)
            } catch (e: CancellationException) {
                cleanUp(pack)
                set(pack.id, VoiceStatus.Missing)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Voice ${pack.id} failed", e)
                cleanUp(pack)
                set(pack.id, VoiceStatus.Failed)
            }
        }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
    }

    fun delete(pack: VoicePack) {
        cancel(pack.id)
        scope.launch {
            dir(pack).deleteRecursively()
            cleanUp(pack)
            set(pack.id, VoiceStatus.Missing)
        }
    }

    private suspend fun install(pack: VoicePack) {
        root.mkdirs()
        cleanUp(pack)
        val part = File(root, "${pack.id}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        runInterruptible {
            Https.open(pack.url) { stream, length ->
                val total = length.takeIf { it > 0 } ?: pack.bytes
                if (total > MAX_BYTES) throw IOException("Voice too big: $total")
                copy(stream, part, digest) { done -> set(pack.id, VoiceStatus.Downloading((done.toFloat() / total).coerceIn(0f, 1f))) }
            }
        }
        currentCoroutineContext().ensureActive()
        pack.sha256?.let { expected ->
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) throw IOException("SHA-256 mismatch for ${pack.id}")
        }
        set(pack.id, VoiceStatus.Downloading(null))
        val tmp = File(root, "${pack.id}.tmp")
        val file = pack.file
        if (file == null) {
            runInterruptible { unpack(part, tmp) }
        } else {
            tmp.mkdirs()
            if (!part.renameTo(File(tmp, file))) throw IOException("Can't move ${pack.id} into place")
        }
        part.delete()
        check(pack.complete(tmp)) { "Incomplete voice ${pack.id}" }
        if (!tmp.renameTo(dir(pack))) throw IOException("Can't move ${pack.id} into place")
    }

    private fun copy(input: InputStream, to: File, digest: MessageDigest, progress: (Long) -> Unit) {
        var written = 0L
        var published = 0L
        FileOutputStream(to).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val n = input.read(buffer)
                if (n < 0) break
                written += n
                if (written > MAX_BYTES) throw IOException("Voice too big")
                out.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                val now = System.nanoTime()
                if (now - published > 200_000_000L) {
                    published = now
                    progress(written)
                }
            }
        }
    }

    private fun set(id: String, status: VoiceStatus) = _status.update { it + (id to status) }

    private fun statusOnDisk(pack: VoicePack) = if (pack.complete(dir(pack))) VoiceStatus.Installed else VoiceStatus.Missing

    private fun cleanUp(pack: VoicePack) {
        File(root, "${pack.id}.part").delete()
        File(root, "${pack.id}.tmp").deleteRecursively()
    }

    companion object {
        private const val TAG = "VoiceStore"
        private const val MAX_BYTES = 300L * 1024 * 1024

        @Volatile
        private var instance: VoiceStore? = null

        /** The process-wide store; [packs] must be the same on every call (the engine's catalog). */
        fun get(context: Context, packs: List<VoicePack>): VoiceStore =
            instance ?: synchronized(this) { instance ?: VoiceStore(context.applicationContext, packs).also { instance = it } }
    }
}

/**
 * Unpacks a `.tar.bz2` voice into [into], dropping the archive's top folder. Entries that would land
 * outside [into] (`..`, absolute paths) and links are refused.
 */
fun unpack(archive: File, into: File) {
    into.mkdirs()
    val base = into.canonicalPath + File.separator
    TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val entry = tar.nextEntry ?: break
            val relative = entry.name.trimStart('/').substringAfter('/', "")
            if (relative.isEmpty()) continue
            if (entry.isSymbolicLink || entry.isLink) throw IOException("Link in voice archive: ${entry.name}")
            val target = File(into, relative)
            if (!target.canonicalPath.startsWith(base)) throw IOException("Entry outside the voice folder: ${entry.name}")
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
            }
        }
    }
}
