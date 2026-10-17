// Based on MJ PDF's PdfExtractor by Mudlej. GPL-3.0.
package app.folio.documents.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.Closeable

/** One entry of the PDF's own outline, flattened: [level] 0 is the top. */
data class TocEntry(val title: String, val page: Int, val level: Int)

/** A page rendered grey: [width] × [height] bytes at [pxPerPoint] pixels per point. */
class GreyPage(val width: Int, val height: Int, val pxPerPoint: Float, val pixels: ByteArray)

/** A search hit: [page] (zero-based) and a snippet around the match. */
data class SearchHit(val page: Int, val snippet: String)

/**
 * A second Pdfium document next to the one PDFView renders: text, outline and search. Pdfium calls are
 * serialized by PdfiumCore's global lock, so this can run on a background thread while pages render.
 */
class PdfText private constructor(private val core: PdfiumCore, private val doc: PdfDocument) : Closeable {
    val pageCount: Int = core.getPageCount(doc)

    fun outline(): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        fun walk(items: List<PdfDocument.Bookmark>, level: Int) {
            for (b in items) {
                val title = b.title?.trim().orEmpty()
                if (title.isNotEmpty() && b.pageIdx in 0L until pageCount.toLong()) out += TocEntry(title, b.pageIdx.toInt(), level)
                if (b.hasChildren() && level < 8) walk(b.children, level + 1)
            }
        }
        runCatching { walk(core.getTableOfContents(doc), 0) }.onFailure { Log.w(TAG, "Outline", it) }
        return out
    }

    /** Plain text of [page] (zero-based); empty when the page has none or fails. */
    fun pageText(page: Int): String = withPage(page) { core.getPageText(doc, page) }.orEmpty()

    /** [page] laid out as paragraphs and headings (MJ PDF's structured text mode); null without a text layer. */
    fun pageBook(page: Int): CharSequence? {
        val metrics = withPage(page) {
            if (core.openTextPage(doc, page) == 0L) return@withPage null
            val count = core.textCountChars(doc, page)
            if (count <= 0 || count > MAX_METRICS_CHARS) return@withPage null
            PageCharMetrics.fromRaw(core.textCharMetrics(doc, page, 0, count))
        }
        val structured = metrics?.let {
            runCatching { StructuredTextFormatter.format(it, joinParagraphs = true, detectHeadings = true, detectCodeBlocks = false) }
                .onFailure { e -> Log.w(TAG, "Structured text, page $page", e) }.getOrNull()
        }
        if (!structured.isNullOrBlank()) return structured
        val plain = pageText(page)
        return if (plain.isBlank()) null else TextModeTextFormatter.formatJoined(plain)
    }

    /**
     * [page] as 8-bit grey (255 = paper) at [pxPerPoint] pixels per PDF point, scaled down so the long side fits
     * [maxSide]; null when it fails. The scale actually used is [GreyPage.pxPerPoint].
     */
    fun pageGrey(page: Int, pxPerPoint: Float, maxSide: Int = 4200): GreyPage? = withPage(page) {
        val size = core.getPageSizePoint(doc, page)
        if (size.width <= 0f || size.height <= 0f) return@withPage null
        val scale = minOf(pxPerPoint, maxSide / maxOf(size.width, size.height))
        val w = (size.width * scale).toInt().coerceAtLeast(1)
        val h = (size.height * scale).toInt().coerceAtLeast(1)
        // RGB_565 halves the peak memory of a big page; grey needs no more.
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        try {
            bitmap.eraseColor(Color.WHITE)
            core.renderPageBitmap(doc, bitmap, page, 0, 0, w, h)
            val row = IntArray(w)
            val grey = ByteArray(w * h)
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                val o = y * w
                for (x in 0 until w) {
                    val c = row[x]
                    grey[o + x] = (((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
                }
            }
            GreyPage(w, h, scale, grey)
        } finally {
            bitmap.recycle()
        }
    }

    /** Whether the first pages carry text at all (scans don't): decides if «Как книга» is offered. */
    fun hasText(): Boolean = (0 until minOf(pageCount, 8)).any { pageText(it).isNotBlank() }

    /** Case-insensitive search from [from], page by page; [onProgress] gets 0..1, [cancelled] stops it. */
    fun search(query: String, onProgress: (Float) -> Unit, cancelled: () -> Boolean): List<SearchHit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        for (page in 0 until pageCount) {
            if (cancelled() || hits.size >= MAX_HITS) break
            val text = pageText(page).replace(Regex("\\s+"), " ")
            val lower = text.lowercase()
            var at = lower.indexOf(needle)
            while (at >= 0 && hits.size < MAX_HITS) {
                val start = (at - 40).coerceAtLeast(0)
                val end = (at + needle.length + 60).coerceAtMost(text.length)
                hits += SearchHit(page, (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else ""))
                at = lower.indexOf(needle, at + needle.length)
            }
            onProgress((page + 1f) / pageCount)
        }
        return hits
    }

    override fun close() {
        runCatching { core.closeDocument(doc) }
    }

    private fun <T> withPage(page: Int, block: () -> T): T? {
        if (page !in 0 until pageCount) return null
        return try {
            core.openPage(doc, page)
            try {
                block()
            } finally {
                core.closePage(doc, page)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Page $page", e)
            null
        }
    }

    companion object {
        private const val TAG = "PdfText"
        private const val MAX_METRICS_CHARS = 20_000
        private const val MAX_HITS = 500

        /** Opens [uri] read-only; throws when it is not a readable PDF. */
        fun open(context: Context, uri: Uri): PdfText {
            val core = PdfiumCore(context)
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("No file")
            return PdfText(core, core.newDocument(fd))
        }
    }
}
