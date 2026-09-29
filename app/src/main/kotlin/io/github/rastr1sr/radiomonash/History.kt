package io.github.rastr1sr.radiomonash

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import org.json.JSONException
import org.json.JSONObject

internal class Played(val title: String, val artist: String?, val at: Instant, val art: String?)

internal fun parseHistory(text: String): List<Played> {
    val list = JSONObject(text).getJSONArray("data")
    return (0 until list.length()).map(list::getJSONObject).map {
        Played(
            it.getString("title"),
            it.str("artist"),
            Instant.parse(it.getString("playoutStart")),
            it.optJSONObject("artwork")?.str("128x128")?.takeIf(::isHttps),
        )
    }
}

internal object Recent {
    private val songs = MutableStateFlow<List<Played>?>(null)
    val list: StateFlow<List<Played>?> = songs

    fun load(): List<Played>? = try {
        parseHistory(radiocult("streaming/history/latest-results?limit=20"))
    } catch (e: IOException) {
        Logs.add("Network", "Recent: ${e.message}")
        null
    } catch (e: JSONException) {
        Logs.add("Network", "Recent: ${e.message}")
        null
    } catch (e: DateTimeParseException) {
        Logs.add("Network", "Recent: ${e.message}")
        null
    }?.also { songs.value = it } ?: songs.value
}

class HistoryViewModel : ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val refreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = refreshing

    internal val songs: StateFlow<Load<List<Played>>> = attempts.flatMapLatest {
        poll(60_000) { Recent.load().also { refreshing.value = false } }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        Recent.list.value?.let { Load.Ready(it) } ?: Load.Loading,
    )

    fun refresh() {
        refreshing.value = true
        attempts.value++
    }
}

@Composable
fun History(modifier: Modifier = Modifier, model: HistoryViewModel = viewModel()) {
    val context = LocalContext.current
    val load by model.songs.collectAsStateWithLifecycle()
    val refreshing by model.isRefreshing.collectAsStateWithLifecycle()
    val favs by favourites.collectAsStateWithLifecycle()
    HistoryContent(load, refreshing, favs, model::refresh, {
        toggleFavourite(context, it)
    }, modifier)
}

@Composable
internal fun HistoryContent(
    load: Load<List<Played>>,
    refreshing: Boolean,
    favs: List<Fav>,
    onRefresh: () -> Unit,
    onFavourite: (Fav) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val time = timeFormat()
    val songs = when (val state = load) {
        Load.Loading -> return Loading(modifier)

        Load.Failed -> return Failed(
            stringResource(R.string.history_failed),
            onRefresh,
            modifier,
        )

        is Load.Ready -> state.value
    }
    Refreshable(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        LazyColumn(
            Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 600.dp),
            contentPadding = PaddingValues(Spacing.md, Spacing.xs, Spacing.md, Spacing.md),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(songs, key = { _, song -> song.at }) { i, song ->
                val fav = Fav(song.title, song.artist, song.art)
                val saved = favs.has(fav)
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(i, songs.size),
                    colors = segmented,
                    leadingContent = {
                        Cover(song.art, Modifier.size(56.dp).clip(MaterialTheme.shapes.small)) {
                            Question()
                        }
                    },
                    trailingContent = {
                        IconToggleButton(saved, { onFavourite(fav) }) {
                            Icon(painterResource(heart(saved)), stringResource(R.string.favourite))
                        }
                    },
                    overlineContent = { Text(song.at.atZone(zone).format(time)) },
                    supportingContent = song.artist?.let {
                        { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                ) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Preview
@Composable
private fun HistoryPreview() {
    val now = Instant.now()
    val songs = listOf(
        Played("Always The Same", "Waliens", now, null),
        Played("Summer Forgive Me", "British India", now.minusSeconds(240), null),
        Played("kalika", "lithu", now.minusSeconds(480), null),
    )
    LemonScentedTheme { HistoryContent(Load.Ready(songs), false, emptyList(), {}, {}) }
}
