package com.nabchat.app.provider

import com.nabchat.app.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.random.Random

class FakeChatProvider : ChatProvider {
    override val platform = ChatPlatform.KICK
    override var autoReconnect: Boolean = true
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = mutableState.asStateFlow()

    override suspend fun resolveChannel(input: String): Result<ChatChannel> = runCatching {
        val slug = normalizeKickSlug(input)
        require(slug.matches(Regex("[a-z0-9_]{2,32}"))) { "Enter a valid Kick channel name." }
        ChatChannel(platform, "fake-$slug", "fake-$slug", slug, input.trim())
    }

    override fun observeMessages(channels: Set<ChatChannel>): Flow<ChatEvent> = flow {
        mutableState.value = ConnectionState.CONNECTED
        emit(ChatEvent.State(ConnectionState.CONNECTED, "Simulation"))
        val samples = listOf("that was wild", "hello chat!", "[emote:25:HYPE] [emote:25:HYPE]", "great stream", "no way 😄", "[emote:42:CLAP] nice play")
        var n = 0
        while (true) {
            if (channels.isEmpty()) { delay(1_000); continue }
            delay(Random.nextLong(650, 1_800))
            val channel = channels.elementAt(n % channels.size)
            val text = samples[n % samples.size]
            val emotes = Regex("\\[emote:(\\d+):([^]]+)]").findAll(text).map { ChatEmote(it.groupValues[1], it.groupValues[2], it.range.first, it.range.last) }.toList()
            val only = isEmoteOnly(parseContentSegments(text, emotes))
            val user = ChatUser(platform, "fake-user-${n % 9}", "viewer${n % 9}", "viewer${n % 9}", if (n % 7 == 0) listOf("subscriber") else emptyList())
            emit(ChatEvent.Message(ChatMessage(platform, UUID.randomUUID().toString(), channel, user, text, System.currentTimeMillis(), System.currentTimeMillis(), emotes, user.badges, isEmoteOnly = only)))
            n++
        }
    }.onCompletion { mutableState.value = ConnectionState.DISCONNECTED }

    override suspend fun connect() { mutableState.value = ConnectionState.CONNECTING }
    override suspend fun disconnect() { mutableState.value = ConnectionState.DISCONNECTED }
}
