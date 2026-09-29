package io.github.rastr1sr.radiomonash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

internal class YouViewModel(private val library: Library) : ViewModel() {
    val stats: StateFlow<Load<Stats>> = poll(60_000) { library.readStats() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Loading)
    val favourites: StateFlow<List<Fav>> = library.favourites

    fun remove(fav: Fav) = library.toggle(fav)
}

@Composable
internal fun You(
    modifier: Modifier = Modifier,
    model: YouViewModel = viewModel { YouViewModel(radio().library) },
) {
    val load by model.stats.collectAsStateWithLifecycle()
    val favs by model.favourites.collectAsStateWithLifecycle()
    YouContent((load as? Load.Ready)?.value, favs, model::remove, modifier)
}

@Composable
internal fun YouContent(
    stats: Stats?,
    favs: List<Fav>,
    onRemove: (Fav) -> Unit,
    modifier: Modifier = Modifier,
) {
    var removing by remember { mutableStateOf<Fav?>(null) }
    var only by remember { mutableStateOf<Boolean?>(null) }
    val mixed = favs.any { it.show } && favs.any { !it.show }
    val shown = if (mixed && only != null) favs.filter { it.show == only } else favs
    LazyColumn(
        modifier.fillMaxSize().wrapContentWidth().widthIn(max = 600.dp),
        contentPadding = PaddingValues(Spacing.md, 0.dp, Spacing.md, Spacing.md),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        stats?.let { s ->
            item { Header(stringResource(R.string.this_week)) }
            if (s.weekSeconds < 60 && s.topArtists.isEmpty()) {
                item { Note(stringResource(R.string.no_stats)) }
                return@let
            }
            item {
                val minutes = s.weekSeconds / 60
                val time = if (minutes < 60) {
                    stringResource(R.string.minutes, minutes)
                } else {
                    stringResource(R.string.hours_minutes, minutes / 60, minutes % 60)
                }
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    colors = segmented,
                    leadingContent = { Icon(painterResource(R.drawable.ic_clock), null) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.streak, s.streak, s.streak))
                    },
                ) { Text(time) }
            }
            ranked(R.string.top_artists, s.topArtists)
            ranked(R.string.top_shows, s.topShows)
        }
        item { Header(stringResource(R.string.favourites)) }
        if (favs.isEmpty()) {
            item { Note(stringResource(R.string.no_favourites)) }
        }
        if (mixed) {
            item {
                Row(
                    Modifier.padding(bottom = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    val kinds = listOf(false to R.string.songs, true to R.string.shows)
                    kinds.forEach { (show, label) ->
                        FilterChip(
                            selected = only == show,
                            onClick = { only = if (only == show) null else show },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            }
        }
        itemsIndexed(
            shown,
            key = { _, fav -> fav.key },
            contentType = { _, _ -> "fav" },
        ) { i, fav ->
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(i, shown.size),
                colors = segmented,
                leadingContent = {
                    val art = fav.art.takeUnless { fav.show }
                    Cover(art, Modifier.size(56.dp).clip(MaterialTheme.shapes.small)) {
                        Lemon(fav.show, Modifier.fillMaxSize(), still = true)
                    }
                },
                trailingContent = {
                    IconToggleButton(true, { removing = fav }) {
                        Icon(painterResource(heart(true)), stringResource(R.string.favourite))
                    }
                },
                supportingContent = fav.artist?.let {
                    { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
            ) {
                Text(fav.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    removing?.let { fav ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(fav.title) },
            text = { Text(stringResource(R.string.remove_question)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onRemove(fav)
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.keep)) }
            },
        )
    }
}

private fun LazyListScope.ranked(title: Int, names: List<String>) {
    if (names.isEmpty()) return
    item { Header(stringResource(title)) }
    itemsIndexed(names, key = { _, name -> name }, contentType = { _, _ -> "ranked" }) { i, name ->
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(i, names.size),
            colors = segmented,
            leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.labelLarge) },
        ) { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
internal fun Header(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, top = Spacing.lg, bottom = Spacing.xs)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Preview
@Composable
private fun YouPreview() {
    val stats = Stats(5_400, 3, listOf("Waliens", "British India"), listOf("anti-radio", "IKTR"))
    val favs =
        listOf(Fav("Always The Same", "Waliens", null), Fav("anti-radio", null, null, show = true))
    LemonScentedTheme { YouContent(stats, favs, {}) }
}
