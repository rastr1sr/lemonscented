package io.github.rastr1sr.radiomonash

import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

internal const val MINUTE_MS = 60_000L
private const val TIMEOUT_MS = 10_000
private const val CACHED_BITMAPS = 64
private const val HTTP_CACHE_BYTES = 20L shl 20

internal fun radiocult(path: String) =
    fetchText("https://api.radiocult.fm/api/station/radio-monash/$path")

internal fun fetchText(url: String) = URL(url).openConnection().run {
    connectTimeout = TIMEOUT_MS
    readTimeout = TIMEOUT_MS
    getInputStream().bufferedReader().use { it.readText() }
}

internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data object Failed : Load<Nothing>

    data class Ready<T>(val value: T) : Load<T>
}

internal fun <T> poll(every: Long, fetch: suspend () -> T?): Flow<Load<T>> = flow {
    var last: T? = null
    while (true) {
        fetch()?.let { last = it }
        emit(last?.let { Load.Ready(it) } ?: Load.Failed)
        delay(every)
    }
}.flowOn(Dispatchers.IO)

internal fun JSONObject.str(key: String) = if (isNull(key)) {
    null
} else {
    optString(key).ifEmpty {
        null
    }
}

internal fun isHttps(url: String) = url.startsWith("https://")

private val bitmaps = LruCache<String, ImageBitmap>(CACHED_BITMAPS)

internal fun cachedBitmap(url: String): ImageBitmap? = bitmaps[url]

internal fun bitmap(url: String): ImageBitmap? = cachedBitmap(url) ?: logged("Network", "Artwork") {
    URL(url).openStream().use(BitmapFactory::decodeStream)
        ?.asImageBitmap()
        ?.also { bitmaps.put(url, it) }
}

internal fun installCache(dir: File) {
    HttpResponseCache.install(dir, HTTP_CACHE_BYTES)
}

internal fun clearArtwork(dir: File) {
    bitmaps.evictAll()
    checkNotNull(HttpResponseCache.getInstalled()).delete()
    installCache(dir)
}

internal fun sameTrack(icy: String, title: String?) =
    title != null && icy.contains(title, ignoreCase = true)

internal enum class AirMode(val label: Int) {
    Live(R.string.live),
    Playlist(R.string.playlist),
    Off(R.string.off_air),
}

internal fun airMode(live: JSONObject) = when {
    live.str("status") == "offAir" -> AirMode.Off
    live.optJSONObject("content")?.optJSONObject("media")?.str("type") == "live" -> AirMode.Live
    else -> AirMode.Playlist
}

internal fun tipTapText(node: JSONObject?): String? {
    fun walk(n: JSONObject): String {
        val type = n.str("type")
        if (type == "text") return n.optString("text")
        if (type == "hardBreak") return "\n"
        val children = n.optJSONArray("content") ?: return ""
        val separator = if (type == "paragraph") "" else "\n\n"
        return (0 until children.length()).joinToString(separator) {
            walk(children.getJSONObject(it))
        }
    }
    return node?.let(::walk)?.trim()?.ifEmpty { null }
}
