package app.folio.documents.djvu

import android.graphics.Bitmap
import java.io.Closeable

/** A page's size in pixels at its own [dpi]. */
class DjvuPageInfo(val width: Int, val height: Int, val dpi: Int)

/** One outline entry: [page] zero-based, −1 when its link points nowhere known; [level] 0 is the top. */
class DjvuOutlineItem(val title: String, val page: Int, val level: Int)

/**
 * A DjVu document through DjVuLibre. [path] must stay readable while it is open (a `/proc/self/fd/N` path works).
 * Not thread-safe: calls are serialized on the object.
 */
class DjvuDocument private constructor(private var handle: Long) : Closeable {
    val pageCount: Int = nativePageCount(handle)

    @Synchronized
    fun pageInfo(page: Int): DjvuPageInfo? {
        if (handle == 0L || page !in 0 until pageCount) return null
        val v = IntArray(3)
        return if (nativePageInfo(handle, page, v)) DjvuPageInfo(v[0], v[1], v[2]) else null
    }

    /**
     * Renders into [bitmap] (ARGB_8888) the tile at [x], [y] of [page] scaled to [fullWidth] × [fullHeight]; the
     * tile is the bitmap's size. False when the page can't be decoded.
     */
    @Synchronized
    fun render(page: Int, bitmap: Bitmap, fullWidth: Int, fullHeight: Int, x: Int = 0, y: Int = 0): Boolean {
        if (handle == 0L || page !in 0 until pageCount) return false
        return nativeRender(handle, page, bitmap, fullWidth, fullHeight, x, y)
    }

    /** [page] as [width] × [height] 8-bit grey, 255 = paper; null when it can't be decoded. */
    @Synchronized
    fun renderGrey(page: Int, width: Int, height: Int): ByteArray? {
        if (handle == 0L || page !in 0 until pageCount) return null
        return nativeRenderGrey(handle, page, width, height)
    }

    @Synchronized
    fun outline(): List<DjvuOutlineItem> {
        if (handle == 0L) return emptyList()
        val flat = nativeOutline(handle) ?: return emptyList()
        return (0 until flat.size / 3).map { i ->
            DjvuOutlineItem(flat[i * 3 + 1].trim(), resolve(flat[i * 3 + 2]), flat[i * 3].toIntOrNull() ?: 0)
        }
    }

    /** Outline links are `#N` (one-based page) or `#name` (a page's id or file name). */
    private fun resolve(url: String): Int {
        if (!url.startsWith("#")) return -1
        val name = url.substring(1)
        name.toIntOrNull()?.let { return (it - 1).takeIf { p -> p in 0 until pageCount } ?: -1 }
        return nativeSearchPage(handle, name)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("foliodjvu")
        }

        /** Opens [path]; null when it is not a readable DjVu document. Blocks while the directory is decoded. */
        fun open(path: String): DjvuDocument? = nativeOpen(path).takeIf { it != 0L }?.let { DjvuDocument(it) }

        @JvmStatic private external fun nativeOpen(path: String): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativePageCount(handle: Long): Int
        @JvmStatic private external fun nativePageInfo(handle: Long, page: Int, out: IntArray): Boolean
        @JvmStatic private external fun nativeRender(handle: Long, page: Int, bitmap: Bitmap, fullW: Int, fullH: Int, x: Int, y: Int): Boolean
        @JvmStatic private external fun nativeRenderGrey(handle: Long, page: Int, width: Int, height: Int): ByteArray?
        @JvmStatic private external fun nativeOutline(handle: Long): Array<String>?
        @JvmStatic private external fun nativeSearchPage(handle: Long, name: String): Int
    }
}
