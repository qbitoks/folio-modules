package app.folio.documents.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import app.folio.documents.PageTheme
import app.folio.documents.ReaderPrefs
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import kotlinx.coroutines.flow.Flow

/**
 * The PDF's own pages through MJ PDF's PDFView: one page per swipe, or a continuous vertical scroll.
 * Pinch and double-tap zoom are PDFView's. [onTap] gets the tap's horizontal fraction (0 left … 1 right).
 */
@Composable
internal fun PagesView(
    uri: Uri,
    startPage: Int,
    prefs: ReaderPrefs,
    jumps: Flow<Int>,
    turns: Flow<Int>,
    onPage: (page: Int, count: Int) -> Unit,
    onTap: (Float) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val page by rememberUpdatedState(startPage)
    val pageListener by rememberUpdatedState(onPage)
    val tapListener by rememberUpdatedState(onTap)
    val failListener by rememberUpdatedState(onFailed)
    // Layout settings need a reload of the document; colours don't.
    val layout = remember(prefs.scroll, prefs.crop) { prefs.scroll to prefs.crop }
    val ref = remember { arrayOfNulls<PDFView>(1) }
    val live = remember { booleanArrayOf(true) }
    AndroidView(
        factory = { context -> PDFView(context, null).also { ref[0] = it } },
        modifier = modifier,
        update = { v -> v.applyTheme(prefs.theme) },
    )
    LaunchedEffect(uri, layout) {
        val v = ref[0] ?: return@LaunchedEffect
        val (scroll, crop) = layout
        // A reload for new layout settings keeps the page being read.
        val start = if (v.pageCount > 0) v.currentPage else page
        v.fromUri(uri)
            .defaultPage(start)
            .swipeHorizontal(!scroll)
            .pageSnap(!scroll)
            .pageFling(!scroll)
            .autoSpacing(!scroll)
            .spacing(if (scroll) 8 else 0)
            .pageFitPolicy(if (scroll) FitPolicy.WIDTH else FitPolicy.BOTH)
            .fitEachPage(!scroll)
            .cropMargins(crop)
            .enableAnnotationRendering(true)
            .enableAntialiasing(true)
            .nightMode(prefs.theme == PageTheme.Night)
            .onPageChange { p, count -> if (live[0]) pageListener(p, count) }
            .onTap { e -> tapListener(e.x / v.width.coerceAtLeast(1)); true }
            .onError { failListener() }
            .load()
        v.applyTheme(prefs.theme)
    }
    LaunchedEffect(Unit) { jumps.collect { p -> ref[0]?.jumpTo(p, false) } }
    LaunchedEffect(Unit) {
        turns.collect { d ->
            val v = ref[0] ?: return@collect
            v.jumpTo((v.currentPage + d).coerceIn(0, (v.pageCount - 1).coerceAtLeast(0)), true)
        }
    }
    DisposableEffect(Unit) { onDispose { live[0] = false; ref[0]?.recycle() } }
}

/** Night inverts the page; Paper and Calm tint white paper like Folio's reader palettes. */
private fun PDFView.applyTheme(theme: PageTheme) {
    setBackgroundColor(theme.background.toArgb())
    setNightMode(theme == PageTheme.Night)
    val filter = when (theme) {
        PageTheme.Original, PageTheme.Night -> null
        PageTheme.Paper, PageTheme.Calm -> {
            val bg = theme.background
            ColorMatrixColorFilter(ColorMatrix().apply { setScale(bg.red, bg.green, bg.blue, 1f) })
        }
    }
    setPageColorFilter(filter)
}
