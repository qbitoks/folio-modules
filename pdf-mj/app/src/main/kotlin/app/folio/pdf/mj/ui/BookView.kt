package app.folio.pdf.mj.ui

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.folio.pdf.mj.BookFont
import app.folio.pdf.mj.R
import app.folio.pdf.mj.ReaderPrefs
import app.folio.pdf.mj.ui.folio.FolioType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * «Как книга»: every PDF page's text laid out as paragraphs and headings (MJ PDF's structured text mode),
 * one continuous column in the chosen font, size and spacing. A thin rule marks where a PDF page starts.
 */
@Composable
internal fun BookView(
    pageCount: Int,
    startPage: Int,
    prefs: ReaderPrefs,
    loadPage: suspend (Int) -> CharSequence?,
    jumps: Flow<Int>,
    turns: Flow<Int>,
    onPage: (Int) -> Unit,
    onTap: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    val pageListener by rememberUpdatedState(onPage)
    val tapListener by rememberUpdatedState(onTap)
    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { pageListener(it) } }
    LaunchedEffect(list) { jumps.collect { list.scrollToItem(it) } }
    LaunchedEffect(list) {
        turns.collect { d -> list.animateScrollBy(d * list.layoutInfo.viewportSize.height * 0.9f) }
    }
    val family = bookFamily(prefs.font)
    val style = TextStyle(
        fontFamily = family,
        fontSize = prefs.textSize.sp,
        lineHeight = (prefs.textSize * prefs.lineSpacing).sp,
        color = prefs.theme.text,
        textAlign = TextAlign.Justify,
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
    )
    LazyColumn(
        state = list,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { tapListener(it.x / size.width.coerceAtLeast(1)) }
        },
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 72.dp, bottom = 120.dp),
    ) {
        items(pageCount, key = { it }) { page ->
            val text by produceState<AnnotatedString?>(null, page) { value = loadPage(page)?.toAnnotated() }
            val t = text
            // Until its text loads a page takes a whole screen: a short list would clamp the start page to 0.
            Box(if (t == null) Modifier.fillMaxWidth().fillParentMaxHeight() else Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (t != null && t.isNotBlank()) {
                    Text(t, style = style, modifier = Modifier.padding(vertical = 8.dp))
                } else if (t != null) {
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.page_no_text),
                        style = FolioType.footnote, color = prefs.theme.text.copy(alpha = 0.45f),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            if (page < pageCount - 1) HorizontalDivider(color = prefs.theme.text.copy(alpha = 0.08f))
        }
    }
}

private fun bookFamily(font: BookFont): FontFamily = when (font) {
    BookFont.Literata -> FontFamily(
        Font(R.font.literata_regular_400, FontWeight.Normal), Font(R.font.literata_regular_700, FontWeight.Bold),
        Font(R.font.literata_italic_400, FontWeight.Normal, FontStyle.Italic), Font(R.font.literata_italic_700, FontWeight.Bold, FontStyle.Italic),
    )
    BookFont.PtSerif -> FontFamily(
        Font(R.font.pt_serif_regular_400, FontWeight.Normal), Font(R.font.pt_serif_regular_700, FontWeight.Bold),
        Font(R.font.pt_serif_italic_400, FontWeight.Normal, FontStyle.Italic), Font(R.font.pt_serif_italic_700, FontWeight.Bold, FontStyle.Italic),
    )
    BookFont.SourceSerif -> FontFamily(
        Font(R.font.source_serif_regular_400, FontWeight.Normal), Font(R.font.source_serif_regular_700, FontWeight.Bold),
        Font(R.font.source_serif_italic_400, FontWeight.Normal, FontStyle.Italic),
    )
}

/** MJ PDF's spans (heading sizes, bold/italic, super/subscript) as Compose styles; other spans are dropped. */
private fun CharSequence.toAnnotated(): AnnotatedString {
    val source = this
    return buildAnnotatedString {
        append(source.toString())
        if (source !is Spanned) return@buildAnnotatedString
        for (span in source.getSpans(0, source.length, Any::class.java)) {
            val start = source.getSpanStart(span)
            val end = source.getSpanEnd(span)
            val style = when (span) {
                is RelativeSizeSpan -> SpanStyle(fontSize = span.sizeChange.em, fontWeight = FontWeight.Bold)
                is StyleSpan -> when (span.style) {
                    Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    else -> null
                }
                is SuperscriptSpan -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.7.em)
                is SubscriptSpan -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.7.em)
                else -> null
            }
            if (style != null && start in 0..end) addStyle(style, start, end)
        }
    }
}
