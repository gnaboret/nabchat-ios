package com.nabchat.app.provider

import android.content.Context
import com.nabchat.app.BuildConfig
import com.nabchat.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

data class TwitchDeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresIn: Int, val interval: Int)
data class TwitchAuthState(val connected: Boolean = false, val login: String? = null, val message: String? = null)

class TwitchChatProvider(context: Context) : ChatProvider {
    override val platform = ChatPlatform.TWITCH
    override var autoReconnect = true
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("twitch_auth", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = mutableState.asStateFlow()
    private var token: String? = prefs.getString("access_token", null)
    private var refreshToken: String? = prefs.getString("refresh_token", null)
    private var authUserId: String? = prefs.getString("user_id", null)
    private val authMutable = MutableStateFlow(TwitchAuthState(message = if (token != null) "Checking saved authorization…" else null))
    val authState = authMutable.asStateFlow()
    private var activeChannels: Map<String, ChatChannel> = emptyMap()
    private val chatterAvatars = ConcurrentHashMap<String, String>()
    private val pendingAvatarLookups = ConcurrentHashMap.newKeySet<String>()

    suspend fun startDeviceAuthorization(): Result<TwitchDeviceCode> = withContext(Dispatchers.IO) { runCatching {
        val body = FormBody.Builder().add("client_id", BuildConfig.TWITCH_CLIENT_ID).add("scopes", "user:read:chat").build()
        val json = requestJson(Request.Builder().url("https://id.twitch.tv/oauth2/device").post(body).build())
        TwitchDeviceCode(json.getString("device_code"), json.getString("user_code"), json.getString("verification_uri"), json.getInt("expires_in"), json.optInt("interval", 5))
    } }

    suspend fun awaitDeviceAuthorization(code: TwitchDeviceCode): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        val deadline = System.currentTimeMillis() + code.expiresIn * 1_000L
        while (System.currentTimeMillis() < deadline) {
            delay(code.interval.coerceAtLeast(3) * 1_000L)
            val body = FormBody.Builder().add("client_id", BuildConfig.TWITCH_CLIENT_ID).add("scopes", "user:read:chat").add("device_code", code.deviceCode).add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
            val response = client.newCall(Request.Builder().url("https://id.twitch.tv/oauth2/token").post(body).build()).execute()
            response.use {
                val text = it.body?.string().orEmpty()
                if (it.isSuccessful) {
                    val json = JSONObject(text)
                    token = json.getString("access_token")
                    refreshToken = json.optString("refresh_token").takeIf { value -> value.isNotBlank() }
                    persistCredentials()
                    validateWithRefresh()
                    return@runCatching
                }
                if (!text.contains("authorization_pending")) error(JSONObject(text).optString("message", "Twitch sign-in failed"))
            }
        }
        error("Twitch sign-in code expired")
    } }

    fun disconnectAccount() { token = null; refreshToken = null; authUserId = null; prefs.edit().clear().apply(); authMutable.value = TwitchAuthState() }

    suspend fun validateSavedAuthorization(): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        if (token == null && refreshToken == null) { authMutable.value = TwitchAuthState(); return@runCatching }
        validateWithRefresh()
    }.onFailure { error ->
        token = null
        authUserId = null
        prefs.edit().remove("access_token").remove("user_id").apply()
        authMutable.value = TwitchAuthState(message = "Authorization required · ${error.message ?: "connect again"}")
    } }

    override suspend fun resolveChannel(input: String): Result<ChatChannel> = withContext(Dispatchers.IO) { runCatching {
        val slug = input.trim().removePrefix("@").substringAfterLast("twitch.tv/").trimEnd('/').lowercase()
        require(slug.matches(Regex("[a-z0-9_]{2,32}"))) { "Enter a valid Twitch username or channel URL." }
        val access = token ?: return@runCatching ChatChannel(platform, "pending:$slug", null, slug, slug)
        val json = helix("https://api.twitch.tv/helix/users?login=$slug", access)
        val user = json.getJSONArray("data").optJSONObject(0) ?: error("Twitch channel not found.")
        ChatChannel(platform, user.getString("id"), user.getString("id"), user.getString("login"), user.getString("display_name"), user.optString("profile_image_url").takeIf(String::isNotBlank))
    } }

    override fun observeMessages(channels: Set<ChatChannel>): Flow<ChatEvent> = callbackFlow {
        val access = token
        val userId = authUserId
        if (access == null || userId == null) { trySend(ChatEvent.State(ConnectionState.ERROR, "Twitch authorization required in Settings")); close(); return@callbackFlow }
        val subscriptions = withContext(Dispatchers.IO) {
            channels.mapNotNull { saved ->
                if (!saved.platformChannelId.startsWith("pending:")) saved.platformChannelId to saved
                else runCatching {
                    val user = helix("https://api.twitch.tv/helix/users?login=${saved.slug}", access).getJSONArray("data").optJSONObject(0)
                        ?: error("Twitch channel @${saved.slug} was not found")
                    user.getString("id") to saved
                }.getOrNull()
            }
        }
        activeChannels = subscriptions.toMap()
        mutableState.value = ConnectionState.CONNECTING
        var socket: WebSocket? = null
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text); val type = root.getJSONObject("metadata").getString("message_type")
                    when (type) {
                        "session_welcome" -> {
                            val sessionId = root.getJSONObject("payload").getJSONObject("session").getString("id")
                            subscriptions.forEach { (channelId, _) -> subscribe(channelId, userId, sessionId, access) }
                            mutableState.value = ConnectionState.CONNECTED; trySend(ChatEvent.State(ConnectionState.CONNECTED, "Twitch EventSub"))
                        }
                        "notification" -> parseNotification(root, this@callbackFlow, access)?.let { trySend(ChatEvent.Message(it)) }
                        "session_reconnect" -> root.getJSONObject("payload").getJSONObject("session").optString("reconnect_url").takeIf { it.isNotBlank() }?.let { socket = client.newWebSocket(Request.Builder().url(it).build(), this) }
                    }
                }.onFailure {
                    val message = it.message ?: "Twitch authorization failed"
                    if (message.contains("OAuth", true) || message.contains("token", true) || message.contains("401")) {
                        authMutable.value = TwitchAuthState(message = "Authorization required · reconnect Twitch")
                        trySend(ChatEvent.State(ConnectionState.ERROR, "Twitch authorization required in Settings"))
                    } else trySend(ChatEvent.ParseFailure(platform, message))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { mutableState.value = ConnectionState.RECONNECTING; trySend(ChatEvent.State(ConnectionState.RECONNECTING, t.message)) }
        }
        socket = client.newWebSocket(Request.Builder().url("wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30").build(), listener)
        awaitClose { socket?.cancel(); mutableState.value = ConnectionState.DISCONNECTED }
    }

    private fun subscribe(channelId: String, userId: String, sessionId: String, access: String) {
        val body = JSONObject().put("type", "channel.chat.message").put("version", "1")
            .put("condition", JSONObject().put("broadcaster_user_id", channelId).put("user_id", userId))
            .put("transport", JSONObject().put("method", "websocket").put("session_id", sessionId))
        client.newCall(helixRequest("https://api.twitch.tv/helix/eventsub/subscriptions", access).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            if (!response.isSuccessful) error("Twitch OAuth/subscription error ${response.code}: ${response.body?.string().orEmpty()}")
        }
    }

    private fun parseNotification(root: JSONObject, flowScope: CoroutineScope, access: String): ChatMessage? {
        val event = root.getJSONObject("payload").getJSONObject("event")
        val channelId = event.getString("broadcaster_user_id")
        val channel = activeChannels[channelId] ?: ChatChannel(platform, channelId, channelId, event.getString("broadcaster_user_login"), event.getString("broadcaster_user_name"))
        val fragments = event.getJSONObject("message").getJSONArray("fragments")
        val segments = mutableListOf<ChatContentSegment>(); val emotes = mutableListOf<ChatEmote>()
        repeat(fragments.length()) { i -> val f = fragments.getJSONObject(i); val e = f.optJSONObject("emote"); if (e != null) { val id=e.getString("id"); val emote=ChatEmote(id,f.getString("text"),null,null,"https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/3.0",platform,channelId); emotes+=emote; segments+=ChatContentSegment.EmoteSegment(emote) } else segments+=ChatContentSegment.TextSegment(f.optString("text")) }
        val badgesArray=event.optJSONArray("badges"); val badges=mutableListOf<String>(); if(badgesArray!=null) repeat(badgesArray.length()){ badges+=badgesArray.getJSONObject(it).getString("set_id") }
        val text=event.getJSONObject("message").getString("text"); val now=System.currentTimeMillis()
        val chatterId = event.getString("chatter_user_id")
        val avatar = chatterAvatars[chatterId]
        if (avatar == null && pendingAvatarLookups.size < 20 && pendingAvatarLookups.add(chatterId)) flowScope.launch(Dispatchers.IO) {
            runCatching {
                val user = helix("https://api.twitch.tv/helix/users?id=$chatterId", access).getJSONArray("data").optJSONObject(0)
                user?.optString("profile_image_url")?.takeIf { it.isNotBlank() }?.let { chatterAvatars[chatterId] = it }
                if (chatterAvatars.size > 2_000) chatterAvatars.clear()
            }
            pendingAvatarLookups.remove(chatterId)
        }
        return ChatMessage(platform,event.getString("message_id"),channel,ChatUser(platform,chatterId,event.getString("chatter_user_login"),event.getString("chatter_user_name"),badges,avatar),text,now,now,emotes,badges,isEmoteOnly=isEmoteOnly(segments),rawMetadata=event.toString(),contentSegments=segments)
    }

    private fun helix(url: String, access: String) = requestJson(helixRequest(url, access).build())
    private fun helixRequest(url: String, access: String) = Request.Builder().url(url).header("Client-Id", BuildConfig.TWITCH_CLIENT_ID).header("Authorization", "Bearer $access")
    private fun requestJson(request: Request): JSONObject = client.newCall(request).execute().use { response -> val text=response.body?.string().orEmpty(); if(!response.isSuccessful) error(JSONObject(text).optString("message", "HTTP ${response.code}")); JSONObject(text) }
    private fun persistCredentials(login: String? = null) {
        prefs.edit()
            .putString("access_token", token)
            .putString("refresh_token", refreshToken)
            .putString("user_id", authUserId)
            .apply { login?.let { putString("login", it) } }
            .apply()
    }

    private fun refreshAccessToken() {
        val refresh = refreshToken ?: error("Twitch authorization expired. Please connect Twitch once more.")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", BuildConfig.TWITCH_CLIENT_ID)
            .build()
        val json = requestJson(Request.Builder().url("https://id.twitch.tv/oauth2/token").post(body).build())
        token = json.getString("access_token")
        refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh
        persistCredentials()
    }

    private fun validate() {
        val access = token ?: error("Connect Twitch in Settings first.")
        val json = requestJson(Request.Builder().url("https://id.twitch.tv/oauth2/validate").header("Authorization", "OAuth $access").build())
        authUserId = json.getString("user_id")
        val login = json.optString("login").takeIf { it.isNotBlank() }
        persistCredentials(login)
        authMutable.value = TwitchAuthState(true, login, "Authorization verified")
    }

    private fun validateWithRefresh() {
        runCatching { validate() }.getOrElse {
            refreshAccessToken()
            validate()
        }
    }

    override suspend fun connect() { validateSavedAuthorization() }
    override suspend fun disconnect() { mutableState.value=ConnectionState.DISCONNECTED }
}
