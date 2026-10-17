package app.folio.documents.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.folio.documents.BookFont
import app.folio.documents.Mode
import app.folio.documents.PageTheme
import app.folio.documents.PdfViewModel
import app.folio.documents.R
import app.folio.documents.ui.folio.CircleButton
import app.folio.documents.ui.folio.Folio
import app.folio.documents.ui.folio.FolioSheet
import app.folio.documents.ui.folio.FolioType
import app.folio.documents.ui.folio.GroupHeader
import app.folio.documents.ui.folio.GroupedCard
import app.folio.documents.ui.folio.GroupedRow
import app.folio.documents.ui.folio.IosSlider
import app.folio.documents.ui.folio.IosSwitch
import app.folio.documents.ui.folio.SegmentedControl
import app.folio.documents.ui.folio.SheetHeader

/** The PDF's own outline; the entry being read is highlighted. */
@Composable
internal fun ContentsSheet(vm: PdfViewModel, page: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val toc by vm.toc.collectAsStateWithLifecycle()
    val c = Folio.colors
    FolioSheet(onDismiss) {
        SheetHeader(stringResource(R.string.contents), trailing = { CircleButton(Icons.Rounded.Close, stringResource(R.string.close), onDismiss) })
        val entries = toc.orEmpty()
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.toc_empty), style = FolioType.body, color = c.secondaryLabel,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp).navigationBarsPadding(),
            )
            return@FolioSheet
        }
        val current = entries.indexOfLast { it.page <= page }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
        LazyColumn(state = list, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(entries) { i, e ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (i == current) c.selectedRow else c.surface.copy(alpha = 0f))
                        .clickable { onPick(e.page) }
                        .heightIn(min = 48.dp).padding(start = 20.dp + (e.level.coerceAtMost(4) * 16).dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e.title, style = if (e.level == 0) FolioType.headline else FolioType.body, color = c.label,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text("${e.page + 1}", style = FolioType.subheadline, color = c.secondaryLabel, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

/** Search the whole book; a hit opens its page. */
@Composable
internal fun SearchSheet(vm: PdfViewModel, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state by vm.search.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.query) }
    val focus = remember { FocusRequester() }
    val c = Folio.colors
    LaunchedEffect(Unit) { focus.requestFocus() }
    FolioSheet(onDismiss) {
        SheetHeader(stringResource(R.string.search), trailing = { CircleButton(Icons.Rounded.Close, stringResource(R.string.close), onDismiss) })
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CircleShape).background(c.fill).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = c.secondaryLabel, modifier = Modifier.size(20.dp))
            BasicTextField(
                query, { query = it }, singleLine = true,
                textStyle = FolioType.body.copy(color = c.label), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(stringResource(R.string.search_hint), style = FolioType.body, color = c.secondaryLabel)
                    inner()
                },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        val hits = state.hits
        when {
            state.progress < 1f -> Text(
                stringResource(R.string.searching, (state.progress * 100).toInt()), style = FolioType.footnote, color = c.secondaryLabel,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            )
            hits != null && hits.isEmpty() -> Text(
                stringResource(R.string.search_nothing), style = FolioType.body, color = c.secondaryLabel,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
            )
        }
        LazyColumn(Modifier.navigationBarsPadding().padding(top = 8.dp)) {
            itemsIndexed(hits.orEmpty()) { _, hit ->
                Column(Modifier.fillMaxWidth().clickable { onPick(hit.page) }.padding(horizontal = 32.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.search_page, hit.page + 1), style = FolioType.footnote, color = c.secondaryLabel)
                    Text(hit.snippet, style = FolioType.subheadline, color = c.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** «Темы и настройки»: page theme, then the mode's own settings, then keys. Applied at once. */
@Composable
internal fun ThemesSheet(vm: PdfViewModel, onDismiss: () -> Unit) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val hasText by vm.hasText.collectAsStateWithLifecycle()
    val c = Folio.colors
    FolioSheet(onDismiss) {
        SheetHeader(stringResource(R.string.appearance), trailing = { CircleButton(Icons.Rounded.Close, stringResource(R.string.close), onDismiss) })
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            if (hasText == true) {
                SegmentedControl(
                    listOf(stringResource(R.string.mode_pages), stringResource(R.string.mode_book)),
                    selected = prefs.mode.ordinal,
                    onSelect = { i -> vm.update { it.copy(mode = Mode.entries[i]) } },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
                )
            } else if (hasText == false) {
                Text(stringResource(R.string.no_text), style = FolioType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 32.dp))
            }
            GroupHeader(stringResource(R.string.theme))
            ThemeRow(prefs.theme) { t -> vm.update { it.copy(theme = t) } }
            if (prefs.mode == Mode.Pages) {
                GroupHeader(stringResource(R.string.turning))
                SegmentedControl(
                    listOf(stringResource(R.string.turn_pages), stringResource(R.string.turn_scroll)),
                    selected = if (prefs.scroll) 1 else 0,
                    onSelect = { i -> vm.update { it.copy(scroll = i == 1) } },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                GroupedCard {
                    GroupedRow(stringResource(R.string.crop), showDivider = false, trailing = {
                        IosSwitch(prefs.crop, { v -> vm.update { it.copy(crop = v) } })
                    })
                }
            } else {
                GroupHeader(stringResource(R.string.font))
                SegmentedControl(
                    listOf("Literata", "PT Serif", "Source Serif"),
                    selected = prefs.font.ordinal,
                    onSelect = { i -> vm.update { it.copy(font = BookFont.entries[i]) } },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                )
                GroupHeader(stringResource(R.string.text_size))
                IosSlider(
                    prefs.textSize, { v -> vm.update { it.copy(textSize = v) } }, valueRange = 14f..30f,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                GroupHeader(stringResource(R.string.spacing))
                IosSlider(
                    prefs.lineSpacing, { v -> vm.update { it.copy(lineSpacing = v) } }, valueRange = 1.1f..2f,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            GroupedCard {
                GroupedRow(stringResource(R.string.volume_keys), showDivider = false, trailing = {
                    IosSwitch(prefs.volumeKeys, { v -> vm.update { it.copy(volumeKeys = v) } })
                })
            }
            Text(
                stringResource(R.string.about), style = FolioType.footnote, color = c.secondaryLabel,
                modifier = Modifier.padding(start = 34.dp, end = 34.dp, top = 12.dp),
            )
        }
    }
}

/** Four page swatches like Folio's palette cards. */
@Composable
private fun ThemeRow(selected: PageTheme, onSelect: (PageTheme) -> Unit) {
    val c = Folio.colors
    val names = mapOf(
        PageTheme.Original to R.string.theme_original, PageTheme.Paper to R.string.theme_paper,
        PageTheme.Calm to R.string.theme_calm, PageTheme.Night to R.string.theme_night,
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTheme.entries.forEach { t ->
            val chosen = t == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(t.background)
                    .border(if (chosen) 2.dp else 0.5.dp, if (chosen) c.label else c.separator, RoundedCornerShape(14.dp))
                    .clickable { onSelect(t) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Aa", style = FolioType.title3, color = t.text)
                Text(stringResource(names.getValue(t)), style = FolioType.caption, color = t.text.copy(alpha = 0.8f), maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Spacer(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(if (chosen) t.text else t.text.copy(alpha = 0f)),
                )
            }
        }
    }
}
