package io.github.rastr1sr.radiomonash

import java.net.URLEncoder
import org.json.JSONException
import org.json.JSONObject

internal class Platform(val name: String, val icon: Int, val url: String, val exact: Boolean)

private class Service(val key: String, val name: String, val icon: Int, val search: String?)

private val services = listOf(
    Service("spotify", "Spotify", R.drawable.ic_spotify, "https://open.spotify.com/search/"),
    Service(
        "appleMusic",
        "Apple Music",
        R.drawable.ic_applemusic,
        "https://music.apple.com/au/search?term=",
    ),
    Service(
        "youtubeMusic",
        "YouTube Music",
        R.drawable.ic_youtubemusic,
        "https://music.youtube.com/search?q=",
    ),
    Service("deezer", "Deezer", R.drawable.ic_deezer, "https://www.deezer.com/search/"),
    Service("tidal", "TIDAL", R.drawable.ic_tidal, "https://tidal.com/search?q="),
    Service("amazonMusic", "Amazon Music", R.drawable.ic_amazonmusic, null),
    Service(
        "soundcloud",
        "SoundCloud",
        R.drawable.ic_soundcloud,
        "https://soundcloud.com/search?q=",
    ),
    Service("bandcamp", "Bandcamp", R.drawable.ic_bandcamp, "https://bandcamp.com/search?q="),
)

private const val ITUNES = "https://itunes.apple.com/search?entity=song&limit=10&country=AU"

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

private fun String.base() = substringBefore(" - ").substringBefore(" (").trim()

internal fun appleTrack(json: String, title: String, artist: String): Long? {
    val results = JSONObject(json).getJSONArray("results")
    return (0 until results.length()).map(results::getJSONObject).firstOrNull {
        it.getString("artistName").contains(artist, ignoreCase = true) &&
            it.getString("trackName").base().equals(title.base(), ignoreCase = true)
    }?.getLong("trackId")
}

internal fun listenLinks(html: String): Map<String, String> {
    val data = html.substringAfter("id=\"__NEXT_DATA__\"", "").substringAfter(">")
        .substringBefore("</script>")
    val sections = JSONObject(data).getJSONObject("props").getJSONObject("pageProps")
        .getJSONObject("pageData").getJSONArray("sections")
    val listen = (0 until sections.length()).map(sections::getJSONObject)
        .firstOrNull { it.optString("sectionId").endsWith("|listen") }
        ?: throw JSONException("No listen links")
    val links = listen.getJSONArray("links")
    return (0 until links.length()).map(links::getJSONObject)
        .mapNotNull { link ->
            link.str("url")?.takeIf(::isHttps)?.let { link.getString("platform") to it }
        }
        .toMap()
}

internal fun mergePlatforms(
    query: String,
    exact: Map<String, String>,
    complete: Boolean,
    spotifyApp: Boolean,
): List<Platform> = services.mapNotNull { service ->
    val url = exact[service.key]
    val search = service.search?.takeUnless { complete }
        ?.takeIf { service.key != "spotify" || spotifyApp }
        ?.plus(encode(query))
    (url ?: search)?.let { Platform(service.name, service.icon, it, exact = url != null) }
}

internal fun platforms(title: String, artist: String?, spotifyApp: Boolean): List<Platform> {
    val query = listOfNotNull(title, artist).joinToString(" ")
    val id = artist?.let {
        logged("Links", "iTunes") {
            val json = fetchText("$ITUNES&term=${encode(query)}")
            appleTrack(json, title, artist)
        }
    }
    val page = id?.let {
        logged("Links", "song.link") { listenLinks(fetchText("https://song.link/au/i/$it")) }
    }
        .orEmpty()
    return mergePlatforms(query, page, complete = page.isNotEmpty(), spotifyApp)
}
