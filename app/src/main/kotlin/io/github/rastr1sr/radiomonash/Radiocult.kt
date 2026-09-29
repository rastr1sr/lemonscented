package io.github.rastr1sr.radiomonash

import android.content.Context
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

fun radiocult(path: String): String {
    val url = URL("https://api.radiocult.fm/api/station/radio-monash/$path")
    return url.openConnection().run {
        connectTimeout = 10_000
        readTimeout = 10_000
        getInputStream().bufferedReader().use { it.readText() }
    }
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

fun JSONObject.str(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }

fun isHttps(url: String) = url.startsWith("https://")

private val bitmaps = LruCache<String, ImageBitmap>(64)

fun cachedBitmap(url: String): ImageBitmap? = bitmaps[url]

fun bitmap(url: String): ImageBitmap? = cachedBitmap(url) ?: try {
    URL(url).openStream().use(BitmapFactory::decodeStream)
        ?.asImageBitmap()
        ?.also { bitmaps.put(url, it) }
} catch (e: IOException) {
    null
}

fun installCache(context: Context) {
    HttpResponseCache.install(File(context.cacheDir, "http"), 20L shl 20)
}

fun clearArtwork(context: Context) {
    bitmaps.evictAll()
    HttpResponseCache.getInstalled()?.delete()
    installCache(context)
}

fun sameTrack(icy: String, title: String?) = title != null && icy.contains(title, ignoreCase = true)

enum class AirMode(val label: Int) {
    Live(R.string.live),
    Playlist(R.string.playlist),
    Off(R.string.off_air),
}

fun airMode(live: JSONObject) = when {
    live.str("status") == "offAir" -> AirMode.Off
    live.optJSONObject("content")?.optJSONObject("media")?.str("type") == "live" -> AirMode.Live
    else -> AirMode.Playlist
}

fun tipTapText(node: JSONObject?): String? {
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
