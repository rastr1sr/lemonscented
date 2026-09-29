package io.github.rastr1sr.radiomonash

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

internal enum class ChatError { Load, Send }

internal class Chat(context: Context) {
    private val prefs = context.getSharedPreferences("chat", Context.MODE_PRIVATE)

    private val list = MutableStateFlow<List<Message>>(emptyList())
    private val online = MutableStateFlow(false)
    private val more = MutableStateFlow(true)
    private val problems = MutableSharedFlow<ChatError>(extraBufferCapacity = 4)
    val messages: StateFlow<List<Message>> = list
    val connected: StateFlow<Boolean> = online
    val errors: SharedFlow<ChatError> = problems

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private var open = false

    fun userId() = prefs.getString("id", null)

    fun name() = prefs.getString("name", null)

    fun blocked(): Map<String, String> = prefs.getStringSet("blocked", emptySet()).orEmpty()
        .associate { it.substringBefore('|') to it.substringAfter('|') }

    fun block(message: Message) {
        val set =
            prefs.getStringSet("blocked", emptySet()).orEmpty() +
                "${message.userId}|${message.name}"
        prefs.edit { putStringSet("blocked", set) }
    }

    fun unblock(userId: String) {
        val set = prefs.getStringSet("blocked", emptySet()).orEmpty().filterNot {
            it.startsWith("$userId|")
        }
        prefs.edit { putStringSet("blocked", set.toSet()) }
    }

    fun lastSeen() = prefs.getLong("seen", 0)

    fun seen() {
        list.value.lastOrNull()?.let { last -> prefs.edit { putLong("seen", last.at) } }
    }

    fun open() {
        if (open) return
        open = true
        thread {
            val history = try {
                parseMessages(
                    JSONObject(request("$API/messages/$STATION")).optJSONArray("messages"),
                )
            } catch (e: IOException) {
                Logs.add("Chat", "History: ${e.message}")
                null
            } catch (e: JSONException) {
                Logs.add("Chat", "History: ${e.message}")
                null
            }
            main.post {
                if (history == null) problems.tryEmit(ChatError.Load) else merge(history)
                connect()
            }
        }
    }

    fun close() {
        open = false
        socket?.close(1000, null)
        socket = null
        online.value = false
    }

    fun loadOlder() {
        val first = list.value.firstOrNull()?.timestampId ?: return
        if (!more.value) return
        more.value = false
        thread {
            val page = try {
                parseMessages(
                    JSONObject(
                        request("$API/messages/$STATION?fromTime=$first"),
                    ).optJSONArray("messages"),
                )
            } catch (e: IOException) {
                Logs.add("Chat", "Older messages: ${e.message}")
                null
            } catch (e: JSONException) {
                Logs.add("Chat", "Older messages: ${e.message}")
                null
            }
            main.post {
                if (page != null) merge(page)
                more.value = page == null || page.isNotEmpty()
            }
        }
    }

    fun setName(name: String, done: (String?) -> Unit) {
        val id = userId()
        val old = this.name()
        thread {
            val result = runCatching {
                val reply = if (id == null) {
                    request("$API/user", "PUT", JSONObject().put("displayName", name))
                } else {
                    request(
                        "$API/user/$id/display-name",
                        "POST",
                        JSONObject().put("newDisplayName", name),
                    )
                }
                val user = JSONObject(reply).getJSONObject("user")
                user.getString("id") to user.getString("displayName")
            }
            main.post {
                result.onSuccess { (newId, newName) ->
                    prefs.edit {
                        putString("id", newId)
                        putString("name", newName)
                    }
                    val notice = when (old) {
                        null -> "$newName joined the chat"
                        else -> "$old changed their name to $newName"
                    }
                    emit(if (old == null) "user_joined" else "user_name_change", notice)
                    done(null)
                }.onFailure { done(it.message) }
            }
        }
    }

    fun send(text: String) = emit("message", text)

    fun report(message: Message) {
        list.update { all -> all.filterNot { it.id == message.id } }
        thread {
            try {
                request(
                    "$API/messages/$STATION/flag",
                    "POST",
                    JSONObject().put("createdAt", message.at),
                )
            } catch (e: IOException) {
                Logs.add("Chat", "Report: ${e.message}")
            }
        }
    }

    private fun emit(type: String, text: String) {
        val id = userId() ?: return
        val name = name() ?: return
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
            Logs.add("Chat", "Send failed, not connected")
            problems.tryEmit(ChatError.Send)
            return
        }
        parseMessage(json)?.let { sent -> list.update { it + sent.copy(acked = false) } }
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

                        text.startsWith("40") -> {
                            Logs.add("Chat", "Connected")
                            online.value = true
                        }

                        text.startsWith("42") -> event(text.substring(2))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logs.add("Chat", "Disconnected: ${t.message}")
                    retry()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logs.add("Chat", "Closed $code")
                    retry()
                }
            },
        )
    }

    private fun retry() {
        main.post {
            online.value = false
            if (open) main.postDelayed({ if (open && !online.value) connect() }, 3000)
        }
    }

    private fun event(payload: String) {
        val array = try {
            JSONArray(payload)
        } catch (e: JSONException) {
            Logs.add("Chat", "Unreadable event: ${e.message}")
            return
        }
        val data = array.optJSONObject(1) ?: return
        main.post {
            when (array.optString(0)) {
                "message" -> merge(
                    listOfNotNull(data.optJSONObject("messages")?.let(::parseMessage)),
                )

                "sent" -> {
                    val id = data.optString("id")
                    if (!data.optBoolean("success")) problems.tryEmit(ChatError.Send)
                    list.update { all ->
                        if (data.optBoolean("success")) {
                            all.map {
                                if (it.id == id) {
                                    it.copy(
                                        acked = true,
                                        timestampId = data.optString("timestampId"),
                                    )
                                } else {
                                    it
                                }
                            }
                        } else {
                            all.filterNot { it.id == id }
                        }
                    }
                }

                "flag_message" -> {
                    val id = data.optJSONObject("flaggedMessage")?.optString("id")
                    list.update { all -> all.filterNot { it.id == id } }
                }
            }
        }
    }

    private fun merge(incoming: List<Message>) {
        val byId = list.value.associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        val sorted = byId.values.filterNot { it.flagged }
            .sortedWith(compareBy({ it.at }, { it.type == "message" || it.type == "gif" }))
        list.value = sorted
    }

    private fun request(url: String, method: String = "GET", body: JSONObject? = null): String {
        val payload = body?.toString()?.toRequestBody(JSON)
        client.newCall(Request.Builder().url(url).method(method, payload).build()).execute().use {
            val text = it.body.string()
            if (it.isSuccessful) return text
            val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()
            throw IOException(reason?.ifEmpty { null } ?: "HTTP ${it.code}")
        }
    }
}
