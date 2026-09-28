package io.github.rastr1sr.radiomonash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
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
        val show = mode == AirMode.Live && meta.artist == null
        val cover = Modifier
            .weight(1f, fill = false)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraLarge)
        val question = @Composable {
            Icon(
                painterResource(R.drawable.ic_question),
                null,
                Modifier.fillMaxSize(0.33f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        when {
            failed -> Cover(null, cover, question)
            show || meta.title == null -> Lemon(show, cover)
            else -> Cover(meta.artworkUri?.toString(), cover, question)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            meta.title?.toString() ?: stringResource(R.string.app_name),
            Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            maxLines = 1,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        val artist = meta.artist?.toString()
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
            Spacer(Modifier.width(56.dp))
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

internal fun heart(saved: Boolean) = if (saved) R.drawable.ic_heart_on else R.drawable.ic_heart_off

internal fun heartLabel(saved: Boolean) =
    if (saved) R.string.in_favourites else R.string.add_favourite

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
