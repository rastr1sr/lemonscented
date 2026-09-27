package io.github.rastr1sr.radiomonash

import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.materialkolor.rememberDynamicColorScheme

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
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(meta.title?.toString() ?: stringResource(R.string.app_name))
            meta.artist?.let { Text(it.toString()) }
            meta.station?.let { Text(it.toString()) }
            Button(
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
                enabled = controller != null,
            ) {
                Text(stringResource(if (on) R.string.stop else R.string.play))
            }
        }
    }
}

@Composable
private fun LemonScentedTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        rememberDynamicColorScheme(seedColor = Seed, isDark = dark)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
