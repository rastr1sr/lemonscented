package io.github.rastr1sr.radiomonash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlayerPage(
    meta: MediaMetadata,
    playing: Boolean,
    failed: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val mode = meta.extras?.getString("mode")?.let(AirMode::valueOf)
        val show = meta.isShow
        val cover = Modifier
            .weight(1f, fill = false)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraLarge)
        Artwork(meta, cover, failed)
        Spacer(Modifier.height(16.dp))
        val artist = meta.artist?.toString()
        SongTitle(meta)
        val station = meta.station?.toString()
        Line(MaterialTheme.typography.bodyLarge) {
            if (artist != null) {
                Text(
                    artist,
                    Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else if (mode != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Line(MaterialTheme.typography.labelLarge) {
            if (artist != null && station != null) {
                Text(
                    station,
                    Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            } else if (show) {
                Equaliser(playing, Modifier.width(160.dp).fillMaxHeight())
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SleepButton()
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(72.dp),
                enabled = enabled,
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play),
                    stringResource(if (playing) R.string.stop else R.string.play),
                    Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            val context = LocalContext.current
            val fav = meta.title?.let {
                Fav(it.toString(), meta.artist?.toString(), meta.artworkUri?.toString(), show)
            }
            val saved = fav != null && isFavourite(fav)
            IconButton({ fav?.let { toggleFavourite(context, it) } }, enabled = fav != null) {
                Icon(painterResource(heart(saved)), stringResource(heartLabel(saved)))
            }
        }
        if (failed) {
            Text(
                stringResource(R.string.stream_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SongTitle(meta: MediaMetadata) {
    val title = meta.title?.toString()
    val artist = meta.artist?.toString()
    var info by remember { mutableStateOf(false) }
    val show = currentShow()?.takeIf { meta.isShow && it.description != null }
    val song = title != null && artist != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (song || show != null) Spacer(Modifier.width(40.dp))
        Text(
            title ?: stringResource(R.string.app_name),
            Modifier.weight(1f, fill = false).basicMarquee(iterations = Int.MAX_VALUE),
            maxLines = 1,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (song || show != null) {
            IconButton({ info = true }, Modifier.size(40.dp)) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    stringResource(if (song) R.string.song_info else R.string.show_info),
                    Modifier.size(20.dp),
                )
            }
        }
    }
    if (!info) return
    if (title != null && artist != null) {
        SongInfo(meta, title, artist) { info = false }
    } else if (show != null) {
        ShowInfo(meta, show) { info = false }
    }
}

@Composable
private fun ShowInfo(meta: MediaMetadata, show: Show, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(meta, Modifier.size(56.dp).clip(MaterialTheme.shapes.small), still = true)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(show.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.live),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        text = {
            Text(show.description.orEmpty(), Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun SongInfo(meta: MediaMetadata, title: String, artist: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val query = Uri.encode("$title $artist")
    val open = { url: String ->
        onDismiss()
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(
                    meta.artworkUri?.toString(),
                    Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                ) {
                    Question()
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    meta.station?.let {
                        Text(
                            it.toString(),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        },
        text = {
            Column {
                TextButton({ open("https://open.spotify.com/search/$query") }) {
                    Text(stringResource(R.string.search_spotify))
                }
                TextButton({ open("https://music.apple.com/search?term=$query") }) {
                    Text(stringResource(R.string.search_apple))
                }
                TextButton({
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(title, "$title - $artist"))
                    onDismiss()
                }) { Text(stringResource(R.string.copy)) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun SleepButton() {
    var picking by remember { mutableStateOf(false) }
    val on = Sleep.until > 0
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
    val end = currentShow()?.end
    AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text(label) },
        text = {
            Column {
                if (on) {
                    val at = Instant.ofEpochMilli(Sleep.until).atZone(ZoneId.systemDefault())
                    Text(
                        stringResource(R.string.stops_at, at.format(time)),
                        Modifier.padding(bottom = 8.dp),
                    )
                }
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    TextButton({
                        Sleep.set(minutes * 60_000L)
                        picking = false
                    }) { Text(pluralStringResource(R.plurals.minutes_count, minutes, minutes)) }
                }
                if (end != null) {
                    TextButton({
                        Sleep.set(end.toEpochMilli() - System.currentTimeMillis())
                        picking = false
                    }) { Text(stringResource(R.string.end_of_show)) }
                }
            }
        },
        confirmButton = {
            if (on) {
                TextButton({
                    Sleep.set(0)
                    picking = false
                }) { Text(stringResource(R.string.turn_off)) }
            }
        },
        dismissButton = {
            TextButton({ picking = false }) { Text(stringResource(R.string.not_now)) }
        },
    )
}

internal fun heart(saved: Boolean) = if (saved) R.drawable.ic_heart_on else R.drawable.ic_heart_off

internal fun heartLabel(saved: Boolean) =
    if (saved) R.string.in_favourites else R.string.add_favourite

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
    var done by remember(safe) { mutableStateOf(safe == null) }
    val art by produceState<ImageBitmap?>(null, safe) {
        value = safe?.let { withContext(Dispatchers.IO) { bitmap(it) } }
        done = true
    }
    val loading = stringResource(R.string.loading)
    val bitmap = art
    when {
        bitmap != null -> Image(bitmap, null, modifier, contentScale = ContentScale.Crop)

        done -> Box(
            modifier.background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { placeholder() }

        else -> Skeleton(modifier.clearAndSetSemantics { contentDescription = loading })
    }
}

@Composable
private fun Line(style: TextStyle, content: @Composable () -> Unit) {
    val height = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(Modifier.height(height), contentAlignment = Alignment.Center) {
        ProvideTextStyle(style, content)
    }
}
