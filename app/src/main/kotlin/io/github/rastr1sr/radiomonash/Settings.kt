package io.github.rastr1sr.radiomonash

import android.content.Context
import android.content.Intent
import android.net.http.HttpResponseCache
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val STREAM = "https://radio-monash.radiocult.fm/stream"
private const val SOURCE = "https://github.com/rastr1sr/lemonscented"
private const val LICENCE = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val LAWN =
    "https://www.monash.edu/about/our-locations/clayton-campus/gardens-at-clayton/lemon-scented-lawn"

internal enum class Theme(val label: Int) {
    System(R.string.theme_system),
    Light(R.string.theme_light),
    Dark(R.string.theme_dark),
}

internal enum class Buffer(val label: Int, val ms: Int) {
    Fast(R.string.buffer_fast, 500),
    Balanced(R.string.buffer_balanced, 2_500),
    Stable(R.string.buffer_stable, 5_000),
}

internal data class Look(
    val theme: Theme = Theme.System,
    val black: Boolean = true,
    val dynamic: Boolean = true,
    val animated: Boolean = true,
    val equaliser: Boolean = true,
    val buffer: Buffer = Buffer.Fast,
    val noisy: Boolean = true,
    val stream: String? = null,
)

internal class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("look", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val look: StateFlow<Look> = state

    fun set(look: Look) {
        prefs.edit {
            putString("theme", look.theme.name)
            putBoolean("black", look.black)
            putBoolean("dynamic", look.dynamic)
            putBoolean("animated", look.animated)
            putBoolean("equaliser", look.equaliser)
            putString("buffer", look.buffer.name)
            putBoolean("noisy", look.noisy)
            putString("stream", look.stream)
        }
        state.value = look
    }

    private fun read() = prefs.run {
        Look(
            Theme.entries.find { it.name == getString("theme", null) } ?: Theme.System,
            getBoolean("black", true),
            getBoolean("dynamic", true),
            getBoolean("animated", true),
            getBoolean("equaliser", true),
            Buffer.entries.find { it.name == getString("buffer", null) } ?: Buffer.Fast,
            getBoolean("noisy", true),
            getString("stream", null),
        )
    }
}

internal class SettingsViewModel(
    private val preferences: Preferences,
    private val library: Library,
    private val artwork: File,
) : ViewModel() {
    val look: StateFlow<Look> = preferences.look
    private val size = MutableStateFlow(cacheSize())
    val cacheSize: StateFlow<Long> = size

    fun set(look: Look) = preferences.set(look)

    fun clearCache() {
        clearArtwork(artwork)
        size.value = cacheSize()
    }

    fun clearStats() = library.clearStats()

    private fun cacheSize() = checkNotNull(HttpResponseCache.getInstalled()).size()
}

private enum class Ask { Theme, Buffer, Stream, Stats }

private typealias SettingRow = @Composable (ListItemShapes) -> Unit

private fun LazyListScope.group(title: Int, rows: List<SettingRow>) {
    item { Header(stringResource(title)) }
    rows.forEachIndexed { i, row ->
        item { row(ListItemDefaults.segmentedShapes(i, rows.size)) }
    }
}

@Composable
private fun Action(
    shapes: ListItemShapes,
    title: String,
    summary: String?,
    onClick: (() -> Unit)? = null,
) {
    val supporting: (@Composable () -> Unit)? = summary?.let { { Text(it) } }
    if (onClick == null) {
        SegmentedListItem(shapes = shapes, colors = segmented, supportingContent = supporting) {
            Text(title)
        }
    } else {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            colors = segmented,
            supportingContent = supporting,
        ) { Text(title) }
    }
}

@Composable
private fun Toggle(
    shapes: ListItemShapes,
    title: Int,
    summary: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    SegmentedListItem(
        checked,
        onChange,
        shapes = shapes.flat(),
        colors = ListItemDefaults.segmentedColors(
            containerColor = scheme.surfaceContainerHigh,
            selectedContainerColor = scheme.surfaceContainerHigh,
            selectedContentColor = scheme.onSurface,
            selectedSupportingContentColor = scheme.onSurfaceVariant,
        ),
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked, null) },
    ) { Text(stringResource(title)) }
}

