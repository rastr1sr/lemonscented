package io.github.rastr1sr.radiomonash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.window.core.layout.WindowSizeClass
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerState(
    val meta: MediaMetadata = MediaMetadata.EMPTY,
    val playing: Boolean = false,
    val failed: Boolean = false,
    val ready: Boolean = false,
    val buffering: Boolean = false,
)

internal class Playback(context: Context) {
    private val state = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = state
    private var controller: MediaController? = null
    private val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    init {
        future.addListener({
            val c = future.get()
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = update(c)
            })
            controller = c
            update(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun update(c: MediaController) {
        state.value = PlayerState(
            c.mediaMetadata,
            playing = c.playWhenReady && c.playbackState != Player.STATE_IDLE,
            failed = c.playerError != null,
            ready = true,
            buffering = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING,
        )
    }

    fun toggle() {
        controller?.run { if (state.value.playing) pause() else play() }
    }

    fun release() = MediaController.releaseFuture(future)
}

internal class PlayerViewModel(
    private val playback: Playback,
    private val library: Library,
    shows: Shows,
    preferences: Preferences,
) : ViewModel() {
    val player: StateFlow<PlayerState> = playback.player
    val favourite: StateFlow<Boolean> = combine(player, library.favourites) { s, list ->
        s.meta.fav()?.let(list::has) == true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val sleepUntil: StateFlow<Long> = Sleep.until
    val schedule: StateFlow<List<Show>?> = shows.list
    val look: StateFlow<Look> = preferences.look

    fun toggle() = playback.toggle()

    fun favourite() {
        player.value.meta.fav()?.let(library::toggle)
    }

    fun sleep(ms: Long) = Sleep.set(ms)

    override fun onCleared() = playback.release()
}

@Composable
internal fun PlayerPage(
    state: PlayerState,
    saved: Boolean,
    sleepUntil: Long,
    shows: List<Show>?,
    look: Look,
    onToggle: () -> Unit,
    onFavourite: () -> Unit,
    onSleep: (Long) -> Unit,
    onChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = state.meta
    val failed = state.failed
    val still = !look.animated
    val details = @Composable {
        Details(
            state,
            saved,
            sleepUntil,
            shows,
            look.equaliser,
            onToggle,
            onFavourite,
            onSleep,
            onChat,
        )
    }
    val window = currentWindowAdaptiveInfoV2().windowSizeClass
    val wide = !window.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND) ||
        window.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    Box(modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        val cover = Modifier.clip(MaterialTheme.shapes.extraLarge)
        if (wide) {
            Row(
                Modifier.fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    meta,
                    Modifier.fillMaxHeight().aspectRatio(1f, true).then(cover),
                    failed,
                    still,
                )
                Box(Modifier.weight(1f, fill = false).widthIn(max = 480.dp)) { details() }
            }
        } else {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(
                    meta,
                    Modifier.weight(1f, fill = false).aspectRatio(1f).then(cover),
                    failed,
                    still,
                )
                details()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Details(
    state: PlayerState,
    saved: Boolean,
    sleepUntil: Long,
    shows: List<Show>?,
    equaliser: Boolean,
    onToggle: () -> Unit,
    onFavourite: () -> Unit,
    onSleep: (Long) -> Unit,
    onChat: () -> Unit,
) {
    val meta = state.meta
    val playing = state.playing
    val failed = state.failed
    val enabled = state.ready
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val mode = meta.extras?.getString("mode")?.let(AirMode::valueOf)
        val show = meta.isShow
        val artist = meta.artist?.toString()
        SongTitle(meta, shows?.current()?.takeIf { meta.isShow && it.description != null })
        val station = meta.station?.toString()
        Spacer(Modifier.height(Spacing.xxs))
        Line(MaterialTheme.typography.bodyLarge) {
            if (artist != null) {
                Text(
                    artist,
                    Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else if (mode != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(mode.label),
                        color = if (show) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (station != null && station != meta.title?.toString()) {
                        Text(station, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.xxs))
        Line(MaterialTheme.typography.labelLarge) {
            if (failed) {
                Text(
                    stringResource(R.string.stream_failed),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            } else if (artist != null && station != null) {
                Text(
                    station,
                    Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            } else if (show && equaliser) {
                Equaliser(playing, Modifier.width(160.dp).fillMaxHeight())
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SleepButton(sleepUntil, shows?.current()?.end, onSleep)
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(72.dp),
                enabled = enabled,
            ) {
                val label = stringResource(if (playing) R.string.stop else R.string.play)
                if (state.buffering) {
                    LoadingIndicator(
                        Modifier.size(48.dp).semantics { contentDescription = label },
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play),
                        label,
                        Modifier.size(32.dp),
                    )
                }
            }
            IconToggleButton(saved, { onFavourite() }, enabled = meta.title != null) {
                Icon(painterResource(heart(saved)), stringResource(R.string.favourite))
            }
        }
        Spacer(Modifier.height(Spacing.md))
        FilledTonalButton(onChat) {
            Icon(painterResource(R.drawable.ic_chat), null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.chat))
        }
    }
}

@Composable
private fun SongTitle(meta: MediaMetadata, show: Show?) {
    val title = meta.title?.toString()
    val artist = meta.artist?.toString()
    var info by remember { mutableStateOf(false) }
    val song = title != null && artist != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (song || show != null) Spacer(Modifier.width(Spacing.xxl))
        Text(
            title ?: stringResource(R.string.app_name),
            Modifier.weight(1f, fill = false).basicMarquee(iterations = Int.MAX_VALUE),
            maxLines = 1,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (song || show != null) {
            IconButton({ info = true }) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    stringResource(if (song) R.string.song_info else R.string.show_info),
                )
            }
        }
    }
    if (!info) return
    Sheet({ info = false }) { close ->
        if (title != null && artist != null) {
            SongInfo(meta, title, artist, close)
        } else if (show != null) {
            ShowInfo(meta, show)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(onDismiss: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    val state = rememberBottomSheetState(SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismiss, sheetState = state) {
        content(close)
    }
}

@Composable
private fun ShowInfo(meta: MediaMetadata, show: Show) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.md)) {
        ListItem(
            overlineContent = {
                Text(stringResource(R.string.live), color = MaterialTheme.colorScheme.error)
            },
            leadingContent = {
                Artwork(meta, Modifier.size(56.dp).clip(MaterialTheme.shapes.small), still = true)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) { Text(show.title) }
        Text(
            show.description.orEmpty(),
            Modifier.padding(horizontal = Spacing.md),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SongInfo(meta: MediaMetadata, title: String, artist: String, close: () -> Unit) {
    val context = LocalContext.current
    val query = Uri.encode("$title $artist")
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    val open = { url: String ->
        close()
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
    Column(Modifier.padding(bottom = Spacing.md)) {
        ListItem(
            supportingContent = { Text(artist) },
            overlineContent = meta.station?.let { { Text(it.toString()) } },
            leadingContent = {
                Cover(
                    meta.artworkUri?.toString(),
                    Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                ) {
                    Question()
                }
            },
            colors = clear,
        ) { Text(title) }
        HorizontalDivider()
        ListItem({ open("https://open.spotify.com/search/$query") }, colors = clear) {
            Text(stringResource(R.string.search_spotify))
        }
        ListItem({ open("https://music.apple.com/search?term=$query") }, colors = clear) {
            Text(stringResource(R.string.search_apple))
        }
        ListItem(
            {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(title, "$title - $artist"))
                close()
            },
            colors = clear,
        ) { Text(stringResource(R.string.copy)) }
    }
}

@Composable
private fun SleepButton(until: Long, end: Instant?, onSleep: (Long) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val on = until > 0
    val label = stringResource(R.string.sleep_timer)
    val colors = if (on) {
        IconButtonDefaults.filledTonalIconButtonColors()
    } else {
        IconButtonDefaults.iconButtonColors()
    }
    IconButton({
        picking = true
    }, colors = colors) { Icon(painterResource(R.drawable.ic_timer), label) }
    if (!picking) return
    val time = timeFormat()
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    Sheet({ picking = false }) { close ->
        val choose = { ms: Long ->
            onSleep(ms)
            close()
        }
        Column(Modifier.padding(bottom = Spacing.md)) {
            ListItem(
                supportingContent = if (on) {
                    {
                        val at = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault())
                        Text(stringResource(R.string.stops_at, at.format(time)))
                    }
                } else {
                    null
                },
                colors = clear,
            ) { Text(label, style = MaterialTheme.typography.titleLarge) }
            listOf(15, 30, 45, 60, 90).forEach { minutes ->
                ListItem({ choose(minutes * 60_000L) }, colors = clear) {
                    Text(pluralStringResource(R.plurals.minutes_count, minutes, minutes))
                }
            }
            if (end != null) {
                ListItem(
                    { choose(end.toEpochMilli() - System.currentTimeMillis()) },
                    colors = clear,
                ) { Text(stringResource(R.string.end_of_show)) }
            }
            if (on) {
                HorizontalDivider()
                ListItem({ choose(0) }, colors = clear) {
                    Text(stringResource(R.string.turn_off))
                }
            }
        }
    }
}

internal fun heart(saved: Boolean) = if (saved) R.drawable.ic_heart_on else R.drawable.ic_heart_off

internal fun MediaMetadata.fav() = title?.let {
    Fav(it.toString(), artist?.toString(), artworkUri?.toString(), isShow)
}

internal val MediaMetadata.isShow
    get() = extras?.getString("mode") == AirMode.Live.name && artist == null

@Composable
internal fun Artwork(
    meta: MediaMetadata,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    still: Boolean = false,
) {
    when {
        failed -> Cover(null, modifier) { Question() }
        meta.isShow || meta.title == null -> Lemon(meta.isShow, modifier, still)
        else -> Cover(meta.artworkUri?.toString(), modifier) { Question() }
    }
}

@Composable
internal fun Question() {
    Icon(
        painterResource(R.drawable.ic_question),
        null,
        Modifier.fillMaxSize(0.33f),
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
internal fun Cover(
    url: String?,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = {},
) {
    val safe = url?.takeIf(::isHttps)
    val cached = safe?.let(::cachedBitmap)
    var done by remember(safe) { mutableStateOf(safe == null || cached != null) }
    val art by produceState(cached, safe) {
        if (value == null) value = safe?.let { withContext(Dispatchers.IO) { bitmap(it) } }
        done = true
    }
    val bitmap = art
    if (bitmap != null) {
        Image(bitmap, null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier.background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (done) placeholder()
        }
    }
}

@Composable
private fun Line(style: TextStyle, content: @Composable () -> Unit) {
    val height = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(Modifier.height(height), contentAlignment = Alignment.Center) {
        ProvideTextStyle(style, content)
    }
}

@Preview
@Composable
private fun PlayerPreview() {
    val meta = MediaMetadata.Builder()
        .setTitle("Always The Same")
        .setArtist("Waliens")
        .setStation("Melbourne Music Scene")
        .build()
    val state = PlayerState(meta, playing = true, ready = true)
    LemonScentedTheme { PlayerPage(state, false, 0, null, Look(), {}, {}, {}, {}) }
}
