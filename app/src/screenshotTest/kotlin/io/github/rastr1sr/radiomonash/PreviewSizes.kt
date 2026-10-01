package io.github.rastr1sr.radiomonash

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.os.bundleOf
import androidx.media3.common.MediaMetadata
import com.android.tools.screenshot.PreviewTest
import java.time.Instant
import java.time.temporal.ChronoUnit

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "phone landscape", device = "spec:width=891dp,height=411dp")
@Preview(name = "foldable", device = "spec:width=673dp,height=841dp")
@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "large text", device = "spec:width=411dp,height=891dp", fontScale = 2f)
annotation class PreviewSizes

@Composable
private fun Themed(content: @Composable () -> Unit) {
    LemonScentedTheme(Look(theme = Theme.Dark, dynamic = false)) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@PreviewTest
@PreviewSizes
@Composable
private fun PlayerScreen() {
    val meta = MediaMetadata.Builder()
        .setTitle("Always The Same")
        .setArtist("Waliens")
        .setStation("Melbourne Music Scene")
        .setExtras(bundleOf("mode" to AirMode.Playlist.name))
        .build()
    val state = PlayerState(meta, playing = true, ready = true)
    Themed { PlayerPage(state, false, 0, null, Look(), {}, {}, {}, {}) }
}

@PreviewTest
@PreviewSizes
@Composable
private fun ScheduleScreen() {
    val start = Instant.parse("2030-09-30T05:00:00Z")
    val shows = listOf(
        Show(
            "a",
            "Soundscaping",
            start,
            start.plusSeconds(3600),
            true,
            "Modern instrumental music.",
        ),
        Show(
            "b",
            "Vegemite on Toast",
            start.plusSeconds(3600),
            start.plusSeconds(7200),
            true,
            null,
        ),
        Show(
            "c",
            "Aussie Pub Rock Hour",
            start.plusSeconds(7200),
            start.plusSeconds(10800),
            false,
            null,
        ),
    )
    Themed {
        ScheduleContent(Load.Ready(shows), false, setOf("b"), emptySet(), {}, {}, { _, _ -> })
    }
}

@PreviewTest
@PreviewSizes
@Composable
private fun RecentScreen() {
    val now = Instant.parse("2026-09-30T05:00:00Z")
    val songs = listOf(
        Played("Always The Same", "Waliens", now, null),
        Played("Summer Forgive Me", "British India", now.minus(4, ChronoUnit.MINUTES), null),
        Played("kalika", "lithu", now.minus(8, ChronoUnit.MINUTES), null),
    )
    Themed { HistoryContent(Load.Ready(songs), false, emptyList(), {}, {}) }
}

@PreviewTest
@PreviewSizes
@Composable
private fun YouScreen() {
    val stats = Stats(5_400, 3, listOf("Waliens", "British India"), listOf("anti-radio", "IKTR"))
    val favs = listOf(
        Fav("Always The Same", "Waliens", null),
        Fav("anti-radio", null, null, show = true),
    )
    Themed { YouContent(stats, favs, {}) }
}

@PreviewTest
@PreviewSizes
@Composable
private fun ChatMessages() {
    val now = 1_790_000_000L
    fun message(id: String, user: String, name: String, text: String, type: String = "message") =
        Message(id, id, user, name, now, type, text, null, 1f, user == "s", flagged = false)
    val messages = listOf(
        message("1", "a", "Adam", "Adam joined the chat", "user_joined"),
        message("2", "a", "Adam", "This show is great"),
        message("3", "s", "Radio Monash", "Thanks for tuning in!"),
        message("4", "me", "Lemon", "@Adam agreed"),
    )
    Themed { Messages(messages, "me", "Lemon", 0, {}, { _, _ -> }, {}) }
}
