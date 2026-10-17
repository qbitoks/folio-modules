package app.folio.pdf.mj.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.folio.pdf.mj.Mode
import app.folio.pdf.mj.PdfViewModel
import app.folio.pdf.mj.R
import app.folio.pdf.mj.ui.folio.CircleButton
import app.folio.pdf.mj.ui.folio.CircleButtonStyle
import app.folio.pdf.mj.ui.folio.Folio
import app.folio.pdf.mj.ui.folio.FolioTheme
import app.folio.pdf.mj.ui.folio.FolioType
import app.folio.pdf.mj.ui.folio.IosSlider
import app.folio.pdf.mj.ui.folio.folioColors
import kotlin.math.roundToInt

private enum class Sheet { Contents, Search, Themes }

/**
 * The reader, laid out like Folio's: tap the middle for the toolbars (title and ×, then the contents row,
 * page slider and actions), tap the edges to turn pages. The controls take the page's colours.
 */
@Composable
fun ReaderScreen(vm: PdfViewModel, onClose: () -> Unit) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val theme = prefs.theme
    val base = folioColors(theme.dark)
    FolioTheme(base.copy(background = theme.background, surface = if (theme.dark) base.surface else theme.background)) {
        ReaderContent(vm, onClose)
    }
}

@Composable
private fun ReaderContent(vm: PdfViewModel, onClose: () -> Unit) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val pageCount by vm.pageCount.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val request = vm.request ?: return
    var chrome by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    ImmersiveBars(visible = chrome, darkPage = prefs.theme.dark)
    BackHandler(enabled = chrome) { chrome = false }
    // Edges turn pages, the middle shows the controls (Folio's tap zones).
    val onTap: (Float) -> Unit = { x ->
        when {
            chrome -> chrome = false
            x < 0.25f -> vm.turn(-1)
            x > 0.75f -> vm.turn(1)
            else -> chrome = true
        }
    }
    val modeStart = remember(prefs.mode, pageCount) { page }
    Box(Modifier.fillMaxSize().background(prefs.theme.background)) {
        when {
            failed -> Text(
                stringResource(R.string.open_failed), style = FolioType.body, color = Folio.colors.label,
                textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            prefs.mode == Mode.Book && pageCount > 0 -> BookView(
                pageCount = pageCount, startPage = modeStart, prefs = prefs, loadPage = vm::bookPage,
                jumps = vm.jumps, turns = vm.turns, onPage = vm::onPage, onTap = onTap, modifier = Modifier.fillMaxSize(),
            )
            prefs.mode == Mode.Pages -> PagesView(
                uri = request.uri, startPage = modeStart, prefs = prefs, jumps = vm.jumps, turns = vm.turns,
                onPage = { p, _ -> vm.onPage(p) }, onTap = onTap, onFailed = { }, modifier = Modifier.fillMaxSize(),
            )
        }
        AnimatedVisibility(chrome, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            TopBar(request.title, onClose)
        }
        AnimatedVisibility(
            chrome, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it },
        ) {
            BottomPanel(vm, page, pageCount, onSheet = { sheet = it })
        }
    }
    when (sheet) {
        Sheet.Contents -> ContentsSheet(vm, page, onDismiss = { sheet = null }, onPick = { vm.jumpTo(it); sheet = null; chrome = false })
        Sheet.Search -> SearchSheet(vm, onDismiss = { sheet = null }, onPick = { vm.jumpTo(it); sheet = null; chrome = false })
        Sheet.Themes -> ThemesSheet(vm, onDismiss = { sheet = null })
        null -> Unit
    }
}

@Composable
private fun TopBar(title: String, onClose: () -> Unit) {
    val c = Folio.colors
    Row(
        Modifier.fillMaxWidth().background(c.background)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(WindowInsets.statusBars.asPaddingValues()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title, style = FolioType.headline, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        CircleButton(Icons.Rounded.Close, stringResource(R.string.close), onClose, style = CircleButtonStyle.Light, size = 48.dp)
    }
}

@Composable
private fun BottomPanel(vm: PdfViewModel, page: Int, pageCount: Int, onSheet: (Sheet) -> Unit) {
    val c = Folio.colors
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val toc by vm.toc.collectAsStateWithLifecycle()
    val hasText by vm.hasText.collectAsStateWithLifecycle()
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag?.roundToInt() ?: page
    val chapter = toc?.lastOrNull { it.page <= shown }?.title
    Column(
        Modifier.fillMaxWidth().background(c.background)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(WindowInsets.navigationBars.asPaddingValues()),
    ) {
        HorizontalDivider(color = c.label.copy(alpha = 0.12f), thickness = 0.5.dp)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
                    .clickable(role = Role.Button) { onSheet(Sheet.Contents) }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.List, stringResource(R.string.contents), tint = c.label, modifier = Modifier.size(24.dp))
                Text(
                    chapter ?: stringResource(R.string.contents), style = FolioType.headline, color = c.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (pageCount > 0) {
                    Text(stringResource(R.string.page_of, shown + 1, pageCount), style = FolioType.footnote, color = c.secondaryLabel)
                }
            }
            if (pageCount > 1) {
                IosSlider(
                    value = drag ?: page.toFloat(),
                    onValueChange = { drag = it },
                    onValueChangeFinished = {
                        drag?.let { vm.jumpTo(it.roundToInt()) }
                        drag = null
                    },
                    onValueChangeCancelled = { drag = null },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 8.dp)) {
            Action(Icons.Rounded.TextFields, stringResource(R.string.appearance), Modifier.weight(1f)) { onSheet(Sheet.Themes) }
            Action(
                Icons.Rounded.AutoStories, stringResource(R.string.mode_book), Modifier.weight(1f),
                active = prefs.mode == Mode.Book, enabled = hasText == true,
            ) { vm.update { it.copy(mode = if (it.mode == Mode.Book) Mode.Pages else Mode.Book) } }
            Action(Icons.Rounded.Search, stringResource(R.string.search), Modifier.weight(1f), enabled = hasText == true) { onSheet(Sheet.Search) }
        }
    }
}

@Composable
private fun Action(
    icon: ImageVector, description: String, modifier: Modifier,
    active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit,
) {
    val c = Folio.colors
    Box(
        modifier.heightIn(min = 52.dp).clip(CircleShape)
            .background(if (active) c.label.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) c.label else c.secondaryLabel.copy(alpha = 0.45f), modifier = Modifier.size(26.dp))
    }
}
