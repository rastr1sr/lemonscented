package io.github.rastr1sr.radiomonash

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val STATION = "radio-monash"
private const val API = "https://api.radiocult.fm/api/chat"
private val JSON = "application/json".toMediaType()
private const val SOCKET = "wss://api.radiocult.fm/socket.io/?EIO=4&transport=websocket" +
    "&stationId=$STATION"

internal data class Message(
    val id: String,
    val timestampId: String,
    val userId: String,
    val name: String,
    val at: Long,
    val type: String,
    val text: String?,
    val gif: String?,
    val ratio: Float,
    val fromStation: Boolean,
    val flagged: Boolean,
    val acked: Boolean = true,
)

internal fun parseMessage(json: JSONObject): Message? = try {
    val content = json.optJSONObject("content")
    val media = content?.optJSONObject("media")
    Message(
        json.getString("id"),
        json.optString("timestampId"),
        json.getString("userId"),
        json.getString("displayName"),
        json.getLong("createdAt"),
        json.getString("type"),
        content?.str("text"),
        media?.str("url")?.takeIf(::isHttps),
        media?.optDouble("aspectRatio", 1.0)?.toFloat() ?: 1f,
        json.optBoolean("isStationMessage"),
        json.optBoolean("flagged"),
    )
} catch (e: JSONException) {
    null
}

internal fun parseMessages(array: JSONArray?): List<Message> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parseMessage) }
}

internal object Chat {
    val messages = mutableStateListOf<Message>()
    var connected by mutableStateOf(false)
    var older by mutableStateOf(true)
    var failed by mutableStateOf(false)

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private var open = false

    private fun prefs(context: Context) = context.getSharedPreferences("chat", Context.MODE_PRIVATE)

    fun userId(context: Context) = prefs(context).getString("id", null)

    fun name(context: Context) = prefs(context).getString("name", null)

    fun blocked(context: Context): Map<String, String> =
        prefs(context).getStringSet("blocked", emptySet()).orEmpty()
            .associate { it.substringBefore('|') to it.substringAfter('|') }

    fun block(context: Context, message: Message) {
        val set =
            prefs(context).getStringSet("blocked", emptySet()).orEmpty() +
                "${message.userId}|${message.name}"
        prefs(context).edit { putStringSet("blocked", set) }
    }

    fun unblock(context: Context, userId: String) {
        val set = prefs(context).getStringSet("blocked", emptySet()).orEmpty().filterNot {
            it.startsWith("$userId|")
        }
        prefs(context).edit { putStringSet("blocked", set.toSet()) }
    }

    fun lastSeen(context: Context) = prefs(context).getLong("seen", 0)

    fun seen(context: Context) {
        messages.lastOrNull()?.let { last -> prefs(context).edit { putLong("seen", last.at) } }
    }

    fun open() {
        if (open) return
        open = true
        failed = false
        thread {
            val history =
                runCatching {
                    parseMessages(
                        JSONObject(request("$API/messages/$STATION")).optJSONArray("messages"),
                    )
                }
            main.post {
                history.onSuccess { merge(it) }.onFailure { failed = true }
                connect()
            }
        }
    }

    fun close() {
        open = false
        socket?.close(1000, null)
        socket = null
        connected = false
    }

    fun loadOlder() {
        val first = messages.firstOrNull()?.timestampId ?: return
        if (!older) return
        older = false
        thread {
            val page = runCatching {
                parseMessages(
                    JSONObject(
                        request("$API/messages/$STATION?fromTime=$first"),
                    ).optJSONArray("messages"),
                )
            }.getOrNull()
            main.post {
                if (page != null) merge(page)
                older = !page.isNullOrEmpty()
            }
        }
    }

