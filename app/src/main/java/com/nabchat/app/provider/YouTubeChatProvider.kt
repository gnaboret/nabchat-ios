package com.nabchat.app.provider

import com.nabchat.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Read-only public YouTube live chat. No Google account or password is required. */
class YouTubeChatProvider : ChatProvider {
    override val platform = ChatPlatform.YOUTUBE
    override var autoReconnect = true
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = mutableState.asStateFlow()
    private var connected = false
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun resolveChannel(input: String): Result<ChatChannel> = withContext(Dispatchers.IO) { runCatching {
        val target = normalizeYouTubeTarget(input)
        val pageUrl = when {
            target.videoId != null -> "https://www.youtube.com/watch?v=${target.videoId}"
            target.path != null -> "https://www.youtube.com/${target.path}/live"
            else -> error("Enter a YouTube channel, handle, or live-video URL.")
        }
        val page = getText(pageUrl)
        val details = extractJsonObject(page, "ytInitialPlayerResponse")?.optJSONObject("videoDetails")
        val videoId = details?.takeIf { it.optBoolean("isLiveContent", false) }
            ?.optString("videoId")?.takeIf { it.isNotBlank() }
        val channelId = details?.optString("channelId")?.takeIf { it.isNotBlank() }
            ?: Regex("<meta[^>]+itemprop=[\"']channelId[\"'][^>]+content=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(page)?.groupValues?.get(1)
            ?: Regex("\"(?:channelId|externalId|browseId)\":\"(UC[A-Za-z0-9_-]+)\"").find(page)?.groupValues?.get(1)
            ?: error("YouTube did not identify the channel for that live video.")
        // og:title is the broadcast title, not the creator. Prefer channel-owner
        // metadata and fall back to the stable handle supplied by the user.
        val displayName = details?.optString("author")?.takeIf { it.isNotBlank() }
            ?: Regex("\"ownerChannelName\":\"((?:\\\\.|[^\"])*)\"").find(page)?.groupValues?.get(1)?.let(::decodeJsonString)
            ?: Regex("\"author\":\"((?:\\\\.|[^\"])*)\"").find(page)?.groupValues?.get(1)?.let(::decodeJsonString)
            ?: target.label
        val avatar = Regex("https://yt3\\.ggpht\\.com/[^\\\"? ]+").find(page)?.value
        // Store a stable channel path, not the temporary live-video URL. An offline
        // channel can therefore be added now and rediscovered when its next stream starts.
        val stablePath = target.path ?: "channel/$channelId"
        ChatChannel(platform, channelId, videoId, stablePath, displayName, avatar)
    }.recoverCatching { cause ->
        throw IllegalStateException(cause.message ?: "Could not resolve that YouTube live channel.", cause)
    } }

    override fun observeMessages(channels: Set<ChatChannel>): Flow<ChatEvent> = channelFlow {
        if (channels.isEmpty()) { send(ChatEvent.State(ConnectionState.DISCONNECTED)); return@channelFlow }
        val sessions = mutableMapOf<String, LiveSession>()
        var failures = 0
        while (currentCoroutineContext().isActive && connected) {
            try {
                mutableState.value = if (failures == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING
                var nextDelay = 5_000L
                var successfulChannels = 0
                var lastChannelError: String? = null
                channels.forEach { channel ->
                    val channelKey = "${channel.platform.name}:${channel.platformChannelId}"
                    try {
                        val videoId = discoverLiveVideoId(channel)
                        if (videoId == null) {
                            sessions.remove(channelKey)
                            nextDelay = maxOf(nextDelay, 20_000L)
                            return@forEach
                        }
                        val existing = sessions[channelKey]
                        val session = if (existing?.videoId == videoId) existing else openSession(videoId).also { sessions[channelKey] = it }
                        val response = poll(session)
                        response.messages.forEach { send(ChatEvent.Message(it.copy(channel = channel.copy(chatroomId = videoId)))) }
                        sessions[channelKey] = session.copy(continuation = response.continuation ?: session.continuation)
                        nextDelay = min(nextDelay, response.timeoutMillis.coerceIn(2_000L, 10_000L))
                        successfulChannels++
                    } catch (error: Exception) {
                        sessions.remove(channelKey)
                        lastChannelError = "YouTube ${channel.displayName}: ${error.message ?: "live chat unavailable"}"
                        nextDelay = maxOf(nextDelay, 10_000L)
                    }
                }
                if (successfulChannels > 0) {
                    if (mutableState.value != ConnectionState.CONNECTED) send(ChatEvent.State(ConnectionState.CONNECTED, "YouTube public live chat"))
                    mutableState.value = ConnectionState.CONNECTED
                    failures = 0
                } else {
                    failures++
                    mutableState.value = ConnectionState.RECONNECTING
                    send(ChatEvent.State(ConnectionState.RECONNECTING, lastChannelError ?: "YouTube: waiting for a live stream with chat enabled"))
                }
                delay(nextDelay)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                failures++
                sessions.clear()
                if (!autoReconnect) { send(ChatEvent.State(ConnectionState.ERROR, error.message)); break }
                send(ChatEvent.State(ConnectionState.RECONNECTING, error.message))
                delay(min(30_000L, 1_000L shl min(failures, 5)))
            }
        }
    }.flowOn(Dispatchers.IO).onCompletion { mutableState.value = ConnectionState.DISCONNECTED }

    override suspend fun connect() { connected = true; mutableState.value = ConnectionState.CONNECTING }
    override suspend fun disconnect() { connected = false; mutableState.value = ConnectionState.DISCONNECTED }

    private fun discoverLiveVideoId(channel: ChatChannel): String? {
        val page = getText("https://www.youtube.com/${channel.slug.trim('/')}/live")
        val details = extractJsonObject(page, "ytInitialPlayerResponse")?.optJSONObject("videoDetails") ?: return null
        return details.takeIf { it.optBoolean("isLiveContent", false) }
            ?.optString("videoId")?.takeIf { it.isNotBlank() }
    }

    private fun openSession(videoId: String): LiveSession {
        val watchPage = getText("https://www.youtube.com/watch?v=$videoId")
        val chatPage = getText("https://www.youtube.com/live_chat?v=$videoId&is_popout=1")
        val page = chatPage + watchPage
        val key = Regex("\\\"INNERTUBE_API_KEY\\\":\\\"([^\\\"]+)\\\"").find(page)?.groupValues?.get(1)
            ?: error("YouTube did not provide a live-chat API key.")
        val version = Regex("\\\"INNERTUBE_CLIENT_VERSION\\\":\\\"([^\\\"]+)\\\"").find(page)?.groupValues?.get(1) ?: "2.20260901.00.00"
        val initial = extractJsonObject(chatPage, "ytInitialData")
            ?: extractJsonObject(watchPage, "ytInitialData")
            ?: error("YouTube live-chat data was unavailable.")
        val liveChat = findObject(initial, "liveChatContinuation") ?: findObject(initial, "liveChatRenderer")
            ?: error(findMessageText(initial) ?: "Live chat is unavailable or disabled for this stream.")
        val continuation = findContinuation(liveChat) ?: error("Live chat is unavailable or disabled for this stream.")
        val visitorData = Regex("\\\"VISITOR_DATA\\\":\\\"([^\\\"]+)\\\"").find(page)?.groupValues?.get(1)
            ?: findString(initial, "visitorData")
        return LiveSession(key, version, continuation, visitorData, videoId)
    }

    private fun poll(session: LiveSession): PollResult {
        val clientContext = JSONObject().put("clientName", "WEB").put("clientVersion", session.clientVersion)
        session.visitorData?.let { clientContext.put("visitorData", it) }
        val body = JSONObject().put("context", JSONObject().put("client", clientContext))
            .put("continuation", session.continuation).toString()
        val request = Request.Builder().url("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=${session.apiKey}")
            .header("User-Agent", USER_AGENT).header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/watch?v=${session.videoId}")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", session.clientVersion)
            .apply { session.visitorData?.let { header("X-Goog-Visitor-Id", it) } }
            .post(body.toRequestBody(JSON_MEDIA)).build()
        val root = executeJson(request)
        val continuation = findContinuation(root)
        val timeout = findLong(root, "timeoutMs") ?: 5_000L
        return PollResult(collectRenderers(root).mapNotNull(::parseRenderer), continuation, timeout)
    }

    private fun parseRenderer(renderer: JSONObject): ChatMessage? = runCatching {
        val id = renderer.optString("id").ifBlank { return null }
        val author = renderer.optJSONObject("authorName")?.optString("simpleText").orEmpty().ifBlank { "YouTube user" }.removePrefix("@")
        val authorId = renderer.optString("authorExternalChannelId").takeIf { it.isNotBlank() }
        val authorThumbnails = renderer.optJSONObject("authorPhoto")?.optJSONArray("thumbnails")
        val authorAvatar = authorThumbnails?.optJSONObject((authorThumbnails.length() - 1).coerceAtLeast(0))?.optString("url")?.takeIf { it.isNotBlank() }
        val badges = collectKeys(renderer.optJSONArray("authorBadges"), mutableListOf())
        val runs = renderer.optJSONObject("message")?.optJSONArray("runs") ?: JSONArray()
        val text = StringBuilder()
        val emotes = mutableListOf<ChatEmote>()
        repeat(runs.length()) { index ->
            val run = runs.optJSONObject(index) ?: return@repeat
            val emoji = run.optJSONObject("emoji")
            if (emoji == null) text.append(run.optString("text")) else {
                val shortcuts = emoji.optJSONArray("shortcuts")
                val name = shortcuts?.optString(0)?.takeIf { it.isNotBlank() } ?: emoji.optString("emojiId")
                val start = text.length
                text.append(name)
                val thumbnails = emoji.optJSONObject("image")?.optJSONArray("thumbnails")
                val imageUrl = thumbnails?.optJSONObject((thumbnails.length() - 1).coerceAtLeast(0))?.optString("url").orEmpty()
                emotes += ChatEmote(emoji.optString("emojiId", name), name, start, text.length - 1, imageUrl, platform)
            }
        }
        val timestamp = renderer.optString("timestampUsec").toLongOrNull()?.div(1_000) ?: System.currentTimeMillis()
        val placeholder = ChatChannel(platform, "", null, "", "")
        val segments = parseContentSegments(text.toString(), emotes)
        ChatMessage(platform, id, placeholder, ChatUser(platform, authorId, author, author, badges, authorAvatar), text.toString(), timestamp, System.currentTimeMillis(), emotes, badges, isEmoteOnly = isEmoteOnly(segments), rawMetadata = renderer.toString(), contentSegments = segments)
    }.getOrNull()

    private fun collectRenderers(value: Any?, result: MutableList<JSONObject> = mutableListOf()): List<JSONObject> {
        when (value) {
            is JSONObject -> {
                listOf("liveChatTextMessageRenderer", "liveChatPaidMessageRenderer", "liveChatMembershipItemRenderer").forEach { key -> value.optJSONObject(key)?.let(result::add) }
                value.keys().forEach { collectRenderers(value.opt(it), result) }
            }
            is JSONArray -> repeat(value.length()) { collectRenderers(value.opt(it), result) }
        }
        return result
    }

    private fun findContinuation(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                listOf("timedContinuationData", "invalidationContinuationData", "reloadContinuationData").forEach { key ->
                    value.optJSONObject(key)?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { return it }
                }
                value.keys().forEach { findContinuation(value.opt(it))?.let { found -> return found } }
            }
            is JSONArray -> repeat(value.length()) { findContinuation(value.opt(it))?.let { found -> return found } }
        }
        return null
    }

    private fun findObject(value: Any?, key: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject(key)?.let { return it }
                value.keys().forEach { findObject(value.opt(it), key)?.let { found -> return found } }
            }
            is JSONArray -> repeat(value.length()) { findObject(value.opt(it), key)?.let { found -> return found } }
        }
        return null
    }

    private fun findLong(value: Any?, key: String): Long? {
        when (value) {
            is JSONObject -> { if (value.has(key)) return value.optLong(key); value.keys().forEach { findLong(value.opt(it), key)?.let { found -> return found } } }
            is JSONArray -> repeat(value.length()) { findLong(value.opt(it), key)?.let { found -> return found } }
        }
        return null
    }

    private fun findString(value: Any?, key: String): String? {
        when (value) {
            is JSONObject -> { value.optString(key).takeIf { it.isNotBlank() }?.let { return it }; value.keys().forEach { findString(value.opt(it), key)?.let { found -> return found } } }
            is JSONArray -> repeat(value.length()) { findString(value.opt(it), key)?.let { found -> return found } }
        }
        return null
    }

    private fun findMessageText(value: Any?): String? {
        val renderer = findObject(value, "messageRenderer") ?: return null
        return renderer.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
    }

    private fun collectKeys(value: Any?, result: MutableList<String>): List<String> {
        when (value) {
            is JSONObject -> { value.keys().forEach { key -> if (key.endsWith("BadgeRenderer")) result += key.removeSuffix("Renderer"); collectKeys(value.opt(key), result) } }
            is JSONArray -> repeat(value.length()) { collectKeys(value.opt(it), result) }
        }
        return result
    }

    private fun getText(url: String): String = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept-Language", "en-US,en;q=0.9").header("Cookie", "CONSENT=YES+cb").build()).execute().use {
        if (!it.isSuccessful) error("YouTube returned HTTP ${it.code}")
        it.body?.string() ?: error("YouTube returned an empty response.")
    }

    private fun executeJson(request: Request): JSONObject = client.newCall(request).execute().use {
        if (!it.isSuccessful) error("YouTube returned HTTP ${it.code}")
        JSONObject(it.body?.string() ?: error("YouTube returned an empty response."))
    }

    private data class LiveSession(val apiKey: String, val clientVersion: String, val continuation: String, val visitorData: String?, val videoId: String)
    private data class PollResult(val messages: List<ChatMessage>, val continuation: String?, val timeoutMillis: Long)
    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        // YouTube does not expose its live-chat document to the mobile web client.
        // Request the desktop surface even though nabchat itself runs on Android.
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}

