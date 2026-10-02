package com.nabchat.app.provider

import com.nabchat.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.min

/**
 * Experimental, read-only Kick web transport. Kick's official developer API exposes incoming
 * chat as authenticated webhooks, which cannot be received directly by a local Android app.
 * This adapter keeps the current public web-history polling contract isolated and replaceable.
 */
class KickChatProvider : ChatProvider {
    override val platform = ChatPlatform.KICK
    override var autoReconnect: Boolean = true
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = mutableState.asStateFlow()
    private var connected = false
    private val emoteCatalog = EmoteCatalog()

    override suspend fun resolveChannel(input: String): Result<ChatChannel> = withContext(Dispatchers.IO) {
        runCatching {
            val slug = normalizeKickSlug(input)
            require(slug.matches(Regex("[a-z0-9_]{2,32}"))) { "Enter a valid Kick username or channel URL." }
            val root = requestJson("https://kick.com/api/v2/channels/$slug")
            val id = root.optLong("id", -1).takeIf { it > 0 } ?: error("Kick did not return a channel ID for $slug.")
            val user = root.optJSONObject("user")
            val chatroom = root.optJSONObject("chatroom")
            ChatChannel(
                platform = platform,
                platformChannelId = id.toString(),
                chatroomId = chatroom?.optLong("id", id)?.toString() ?: id.toString(),
                slug = root.optString("slug", slug),
                displayName = user?.optString("username", slug).orEmpty().ifBlank { slug },
                avatarUrl = user?.optString("profile_pic")?.takeIf { it.isNotBlank() }
            )
        }.recoverCatching { error ->
            throw IllegalStateException(when {
                error.message?.contains("HTTP 404") == true -> "Kick channel not found. Check the username and try again."
                error.message?.contains("HTTP 403") == true -> "Kick temporarily refused the public lookup. Try again shortly."
                error is java.net.SocketTimeoutException -> "Kick took too long to respond. Check your connection and try again."
                error is java.net.UnknownHostException -> "Could not reach Kick. Check your internet connection."
                else -> error.message ?: "Could not resolve that Kick channel."
            }, error)
        }
    }

