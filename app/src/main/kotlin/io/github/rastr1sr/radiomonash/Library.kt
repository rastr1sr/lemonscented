package io.github.rastr1sr.radiomonash

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

internal fun List<Fav>.has(fav: Fav) = any { it.key == fav.key }

internal class Library(context: Context) {
    private val favPrefs = context.getSharedPreferences("favourites", Context.MODE_PRIVATE)
    private val listenPrefs = context.getSharedPreferences("listening", Context.MODE_PRIVATE)
    private val playLog = File(context.filesDir, "plays.jsonl")
    private val favs = MutableStateFlow(
        favPrefs.all.values.map { value ->
            val json = JSONObject(value.toString())
            Fav(
                json.getString("title"),
                json.str("artist"),
                json.str("art"),
                json.optBoolean("show"),
                json.getLong("at"),
            )
        }.sortedByDescending { it.at },
    )
    val favourites: StateFlow<List<Fav>> = favs

    fun toggle(fav: Fav) {
        if (favs.value.has(fav)) {
            favs.value = favs.value.filterNot { it.key == fav.key }
            favPrefs.edit { remove(fav.key) }
            return
        }
        favs.value = listOf(fav) + favs.value
        val json = JSONObject()
            .put("title", fav.title)
            .put("artist", fav.artist)
            .put("art", fav.art)
            .put("show", fav.show)
            .put("at", fav.at)
        favPrefs.edit { putString(fav.key, json.toString()) }
    }

    fun addListening(seconds: Long) {
        val day = LocalDate.now().toString()
        listenPrefs.edit { putLong(day, listenPrefs.getLong(day, 0) + seconds) }
    }

    fun clearStats() {
        listenPrefs.edit { clear() }
        playLog.delete()
        Logs.add("Storage", "Listening stats cleared")
    }

    fun logPlay(artist: String?, show: String?) {
        val line = JSONObject().put("artist", artist).put("show", show).toString()
        try {
            playLog.appendText(line + "\n")
        } catch (e: IOException) {
            Logs.add("Storage", "Play log: ${e.message}")
        }
    }

    fun readStats(): Stats {
        val days = listenPrefs.all.map { (day, seconds) ->
            LocalDate.parse(day) to seconds as Long
        }.toMap()
        val plays = if (playLog.exists()) {
            playLog.readLines().map { JSONObject(it).run { Play(str("artist"), str("show")) } }
        } else {
            emptyList()
        }
        return stats(days, plays, LocalDate.now())
    }
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
