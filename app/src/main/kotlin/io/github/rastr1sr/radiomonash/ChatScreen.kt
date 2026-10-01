package io.github.rastr1sr.radiomonash

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.format.DateFormat
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaMetadata
import com.materialkolor.hct.Hct
import java.net.URL
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private sealed interface Entry {
    val key: String

    class Day(val date: LocalDate) : Entry {
        override val key = "day$date"
    }

    object Unread : Entry {
        override val key = "unread"
    }

    class Line(val message: Message, val first: Boolean) : Entry {
        override val key = message.id
    }
}

private const val GROUP_GAP_S = 300

internal class ChatViewModel(private val chat: Chat) : ViewModel() {
    internal val messages: StateFlow<List<Message>> = chat.messages
    internal val error: StateFlow<ChatError?> = chat.error
    val connected: StateFlow<Boolean> = chat.connected
    private val nameState = MutableStateFlow(chat.name())
    private val blockedState = MutableStateFlow(chat.blocked())
    private val seenState = MutableStateFlow(chat.lastSeen())
    val name: StateFlow<String?> = nameState
    val blocked: StateFlow<Map<String, String>> = blockedState
    val seen: StateFlow<Long> = seenState

    fun open() {
        seenState.value = chat.lastSeen()
        chat.open()
    }

    fun close() {
        chat.seen()
        chat.close()
    }

    fun userId() = chat.userId()

    fun errorShown() = chat.errorShown()

    fun send(text: String) = chat.send(text)

    fun rename(name: String, done: (String?) -> Unit) = chat.setName(name) {
        if (it == null) nameState.value = chat.name()
        done(it)
    }

    fun loadOlder() = chat.loadOlder()

    internal fun report(message: Message) = chat.report(message)

    internal fun block(message: Message) {
        chat.block(message)
        blockedState.value = chat.blocked()
    }

    fun unblock(userId: String) {
        chat.unblock(userId)
        blockedState.value = chat.blocked()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ChatScreen(
    meta: MediaMetadata,
    playing: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    model: ChatViewModel = viewModel { ChatViewModel(radio().chat) },
) {
    DisposableEffect(model) {
        model.open()
        onDispose { model.close() }
    }
    val messages by model.messages.collectAsStateWithLifecycle()
    val connected by model.connected.collectAsStateWithLifecycle()
    val me by model.name.collectAsStateWithLifecycle()
    val blocked by model.blocked.collectAsStateWithLifecycle()
    val seen by model.seen.collectAsStateWithLifecycle()
    val myId = remember(me) { model.userId() }
    var draft by rememberSaveable { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    var showBlocked by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<Boolean, Message>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val loadError = stringResource(R.string.chat_failed)
    val sendError = stringResource(R.string.send_failed)
    val error by model.error.collectAsStateWithLifecycle()
    LaunchedEffect(error) {
        val shown = error ?: return@LaunchedEffect
        snackbar.showSnackbar(if (shown == ChatError.Load) loadError else sendError)
        model.errorShown()
    }
    val post = {
        model.send(draft.trim())
        draft = ""
    }
    val send = { if (me == null) naming = true else post() }
    val connecting = stringResource(R.string.connecting)
    Scaffold(
        modifier,
        topBar = {
            ChatBar(
                meta,
                playing,
                enabled,
                onToggle,
                onBack,
                named = me != null,
                onRename = { naming = true },
                onBlockList = { showBlocked = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxWidth()
                .wrapContentWidth()
                .widthIn(max = 1040.dp),
        ) {
            if (messages.isEmpty() && !connected) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(
                        Spacing.md,
                        Alignment.CenterVertically,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LoadingIndicator()
                    Text(
                        connecting,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                if (!connected) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().semantics { contentDescription = connecting },
                    )
                }
                Messages(
                    remember(messages, blocked) { messages.filter { it.userId !in blocked } },
                    myId,
                    me,
                    seen,
                    onNick = { draft = "$draft@$it ".trimStart() },
                    onAction = { report, message -> confirm = report to message },
                    onOlder = model::loadOlder,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    draft,
                    { draft = it },
                    Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.message_hint)) },
                    shape = MaterialTheme.shapes.extraLarge,
                    maxLines = 4,
                )
                IconButton(send, enabled = draft.isNotBlank() && connected) {
                    Icon(painterResource(R.drawable.ic_send), stringResource(R.string.send))
                }
            }
        }
    }
    if (naming) {
        NameDialog(
            me,
            onSave = { name, done ->
                val first = me == null
                model.rename(name) { error ->
                    done(error)
                    if (error == null) {
                        naming = false
                        if (first && draft.isNotBlank()) post()
                    }
                }
            },
            onDismiss = { naming = false },
        )
    }
    if (showBlocked) {
        BlockedDialog(blocked, onUnblock = model::unblock, onDismiss = { showBlocked = false })
    }
    confirm?.let { (report, message) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    stringResource(
                        if (report) R.string.report else R.string.block_name,
                        message.name,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (report) R.string.report_question else R.string.block_question,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (report) model.report(message) else model.block(message)
                    confirm = null
                }) { Text(stringResource(if (report) R.string.report else R.string.block)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.keep)) }
            },
        )
    }
}

