package io.github.rastr1sr.radiomonash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal class Played(val title: String, val artist: String?, val at: Instant, val art: String?)

internal fun parseHistory(text: String): List<Played>? = try {
    val list = JSONObject(text).getJSONArray("data")
    (0 until list.length()).map(list::getJSONObject).map {
        Played(
            it.getString("title"),
            it.str("artist"),
            Instant.parse(it.getString("playoutStart")),
            it.optJSONObject("artwork")?.str("128x128")?.takeIf(::isHttps),
        )
    }
} catch (e: JSONException) {
    null
} catch (e: DateTimeParseException) {
    null
}

private fun history(): List<Played>? = try {
    parseHistory(radiocult("streaming/history/latest-results?limit=20"))
} catch (e: IOException) {
    null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun History(modifier: Modifier = Modifier) {
    var attempt by remember { mutableIntStateOf(0) }
    var played by remember { mutableStateOf<List<Played>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        while (true) {
            val fresh = withContext(Dispatchers.IO) { history() }
            if (fresh != null) played = fresh
            failed = played == null
            refreshing = false
            delay(60_000)
        }
    }
    val zone = ZoneId.systemDefault()
    val time = timeFormat()
    if (failed) {
        Failed(stringResource(R.string.history_failed), { attempt++ }, modifier)
        return
    }
    val songs = played ?: return SkeletonRows(DpSize(48.dp, 48.dp), modifier)
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            attempt++
        },
        modifier = modifier,
    ) {
        LazyColumn(Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 600.dp)) {
            items(songs, key = { it.at.toString() + it.title }) { song ->
                ListItem(
                    headlineContent = {
                        Text(
                            song.title,
                            Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                            maxLines = 1,
                        )
                    },
                    supportingContent = song.artist?.let { { Text(it, maxLines = 1) } },
                    leadingContent = {
                        Cover(song.art, Modifier.size(48.dp).clip(MaterialTheme.shapes.small))
                    },
                    trailingContent = { Text(song.at.atZone(zone).format(time)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