internal data class YouTubeTarget(val videoId: String?, val path: String?, val label: String)

internal fun normalizeYouTubeTarget(input: String): YouTubeTarget {
    val raw = input.trim().substringBefore('#')
    val videoId = Regex("(?:youtu\\.be/|[?&]v=|/live/)([A-Za-z0-9_-]{11})", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
    if (videoId != null) return YouTubeTarget(videoId, null, videoId)
    val cleaned = raw.replace(Regex("(?i)^https?://(www\\.)?youtube\\.com/"), "").trim('/').removeSuffix("/live")
    val path = when {
        cleaned.startsWith("@") -> cleaned.substringBefore('/')
        cleaned.startsWith("channel/") || cleaned.startsWith("c/") || cleaned.startsWith("user/") -> cleaned.substringBefore('?')
        cleaned.isNotBlank() && !cleaned.contains('/') -> "@${cleaned.removePrefix("@")}" 
        else -> null
    }
    return YouTubeTarget(null, path, path?.substringAfterLast('/')?.removePrefix("@").orEmpty().ifBlank { "YouTube" })
}

internal fun extractJsonObject(source: String, variable: String): JSONObject? {
    val escapedVariable = Regex.escape(variable)
    val marker = listOf(
        Regex("(?:(?:var\\s+)?$escapedVariable|window\\[\\\"$escapedVariable\\\"\\])\\s*=\\s*", RegexOption.IGNORE_CASE),
        Regex("\\\"$escapedVariable\\\"\\s*:\\s*", RegexOption.IGNORE_CASE)
    ).mapNotNull { it.find(source) }.minByOrNull { it.range.first } ?: return null
    val start = source.indexOf('{', marker.range.last + 1).takeIf { it >= 0 } ?: return null
    var depth = 0
    var quoted = false
    var escaped = false
    for (index in start until source.length) {
        val char = source[index]
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{' -> depth++
            '}' -> if (--depth == 0) return runCatching { JSONObject(source.substring(start, index + 1)) }.getOrNull()
        }
    }
    return null
}

internal fun decodeJsonString(value: String): String = Regex("\\\\u([0-9a-fA-F]{4})").replace(value) {
    it.groupValues[1].toInt(16).toChar().toString()
}.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
    .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
