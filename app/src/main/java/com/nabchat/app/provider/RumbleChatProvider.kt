package com.nabchat.app.provider

import com.nabchat.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Read-only adapter for Rumble's documented creator Live Stream API. Rumble currently does not
 * publish a viewer API for resolving arbitrary channel names to chat, so the creator-supplied API
 * URL is the channel credential. It is never logged or placed in message metadata.
 */
class RumbleChatProvider : ChatProvider {
    override val platform = ChatPlatform.RUMBLE
    override var autoReconnect = true
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = mutableState.asStateFlow()
    private var connected = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun resolveChannel(input: String): Result<ChatChannel> = withContext(Dispatchers.IO) {
        runCatching {
            val apiUrl = normalizeRumbleApiUrl(input)
            val snapshot = requestJson(apiUrl)
            validateSnapshot(snapshot)
            val streams = snapshot.optJSONArray("livestreams") ?: JSONArray()
            val live = (0 until streams.length()).mapNotNull(streams::optJSONObject)
                .firstOrNull { it.optBoolean("is_live", false) }
                ?: (0 until streams.length()).mapNotNull(streams::optJSONObject).firstOrNull()
            val accountId = snapshot.opt("channel_id")?.toString()?.takeUnless { it == "null" }
                ?: snapshot.opt("user_id")?.toString()?.takeUnless { it == "null" }
                ?: apiUrl.sha256().take(20)
            val label = snapshot.optString("channel_name").ifBlank { snapshot.optString("username") }
                .ifBlank { live?.optString("title").orEmpty() }.ifBlank { "Rumble $accountId" }
            ChatChannel(
                platform = platform,
                platformChannelId = accountId,
                chatroomId = live?.opt("id")?.toString()?.takeUnless { it == "null" },
                slug = apiUrl,
                displayName = label,
                avatarUrl = "https://rumble.com/favicon.ico"
            )
        }.recoverCatching { cause ->
            throw IllegalStateException(cause.message ?: "Could not connect with that Rumble connection link.", cause)
        }
    }

    override fun observeMessages(channels: Set<ChatChannel>): Flow<ChatEvent> = channelFlow {
        if (channels.isEmpty()) { send(ChatEvent.State(ConnectionState.DISCONNECTED)); return@channelFlow }
        val seen = LinkedHashSet<String>()
        var failures = 0
        while (currentCoroutineContext().isActive && connected) {
            try {
                mutableState.value = if (failures == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
                channels.forEach { channel ->
                    val snapshot = requestJson(channel.slug)
                    validateSnapshot(snapshot)
                    val streams = snapshot.optJSONArray("livestreams") ?: JSONArray()
                    repeat(streams.length()) { index ->
                        val stream = streams.optJSONObject(index) ?: return@repeat
                        val streamId = stream.opt("id")?.toString().orEmpty()
                        val chat = stream.optJSONObject("chat") ?: return@repeat
                        val messages = combined(chat.optJSONObject("latest_message"), chat.optJSONArray("recent_messages"))
                        messages.sortedBy { parseTime(it.optString("created_on")) }.forEach { raw ->
                            parseMessage(raw, channel, streamId)?.let { message ->
                                if (seen.add(message.messageId)) send(ChatEvent.Message(message))
                            }
                        }
                    }
                }
                while (seen.size > 5_000) seen.remove(seen.first())
                if (mutableState.value != ConnectionState.CONNECTED) send(ChatEvent.State(ConnectionState.CONNECTED, "Rumble Live Stream API"))
                mutableState.value = ConnectionState.CONNECTED
                failures = 0
                delay(3_000)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                failures++
                if (!autoReconnect) { mutableState.value = ConnectionState.ERROR; send(ChatEvent.State(ConnectionState.ERROR, error.message)); break }
                mutableState.value = ConnectionState.RECONNECTING
                send(ChatEvent.State(ConnectionState.RECONNECTING, error.message))
                delay(min(30_000L, 1_000L shl min(failures, 5)))
            }
        }
    }.flowOn(Dispatchers.IO).onCompletion { mutableState.value = ConnectionState.DISCONNECTED }

    override suspend fun connect() { connected = true; mutableState.value = ConnectionState.CONNECTING }
    override suspend fun disconnect() { connected = false; mutableState.value = ConnectionState.DISCONNECTED }

    private fun parseMessage(raw: JSONObject, channel: ChatChannel, streamId: String): ChatMessage? = runCatching {
        val username = raw.optString("username", raw.optString("user")).ifBlank { "Rumble user" }
        val text = raw.optString("text")
        if (text.isBlank()) return null
        val timestamp = parseTime(raw.optString("created_on")).takeIf { it > 0 } ?: System.currentTimeMillis()
        val badges = raw.optJSONArray("badges")?.let { array -> List(array.length()) { array.optString(it) }.filter(String::isNotBlank) }.orEmpty()
        val stable = "$streamId|$username|$timestamp|$text"
        val id = "rumble-${stable.sha256()}"
        val placeholder = parseRumbleShortcodes(text, channel.platformChannelId)
        val segments = parseContentSegments(text, placeholder)
        ChatMessage(platform, id, channel, ChatUser(platform, null, username, username, badges), text, timestamp,
            System.currentTimeMillis(), placeholder, badges, isEmoteOnly = isEmoteOnly(segments), rawMetadata = raw.toString(), contentSegments = segments)
    }.getOrNull()

    /* The official snapshot exposes shortcode text but no image catalog. Keeping shortcode
       boundaries structured means PULSE counts them consistently and a future catalog can fill URLs. */
    private fun parseRumbleShortcodes(text: String, ownerId: String): List<ChatEmote> =
        Regex(":([A-Za-z0-9_+\u002D]+):").findAll(text).map { match ->
            val code = match.groupValues[1]
            ChatEmote("rumble:$code", ":$code:", match.range.first, match.range.last, "", platform, ownerId)
        }.toList()

    private fun combined(latest: JSONObject?, recent: JSONArray?): List<JSONObject> = buildList {
        if (recent != null) repeat(recent.length()) { recent.optJSONObject(it)?.let(::add) }
        latest?.let(::add)
    }.distinctBy { "${it.optString("created_on")}|${it.optString("username")}|${it.optString("text")}" }

    private fun validateSnapshot(root: JSONObject) {
        require(root.has("livestreams") || root.has("followers") || root.has("subscribers")) {
            "That does not look like a Rumble connection link."
        }
    }

    private fun requestJson(url: String): JSONObject = client.newCall(Request.Builder().url(url)
        .header("Accept", "application/json").header("User-Agent", USER_AGENT).build()).execute().use {
        if (!it.isSuccessful) error("Rumble returned HTTP ${it.code}. Check or regenerate the connection link.")
        JSONObject(it.body?.string() ?: error("Rumble returned an empty response."))
    }

    private fun parseTime(value: String): Long = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(0)

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object { const val USER_AGENT = "nabchat/0.7 Android (read-only Rumble Live Stream API)" }
}

internal fun normalizeRumbleApiUrl(input: String): String {
    val raw = input.trim()
    val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("Paste the full Rumble connection link.") }
    val host = uri.host.orEmpty()
    require(uri.scheme.equals("https", true) && (host.equals("rumble.com", true) || host.endsWith(".rumble.com", true))) {
        "Use the connection link from the Rumble page opened above."
    }
    require(!uri.rawQuery.isNullOrBlank() || uri.path.contains("livestream", true)) {
        "That is a public channel link. Use the creator connection link from the Rumble page opened above."
    }
    return uri.toASCIIString()
}