    override fun observeMessages(channels: Set<ChatChannel>): Flow<ChatEvent> = channelFlow {
        if (channels.isEmpty()) { send(ChatEvent.State(ConnectionState.DISCONNECTED)); return@channelFlow }
        var failures = 0
        val seen = LinkedHashSet<String>()
        while (currentCoroutineContext().isActive && connected) {
            try {
                mutableState.value = if (failures == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
                channels.forEach { channel ->
                    // The 2026 web-history route currently expects the broadcaster/channel id,
                    // not the distinct legacy chatroom id returned in channel metadata.
                    val root = requestJson("https://web.kick.com/api/v1/chat/${channel.platformChannelId}/history")
                    extractMessages(root).sortedBy { parseTime(it.optString("created_at")) }.forEach { raw ->
                        parseMessage(raw, channel)?.let { message -> if (seen.add(message.messageId)) send(ChatEvent.Message(message)) }
                    }
                }
                while (seen.size > 5_000) seen.remove(seen.first())
                if (mutableState.value != ConnectionState.CONNECTED) send(ChatEvent.State(ConnectionState.CONNECTED, "Kick web history (experimental)"))
                mutableState.value = ConnectionState.CONNECTED
                failures = 0
                delay(4_000)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                failures++
                if (!autoReconnect) {
                    mutableState.value = ConnectionState.ERROR
                    send(ChatEvent.State(ConnectionState.ERROR, error.message))
                    break
                }
                mutableState.value = ConnectionState.RECONNECTING
                send(ChatEvent.State(ConnectionState.RECONNECTING, error.message))
                delay(min(30_000L, 1_000L shl min(failures, 5)))
            }
        }
    }.flowOn(Dispatchers.IO).onCompletion { mutableState.value = ConnectionState.DISCONNECTED }

    override suspend fun connect() { connected = true; mutableState.value = ConnectionState.CONNECTING }
    override suspend fun disconnect() { connected = false; mutableState.value = ConnectionState.DISCONNECTED }

    private fun extractMessages(root: JSONObject): List<JSONObject> {
        val value = when {
            root.optJSONArray("messages") != null -> root.optJSONArray("messages")
            root.optJSONArray("data") != null -> root.optJSONArray("data")
            root.optJSONObject("data")?.optJSONArray("messages") != null -> root.optJSONObject("data")?.optJSONArray("messages")
            else -> null
        } ?: JSONArray()
        return List(value.length()) { value.optJSONObject(it) }.filterNotNull()
    }

    private fun parseMessage(raw: JSONObject, channel: ChatChannel): ChatMessage? = runCatching {
        val senderObject = raw.optJSONObject("sender") ?: raw.optJSONObject("user") ?: JSONObject()
        val messageId = raw.optString("id").ifBlank { raw.optString("message_id") }.ifBlank { return null }
        val text = raw.optString("content", raw.optString("message"))
        val badges = mutableListOf<String>()
        senderObject.optJSONArray("identity")?.let { a -> repeat(a.length()) { badges += a.optString(it) } }
        senderObject.optJSONObject("identity")?.optJSONArray("badges")?.let { a -> repeat(a.length()) { i -> badges += (a.optJSONObject(i)?.optString("type") ?: a.optString(i)) } }
        val tokenRegex = Regex("\\[emote:(\\d+):([^]]+)]")
        val emotes = tokenRegex.findAll(text).map {
            val definition = emoteCatalog.resolve(platform, it.groupValues[1], it.groupValues[2], channel.platformChannelId)
            ChatEmote(definition.id, definition.name, it.range.first, it.range.last, definition.imageUrl, definition.platform, definition.ownerChannelId)
        }.toList()
        val segments = parseContentSegments(text, emotes)
        val replyObject = raw.optJSONObject("replies_to")
        val timestamp = parseTime(raw.optString("created_at")).takeIf { it > 0 } ?: System.currentTimeMillis()
        val userId = senderObject.opt("id")?.toString()?.takeIf { it != "null" }
        val username = senderObject.optString("username", senderObject.optString("slug", "unknown"))
        val avatar = avatarUrl(senderObject)
        ChatMessage(platform, messageId, channel, ChatUser(platform, userId, username, username, badges, avatar), text, timestamp, System.currentTimeMillis(), emotes, badges,
            replyObject?.let { ReplyInfo(it.opt("message_id")?.toString(), it.optJSONObject("sender")?.optString("username"), it.optString("content")) },
            isEmoteOnly(segments), raw.toString(), segments)
    }.getOrNull()

    /** Kick has used both flat and nested sender profile shapes; keep this local and network-free. */
    private fun avatarUrl(sender: JSONObject): String? {
        val containers = listOfNotNull(
            sender,
            sender.optJSONObject("profile"),
            sender.optJSONObject("user"),
            sender.optJSONObject("channel")
        )
        val keys = listOf("profile_pic", "profile_picture", "profile_image", "avatar", "avatar_url", "image")
        return containers.firstNotNullOfOrNull { container ->
            keys.firstNotNullOfOrNull { key ->
                container.optString(key).takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
        }
    }

    private fun parseTime(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)

    private fun requestJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125 Mobile Safari/537.36")
            connection.setRequestProperty("Origin", "https://kick.com")
            connection.setRequestProperty("Referer", "https://kick.com/")
            if (connection.responseCode !in 200..299) error("Kick returned HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
}

internal fun normalizeKickSlug(input: String): String {
    var value = input.trim().removePrefix("@").substringBefore('?').substringBefore('#').trimEnd('/')
    value = value.replace(Regex("(?i)^https?://(www\\.)?kick\\.com/"), "")
    return value.substringBefore('/').lowercase()
}
