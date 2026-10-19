package app.folio.documents.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import app.folio.documents.djvu.DjvuDocument
import app.folio.documents.djvu.DjvuPageInfo

/**
 * A DjVu book: page images for «Оригинал» and «Скан как книга», and its outline. DjVu has no text here, so «Как
 * книга» and search stay off. DjVuLibre reads the file through `/proc/self/fd`, so [fd] stays open with it.
 */
class DjvuPages private constructor(private val fd: ParcelFileDescriptor, private val doc: DjvuDocument) : PageImages {
    val pageCount: Int = doc.pageCount
    private val infos = arrayOfNulls<DjvuPageInfo>(pageCount)

    fun outline(): List<TocEntry> = runCatching {
        doc.outline().filter { it.title.isNotEmpty() && it.page >= 0 }.map { TocEntry(it.title, it.page, it.level) }
    }.onFailure { Log.w(TAG, "Outline", it) }.getOrDefault(emptyList())

    /** The page's aspect ratio (width / height); null when it can't be read. */
    fun aspect(page: Int): Float? = info(page)?.let { it.width.toFloat() / it.height }

    /** [page] fitted into [maxWidth] × [maxHeight] px, keeping its proportions; null when it can't be decoded. */
    fun render(page: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        val info = info(page) ?: return null
        val scale = minOf(maxWidth.toFloat() / info.width, maxHeight.toFloat() / info.height)
        val w = (info.width * scale).toInt().coerceAtLeast(1)
        val h = (info.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return if (doc.render(page, bitmap, w, h)) bitmap else null.also { bitmap.recycle() }
    }

    override fun pageGrey(page: Int, pxPerPoint: Float, maxSide: Int): GreyPage? {
        val info = info(page) ?: return null
        val wPt = info.width * 72f / info.dpi
        val hPt = info.height * 72f / info.dpi
        val scale = minOf(pxPerPoint, maxSide / maxOf(wPt, hPt))
        val w = (wPt * scale).toInt().coerceAtLeast(1)
        val h = (hPt * scale).toInt().coerceAtLeast(1)
        val pixels = doc.renderGrey(page, w, h) ?: return null
        return GreyPage(w, h, scale, pixels)
    }

    private fun info(page: Int): DjvuPageInfo? {
        if (page !in 0 until pageCount) return null
        return infos[page] ?: doc.pageInfo(page)?.also { infos[page] = it }
    }

    override fun close() {
        runCatching { doc.close() }
        runCatching { fd.close() }
    }

    companion object {
        private const val TAG = "DjvuPages"

        /** Opens [uri] read-only; throws when it is not a readable DjVu document. */
        fun open(context: Context, uri: Uri): DjvuPages {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("No file")
            val doc = DjvuDocument.open("/proc/self/fd/${fd.fd}")
            if (doc == null) {
                fd.close()
                error("Not a DjVu document")
            }
            return DjvuPages(fd, doc)
        }
    }
}
