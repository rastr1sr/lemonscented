package io.github.rastr1sr.radiomonash

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.time.LocalDate
import org.json.JSONException
import org.json.JSONObject

internal class Fav(
    val title: String,
    val artist: String?,
    val art: String?,
    val show: Boolean = false,
    val at: Long = System.currentTimeMillis(),
) {
    val key get() = "${artist.orEmpty()}|$title"
}

internal class Play(val artist: String?, val show: String?)

internal class Stats(
    val weekSeconds: Long,
    val streak: Int,
    val topArtists: List<String>,
    val topShows: List<String>,
)

internal val favourites = mutableStateListOf<Fav>()

private fun favPrefs(context: Context) =
    context.getSharedPreferences("favourites", Context.MODE_PRIVATE)

private fun listenPrefs(context: Context) =
    context.getSharedPreferences("listening", Context.MODE_PRIVATE)

private fun playLog(context: Context) = File(context.filesDir, "plays.jsonl")

fun loadFavourites(context: Context) {
    if (favourites.isNotEmpty()) return
    favourites += favPrefs(context).all.values.mapNotNull { value ->
        try {
            val json = JSONObject(value.toString())
            Fav(
                json.getString("title"),
                json.str("artist"),
                json.str("art"),
                json.optBoolean("show"),
                json.getLong("at"),
            )
        } catch (e: JSONException) {
            null
        }
    }.sortedByDescending { it.at }
}

internal fun isFavourite(fav: Fav) = favourites.any { it.key == fav.key }

internal fun toggleFavourite(context: Context, fav: Fav) {
    if (isFavourite(fav)) {
        favourites.removeAll { it.key == fav.key }
        favPrefs(context).edit { remove(fav.key) }
        return
    }
    favourites.add(0, fav)
    val json = JSONObject()
        .put("title", fav.title)
        .put("artist", fav.artist)
        .put("art", fav.art)
        .put("show", fav.show)
        .put("at", fav.at)
    favPrefs(context).edit { putString(fav.key, json.toString()) }
}

fun addListening(context: Context, seconds: Long) {
    val day = LocalDate.now().toString()
    listenPrefs(context).edit { putLong(day, listenPrefs(context).getLong(day, 0) + seconds) }
}

fun logPlay(context: Context, artist: String?, show: String?) {
    val line = JSONObject().put("artist", artist).put("show", show).toString()
    try {
        playLog(context).appendText(line + "\n")
    } catch (e: IOException) {
        return
    }
}

internal fun readStats(context: Context): Stats {
    val days = listenPrefs(context).all.mapNotNull { (day, seconds) ->
        (seconds as? Long)?.let { LocalDate.parse(day) to it }
    }.toMap()
    val plays = try {
        playLog(context).readLines().mapNotNull {
            try {
                JSONObject(it).run { Play(str("artist"), str("show")) }
            } catch (e: JSONException) {
                null
            }
        }
    } catch (e: IOException) {
        emptyList()
    }
    return stats(days, plays, LocalDate.now())
}

internal fun stats(days: Map<LocalDate, Long>, plays: List<Play>, today: LocalDate): Stats {
    val week = (0L..6L).sumOf { days[today.minusDays(it)] ?: 0L }
    var day = if ((days[today] ?: 0L) > 0) today else today.minusDays(1)
    var streak = 0
    while ((days[day] ?: 0L) > 0) {
        streak++
        day = day.minusDays(1)
    }
    fun top(names: List<String?>, n: Int) = names.filterNotNull().groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.take(n).map { it.key }
    return Stats(week, streak, top(plays.map { it.artist }, 5), top(plays.map { it.show }, 3))
}