@Composable
private fun ChatBar(
    meta: MediaMetadata,
    playing: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    named: Boolean,
    onRename: () -> Unit,
    onBlockList: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(meta, Modifier.size(40.dp).clip(MaterialTheme.shapes.small), still = true)
                Column(Modifier.padding(start = Spacing.sm)) {
                    Text(
                        meta.title?.toString() ?: stringResource(R.string.app_name),
                        Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        (meta.artist ?: meta.station)?.toString().orEmpty(),
                        Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onBack) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
            }
        },
        actions = {
            IconButton(onToggle, enabled = enabled) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play),
                    stringResource(if (playing) R.string.stop else R.string.play),
                )
            }
            Box {
                IconButton({ menu = true }) {
                    Icon(painterResource(R.drawable.ic_more), stringResource(R.string.more))
                }
                DropdownMenu(menu, { menu = false }) {
                    if (named) {
                        DropdownMenuItem({ Text(stringResource(R.string.change_name)) }, {
                            menu = false
                            onRename()
                        })
                    }
                    DropdownMenuItem({ Text(stringResource(R.string.blocked_users)) }, {
                        menu = false
                        onBlockList()
                    })
                }
            }
        },
    )
}

@Composable
internal fun Messages(
    messages: List<Message>,
    myId: String?,
    me: String?,
    seen: Long,
    onNick: (String) -> Unit,
    onAction: (Boolean, Message) -> Unit,
    onOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val rows = remember(messages, seen) {
        buildList {
            var day: LocalDate? = null
            var previous: Message? = null
            var unread = seen == 0L
            messages.forEach { m ->
                val date = Instant.ofEpochSecond(m.at).atZone(zone).toLocalDate()
                if (date != day) {
                    add(Entry.Day(date))
                    day = date
                    previous = null
                }
                if (!unread && m.at > seen) {
                    add(Entry.Unread)
                    unread = true
                }
                val first =
                    previous?.let {
                        it.userId != m.userId || m.at - it.at > GROUP_GAP_S ||
                            it.type != "message"
                    }
                        ?: true
                add(Entry.Line(m, first))
                previous = m
            }
        }.asReversed()
    }
    val list = rememberLazyListState()
    val older by rememberUpdatedState(onOlder)
    LaunchedEffect(list, rows.size) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { if (it >= rows.size - 3) older() }
    }
    val time = timeFormat()
    val locale = LocalConfiguration.current.locales[0]
    val dayFormat = DateTimeFormatter.ofPattern(
        DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"),
    )
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is Entry.Day -> Centred(row.date.format(dayFormat))

                Entry.Unread -> Centred(
                    stringResource(R.string.new_messages),
                    MaterialTheme.colorScheme.primary,
                )

                is Entry.Line -> if (row.message.type == "message" || row.message.type == "gif") {
                    Bubble(
                        row.message,
                        mine = row.message.userId == myId,
                        first = row.first,
                        mention =
                            me != null &&
                                row.message.text?.contains("@$me", ignoreCase = true) == true,
                        time = Instant.ofEpochSecond(row.message.at).atZone(zone).format(time),
                        onNick = onNick,
                        onAction = onAction,
                    )
                } else {
                    Centred(row.message.text.orEmpty())
                }
            }
        }
    }
}