    fun setName(context: Context, name: String, done: (String?) -> Unit) {
        val id = userId(context)
        val old = name(context)
        thread {
            val result = runCatching {
                val reply = if (id == null) {
                    request("$API/user", "PUT", JSONObject().put("displayName", name))
                } else {
                    request(
                        "$API/user/$id/display-name",
                        "PUT",
                        JSONObject().put("newDisplayName", name),
                    )
                }
                val user = JSONObject(reply).getJSONObject("user")
                user.getString("id") to user.getString("displayName")
            }
            main.post {
                result.onSuccess { (newId, newName) ->
                    prefs(context).edit {
                        putString("id", newId)
                        putString("name", newName)
                    }
                    val notice = when (old) {
                        null -> "$newName joined the chat"
                        else -> "$old changed their name to $newName"
                    }
                    emit(context, if (old == null) "user_joined" else "user_name_change", notice)
                    done(null)
                }.onFailure { done(it.message) }
            }
        }
    }

    fun send(context: Context, text: String) = emit(context, "message", text)

    fun report(message: Message) {
        messages.removeAll { it.id == message.id }
        thread {
            runCatching {
                request(
                    "$API/messages/$STATION/flag",
                    "POST",
                    JSONObject().put("createdAt", message.at),
                )
            }
        }
    }

    private fun emit(context: Context, type: String, text: String) {
        val id = userId(context) ?: return
        val name = name(context) ?: return
        val json = JSONObject()
            .put("station", STATION)
            .put("id", UUID.randomUUID().toString())
            .put("displayName", name)
            .put("userId", id)
            .put("createdAt", System.currentTimeMillis() / 1000)
            .put("timestampId", "")
            .put("flagged", false)
            .put("type", type)
            .put("content", JSONObject().put("text", text))
        if (socket?.send("42" + JSONArray().put("message").put(json)) != true) {
            failed = true
            return
        }
        parseMessage(json)?.let { messages += it.copy(acked = false) }
    }

    private fun connect() {
        if (!open) return
        socket = client.newWebSocket(
            Request.Builder().url(SOCKET).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when {
                        text.startsWith("0") -> webSocket.send("40")
                        text == "2" -> webSocket.send("3")
                        text.startsWith("40") -> main.post { connected = true }
                        text.startsWith("42") -> event(text.substring(2))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    retry()

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()
            },
        )
    }

    private fun retry() {
        main.post {
            connected = false
            if (open) main.postDelayed({ if (open && !connected) connect() }, 3000)
        }
    }

    private fun event(payload: String) {
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return
        val data = array.optJSONObject(1) ?: return
        main.post {
            when (array.optString(0)) {
                "message" -> merge(
                    listOfNotNull(data.optJSONObject("messages")?.let(::parseMessage)),
                )

                "sent" -> {
                    val i = messages.indexOfFirst { it.id == data.optString("id") }
                    if (i >= 0 && data.optBoolean("success")) {
                        messages[i] =
                            messages[i].copy(
                                acked = true,
                                timestampId = data.optString("timestampId"),
                            )
                    } else if (i >= 0) {
                        messages.removeAt(i)
                        failed = true
                    }
                }

                "flag_message" -> {
                    val id = data.optJSONObject("flaggedMessage")?.optString("id")
                    messages.removeAll { it.id == id }
                }
            }
        }
    }

    private fun merge(incoming: List<Message>) {
        val byId = messages.associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        val sorted = byId.values.filterNot { it.flagged }
            .sortedWith(compareBy({ it.at }, { it.type == "message" || it.type == "gif" }))
        messages.clear()
        messages.addAll(sorted)
    }

    private fun request(url: String, method: String = "GET", body: JSONObject? = null): String {
        val payload = body?.toString()?.toRequestBody(JSON)
        client.newCall(Request.Builder().url(url).method(method, payload).build()).execute().use {
            val text = it.body.string()
            if (it.isSuccessful) return text
            val reason = runCatching { JSONObject(text).optString("message") }.getOrNull()
            throw IOException(reason?.ifEmpty { null } ?: "HTTP ${it.code}")
        }
    }
}
