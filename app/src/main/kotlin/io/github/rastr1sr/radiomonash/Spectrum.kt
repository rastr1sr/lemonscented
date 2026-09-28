package io.github.rastr1sr.radiomonash

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val SIZE = 1024
private const val BANDS = 16
private const val OUTPUT_LAG_MS = 300L

@UnstableApi
internal object Spectrum : TeeAudioProcessor.AudioBufferSink {
    private val queue = ArrayDeque<Pair<Long, FloatArray>>()
    private var current = FloatArray(BANDS)
    private var rate = 0
    private var start = 0L
    private var frames = 0L
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private val samples = FloatArray(SIZE)
    private var filled = 0

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        filled = 0
        frames = 0
        start = 0
        synchronized(queue) { queue.clear() }
    }

    fun levels(): FloatArray = synchronized(queue) {
        val due = SystemClock.uptimeMillis() - OUTPUT_LAG_MS
        while ((queue.firstOrNull()?.first ?: Long.MAX_VALUE) <= due) {
            current = queue.removeFirst().second
        }
        current
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (encoding != C.ENCODING_PCM_16BIT) return
        if (start == 0L) start = SystemClock.uptimeMillis()
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frame = 2 * channels
        var i = data.position()
        while (i + frame <= data.limit()) {
            var sum = 0
            for (c in 0 until channels) sum += data.getShort(i + 2 * c)
            samples[filled++] = sum / (channels * 32_768f)
            frames++
            if (filled == SIZE) {
                analyse()
                filled = 0
            }
            i += frame
        }
    }

    private fun analyse() {
        val chunk = SIZE / BANDS
        val levels = FloatArray(BANDS) { b ->
            val power = (b * chunk until (b + 1) * chunk).sumOf { samples[it].toDouble().pow(2) }
            ((20 * log10(sqrt(power / chunk) + 1e-6) + 45) / 40).toFloat().coerceIn(0f, 1f)
        }
        synchronized(queue) {
            queue.addLast(start + frames * 1000 / rate to levels)
            if (queue.size > 64) queue.removeFirst()
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun Equaliser(active: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    var shown by remember { mutableStateOf(FloatArray(BANDS)) }
    LaunchedEffect(active) {
        while (active || shown.any { it > 0.005f }) {
            withFrameNanos {
                val target = Spectrum.levels()
                shown = FloatArray(BANDS) { i ->
                    val goal = if (active) target[i] else 0f
                    shown[i] + (goal - shown[i]) * if (goal > shown[i]) 0.45f else 0.1f
                }
            }
        }
    }
    Canvas(modifier) {
        val points = BANDS * 2 + 1
        val mid = size.height / 2
        val path = Path()
        var previous = Offset(0f, mid)
        path.moveTo(previous.x, previous.y)
        for (p in 1 until points) {
            val band = abs(p - BANDS).coerceAtMost(BANDS - 1)
            val x = size.width * p / (points - 1)
            val taper = sin(PI * p / (points - 1)).toFloat()
            val sign = if (p % 2 == 0) 1 else -1
            val point = Offset(x, mid + sign * shown[band] * taper * mid)
            path.quadraticTo(
                previous.x,
                previous.y,
                (previous.x + point.x) / 2,
                (previous.y + point.y) / 2,
            )
            previous = point
        }
        path.lineTo(size.width, mid)
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
