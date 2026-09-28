package io.github.rastr1sr.radiomonash

import android.content.ComponentName
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import java.io.File
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Seed = Color(0xFF0439D9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        HttpResponseCache.install(File(cacheDir, "http"), 20L shl 20)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LemonScentedTheme {
                Radio()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Radio() {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var on by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf(MediaMetadata.EMPTY) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            val update = {
                on = c.playWhenReady && c.playbackState != Player.STATE_IDLE
                failed = c.playerError != null
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
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            PrimaryTabRow(pager.currentPage, Modifier.statusBarsPadding()) {
                listOf(R.string.player, R.string.schedule).forEachIndexed {
                        i,
                        label,
                    ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(pager, Modifier.padding(padding), beyondViewportPageCount = 1) { page ->
            if (page == 1) {
                Schedule()
            } else {
                PlayerPage(
                    meta,
                    playing = on,
                    failed = failed,
                    enabled = controller != null,
                    onToggle = { controller?.run { if (on) pause() else play() } },
                )
            }
        }
    }
}

@Composable
fun Failed(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
fun SkeletonRows(lead: DpSize, modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.loading)
    Column(
        modifier
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .clearAndSetSemantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        repeat(8) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Skeleton(Modifier.size(lead))
                Skeleton(Modifier.height(16.dp).fillMaxWidth(0.5f + it % 3 * 0.15f))
            }
        }
    }
}

@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    Box(modifier.background(color, MaterialTheme.shapes.small))
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
