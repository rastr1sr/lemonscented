package io.github.rastr1sr.radiomonash

import java.net.URL
import org.json.JSONObject

fun radiocult(path: String): JSONObject {
    val url = URL("https://api.radiocult.fm/api/station/radio-monash/$path")
    return url.openConnection().run {
        connectTimeout = 10_000
        readTimeout = 10_000
        JSONObject(getInputStream().bufferedReader().use { it.readText() })
    }
}

fun JSONObject.str(key: String) = optString(key).ifEmpty { null }

fun sameTrack(icy: String, title: String?) = title != null && icy.contains(title, ignoreCase = true)
