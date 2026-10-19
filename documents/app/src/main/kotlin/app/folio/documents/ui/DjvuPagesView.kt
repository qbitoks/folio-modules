package app.folio.documents.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.folio.documents.PageTheme
import app.folio.documents.ReaderPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** Pages render this much sharper than they fit, so a pinch zoom stays readable. */
private const val RENDER_SCALE = 1.5f

/**
 * A DjVu book's own pages: one page per swipe, or a continuous vertical scroll (like [PagesView] for PDF).
 * Pinch zooms, a pan moves the zoomed view. [onTap] gets the tap's horizontal fraction (0 left … 1 right).
 */
@Composable
internal fun DjvuPagesView(
    pageCount: Int,
    startPage: Int,
    prefs: ReaderPrefs,
    loadPage: suspend (page: Int, width: Int, height: Int) -> Bitmap?,
    aspect: (Int) -> Float?,
    jumps: Flow<Int>,
    turns: Flow<Int>,
    onPage: (Int) -> Unit,
    onTap: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val pageListener by rememberUpdatedState(onPage)
    val tapListener by rememberUpdatedState(onTap)
    val filter = remember(prefs.theme) { pageFilter(prefs.theme) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // Two fingers zoom; one finger pans only while zoomed, else the pager or list scrolls.
                        if (event.changes.size > 1 || zoom > 1f) {
                            val next = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                            val max = Offset(size.width * (next - 1) / 2, size.height * (next - 1) / 2)
                            val moved = pan + event.calculatePan()
                            pan = if (next == 1f) Offset.Zero else Offset(moved.x.coerceIn(-max.x, max.x), moved.y.coerceIn(-max.y, max.y))
                            zoom = next
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) { detectTapGestures { tapListener(it.x / size.width.coerceAtLeast(1)) } }
            .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
    ) {
        val density = LocalDensity.current
        val w = with(density) { (maxWidth.toPx() * RENDER_SCALE).roundToInt() }
        val h = with(density) { (maxHeight.toPx() * RENDER_SCALE).roundToInt() }
        if (prefs.scroll) {
            val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
            LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { pageListener(it) } }
            LaunchedEffect(list) { jumps.collect { list.scrollToItem(it) } }
            LaunchedEffect(list) { turns.collect { d -> list.animateScrollToItem((list.firstVisibleItemIndex + d).coerceIn(0, pageCount - 1)) } }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pageCount, key = { it }) { page ->
                    // Width-fitted: the height bound is loose so tall pages keep the screen's width.
                    DjvuPage(page, w, h * 4, aspect(page), filter, loadPage, fillWidth = true)
                }
            }
        } else {
            val pager = rememberPagerState(initialPage = start) { pageCount }
            LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { zoom = 1f; pan = Offset.Zero; pageListener(it) } }
            LaunchedEffect(pager) { jumps.collect { pager.scrollToPage(it) } }
            LaunchedEffect(pager) { turns.collect { d -> pager.animateScrollToPage((pager.currentPage + d).coerceIn(0, pageCount - 1)) } }
            HorizontalPager(pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = zoom == 1f, beyondViewportPageCount = 1) { page ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DjvuPage(page, w, h, aspect(page), filter, loadPage, fillWidth = false)
                }
            }
        }
    }
}

@Composable
private fun DjvuPage(
    page: Int, width: Int, height: Int, aspect: Float?, filter: ColorFilter?,
    loadPage: suspend (Int, Int, Int) -> Bitmap?, fillWidth: Boolean,
) {
    val bitmap by produceState<Bitmap?>(null, page, width, height) { value = loadPage(page, width, height) }
    val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: aspect ?: 0.7f
    // The list fills the width; the pager fits the whole page on screen.
    Box((if (fillWidth) Modifier.fillMaxWidth() else Modifier).aspectRatio(ratio), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b == null) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        } else {
            val image = remember(b) { b.asImageBitmap() }
            Image(image, contentDescription = null, colorFilter = filter, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Night inverts the page; Paper and Calm tint white paper like Folio's reader palettes (as in [PagesView]). */
private fun pageFilter(theme: PageTheme): ColorFilter? = when (theme) {
    PageTheme.Original -> null
    PageTheme.Night -> ColorFilter.colorMatrix(
        ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)),
    )
    PageTheme.Paper, PageTheme.Calm -> ColorFilter.colorMatrix(
        ColorMatrix().apply { setToScale(theme.background.red, theme.background.green, theme.background.blue, 1f) },
    )
}
