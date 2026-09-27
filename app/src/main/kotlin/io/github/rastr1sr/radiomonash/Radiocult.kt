package io.github.rastr1sr.radiomonash

import java.net.URL
import org.json.JSONObject

fun radiocult(path: String): String {
    val url = URL("https://api.radiocult.fm/api/station/radio-monash/$path")
    return url.openConnection().run {
        connectTimeout = 10_000
        readTimeout = 10_000
        getInputStream().bufferedReader().use { it.readText() }
    }
}

fun JSONObject.str(key: String) = optString(key).ifEmpty { null }

fun isHttps(url: String) = url.startsWith("https://")

fun sameTrack(icy: String, title: String?) = title != null && icy.contains(title, ignoreCase = true)

fun airMode(live: JSONObject) = when {
    live.str("status") == "offAir" -> "off"
    live.optJSONObject("content")?.optJSONObject("media")?.str("type") == "live" -> "live"
    else -> "playlist"
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
