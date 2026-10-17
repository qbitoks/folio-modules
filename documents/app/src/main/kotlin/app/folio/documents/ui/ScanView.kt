package app.folio.documents.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.folio.documents.R
import app.folio.documents.ReaderPrefs
import app.folio.documents.engine.ScanLayout
import app.folio.documents.ui.folio.FolioType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** k2pdfopt's output resolution: higher costs memory and seconds per page for little gain on a phone. */
private const val MAX_DPI = 240

/**
 * «Скан как книга»: every page image reflowed by k2pdfopt into one column of the screen's width (columns found
 * and put in order, figures and formulas kept as pictures), painted in the theme's text colour.
 */
@Composable
internal fun ScanView(
    pageCount: Int,
    startPage: Int,
    prefs: ReaderPrefs,
    loadPage: suspend (Int, ScanLayout) -> Bitmap?,
    prefetch: (Int, ScanLayout) -> Unit,
    jumps: Flow<Int>,
    turns: Flow<Int>,
    onPage: (Int) -> Unit,
    onTap: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    val pageListener by rememberUpdatedState(onPage)
    val tapListener by rememberUpdatedState(onTap)
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        // Lay out at up to MAX_DPI and let the image scale to the screen: same text size, a fraction of the work.
        val screenDpi = (density.density * 160).roundToInt().coerceAtLeast(1)
        val dpi = minOf(screenDpi, MAX_DPI)
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val layout = remember(widthPx, heightPx, dpi, prefs.scanZoom) {
            ScanLayout((widthPx * dpi / screenDpi).roundToInt(), (heightPx * dpi / screenDpi).roundToInt(), dpi, prefs.scanZoom)
        }
        val prefetcher by rememberUpdatedState(prefetch)
        LaunchedEffect(list, layout) {
            snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect {
                pageListener(it)
                prefetcher(it, layout)
            }
        }
        LaunchedEffect(list) { jumps.collect { list.scrollToItem(it) } }
        LaunchedEffect(list) {
            turns.collect { d -> list.animateScrollBy(d * list.layoutInfo.viewportSize.height * 0.9f) }
        }
        val ink = ColorFilter.tint(prefs.theme.text)
        LazyColumn(
            state = list,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures { tapListener(it.x / size.width.coerceAtLeast(1)) }
            },
            contentPadding = PaddingValues(top = 48.dp, bottom = 96.dp),
        ) {
            items(pageCount, key = { it }) { page ->
                val image by produceState<Result<Bitmap?>?>(null, page, layout) { value = Result.success(loadPage(page, layout)) }
                val loaded = image
                when {
                    loaded == null -> Box(Modifier.fillMaxWidth().fillParentMaxHeight(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = prefs.theme.text.copy(alpha = 0.4f), strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                    loaded.getOrNull() != null -> {
                        val bitmap = loaded.getOrNull()!!
                        val bitmapImage = remember(bitmap) { bitmap.asImageBitmap() }
                        Image(
                            bitmapImage, contentDescription = null, colorFilter = ink, contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> Text(
                        stringResource(R.string.page_no_text), style = FolioType.footnote, color = prefs.theme.text.copy(alpha = 0.45f),
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                    )
                }
                if (page < pageCount - 1) HorizontalDivider(color = prefs.theme.text.copy(alpha = 0.08f))
            }
        }
    }
}