@Composable
private fun Centred(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs, horizontal = Spacing.lg),
        color = color,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Bubble(
    message: Message,
    mine: Boolean,
    first: Boolean,
    mention: Boolean,
    time: String,
    onNick: (String) -> Unit,
    onAction: (Boolean, Message) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val container = when {
        mine -> scheme.primaryContainer
        message.fromStation -> scheme.tertiaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val content = when {
        mine -> scheme.onPrimaryContainer
        message.fromStation -> scheme.onTertiaryContainer
        else -> scheme.onSurface
    }
    Column(
        Modifier.fillMaxWidth().padding(
            start = Spacing.sm,
            end = Spacing.sm,
            top = if (first) Spacing.xs else 0.dp,
        ),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (first && !mine) {
            Text(
                message.name,
                Modifier.padding(start = Spacing.sm, bottom = Spacing.xxs),
                color = if (message.fromStation) scheme.tertiary else nickColor(message.userId),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Box {
            Surface(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(MaterialTheme.shapes.large)
                    .then(
                        if (mention) {
                            Modifier.border(
                                2.dp,
                                scheme.primary,
                                MaterialTheme.shapes.large,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .combinedClickable(
                        onLongClickLabel =
                            stringResource(R.string.message_actions).takeUnless { mine },
                        onLongClick = { if (!mine) menu = true },
                        onClickLabel = stringResource(R.string.mention).takeUnless { mine },
                        onClick = { if (!mine) onNick(message.name) },
                    ),
                color = container,
                contentColor = content,
            ) {
                Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) {
                    message.gif?.let {
                        Gif(
                            it,
                            message.ratio,
                            Modifier.width(200.dp).clip(MaterialTheme.shapes.medium),
                        )
                    }
                    message.text?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    Row(
                        Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    ) {
                        Text(
                            time,
                            color = content.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (mine) {
                            Icon(
                                painterResource(
                                    if (message.acked) R.drawable.ic_check else R.drawable.ic_clock,
                                ),
                                stringResource(
                                    if (message.acked) R.string.sent else R.string.sending,
                                ),
                                Modifier.size(16.dp),
                                tint = content.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.report)) }, {
                    menu = false
                    onAction(true, message)
                })
                DropdownMenuItem({ Text(stringResource(R.string.block_name, message.name)) }, {
                    menu = false
                    onAction(false, message)
                })
            }
        }
    }
}

@Composable
private fun nickColor(id: String): Color {
    val tone = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 80.0 else 40.0
    return Color(Hct.from(id.hashCode().mod(360).toDouble(), 48.0, tone).toInt())
}

@Composable
private fun Gif(url: String, ratio: Float, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val drawable by produceState<Drawable?>(null, url) {
        value = withContext(Dispatchers.IO) {
            logged("Chat", "GIF") {
                val bytes = URL(url).openStream().use { it.readBytes() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                } else {
                    BitmapDrawable(
                        resources,
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size),
                    )
                }
            }
        }
    }
    val shape = modifier.aspectRatio(ratio.coerceIn(0.3f, 3f))
    val loaded =
        drawable ?: return Box(shape.background(MaterialTheme.colorScheme.surfaceContainerHighest))
    AndroidView(
        { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        shape,
        update = {
            it.setImageDrawable(loaded)
            (loaded as? Animatable)?.start()
        },
    )
}

@Composable
private fun NameDialog(
    current: String?,
    onSave: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(current.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.display_name)) },
        text = {
            OutlinedTextField(
                name,
                {
                    name = it
                    error = null
                },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    onSave(name.trim()) {
                        busy = false
                        error = it
                    }
                },
                enabled = name.isNotBlank() && !busy,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) }
        },
    )
}

@Composable
private fun BlockedDialog(
    blocked: Map<String, String>,
    onUnblock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blocked_users)) },
        text = {
            Column {
                if (blocked.isEmpty()) Text(stringResource(R.string.no_blocked))
                blocked.forEach { (id, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = {
                            onUnblock(id)
                        }) { Text(stringResource(R.string.unblock)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
