package app.folio.documents.engine

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import app.folio.documents.reflow.Reflow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

/** How «Скан как книга» lays pages out: [width] × [height] px of the view at [dpi], text [zoom] times print size. */
data class ScanLayout(val width: Int, val height: Int, val dpi: Int, val zoom: Float)

/**
 * «Скан как книга»: each PDF page rendered as an image and reflowed by k2pdfopt into one column of the view's width.
 * Results are [Bitmap.Config.ALPHA_8] masks (ink = opaque) so any theme can tint them; kept in memory and on disk
 * under [cacheDir], one file per page and layout. Not thread-safe: call from one thread.
 */
class ScanPages(private val text: PdfText, private val cacheDir: File) {
    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(page: Int, layout: ScanLayout): Bitmap? = memory.get(key(page, layout))

    /** [page] reflowed for [layout]; null when it can't be rendered or holds nothing. */
    fun page(page: Int, layout: ScanLayout): Bitmap? {
        val key = key(page, layout)
        memory.get(key)?.let { return it }
        val file = File(cacheDir, "$key.a8")
        val bitmap = read(file) ?: reflow(page, layout)?.also { write(file, it) }
        return bitmap?.also { memory.put(key, it) }
    }

    private fun reflow(page: Int, layout: ScanLayout): Bitmap? {
        val grey = text.pageGrey(page, layout.dpi * layout.zoom / 72f) ?: return null
        val out = runCatching {
            Reflow.reflow(grey.pixels, grey.width, grey.height, layout.width, layout.height, layout.dpi)
        }.onFailure { Log.w(TAG, "Reflow, page $page", it) }.getOrNull() ?: return null
        if (out.width <= 0 || out.height <= 0) return null
        return mask(out.width, out.height, out.pixels)
    }

    /** Grey (255 = paper) → alpha (255 = ink). */
    private fun mask(width: Int, height: Int, grey: ByteArray): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val stride = bitmap.rowBytes
        val buffer = ByteArray(stride * height)
        for (y in 0 until height) {
            val from = y * width
            val to = y * stride
            for (x in 0 until width) buffer[to + x] = (255 - (grey[from + x].toInt() and 0xFF)).toByte()
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(buffer))
        return bitmap
    }

    private fun write(file: File, bitmap: Bitmap) {
        runCatching {
            file.parentFile?.mkdirs()
            val buffer = ByteBuffer.allocate(bitmap.rowBytes * bitmap.height)
            bitmap.copyPixelsToBuffer(buffer)
            val tmp = File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use {
                it.writeInt(bitmap.width)
                it.writeInt(bitmap.height)
                it.writeInt(bitmap.rowBytes)
                it.write(buffer.array())
            }
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Cache write", it) }
    }

    private fun read(file: File): Bitmap? {
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use {
                val w = it.readInt()
                val h = it.readInt()
                val stride = it.readInt()
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                check(bitmap.rowBytes == stride)
                val bytes = ByteArray(stride * h)
                it.readFully(bytes)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                bitmap
            }
        }.onFailure { file.delete() }.getOrNull()
    }

    private fun key(page: Int, l: ScanLayout) = "${page}_${l.width}x${l.height}_${l.dpi}_${(l.zoom * 100).toInt()}"

    companion object {
        private const val TAG = "ScanPages"

        /** Disk cache of one book; the whole tree is trimmed to the newest books when it grows past [limitBytes]. */
        fun bookDir(root: File, bookId: String, limitBytes: Long = 200L * 1024 * 1024): File {
            val dir = File(root, "reflow/$bookId")
            runCatching {
                val books = File(root, "reflow").listFiles()?.filter { it.isDirectory && it != dir }.orEmpty()
                var total = books.sumOf { b -> b.listFiles()?.sumOf { it.length() } ?: 0L }
                for (b in books.sortedBy { it.lastModified() }) {
                    if (total <= limitBytes) break
                    total -= b.listFiles()?.sumOf { it.length() } ?: 0L
                    b.deleteRecursively()
                }
            }
            dir.mkdirs()
            dir.setLastModified(System.currentTimeMillis())
            return dir
        }
    }
}
