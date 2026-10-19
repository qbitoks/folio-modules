package app.folio.documents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * The protocol with Folio (its side: `core/data/.../modules/PdfModule.kt`). Folio opens a book with
 * [ACTION_OPEN] and a read-only `content://` URI typed PDF or DjVu ([DJVU_TYPES]); the page goes back through Folio's provider. Names are stable API.
 */
object FolioLink {
    const val ACTION_OPEN = "app.folio.action.OPEN_PDF"
    const val EXTRA_BOOK_ID = "app.folio.extra.BOOK_ID"
    const val EXTRA_PAGE = "app.folio.extra.PAGE"
    const val EXTRA_TITLE = "app.folio.extra.TITLE"
    const val EXTRA_APPEARANCE = "app.folio.extra.APPEARANCE"
    val DJVU_TYPES = setOf("image/vnd.djvu", "image/x-djvu")

    private val PROVIDER: Uri = Uri.parse("content://app.folio.reading")
    private const val METHOD_POSITION = "position"
    private const val KEY_PAGE = "page"
    private const val KEY_PAGES = "pages"

    /** What Folio asked to open. */
    data class Request(
        val uri: Uri, val bookId: String, val page: Int, val title: String, val appearance: String, val djvu: Boolean = false,
    )

    fun request(intent: Intent): Request? {
        if (intent.action != ACTION_OPEN) return null
        val uri = intent.data ?: return null
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return null
        return Request(
            uri = uri,
            bookId = bookId,
            page = intent.getIntExtra(EXTRA_PAGE, 0).coerceAtLeast(0),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            appearance = intent.getStringExtra(EXTRA_APPEARANCE) ?: "auto",
            djvu = intent.type in DJVU_TYPES,
        )
    }

    /** Saves the page in Folio's library: «Продолжить», progress and «Прочитано» stay Folio's. Blocking. */
    fun reportPage(context: Context, bookId: String, page: Int, pages: Int) {
        val extras = Bundle().apply {
            putInt(KEY_PAGE, page)
            putInt(KEY_PAGES, pages)
        }
        try {
            context.contentResolver.call(PROVIDER, METHOD_POSITION, bookId, extras)
        } catch (e: Exception) {
            // Folio removed or older than the protocol: the module still keeps its own last page.
            Log.w("FolioLink", "Position not reported", e)
        }
    }
}
