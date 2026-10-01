package io.github.rastr1sr.radiomonash

import android.content.Context
import androidx.core.content.edit
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
private const val OPEN = "0"
private const val CONNECT = "40"
private const val PING = "2"
private const val PONG = "3"
private const val EVENT = "42"
private const val RETRY_MS = 3_000L

internal class ServerError(reason: String) : IOException(reason)

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

internal class Chat(private val context: Context) {
    private val prefs = context.getSharedPreferences("chat", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val list = MutableStateFlow<List<Message>>(emptyList())
    private val online = MutableStateFlow(false)
    private val problem = MutableStateFlow<ChatError?>(null)
    val messages: StateFlow<List<Message>> = list
    val connected: StateFlow<Boolean> = online
    val error: StateFlow<ChatError?> = problem

    private val client = OkHttpClient()
    private var socket: WebSocket? = null
    private var open = false
    private var more = true

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

    fun errorShown() {
        problem.value = null
    }

    fun lastSeen() = prefs.getLong("seen", 0)

    fun seen() {
        list.value.lastOrNull()?.let { last -> prefs.edit { putLong("seen", last.at) } }
    }

    fun open() {
        if (open) return
        open = true
        scope.launch {
            val history = page("$API/messages/$STATION", "History")
            if (history == null) problem.value = ChatError.Load else merge(history)
            connect()
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
        if (!more) return
        more = false
        scope.launch {
            val older = page("$API/messages/$STATION?fromTime=$first", "Older messages")
            if (older != null) merge(older)
            more = older == null || older.isNotEmpty()
        }
    }

    fun setName(name: String, done: (String?) -> Unit) {
        val id = userId()
        val old = this.name()
        scope.launch {
            val user = try {
                withContext(Dispatchers.IO) {
                    val reply = if (id == null) {
                        request("$API/user", "PUT", JSONObject().put("displayName", name))
                    } else {
                        request(
                            "$API/user/$id/display-name",
                            "POST",
                            JSONObject().put("newDisplayName", name),
                        )
                    }
                    JSONObject(reply).getJSONObject("user")
                }
            } catch (e: ServerError) {
                done(e.message)
                return@launch
            } catch (e: IOException) {
                Logs.add("Chat", "Name: ${e.message}")
                done(context.getString(R.string.name_failed))
                return@launch
            } catch (e: JSONException) {
                Logs.add("Chat", "Name: ${e.message}")
                done(context.getString(R.string.name_failed))
                return@launch
            }
            val newName = user.getString("displayName")
            prefs.edit {
                putString("id", user.getString("id"))
                putString("name", newName)
            }
            val notice = when (old) {
                null -> "$newName joined the chat"
                else -> "$old changed their name to $newName"
            }
            emit(if (old == null) "user_joined" else "user_name_change", notice)
            done(null)
        }
    }

    fun send(text: String) = emit("message", text)

    fun report(message: Message) {
        list.update { all -> all.filterNot { it.id == message.id } }
        scope.launch(Dispatchers.IO) {
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
        if (socket?.send(EVENT + JSONArray().put("message").put(json)) != true) {
            Logs.add("Chat", "Send failed, not connected")
            problem.value = ChatError.Send
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
                        text.startsWith(CONNECT) -> {
                            Logs.add("Chat", "Connected")
                            online.value = true
                        }

                        text.startsWith(EVENT) -> event(text.substring(EVENT.length))

                        text.startsWith(OPEN) -> webSocket.send(CONNECT)

                        text == PING -> webSocket.send(PONG)
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
        scope.launch {
            online.value = false
            delay(RETRY_MS)
            if (open && !online.value) connect()
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
        scope.launch {
            when (array.optString(0)) {
                "message" -> merge(
                    listOfNotNull(data.optJSONObject("messages")?.let(::parseMessage)),
                )

                "sent" -> {
                    val id = data.optString("id")
                    if (!data.optBoolean("success")) problem.value = ChatError.Send
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

    private suspend fun page(url: String, what: String) = withContext(Dispatchers.IO) {
        logged("Chat", what) { parseMessages(JSONObject(request(url)).optJSONArray("messages")) }
    }

    private fun merge(incoming: List<Message>) {
        val byId = list.value.associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        list.value = byId.values.filterNot { it.flagged }
            .sortedWith(compareBy({ it.at }, { it.type == "message" || it.type == "gif" }))
    }

    private fun request(url: String, method: String = "GET", body: JSONObject? = null): String {
        val payload = body?.toString()?.toRequestBody(JSON)
        client.newCall(Request.Builder().url(url).method(method, payload).build()).execute().use {
            val text = it.body.string()
            if (it.isSuccessful) return text
            val reason = try {
                JSONObject(text).optString("error").ifEmpty { null }
            } catch (e: JSONException) {
                null
            }
            throw if (reason != null) ServerError(reason) else IOException("HTTP ${it.code}")
        }
    }
}
