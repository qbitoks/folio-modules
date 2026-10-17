package app.folio.pdf.mj

import android.app.Application
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.folio.pdf.mj.engine.PdfText
import app.folio.pdf.mj.engine.SearchHit
import app.folio.pdf.mj.engine.TocEntry
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

/** A book opened by Folio: page, settings, outline, search and «Как книга» text. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PdfViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PrefsStore(app)
    private val pdfThread = Dispatchers.IO.limitedParallelism(1)
    private var text: PdfText? = null
    private val bookPages = LruCache<Int, CharSequence>(24)

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
        viewModelScope.launch {
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
            if (_hasText.value == false && _prefs.value.mode == Mode.Book) update { it.copy(mode = Mode.Pages) }
        }
        // Folio gets the page a moment after the reader settles on it.
        viewModelScope.launch {
            _page.drop(1).debounce(600).collect { report(it) }
        }
    }

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
        val next = change(_prefs.value)
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
        val doc = text
        text = null
        // Close on the Pdfium thread so a page in flight finishes first.
        @Suppress("OPT_IN_USAGE")
        kotlinx.coroutines.GlobalScope.launch(pdfThread) { doc?.close() }
    }

    private companion object {
        const val TAG = "PdfViewModel"
    }
}
