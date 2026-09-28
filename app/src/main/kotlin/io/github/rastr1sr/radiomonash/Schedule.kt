package io.github.rastr1sr.radiomonash

import android.text.format.DateFormat
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val tz = URLEncoder.encode(zone.id, "UTF-8")
    val text = radiocult("schedule?startDate=$from&endDate=$to&timezone=$tz")
    parseSchedule(text)?.also { saved.writeText(text) }
} catch (e: IOException) {
    null
}

private fun saved(file: File): List<Show>? = try {
    parseSchedule(file.readText())
} catch (e: IOException) {
    null
}

internal fun preview(text: String): String {
    val first = text.substringBefore('\n')
    return if (first.length < text.length) first.trimEnd('.') + "…" else first
}

internal fun parseSchedule(text: String): List<Show>? = try {
    val list = JSONObject(text).getJSONArray("schedules")
    (0 until list.length()).map(list::getJSONObject).map {
        Show(
            it.getString("id"),
            it.getString("title"),
            Instant.parse(it.getString("start")),
            Instant.parse(it.getString("end")),
            it.optJSONObject("media")?.str("type") == "live",
            tipTapText(it.optJSONObject("description")),
        )
    }.sortedBy { it.start }
} catch (e: JSONException) {
    null
} catch (e: DateTimeParseException) {
    null
}

@Composable
fun Schedule(modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    var attempt by remember { mutableIntStateOf(0) }
    var shows by remember { mutableStateOf(cache) }
    var failed by remember { mutableStateOf(false) }
    val file = File(LocalContext.current.filesDir, "schedule.json")
    val now by produceState(Instant.now()) {
        while (true) {
            delay(60_000)
            value = Instant.now()
        }
    }
    LaunchedEffect(attempt, now) {
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
    val locale = LocalConfiguration.current.locales[0]
    val day = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"))
    val time = timeFormat()
    if (failed) {
        Failed(stringResource(R.string.schedule_failed), { attempt++ }, modifier)
        return
    }
    val loaded = shows ?: return SkeletonRows(DpSize(56.dp, 16.dp), modifier)
    val measurer = rememberTextMeasurer()
    val timeStyle = MaterialTheme.typography.bodyMedium
    val liveStyle = MaterialTheme.typography.labelSmall
    val live = stringResource(R.string.live)
    val nowLabel = stringResource(R.string.now)
    val describe = stringResource(R.string.show_description)
    val density = LocalDensity.current
    val timeWidth = remember(loaded, time, timeStyle) {
        with(density) {
            (loaded.map { it.start.atZone(zone).format(time) } + nowLabel)
                .maxOf { measurer.measure(it, timeStyle).size.width }
                .toDp()
        }
    }
    val liveWidth = with(density) { measurer.measure(live, liveStyle).size.width.toDp() }
    var open by remember { mutableStateOf<String?>(null) }
    val upcoming = loaded.filter { it.end > now }
    if (upcoming.isEmpty()) {
        return Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_shows))
        }
    }
    LazyColumn(modifier.fillMaxSize().wrapContentWidth().widthIn(max = 600.dp)) {
        upcoming.groupBy {
            it.start.atZone(zone).toLocalDate()
        }.forEach { (date, list) ->
            item(date.toString()) {
                Text(
                    date.format(day),
                    Modifier
                        .padding(start = 24.dp, top = 16.dp, bottom = 4.dp)
                        .semantics { heading() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            items(list, key = { it.id }) { show ->
                val onNow = now >= show.start && now < show.end
                ListItem(
                    headlineContent = { Text(show.title, Modifier.basicMarquee(), maxLines = 1) },
                    modifier = Modifier.clickable(
                        enabled = show.description != null,
                        onClickLabel = describe,
                    ) {
                        open = if (open == show.id) null else show.id
                    },
                    supportingContent = show.description?.let {
                        {
                            Text(
                                if (open == show.id) it else preview(it),
                                maxLines = if (open == show.id) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    leadingContent = {
                        Text(
                            if (onNow) nowLabel else show.start.atZone(zone).format(time),
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

@Composable
internal fun timeFormat(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    val clock = if (DateFormat.is24HourFormat(LocalContext.current)) "HHmm" else "hhmma"
    val hours = DateFormat.getBestDateTimePattern(
        locale,
        clock,
    ).replace(Regex("\\b([hH])\\b"), "$1$1")
    return DateTimeFormatter.ofPattern(hours)
}
