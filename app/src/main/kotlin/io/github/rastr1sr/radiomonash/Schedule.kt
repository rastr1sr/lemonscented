package io.github.rastr1sr.radiomonash

import android.Manifest
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
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

internal class Shows(private val context: Context, private val reminders: Reminders) {
    private val shows = MutableStateFlow<List<Show>?>(null)
    val list: StateFlow<List<Show>?> = shows
    private var fetchedAt = Instant.EPOCH

    fun load(force: Boolean): List<Show>? {
        val file = File(context.filesDir, "schedule.json")
        if (shows.value == null) shows.value = saved(file)
        if (force || shows.value == null || Instant.now() > fetchedAt.plusSeconds(600)) {
            schedule(ZoneId.systemDefault(), file)?.let {
                shows.value = it
                fetchedAt = Instant.now()
                reminders.remindFollowed(it)
            }
        }
        return shows.value
    }
}

internal fun List<Show>.current(now: Instant = Instant.now()) = firstOrNull {
    now >= it.start &&
        now < it.end
}

internal class ScheduleViewModel(private val source: Shows, private val reminders: Reminders) :
    ViewModel() {
    private val attempts = MutableStateFlow(0)
    private val refreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = refreshing
    val reminded: StateFlow<Set<String>> = reminders.reminded
    val followed: StateFlow<Set<String>> = reminders.followed

    internal val shows: StateFlow<Load<List<Show>>> = attempts.flatMapLatest { attempt ->
        var force = attempt > 0
        poll(MINUTE_MS) {
            source.load(force).also {
                force = false
                refreshing.value = false
            }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        source.list.value?.let { Load.Ready(it) } ?: Load.Loading,
    )

    fun refresh() {
        refreshing.value = true
        attempts.value++
    }

    fun toggleReminder(show: Show) = reminders.toggleReminder(show)

    fun toggleFollow(show: Show, shows: List<Show>) = reminders.toggleFollow(show, shows)
}

private fun schedule(zone: ZoneId, saved: File): List<Show>? = logged("Network", "Schedule") {
    val from = LocalDate.now(zone).atStartOfDay(zone).toInstant()
    val to = from.plus(7, ChronoUnit.DAYS)
    val tz = URLEncoder.encode(zone.id, "UTF-8")
    val text = radiocult("schedule?startDate=$from&endDate=$to&timezone=$tz")
    parseSchedule(text).also { saved.writeText(text) }
}

private fun saved(file: File): List<Show>? = if (!file.exists()) {
    null
} else {
    try {
        parseSchedule(file.readText())
    } catch (e: JSONException) {
        Logs.add("Storage", "Saved schedule: ${e.message}")
        null
    }
}

internal fun preview(text: String): String {
    val first = text.substringBefore('\n')
    return if (first.length < text.length) first.trimEnd('.') + "…" else first
}

internal fun parseSchedule(text: String): List<Show> {
    val list = JSONObject(text).getJSONArray("schedules")
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
internal fun Schedule(
    modifier: Modifier = Modifier,
    model: ScheduleViewModel = viewModel { ScheduleViewModel(radio().shows, radio().reminders) },
) {
    val load by model.shows.collectAsStateWithLifecycle()
    val refreshing by model.isRefreshing.collectAsStateWithLifecycle()
    val reminded by model.reminded.collectAsStateWithLifecycle()
    val followed by model.followed.collectAsStateWithLifecycle()
    ScheduleContent(
        load,
        refreshing,
        reminded,
        followed,
        onRefresh = model::refresh,
        onRemind = model::toggleReminder,
        onFollow = model::toggleFollow,
        modifier = modifier,
    )
}

@Composable
internal fun ScheduleContent(
    load: Load<List<Show>>,
    refreshing: Boolean,
    reminded: Set<String>,
    followed: Set<String>,
    onRefresh: () -> Unit,
    onRemind: (Show) -> Unit,
    onFollow: (Show, List<Show>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    var picked by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<Show?>(null) }
    var following by remember { mutableStateOf<Show?>(null) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val now by produceState(Instant.now()) {
        while (true) {
            delay(MINUTE_MS)
            value = Instant.now()
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val day = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"))
    val time = timeFormat()
    val loaded = when (val state = load) {
        Load.Loading -> return Loading(modifier)

        Load.Failed -> return Failed(
            stringResource(R.string.schedule_failed),
            onRefresh,
            modifier,
        )

        is Load.Ready -> state.value
    }
    val measurer = rememberTextMeasurer()
    val timeStyle = MaterialTheme.typography.labelLarge
    val playlist = stringResource(R.string.playlist)
    val nowLabel = stringResource(R.string.now)
    val density = LocalDensity.current
    val timeWidth = remember(loaded, time, timeStyle) {
        with(density) {
            (loaded.map { it.start.atZone(zone).format(time) } + nowLabel)
                .maxOf { measurer.measure(it, timeStyle).size.width }
                .toDp()
        }
    }
    val upcoming = loaded.filter { it.end > now }
    if (upcoming.isEmpty()) {
        return Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_shows))
        }
    }
    ListDetail(
        upcoming.find { it.id == picked },
        stringResource(R.string.select_show),
        onClose = { picked = null },
        detail = { show, close ->
            val start = show.start.atZone(zone)
            ShowDetail(
                show,
                "${start.format(day)} · ${start.format(time)}",
                playlist,
                reminder = show.id in reminded,
                followed = show.title in followed,
                upcoming = show.start > now,
                onRemind = {
                    close()
                    asking = show
                },
                onFollow = {
                    close()
                    following = show
                },
            )
        },
        modifier = modifier,
    ) { twoPane ->
        Refreshable(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
        ) {
            LazyColumn(
                Modifier.fillMaxSize().wrapContentWidth().widthIn(max = Spacing.content),
                contentPadding = PaddingValues(margin, 0.dp, margin, Spacing.md),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                upcoming.groupBy {
                    it.start.atZone(zone).toLocalDate()
                }.forEach { (date, list) ->
                    item(date.toString(), contentType = "day") { Header(date.format(day)) }
                    itemsIndexed(
                        list,
                        key = { _, show -> show.id },
                        contentType = { _, _ -> "show" },
                    ) { i, show ->
                        val onNow = now >= show.start && now < show.end
                        SegmentedListItem(
                            selected = onNow || (twoPane && show.id == picked),
                            onClick = { picked = show.id },
                            shapes = ListItemDefaults.segmentedShapes(i, list.size).flat(),
                            colors = segmented,
                            leadingContent = {
                                Text(
                                    if (onNow) nowLabel else show.start.atZone(zone).format(time),
                                    Modifier.width(timeWidth),
                                    style = timeStyle,
                                )
                            },
                            trailingContent = if (show.id in reminded) {
                                {
                                    Icon(
                                        painterResource(R.drawable.ic_bell_on),
                                        stringResource(R.string.reminder_set),
                                    )
                                }
                            } else {
                                null
                            },
                            overlineContent = if (show.live) null else ({ Text(playlist) }),
                            supportingContent = show.description?.let {
                                {
                                    Text(
                                        preview(it),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                        ) {
                            Text(show.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    val ask = { granted: Boolean ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    following?.let { show ->
        val isFollowed = show.title in followed
        ShowDialog(
            show.title,
            if (isFollowed) R.string.unfollow_question else R.string.follow_question,
            if (isFollowed) R.string.unfollow else R.string.follow,
            if (isFollowed) R.string.keep else R.string.not_now,
            onConfirm = {
                onFollow(show, loaded)
                ask(isFollowed)
            },
            onDismiss = { following = null },
        )
    }
    asking?.let { show ->
        val hasReminder = show.id in reminded
        ShowDialog(
            show.title,
            if (hasReminder) R.string.cancel_question else R.string.remind_question,
            if (hasReminder) R.string.cancel_reminder else R.string.remind_me,
            if (hasReminder) R.string.keep else R.string.not_now,
            onConfirm = {
                onRemind(show)
                ask(hasReminder)
            },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun ShowDetail(
    show: Show,
    starts: String,
    playlist: String,
    reminder: Boolean,
    followed: Boolean,
    upcoming: Boolean,
    onRemind: () -> Unit,
    onFollow: () -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                listOfNotNull(starts, playlist.takeUnless { show.live }).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(show.title, style = MaterialTheme.typography.headlineSmall)
        }
        show.description?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (upcoming) {
                FilledTonalButton(onRemind) {
                    Icon(
                        painterResource(
                            if (reminder) R.drawable.ic_bell_on else R.drawable.ic_bell_off,
                        ),
                        null,
                        Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(
                        stringResource(if (reminder) R.string.reminder_set else R.string.remind_me),
                    )
                }
            }
            OutlinedButton(onFollow) {
                Text(stringResource(if (followed) R.string.following else R.string.follow))
            }
        }
    }
}

@Composable
private fun ShowDialog(
    title: String,
    question: Int,
    confirm: Int,
    dismiss: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(stringResource(question)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(dismiss)) } },
    )
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
