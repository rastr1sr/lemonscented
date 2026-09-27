package io.github.rastr1sr.radiomonash

import android.content.ComponentName
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Seed = Color(0xFF0439D9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LemonScentedTheme {
                Radio()
            }
        }
    }
}

@Composable
private fun Radio() {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var on by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf(MediaMetadata.EMPTY) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            val update = {
                on = c.playWhenReady && c.playbackState != Player.STATE_IDLE
                meta = c.mediaMetadata
            }
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = update()
            })
            update()
            controller = c
        }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }
    val art by produceState<ImageBitmap?>(null, meta.artworkUri) {
        value = meta.artworkUri?.let { uri ->
            withContext(Dispatchers.IO) {
                try {
                    val url = URL(uri.toString())
                    url.openStream().use(BitmapFactory::decodeStream)?.asImageBitmap()
                } catch (e: IOException) {
                    null
                }
            }
        }
    }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val cover = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraLarge)
            art?.let { Image(it, null, cover, contentScale = ContentScale.Crop) } ?: Box(
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
            meta.station?.let {
                Text(
                    it.toString(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(16.dp))
            FilledIconButton(
                onClick = {
                    controller?.run {
                        if (on) {
                            stop()
                        } else {
                            prepare()
                            play()
                        }
                    }
                },
                modifier = Modifier.size(72.dp),
                enabled = controller != null,
            ) {
                Icon(
                    painterResource(if (on) R.drawable.ic_stop else R.drawable.ic_play),
                    stringResource(if (on) R.string.stop else R.string.play),
                    Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun LemonScentedTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) {
            dynamicDarkColorScheme(context).copy(background = Color.Black, surface = Color.Black)
        } else {
            dynamicLightColorScheme(context)
        }
    } else {
        rememberDynamicColorScheme(
            seedColor = Seed,
            isDark = dark,
            isAmoled = dark,
            style = PaletteStyle.Fidelity,
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
