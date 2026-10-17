package app.folio.documents

import android.content.Context
import androidx.compose.ui.graphics.Color

/**
 * How the book is shown: the PDF's own pages, its text laid out like a book (MJ PDF's text mode), or its page
 * images reflowed into one column by k2pdfopt («Скан как книга», works for scans too).
 */
enum class Mode { Pages, Book, Scan }

/** Page colours, Folio's reader palettes; in «Оригинал» mode only Original and Night apply (Night inverts pages). */
enum class PageTheme(val background: Color, val text: Color, val dark: Boolean) {
    Original(Color(0xFFFFFFFF), Color(0xFF000000), false),
    Paper(Color(0xFFEDEDED), Color(0xFF000000), false),
    Calm(Color(0xFFF0E2C8), Color(0xFF2F2019), false),
    Night(Color(0xFF000000), Color(0xFFFFFFFF), true),
}

/** «Как книга» typefaces: the serif faces Folio ships. */
enum class BookFont { Literata, PtSerif, SourceSerif }

data class ReaderPrefs(
    val mode: Mode = Mode.Pages,
    val theme: PageTheme = PageTheme.Original,
    /** Vertical continuous scroll instead of one page per swipe. */
    val scroll: Boolean = false,
    val crop: Boolean = false,
    val textSize: Float = 19f,
    val lineSpacing: Float = 1.45f,
    val font: BookFont = BookFont.Literata,
    val volumeKeys: Boolean = true,
    /** «Скан как книга»: text size against print. */
    val scanZoom: Float = 1.0f,
)

/** Module-wide reading settings and the last page of each book (Folio keeps the real position). */
class PrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun load(): ReaderPrefs = ReaderPrefs(
        mode = enumOr(prefs.getString("mode", null), Mode.Pages),
        theme = enumOr(prefs.getString("theme", null), PageTheme.Original),
        scroll = prefs.getBoolean("scroll", false),
        crop = prefs.getBoolean("crop", false),
        textSize = prefs.getFloat("text_size", 19f),
        lineSpacing = prefs.getFloat("line_spacing", 1.45f),
        font = enumOr(prefs.getString("font", null), BookFont.Literata),
        volumeKeys = prefs.getBoolean("volume_keys", true),
        scanZoom = prefs.getFloat("scan_zoom", 1.0f),
    )

    fun save(p: ReaderPrefs) {
        prefs.edit()
            .putString("mode", p.mode.name)
            .putString("theme", p.theme.name)
            .putBoolean("scroll", p.scroll)
            .putBoolean("crop", p.crop)
            .putFloat("text_size", p.textSize)
            .putFloat("line_spacing", p.lineSpacing)
            .putString("font", p.font.name)
            .putBoolean("volume_keys", p.volumeKeys)
            .putFloat("scan_zoom", p.scanZoom)
            .apply()
    }

    fun lastPage(bookId: String): Int? = prefs.getInt("page_$bookId", -1).takeIf { it >= 0 }

    fun saveLastPage(bookId: String, page: Int) = prefs.edit().putInt("page_$bookId", page).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
