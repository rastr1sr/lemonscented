package io.github.rastr1sr.radiomonash

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import java.io.IOException
import java.net.URL
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
    var art by remember { mutableStateOf<ImageBitmap?>(null) }
    var artLoading by remember { mutableStateOf(false) }
    LaunchedEffect(meta.artworkUri) {
        art = null
        val uri = meta.artworkUri?.toString()?.takeIf(::isHttps) ?: return@LaunchedEffect
        artLoading = true
        art = withContext(Dispatchers.IO) {
            try {
                URL(uri).openStream().use(BitmapFactory::decodeStream)?.asImageBitmap()
            } catch (e: IOException) {
                null
            }
        }
        artLoading = false
    }
    val loading = stringResource(R.string.loading)
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val cover = Modifier
            .weight(1f, fill = false)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraLarge)
        val bitmap = art
        when {
            artLoading -> Skeleton(cover.clearAndSetSemantics { contentDescription = loading })

            bitmap != null -> Image(bitmap, null, cover, contentScale = ContentScale.Crop)

            else -> Box(
                cover.background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_question),
                    null,
                    Modifier.fillMaxSize(0.33f),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            meta.title?.toString() ?: stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        meta.artist?.let {
            Text(
                it.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            meta.extras?.getString("mode")?.let(AirMode::valueOf)?.let {
                Text(
                    stringResource(it.label),
                    color = if (it == AirMode.Live) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            meta.station?.let {
                Text(
                    it.toString(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        FilledIconButton(onClick = onToggle, modifier = Modifier.size(72.dp), enabled = enabled) {
            Icon(
                painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play),
                stringResource(if (playing) R.string.stop else R.string.play),
                Modifier.size(32.dp),
            )
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
