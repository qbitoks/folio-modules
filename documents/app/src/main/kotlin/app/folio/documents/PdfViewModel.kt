package app.folio.documents

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.folio.documents.engine.DjvuPages
import app.folio.documents.engine.PageImages
import app.folio.documents.engine.PdfText
import app.folio.documents.engine.ScanLayout
import app.folio.documents.engine.ScanPages
import app.folio.documents.engine.SearchHit
import app.folio.documents.engine.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search state: [hits] null while nothing was searched; [progress] < 1 while running. */
data class SearchState(val query: String = "", val hits: List<SearchHit>? = null, val progress: Float = 1f)

/** A book (PDF or DjVu) opened by Folio: page, settings, outline, search and «Как книга» text. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PdfViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PrefsStore(app)
    private val pdfThread = Dispatchers.IO.limitedParallelism(1)
    private var text: PdfText? = null
    /** A DjVu book instead of [text]: its pages for «Оригинал», no text. */
    private var djvu: DjvuPages? = null
    private val djvuPages = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val bookPages = LruCache<Int, CharSequence>(24)
    /** k2pdfopt is single-threaded and slow: its own thread and its own Pdfium document keep text and search free. */
    private val scanThread = Dispatchers.Default.limitedParallelism(1)
    private var scans: ScanPages? = null
    private var scanText: PageImages? = null
    private var prefetch: Job? = null

    var request: FolioLink.Request? = null
        private set

    private val _prefs = MutableStateFlow(store.load())
    val prefs: StateFlow<ReaderPrefs> = _prefs.asStateFlow()

    private val _page = MutableStateFlow(0)
    /** Zero-based page shown now, in either mode. */
    val page: StateFlow<Int> = _page.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val _hasText = MutableStateFlow<Boolean?>(null)
    /** Null while checking; false for scans: «Как книга» is then unavailable. */
    val hasText: StateFlow<Boolean?> = _hasText.asStateFlow()

    private val _toc = MutableStateFlow<List<TocEntry>?>(null)
    val toc: StateFlow<List<TocEntry>?> = _toc.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searching: Job? = null

    private val _jumps = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** Pages to show (outline, search, scrubber); the visible view scrolls there. */
    val jumps: SharedFlow<Int> = _jumps

    private val _turns = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** +1 / −1 page turns from volume keys. */
    val turns: SharedFlow<Int> = _turns

    fun open(request: FolioLink.Request) {
        if (this.request?.bookId == request.bookId && this.request?.uri == request.uri) return
        this.request = request
        _page.value = request.page
        if (request.djvu) {
            openDjvu(request)
        } else viewModelScope.launch {
            val opened = withContext(pdfThread) {
                runCatching { PdfText.open(getApplication(), request.uri) }.onFailure { Log.w(TAG, "Open", it) }.getOrNull()
            }
            if (opened == null) {
                _failed.value = true
                return@launch
            }
            text = opened
            _pageCount.value = opened.pageCount
            _page.value = request.page.coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
            _toc.value = withContext(pdfThread) { opened.outline() }
            _hasText.value = withContext(pdfThread) { opened.hasText() }
            if (_hasText.value == false && _prefs.value.mode == Mode.Book) update { it.copy(mode = Mode.Scan) }
        }
        // Folio gets the page a moment after the reader settles on it.
        viewModelScope.launch {
            _page.drop(1).debounce(600).collect { report(it) }
        }
    }

    private fun openDjvu(request: FolioLink.Request) = viewModelScope.launch {
        val opened = withContext(pdfThread) {
            runCatching { DjvuPages.open(getApplication(), request.uri) }.onFailure { Log.w(TAG, "Open DjVu", it) }.getOrNull()
        }
        if (opened == null) {
            _failed.value = true
            return@launch
        }
        djvu = opened
        _pageCount.value = opened.pageCount
        _page.value = request.page.coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
        _toc.value = withContext(pdfThread) { opened.outline() }
        // DjVu has no text here: «Оригинал» and «Скан как книга» only.
        _hasText.value = false
        if (_prefs.value.mode == Mode.Book) update { it.copy(mode = Mode.Scan) }
    }

    /** DjVu [page] fitted into [width] × [height] px, cached; null when it can't be decoded. */
    suspend fun djvuPage(page: Int, width: Int, height: Int): Bitmap? {
        val key = "${page}_${width}x$height"
        djvuPages.get(key)?.let { return it }
        val doc = djvu ?: return null
        return withContext(pdfThread) { doc.render(page, width, height) }?.also { djvuPages.put(key, it) }
    }

    /** Width / height of DjVu [page], for laying out pages before they render. */
    fun djvuAspect(page: Int): Float? = djvu?.aspect(page)

    fun onPage(page: Int) {
        if (page >= 0) _page.value = page
    }

    fun jumpTo(page: Int) {
        val target = page.coerceIn(0, (_pageCount.value - 1).coerceAtLeast(0))
        _page.value = target
        _jumps.tryEmit(target)
    }

    fun turn(delta: Int) {
        _turns.tryEmit(delta)
    }

    fun update(change: (ReaderPrefs) -> ReaderPrefs) {
        // Only a text PDF has a choice of reading modes; a scan's forced «Скан» isn't one.
        val next = change(_prefs.value).let {
            if (it.mode != Mode.Pages && _hasText.value == true) it.copy(lastReflow = it.mode) else it
        }
        if (next == _prefs.value) return
        _prefs.value = next
        store.save(next)
    }

    /** [page] as book text, cached; null for a page without text. */
    suspend fun bookPage(page: Int): CharSequence? {
        bookPages.get(page)?.let { return it }
        val doc = text ?: return null
        return withContext(pdfThread) { doc.pageBook(page) }?.also { bookPages.put(page, it) }
    }

    /** [page] reflowed for «Скан как книга», from the caches when it was done before; null when it fails. */
    suspend fun scanPage(page: Int, layout: ScanLayout): Bitmap? {
        scans?.cached(page, layout)?.let { return it }
        // The page on screen goes before the read-ahead queue.
        prefetch?.cancel()
        return withContext(scanThread) { scanPages()?.page(page, layout) }
    }

    /** Reflows the pages after [page] in the background so turning to them doesn't wait. */
    fun prefetchScans(page: Int, layout: ScanLayout) {
        prefetch?.cancel()
        prefetch = viewModelScope.launch(scanThread) {
            for (p in page + 1..minOf(page + PREFETCH, _pageCount.value - 1)) {
                if (!isActive) break
                scanPages()?.page(p, layout)
            }
        }
    }

    private fun scanPages(): ScanPages? {
        scans?.let { return it }
        val r = request ?: return null
        val doc = runCatching<PageImages> {
            if (r.djvu) DjvuPages.open(getApplication(), r.uri) else PdfText.open(getApplication(), r.uri)
        }.onFailure { Log.w(TAG, "Open for scans", it) }.getOrNull() ?: return null
        scanText = doc
        val id = r.bookId.filter { it.isLetterOrDigit() }.ifEmpty { "book" }
        return ScanPages(doc, ScanPages.bookDir(getApplication<Application>().cacheDir, id)).also { scans = it }
    }

    fun search(query: String) {
        searching?.cancel()
        val doc = text ?: return
        if (query.isBlank()) {
            _search.value = SearchState()
            return
        }
        _search.value = SearchState(query, emptyList(), 0f)
        searching = viewModelScope.launch {
            val hits = withContext(pdfThread) {
                doc.search(query, onProgress = { p -> _search.update { it.copy(progress = p) } }, cancelled = { !isActive })
            }
            _search.value = SearchState(query, hits, 1f)
        }
    }

    /** Saves the page now (leaving the reader); the debounced report may not have run yet. */
    fun flush() {
        report(_page.value)
    }

    private fun report(page: Int) {
        val r = request ?: return
        val pages = _pageCount.value
        if (pages <= 0) return
        store.saveLastPage(r.bookId, page)
        viewModelScope.launch(NonCancellable + Dispatchers.IO) { FolioLink.reportPage(getApplication(), r.bookId, page, pages) }
    }

    override fun onCleared() {
        val doc: PageImages? = text ?: djvu
        text = null
        djvu = null
        // Close on the Pdfium thread so a page in flight finishes first.
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch(pdfThread) { doc?.close() }
        val scanDoc = scanText
        scanText = null
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch(scanThread) { scanDoc?.close() }
    }

    private companion object {
        const val TAG = "PdfViewModel"
        const val PREFETCH = 2
    }
}
