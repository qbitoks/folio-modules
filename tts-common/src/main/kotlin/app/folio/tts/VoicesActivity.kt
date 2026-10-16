package app.folio.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/** What an engine app offers: its downloadable packs and the voices they make available. */
interface EngineCatalog {
    val packs: List<VoicePack>

    /** Voices whose packs are installed in [store]. */
    fun voices(store: VoiceStore): List<EngineVoice>

    /** A short phrase to try a voice of [locale] with. */
    fun sample(locale: Locale): String = when (locale.language) {
        "ru" -> "Здравствуйте! Так звучит этот голос."
        "uk" -> "Добрий день! Так звучить цей голос."
        else -> "Hello! This is how this voice sounds."
    }
}

/**
 * The engine app's only screen, also opened by Android and Folio for «install voice data»: download and
 * delete voice packs, pick the default voice per language, listen to a voice.
 */
abstract class VoicesActivity : ComponentActivity() {
    protected abstract val catalog: EngineCatalog
    protected abstract val about: Int

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = VoiceStore.get(this, catalog.packs)
        val defaults = VoiceDefaults(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)) {
                VoicesScreen(store, defaults)
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun listen(voice: EngineVoice) {
        val ready = tts
        if (ready != null) {
            speak(ready, voice)
            return
        }
        var created: TextToSpeech? = null
        created = TextToSpeech(this, { status -> if (status == TextToSpeech.SUCCESS) created?.let { speak(it, voice) } }, packageName)
        tts = created
    }

    private fun speak(t: TextToSpeech, voice: EngineVoice) {
        t.voices?.firstOrNull { it.name == voice.name }?.let { t.voice = it }
        t.speak(catalog.sample(voice.locale), TextToSpeech.QUEUE_FLUSH, null, "sample")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VoicesScreen(store: VoiceStore, defaults: VoiceDefaults) {
        val status by store.status.collectAsStateWithLifecycle()
        var picks by remember { mutableIntStateOf(0) }
        val voices = remember(status) { catalog.voices(store) }
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.voices_title, title)) }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                val byLanguage = voices.groupBy { it.locale.language }
                if (byLanguage.isNotEmpty()) {
                    item { Header(stringResource(R.string.voices_default)) }
                    for ((language, list) in byLanguage) {
                        val chosen = picks.let { defaults.get(language) } ?: list.first().name
                        items(list) { voice ->
                            ListItem(
                                headlineContent = { Text(voice.title) },
                                supportingContent = { Text(voice.locale.getDisplayName(Locale.getDefault())) },
                                leadingContent = {
                                    RadioButton(selected = voice.name == chosen, onClick = {
                                        defaults.set(language, voice.name)
                                        picks++
                                    })
                                },
                                trailingContent = {
                                    IconButton(onClick = { listen(voice) }) {
                                        Icon(Icons.Rounded.PlayArrow, stringResource(R.string.voices_listen))
                                    }
                                },
                            )
                        }
                    }
                }
                for ((group, packs) in catalog.packs.groupBy { it.group }) {
                    item { Header(group) }
                    items(packs) { pack -> PackRow(store, pack, status[pack.id] ?: VoiceStatus.Missing) }
                }
                item { Text(stringResource(about), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
            }
        }
    }

    @Composable
    private fun PackRow(store: VoiceStore, pack: VoicePack, status: VoiceStatus) {
        val size = Formatter.formatShortFileSize(this, pack.bytes)
        ListItem(
            headlineContent = { Text(pack.title) },
            supportingContent = {
                when (status) {
                    VoiceStatus.Missing -> Text(size)
                    VoiceStatus.Installed -> Text(stringResource(R.string.voices_installed))
                    VoiceStatus.Failed -> Text(stringResource(R.string.voices_failed), color = MaterialTheme.colorScheme.error)
                    is VoiceStatus.Downloading ->
                        if (status.fraction == null) LinearProgressIndicator(Modifier.padding(top = 8.dp))
                        else LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    when (status) {
                        VoiceStatus.Installed -> TextButton(onClick = { store.delete(pack) }) { Text(stringResource(R.string.voices_delete)) }
                        is VoiceStatus.Downloading -> TextButton(onClick = { store.cancel(pack.id) }) { Text(stringResource(R.string.voices_cancel)) }
                        else -> TextButton(onClick = { store.download(pack) }) { Text(stringResource(R.string.voices_download)) }
                    }
                }
            },
        )
    }

    @Composable
    private fun Header(text: String) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
    }
}
