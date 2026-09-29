package io.github.rastr1sr.radiomonash

import android.content.ClipData
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONException

internal object Logs {
    class Entry(val id: Long, val at: Instant, val tag: String, val text: String)

    private val state = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = state

    fun add(tag: String, text: String) {
        Log.i("LemonScented", "$tag: $text")
        state.update {
            val id = (it.lastOrNull()?.id ?: -1) + 1
            (it + Entry(id, Instant.now(), tag, text)).takeLast(500)
        }
    }

    fun clear() {
        state.value = emptyList()
    }
}

internal inline fun <T> logged(tag: String, what: String, block: () -> T): T? = try {
    block()
} catch (e: IOException) {
    Logs.add(tag, "$what: ${e.message}")
    null
} catch (e: JSONException) {
    Logs.add(tag, "$what: ${e.message}")
    null
} catch (e: DateTimeParseException) {
    Logs.add(tag, "$what: ${e.message}")
    null
}

private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun Logs.Entry.line() = "${stamp.format(at)}  $tag  $text"

@Composable
internal fun LogsScreen(onBack: () -> Unit) {
    val entries by Logs.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs)) },
                navigationIcon = {
                    IconButton(onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        {
                            val text = entries.joinToString("\n") { it.line() }
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText(copied, text)),
                                )
                                snackbar.showSnackbar(copied)
                            }
                        },
                        enabled = entries.isNotEmpty(),
                    ) {
                        Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.copy))
                    }
                    IconButton(Logs::clear, enabled = entries.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.clear))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.no_logs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        val direction = LocalLayoutDirection.current
        LazyColumn(
            Modifier
                .padding(
                    start = padding.calculateStartPadding(direction),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction),
                )
                .consumeWindowInsets(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                bottom = Spacing.md + padding.calculateBottomPadding(),
            ),
            reverseLayout = true,
        ) {
            items(entries.asReversed(), key = { it.id }) {
                Text(
                    it.line(),
                    Modifier.fillMaxWidth().padding(vertical = Spacing.xxs),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
