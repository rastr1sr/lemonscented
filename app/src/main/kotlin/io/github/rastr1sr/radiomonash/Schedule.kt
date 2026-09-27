package io.github.rastr1sr.radiomonash

import android.text.format.DateFormat
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal class Show(
    val id: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val live: Boolean,
    val description: String?,
)

private var cache: List<Show>? = null
private var cachedAt = Instant.EPOCH

private fun schedule(zone: ZoneId, saved: File): List<Show>? = try {
    val from = LocalDate.now(zone).atStartOfDay(zone).toInstant()
    val to = from.plus(7, ChronoUnit.DAYS)
    val json = radiocult("schedule?startDate=$from&endDate=$to&timezone=${zone.id}")
    saved.writeText(json.toString())
    parseSchedule(json)
} catch (e: IOException) {
    null
} catch (e: JSONException) {
    null
}

private fun saved(file: File): List<Show>? = try {
    parseSchedule(JSONObject(file.readText()))
} catch (e: IOException) {
    null
} catch (e: JSONException) {
    null
}

internal fun parseSchedule(json: JSONObject): List<Show> {
    val list = json.getJSONArray("schedules")
    return (0 until list.length()).map(list::getJSONObject).map {
        Show(
            it.getString("id"),
            it.getString("title"),
            Instant.parse(it.getString("start")),
            Instant.parse(it.getString("end")),
            it.optJSONObject("media")?.str("type") == "live",
            tipTapText(it.optJSONObject("description")),
        )
    }.sortedBy { it.start }
}

@Composable
fun Schedule(modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    var attempt by remember { mutableIntStateOf(0) }
    var shows by remember { mutableStateOf(cache) }
    var failed by remember { mutableStateOf(false) }
    val file = File(LocalContext.current.filesDir, "schedule.json")
    LaunchedEffect(attempt) {
        if (shows == null) shows = withContext(Dispatchers.IO) { saved(file) }
        if (shows != null && Instant.now() < cachedAt.plusSeconds(600)) return@LaunchedEffect
        failed = false
        val fresh = withContext(Dispatchers.IO) { schedule(zone, file) }
        if (fresh != null) {
            cache = fresh
            cachedAt = Instant.now()
            shows = fresh
        }
        failed = shows == null
    }
    val now = Instant.now()
    val day = DateTimeFormatter.ofPattern("EEEE d MMMM")
    val time = DateTimeFormatter.ofPattern(
        if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "hh:mm a",
    )
    if (failed) {
        Column(
            modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.schedule_failed))
            TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
        }
        return
    }
    val loaded = shows ?: return Column(
        modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Skeleton(Modifier.size(120.dp, 14.dp))
        repeat(8) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Skeleton(Modifier.size(56.dp, 16.dp))
                Skeleton(Modifier.height(16.dp).fillMaxWidth(0.5f + it % 3 * 0.15f))
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val timeStyle = MaterialTheme.typography.bodyMedium
    val liveStyle = MaterialTheme.typography.labelSmall
    val live = stringResource(R.string.live)
    val density = LocalDensity.current
    val timeWidth = with(density) {
        loaded.maxOfOrNull {
            measurer.measure(it.start.atZone(zone).format(time), timeStyle).size.width
        }
            ?.toDp() ?: 0.dp
    }
    val liveWidth = with(density) { measurer.measure(live, liveStyle).size.width.toDp() }
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier) {
        loaded.filter { it.end > now }.groupBy {
            it.start.atZone(zone).toLocalDate()
        }.forEach { (date, list) ->
            item(date.toString()) {
                Text(
                    date.format(day),
                    Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(list, key = { it.id }) { show ->
                val onNow = now >= show.start && now < show.end
                ListItem(
                    headlineContent = { Text(show.title, Modifier.basicMarquee(), maxLines = 1) },
                    modifier = Modifier.clickable(enabled = show.description != null) {
                        open = if (open == show.id) null else show.id
                    },
                    supportingContent = show.description?.takeIf {
                        open == show.id
                    }?.let { { Text(it) } },
                    leadingContent = {
                        Text(
                            show.start.atZone(zone).format(time),
                            Modifier.width(timeWidth),
                            style = timeStyle,
                        )
                    },
                    trailingContent = {
                        Text(
                            if (show.live) live else "",
                            Modifier.width(liveWidth),
                            color = MaterialTheme.colorScheme.error,
                            style = liveStyle,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (onNow) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                    ),
                )
            }
        }
    }
}