@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onLogs: () -> Unit,
    model: SettingsViewModel = viewModel {
        SettingsViewModel(radio().preferences, radio().library, radio().artwork)
    },
) {
    val context = LocalContext.current
    val look by model.look.collectAsStateWithLifecycle()
    val cacheSize by model.cacheSize.collectAsStateWithLifecycle()
    val set = model::set
    val uri = LocalUriHandler.current
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    var asking by remember { mutableStateOf<Ask?>(null) }
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val theme = stringResource(look.theme.label)
    val buffer = stringResource(R.string.buffer_current, stringResource(look.buffer.label))
    val stream = look.stream ?: stringResource(R.string.stream_default)
    val size = Formatter.formatShortFileSize(context, cacheSize)
    val used = stringResource(R.string.cache_used, size)
    val appName = stringResource(R.string.app_name)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            Modifier
                .padding(
                    start = padding.calculateStartPadding(direction),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction),
                )
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .wrapContentWidth()
                .widthIn(max = Spacing.content),
            contentPadding = PaddingValues(
                margin,
                0.dp,
                margin,
                Spacing.md + padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            val appearance = listOfNotNull<SettingRow>(
                { Action(it, stringResource(R.string.theme), theme) { asking = Ask.Theme } },
                {
                    Toggle(it, R.string.pure_black, R.string.pure_black_summary, look.black) { on ->
                        set(look.copy(black = on))
                    }
                },
                if (dynamicAvailable) {
                    {
                        Toggle(
                            it,
                            R.string.dynamic_colour,
                            R.string.dynamic_colour_summary,
                            look.dynamic,
                        ) { on -> set(look.copy(dynamic = on)) }
                    }
                } else {
                    null
                },
                {
                    Toggle(
                        it,
                        R.string.animated_artwork,
                        R.string.animated_artwork_summary,
                        look.animated,
                    ) { on -> set(look.copy(animated = on)) }
                },
                {
                    Toggle(
                        it,
                        R.string.equaliser,
                        R.string.equaliser_summary,
                        look.equaliser,
                    ) { on -> set(look.copy(equaliser = on)) }
                },
            )
            group(R.string.appearance, appearance)
            group(
                R.string.playback,
                listOf(
                    {
                        Action(it, stringResource(R.string.start_buffer), buffer) {
                            asking = Ask.Buffer
                        }
                    },
                    {
                        Toggle(
                            it,
                            R.string.headphones,
                            R.string.headphones_summary,
                            look.noisy,
                        ) { on -> set(look.copy(noisy = on)) }
                    },
                ),
            )
            group(
                R.string.notifications,
                listOf(
                    {
                        Action(
                            it,
                            stringResource(R.string.notifications),
                            stringResource(R.string.notifications_summary),
                        ) {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    },
                ),
            )
            group(
                R.string.storage,
                listOf(
                    {
                        Action(it, stringResource(R.string.artwork_cache), used) {
                            model.clearCache()
                        }
                    },
                    {
                        Action(
                            it,
                            stringResource(R.string.listening_stats),
                            stringResource(R.string.listening_stats_summary),
                        ) { asking = Ask.Stats }
                    },
                ),
            )
            group(
                R.string.advanced,
                listOf(
                    {
                        Action(it, stringResource(R.string.stream_address), stream) {
                            asking = Ask.Stream
                        }
                    },
                    {
                        Action(
                            it,
                            stringResource(R.string.logs),
                            stringResource(R.string.logs_summary),
                            onLogs,
                        )
                    },
                ),
            )
            group(
                R.string.about,
                listOf(
                    { Action(it, appName, stringResource(R.string.about_summary)) },
                    {
                        Action(
                            it,
                            stringResource(R.string.name_origin),
                            stringResource(R.string.name_origin_summary),
                        ) { uri.openUri(LAWN) }
                    },
                    { Action(it, stringResource(R.string.version), version) },
                    {
                        Action(
                            it,
                            stringResource(R.string.source_code),
                            SOURCE.removePrefix("https://"),
                        ) { uri.openUri(SOURCE) }
                    },
                    {
                        Action(
                            it,
                            stringResource(R.string.licence),
                            stringResource(R.string.licence_name),
                        ) { uri.openUri(LICENCE) }
                    },
                ),
            )
        }
    }
    val close = { asking = null }
    when (asking) {
        Ask.Theme -> Choice(
            R.string.theme,
            Theme.entries.map { it.label },
            Theme.entries.indexOf(look.theme),
            { set(look.copy(theme = Theme.entries[it])) },
            close,
        )

        Ask.Buffer -> Choice(
            R.string.start_buffer,
            Buffer.entries.map { it.label },
            Buffer.entries.indexOf(look.buffer),
            { set(look.copy(buffer = Buffer.entries[it])) },
            close,
        )

        Ask.Stream -> StreamDialog(look.stream, { set(look.copy(stream = it)) }, close)

        Ask.Stats -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.listening_stats)) },
            text = { Text(stringResource(R.string.clear_stats_question)) },
            confirmButton = {
                TextButton({
                    model.clearStats()
                    close()
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton(close) { Text(stringResource(R.string.cancel)) } },
        )

        null -> Unit
    }
}

@Composable
private fun Choice(
    title: Int,
    labels: List<Int>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                labels.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .selectable(
                                selected = i == selected,
                                onClick = {
                                    onPick(i)
                                    onDismiss()
                                },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(i == selected, null)
                        Spacer(Modifier.width(Spacing.md))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun StreamDialog(current: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTextFieldState(current ?: STREAM)
    val text = state.text.toString().trim()
    val valid = isHttps(text)
    val save = {
        onSave(text.takeUnless { it == STREAM })
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stream_address)) },
        text = {
            OutlinedTextField(
                state,
                Modifier.fillMaxWidth(),
                isError = !valid,
                trailingIcon = if (text != STREAM) {
                    {
                        IconButton({ state.setTextAndPlaceCursorAtEnd(STREAM) }) {
                            Icon(
                                painterResource(R.drawable.ic_reset),
                                stringResource(R.string.reset),
                            )
                        }
                    }
                } else {
                    null
                },
                supportingText = { Text(stringResource(R.string.stream_invalid)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { if (valid) save() },
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        },
        confirmButton = {
            TextButton(save, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
