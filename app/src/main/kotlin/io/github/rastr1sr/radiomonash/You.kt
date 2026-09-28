package io.github.rastr1sr.radiomonash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun You(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stats by produceState<Stats?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { readStats(context) }
            delay(60_000)
        }
    }
    var open by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Fav?>(null) }
    var only by remember { mutableStateOf<Boolean?>(null) }
    val mixed = favourites.any { it.show } && favourites.any { !it.show }
    val shown = if (mixed && only != null) favourites.filter { it.show == only } else favourites
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    LazyColumn(modifier.fillMaxSize().wrapContentWidth().widthIn(max = 600.dp)) {
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
                ListItem(
                    headlineContent = { Text(time) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.streak, s.streak, s.streak))
                    },
                    colors = clear,
                )
            }
            if (s.topArtists.isNotEmpty()) {
                item { Header(stringResource(R.string.top_artists)) }
                itemsIndexed(s.topArtists) { i, name -> Ranked(i, name, clear) }
            }
            if (s.topShows.isNotEmpty()) {
                item { Header(stringResource(R.string.top_shows)) }
                itemsIndexed(s.topShows) { i, name -> Ranked(i, name, clear) }
            }
        }
        item { Header(stringResource(R.string.favourites)) }
        if (favourites.isEmpty()) {
            item { Note(stringResource(R.string.no_favourites)) }
        }
        if (mixed) {
            item {
                Row(
                    Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
        itemsIndexed(shown, key = { _, fav -> fav.key }) { _, fav ->
            val isOpen = open == fav.key
            ListItem(
                headlineContent = {
                    Text(fav.title, Modifier.basicMarquee(iterations = Int.MAX_VALUE), maxLines = 1)
                },
                modifier = Modifier.clickable { open = if (isOpen) null else fav.key },
                supportingContent = {
                    Column {
                        fav.artist?.let { Text(it, maxLines = 1) }
                        if (isOpen) {
                            TextButton(onClick = { removing = fav }) {
                                Icon(painterResource(heart(true)), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.remove))
                            }
                        }
                    }
                },
                leadingContent = {
                    val art = fav.art.takeUnless { fav.show }
                    Cover(art, Modifier.size(48.dp).clip(MaterialTheme.shapes.small)) {
                        Lemon(fav.show, Modifier.fillMaxSize(), still = true)
                    }
                },
                colors = clear,
            )
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
                    open = null
                    toggleFavourite(context, fav)
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.keep)) }
            },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun Ranked(index: Int, name: String, colors: ListItemColors) {
    ListItem(
        headlineContent = { Text(name, maxLines = 1) },
        leadingContent = {
            Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = colors,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
