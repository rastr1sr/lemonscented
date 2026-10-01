package io.github.rastr1sr.radiomonash

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.painterResource
import com.materialkolor.hct.Hct
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tap ripples. Each tap is a ring wave: the tile is sampled along its slope (fake
 * refraction) and shaded as if lit from the top left.
 *
 * Ring height at distance r, t seconds after the tap (only the sine is differentiated):
 *   h = A * exp(-k * (r - v * t)^2) * (1 - t / 2) * sin(60 * (r - v * t))
 *
 * Distances are in tile units (pixels / short side). Drops are float4(x, y, age, unused),
 * with x and y in pixels.
 * Needs Android 13.
 *
 * References:
 * - https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl
 * - https://developer.android.com/reference/android/graphics/RenderEffect#createRuntimeShaderEffect(android.graphics.RuntimeShader,%20java.lang.String)
 * - https://github.com/JumpingKeyCaps/DynamicVisualEffectsAGSL
 * - https://tympanus.net/codrops/2025/10/25/dissecting-a-wavy-shader-sine-refraction-and-serendipity/
 */
private const val DROPS = """
uniform shader content;
uniform float2 size;
uniform float4 drops[4];

half4 main(float2 p) {
    float s = min(size.x, size.y);
    float2 u = p / s;
    float2 slope = float2(0.0);
    for (int i = 0; i < 4; i++) {
        float age = drops[i].z;
        if (age > 0.0 && age < 2.0) {
            float2 d = u - drops[i].xy / s;
            float r = length(d) + 0.001;
            float x = r - age * 0.4;
            float a = 0.0012 * exp(-x * x * 150.0) * (1.0 - age * 0.5);
            slope += d / r * a * 60.0 * cos(x * 60.0);
        }
    }
    // clamped so the edges never sample transparent pixels
    half4 color = content.eval(clamp(p + slope * s * 0.5, float2(1.0), size - 1.0));
    float shade = dot(slope, float2(-0.7, -0.7)) * 2.5;
    return half4(clamp(color.rgb + shade, 0.0, 1.0), color.a);
}
"""

@Composable
internal fun Lemon(show: Boolean, modifier: Modifier = Modifier, still: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val slice = @Composable { tint: Color ->
        Icon(
            painterResource(R.drawable.ic_launcher_foreground),
            null,
            Modifier.fillMaxSize().scale(1.5f),
            tint = tint,
        )
    }
    val dark = scheme.surface.luminance() < 0.5f
    val colors = remember(dark) {
        val tones = if (dark) listOf(20.0, 40.0, 30.0) else listOf(90.0, 75.0, 85.0)
        listOf(270.0, 255.0, 285.0).zip(tones) { hue, tone ->
            Color(Hct.from(hue, 56.0, tone).toInt())
        }
    }
    val tint = remember(dark) { Color(Hct.from(270.0, 48.0, if (dark) 90.0 else 10.0).toInt()) }
    if (!show) {
        Box(modifier.background(colors[0])) { slice(tint) }
        return
    }
    val ripples = if (still) null else rememberRipples()
    val shader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !still
        ) {
            RuntimeShader(DROPS)
        } else {
            null
        }
    }
    val blobs = remember(still) {
        val random = if (still) Random(0) else Random
        List(4) { FloatArray(4) { random.nextFloat() } }
    }
    val layer = when {
        ripples == null -> modifier

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null ->
            modifier.ripples(ripples).drops(ripples, shader)

        else -> modifier.ripples(ripples)
    }
    Box(
        layer.drawBehind {
            val time = ripples?.time ?: 0f
            drawRect(colors[0])
            blobs.forEachIndexed { i, b ->
                val x = sin(time * (0.15f + b[0] * 0.25f) + b[2] * 7f)
                val y = cos(time * (0.15f + b[1] * 0.25f) + b[3] * 7f)
                val center = Offset(size.width * (0.5f + 0.5f * x), size.height * (0.5f + 0.5f * y))
                val radius = size.maxDimension * 0.8f
                val brush = Brush.radialGradient(
                    listOf(colors[i % 2 + 1], Color.Transparent),
                    center,
                    radius,
                )
                drawCircle(brush, radius, center)
            }
        },
    ) { slice(tint) }
}

/** [time] only advances while visible. */
private class Ripples {
    var time by mutableFloatStateOf(0f)
    var visible by mutableStateOf(false)
    var taps by mutableStateOf(listOf<Pair<Offset, Float>>())

    fun tap(at: Offset) {
        taps = (taps + (at to time)).takeLast(4)
    }
}

@Composable
private fun rememberRipples(): Ripples {
    val ripples = remember { Ripples() }
    LaunchedEffect(ripples.visible) {
        if (!ripples.visible) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos {
                ripples.time += (it - last) / 1e9f
                last = it
            }
        }
    }
    return ripples
}

private fun Modifier.ripples(ripples: Ripples) = onVisibilityChanged { ripples.visible = it }

/**
 * Uniforms are set in the layer block, so frames redraw without recomposing. The
 * RenderEffect is rebuilt each frame because it doesn't pick up uniform changes.
 */
@RequiresApi(33)
private fun Modifier.drops(ripples: Ripples, shader: RuntimeShader): Modifier {
    val drops = FloatArray(16)
    return pointerInput(ripples) { detectTapGestures(onTap = ripples::tap) }.graphicsLayer {
        ripples.taps.forEachIndexed { i, (at, start) ->
            drops[i * 4] = at.x
            drops[i * 4 + 1] = at.y
            drops[i * 4 + 2] = ripples.time - start
        }
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("drops", drops)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }
}
