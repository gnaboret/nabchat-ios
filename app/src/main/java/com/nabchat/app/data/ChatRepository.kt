package com.nabchat.app.data

import com.nabchat.app.BillingState
import com.nabchat.app.domain.*
import com.nabchat.app.provider.ChatProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.Writer
import java.time.Instant

data class AnalyticsSnapshot(
    val totalReceived: Long = 0, val totalDisplayed: Int = 0, val filtered: Long = 0,
    val messagesPerMinute: Int = 0, val uniqueChatters: Int = 0, val watchedChannels: Int = 0,
    val emoteOnly: Long = 0, val textMessages: Long = 0, val databaseMessages: Int = 0,
    val reconnects: Long = 0, val parseFailures: Long = 0
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatRepository(
    private val database: NabchatDatabase,
    private val settingsStore: SettingsStore,
    private val kick: ChatProvider,
    val twitch: com.nabchat.app.provider.TwitchChatProvider,
    private val youtube: ChatProvider,
    private val rumble: ChatProvider,
    private val fake: ChatProvider,
    private val billingState: StateFlow<BillingState>,
    private val scope: CoroutineScope
) {
    // Rumble is intentionally hidden rather than deleted: its public pages do not
    // expose the chat transport, and nabchat will not ask users for creator credentials.
    val channels = database.channels().observeAll().map { list ->
        list.map(ChannelEntity::toDomain).filterNot { it.platform == ChatPlatform.RUMBLE }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val hiddenUsers = database.hiddenUsers().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val userLabels = database.userLabels().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val savedChatters = database.savedChatters().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val settings = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, ChatSettings())
    private val ephemeralMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val savedMessageRows = MutableStateFlow<List<MessageEntity>>(emptyList())
    private val rawMessages = combine(savedMessageRows, channels) { rows, channelList ->
        val channelsByKey = channelList.associateBy { it.key() }
        rows.mapNotNull { row -> channelsByKey[row.channelKey]?.let(row::toDomain) }
    }.debounce(150)
    private val allMessages = combine(rawMessages, ephemeralMessages) { saved, ephemeral ->
        (saved + ephemeral).distinctBy { "${it.platform}:${it.messageId}" }.sortedByDescending { it.timestamp }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val channelActivityMutable = MutableStateFlow<Map<String, Long>>(emptyMap())
    val channelActivity = channelActivityMutable.asStateFlow()
    private val databaseCount = MutableStateFlow(0)
    private val runtimeReceived = MutableStateFlow(0L)
    private val runtimeReconnects = MutableStateFlow(0L)
    private val runtimeParseFailures = MutableStateFlow(0L)
    private val connectionMutable = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val historyLoaded = CompletableDeferred<Unit>()
    val connectionState = connectionMutable.asStateFlow()

    private val displayInputs = combine(allMessages, channels, hiddenUsers, userLabels) { messages, channelList, hidden, labels ->
        DisplayInputs(messages, channelList, hidden, labels)
    }
    private fun baseVisible(message: ChatMessage, input: DisplayInputs, prefs: ChatSettings): Boolean {
        val enabledKeys = input.channels.filter { it.enabled }.map { it.key() }.toSet()
        return message.channel.key() in enabledKeys && input.hidden.none { h ->
            h.platform == message.platform.name && (h.platformUserId?.let { it == message.sender.platformUserId } ?: h.username.equals(message.sender.username, true)) &&
                (h.scope == HiddenScope.EVERYWHERE.name || h.channelKey == message.channel.key())
        } && input.labels.none { it.matches(message) && it.label == "BOT" } &&
            (prefs.showLikelyBots || !ChatContentPolicy.isLikelyBot(message)) &&
            (prefs.showLikelySpam || !ChatContentPolicy.isLikelySpam(message, input.messages))
    }
    val emojiIndicatorMessages = combine(displayInputs, settings) { input, prefs ->
        input.messages.filter { baseVisible(it, input, prefs) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val displayedMessages = combine(displayInputs, settings) { input, prefs ->
        val messages = input.messages
        messages.filter { message -> baseVisible(message, input, prefs) && (prefs.showEmoteOnly || !message.isEmoteOnly) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val analyticsInputs = combine(displayedMessages, emojiIndicatorMessages, allMessages) { displayed, analyticsMessages, all -> Triple(displayed, analyticsMessages, all) }
    private val baseAnalytics = combine(analyticsInputs, channels, runtimeReceived, runtimeReconnects) { inputs, watched, received, reconnects ->
        val (displayed, analyticsMessages, all) = inputs
        val cutoff = System.currentTimeMillis() - 60_000
        AnalyticsSnapshot(received, displayed.size, (all.size - displayed.size).toLong(), displayed.count { it.timestamp >= cutoff }, analyticsMessages.map { it.sender.platformUserId ?: it.sender.username }.toSet().size,
            watched.count { it.enabled }, analyticsMessages.count { it.isEmoteOnly }.toLong(), analyticsMessages.count { !it.isEmoteOnly }.toLong(), 0, reconnects, runtimeParseFailures.value)
    }
    val analytics = baseAnalytics.combine(databaseCount) { snapshot, count -> snapshot.copy(databaseMessages = count) }
        .stateIn(scope, SharingStarted.Eagerly, AnalyticsSnapshot())

    init {
        scope.launch { twitch.validateSavedAuthorization() }
        scope.launch(Dispatchers.IO) {
            try {
                val loaded = database.messages().getRecent(LIVE_MESSAGES_IN_MEMORY)
                val initialCount = database.messages().count()
                val initialActivity = database.messages().getChannelActivity().associate { it.channelKey to it.latestActivity }
                savedMessageRows.update { current ->
                    (current + loaded).distinctBy(MessageEntity::key)
                        .sortedWith(compareByDescending<MessageEntity> { it.timestamp }.thenByDescending { it.receivedAt })
                        .take(LIVE_MESSAGES_IN_MEMORY)
                }
                databaseCount.value = initialCount
                channelActivityMutable.value = initialActivity
            } finally {
                historyLoaded.complete(Unit)
            }
        }
        scope.launch {
            historyLoaded.await()
            combine(channels, settings) { cs, prefs -> cs.filter { it.enabled }.toSet() to prefs }.collectLatest { (enabled, prefs) ->
                val providerGroups = if (prefs.fakeMode) listOf(fake to enabled) else listOf(
                    kick to enabled.filter { it.platform == ChatPlatform.KICK }.toSet(),
                    twitch to enabled.filter { it.platform == ChatPlatform.TWITCH }.toSet(),
                    youtube to enabled.filter { it.platform == ChatPlatform.YOUTUBE }.toSet()
                )
                coroutineScope { providerGroups.filter { it.second.isNotEmpty() }.forEach { (provider, providerChannels) -> launch {
                    provider.autoReconnect = prefs.autoReconnect; provider.connect()
                    try { provider.observeMessages(providerChannels).collect { event ->
                        when (event) {
                            is ChatEvent.Message -> {
                                runtimeReceived.value++
                                channelActivityMutable.update { current ->
                                    current + (event.message.channel.key() to maxOf(current[event.message.channel.key()] ?: 0L, event.message.timestamp, event.message.receivedAt))
                                }
                                if (prefs.saveHistory) {
                                    val entity = event.message.toEntity()
                                    val inserted = database.messages().insert(entity)
                                    if (inserted != -1L) databaseCount.update { it + 1 }
                                    savedMessageRows.update { current ->
                                        (listOf(entity) + current).distinctBy(MessageEntity::key).take(LIVE_MESSAGES_IN_MEMORY)
                                    }
                                }
                                else ephemeralMessages.update { current -> (listOf(event.message) + current).distinctBy { it.messageId }.take(2_000) }
                            }
                            is ChatEvent.State -> { if (event.state == ConnectionState.RECONNECTING) runtimeReconnects.value++; connectionMutable.value = event.state }
                            is ChatEvent.ParseFailure -> runtimeParseFailures.value++
                        }
                    } } finally { provider.disconnect() }
                } } }
            }
        }
    }

    suspend fun addChannel(input: String, platform: ChatPlatform = ChatPlatform.KICK): Result<Unit> {
        val provider = if (settings.value.fakeMode) fake else when (platform) {
            ChatPlatform.KICK -> kick
            ChatPlatform.TWITCH -> twitch
            ChatPlatform.YOUTUBE -> youtube
            ChatPlatform.RUMBLE -> rumble
        }
        return provider.resolveChannel(input).mapCatching { resolved ->
            val existing = database.channels().get(resolved.key())
            val order = existing?.sortOrder ?: (database.channels().maxSortOrder() + 1)
            val canEnable = existing?.enabled ?: (billingState.value.isPlus || channels.value.count { it.enabled } < FREE_CHANNEL_LIMIT)
            database.channels().upsert(resolved.copy(enabled = canEnable, sortOrder = order, favorite = existing?.favorite ?: false).toEntity())
        }
    }
    suspend fun setChannelEnabled(channel: ChatChannel, enabled: Boolean) {
        if (!enabled || channel.enabled || billingState.value.isPlus || channels.value.count { it.enabled } < FREE_CHANNEL_LIMIT) database.channels().setEnabled(channel.key(), enabled)
    }
    suspend fun setChannelFavorite(channel: ChatChannel, favorite: Boolean) = database.channels().setFavorite(channel.key(), favorite)
    suspend fun removeChannel(channel: ChatChannel) = database.channels().delete(channel.key())
    suspend fun reorderVisibleChannels(orderedEnabledKeys: List<String>) {
        val iterator = orderedEnabledKeys.iterator()
        val merged = channels.value.map { channel -> if (channel.enabled && iterator.hasNext()) iterator.next() else channel.key() }
        database.channels().reorder(merged)
    }
    suspend fun restoreChannels(restored: List<ChatChannel>) {
        var enabledCount = channels.value.count { it.enabled && restored.none { restoredChannel -> restoredChannel.key() == it.key() } }
        restored.sortedBy { it.sortOrder }.forEachIndexed { index, channel ->
            val enable = channel.enabled && (billingState.value.isPlus || enabledCount < FREE_CHANNEL_LIMIT)
            if (enable) enabledCount++
            database.channels().upsert(channel.copy(enabled = enable, sortOrder = index).toEntity())
        }
    }
    suspend fun hideUser(message: ChatMessage, scopeValue: HiddenScope) = database.hiddenUsers().insert(HiddenUserEntity(platform = message.platform.name, platformUserId = message.sender.platformUserId, username = message.sender.username, channelKey = if (scopeValue == HiddenScope.THIS_CHANNEL) message.channel.key() else null, scope = scopeValue.name))
    suspend fun unhide(entity: HiddenUserEntity) = database.hiddenUsers().delete(entity)
    suspend fun labelAsBot(message: ChatMessage) {
        val identity = message.sender.platformUserId ?: message.sender.username.lowercase()
        database.userLabels().upsert(UserLabelEntity("${message.platform}:$identity:BOT", message.platform.name, message.sender.platformUserId, message.sender.username, message.sender.displayName, "BOT"))
    }
    suspend fun removeLabel(entity: UserLabelEntity) = database.userLabels().delete(entity)
    suspend fun saveChatter(message: ChatMessage) {
        val key = savedChatterKey(message.platform, message.sender.platformUserId, message.sender.username)
        database.savedChatters().upsert(SavedChatterEntity(key, message.platform.name, message.sender.platformUserId, message.sender.username, message.sender.displayName, DEFAULT_CHATTER_COLOR))
    }
    suspend fun saveChannelAsChatter(channel: ChatChannel) {
        val username = channel.slug.trim('/').substringAfterLast('/').substringBefore('?').ifBlank { channel.displayName }
        val key = savedChatterKey(channel.platform, channel.platformChannelId, username)
        database.savedChatters().upsert(SavedChatterEntity(key, channel.platform.name, channel.platformChannelId, username, channel.displayName, DEFAULT_CHATTER_COLOR))
    }
    suspend fun removeSavedChatter(chatter: SavedChatterEntity) = database.savedChatters().delete(chatter)
    suspend fun setSavedChatterColor(chatter: SavedChatterEntity, colorArgb: Long) = database.savedChatters().setColor(chatter.key, colorArgb)
    suspend fun getAllChatsFor(message: ChatMessage): List<ChatMessage> = withContext(Dispatchers.IO) {
        val knownChannels = channels.value.associateBy { it.key() }
        database.messages().getAllForUser(message.platform.name, message.sender.platformUserId, message.sender.username).map { row ->
            val fallbackId = row.channelKey.substringAfter(':', row.channelKey)
            row.toDomain(knownChannels[row.channelKey] ?: ChatChannel(ChatPlatform.valueOf(row.platform), fallbackId, null, fallbackId, fallbackId))
        }
    }
    suspend fun getAllChatsFor(channel: ChatChannel): List<ChatMessage> = withContext(Dispatchers.IO) {
        database.messages().getAllForChannel(channel.key()).map { it.toDomain(channel) }
    }
    suspend fun updateSettings(value: ChatSettings) = settingsStore.update(value)
    suspend fun resetAnalytics() {
        database.messages().deleteAll()
        savedMessageRows.value = emptyList()
        ephemeralMessages.value = emptyList()
        channelActivityMutable.value = emptyMap()
        databaseCount.value = 0
        runtimeReceived.value = 0
        runtimeReconnects.value = 0
        runtimeParseFailures.value = 0
    }

    /** Streams every persisted message in chronological order without loading the full history. */
    suspend fun exportChatLog(writer: Writer): Int = withContext(Dispatchers.IO) {
        exportChatLog(writer, null)
    }

    suspend fun exportChatterChatLog(writer: Writer, chatter: SavedChatterEntity): Int = withContext(Dispatchers.IO) {
        exportChatLog(writer) { message ->
            message.platform == chatter.platform && (chatter.platformUserId?.let { it == message.platformUserId }
                ?: chatter.username.equals(message.username, ignoreCase = true))
        }
    }

    private suspend fun exportChatLog(writer: Writer, include: ((MessageEntity) -> Boolean)?): Int {
        return writer.use { output ->
            output.appendLine("timestamp_utc,received_at_utc,platform,channel_key,username,display_name,platform_user_id,message,is_emote_only,emotes_json,badges_json,reply_json,raw_metadata")
            var offset = 0
            var exported = 0
            while (true) {
                val page = database.messages().exportPage(CHAT_EXPORT_PAGE_SIZE, offset)
                if (page.isEmpty()) break
                page.filter { include?.invoke(it) != false }.forEach { message ->
                    output.appendLine(listOf(
                    Instant.ofEpochMilli(message.timestamp).toString(), Instant.ofEpochMilli(message.receivedAt).toString(),
                    message.platform, message.channelKey, message.username, message.displayName, message.platformUserId.orEmpty(),
                    message.text, message.isEmoteOnly.toString(), message.emotesJson, message.badgesJson,
                    message.replyJson.orEmpty(), message.rawMetadata.orEmpty()
                    ).joinToString(",", transform = ::csvCell))
                    exported++
                }
                offset += page.size
                if (page.size < CHAT_EXPORT_PAGE_SIZE) break
            }
            output.flush()
            exported
        }
    }

    private data class DisplayInputs(val messages: List<ChatMessage>, val channels: List<ChatChannel>, val hidden: List<HiddenUserEntity>, val labels: List<UserLabelEntity>)
    private fun UserLabelEntity.matches(message: ChatMessage): Boolean = platform == message.platform.name &&
        (platformUserId?.let { it == message.sender.platformUserId } ?: username.equals(message.sender.username, true))

    private companion object { const val LIVE_MESSAGES_IN_MEMORY = 2_500; const val CHAT_EXPORT_PAGE_SIZE = 500; const val FREE_CHANNEL_LIMIT = 6; const val DEFAULT_CHATTER_COLOR = 0xFFFFB74DL }
}

internal fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

fun savedChatterKey(platform: ChatPlatform, platformUserId: String?, username: String) = "${platform.name}:${platformUserId ?: username.lowercase()}"
