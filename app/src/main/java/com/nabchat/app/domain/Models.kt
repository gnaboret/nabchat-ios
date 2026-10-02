package com.nabchat.app.domain

enum class ChatPlatform { KICK, TWITCH, YOUTUBE, RUMBLE }
enum class HiddenScope { THIS_CHANNEL, EVERYWHERE }
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }
enum class FeedLayoutMode { RIVER, ROOMS, RUG }

const val CHANNEL_INACTIVITY_HIDE_MILLIS: Long = 28L * 60L * 1_000L

fun isChannelRecentlyActive(channel: ChatChannel, latestActivity: Long?, nowMillis: Long): Boolean =
    nowMillis - (latestActivity ?: channel.dateAdded) < CHANNEL_INACTIVITY_HIDE_MILLIS

data class ChatChannel(
    val platform: ChatPlatform,
    val platformChannelId: String,
    val chatroomId: String?,
    val slug: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val enabled: Boolean = true,
    val dateAdded: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val favorite: Boolean = false
)

/** Public viewing destinations only. Rumble's slug currently contains a private creator API URL. */
fun ChatChannel.publicWatchUrl(): String? = when (platform) {
    ChatPlatform.KICK -> "https://kick.com/${slug.trim('/')}"
    ChatPlatform.TWITCH -> "https://www.twitch.tv/${slug.trim('/')}"
    ChatPlatform.YOUTUBE -> chatroomId?.let { "https://www.youtube.com/watch?v=$it" }
    ChatPlatform.RUMBLE -> null
}

data class ChatUser(
    val platform: ChatPlatform,
    val platformUserId: String?,
    val username: String,
    val displayName: String,
    val badges: List<String> = emptyList(),
    val avatarUrl: String? = null
)

fun ChatUser.isVerified(): Boolean = badges.any {
    it.equals("verified", ignoreCase = true) || it.equals("verified_user", ignoreCase = true)
}

data class ChatEmote(
    val id: String,
    val name: String,
    val start: Int?,
    val end: Int?,
    val imageUrl: String = "https://files.kick.com/emotes/$id/fullsize",
    val platform: ChatPlatform = ChatPlatform.KICK,
    val ownerChannelId: String? = null
)
sealed interface ChatContentSegment {
    data class TextSegment(val text: String) : ChatContentSegment
    data class EmoteSegment(val emote: ChatEmote) : ChatContentSegment
}
data class ReplyInfo(val messageId: String?, val sender: String?, val text: String?)

data class ChatMessage(
    val platform: ChatPlatform,
    val messageId: String,
    val channel: ChatChannel,
    val sender: ChatUser,
    val text: String,
    val timestamp: Long,
    val receivedAt: Long,
    val emotes: List<ChatEmote> = emptyList(),
    val badges: List<String> = emptyList(),
    val reply: ReplyInfo? = null,
    val isEmoteOnly: Boolean = false,
    val rawMetadata: String? = null,
    val contentSegments: List<ChatContentSegment> = parseContentSegments(text, emotes)
)

data class StreamSession(
    val platform: ChatPlatform,
    val channelId: String,
    val sessionId: String?,
    val streamStartedAt: Long?,
    val firstObservedAt: Long,
    val lastObservedAt: Long
)

sealed interface ChatEvent {
    data class Message(val message: ChatMessage) : ChatEvent
    data class State(val state: ConnectionState, val detail: String? = null) : ChatEvent
    data class ParseFailure(val provider: ChatPlatform, val detail: String) : ChatEvent
}

/**
 * Two projections over the exact same message instances. Stable message IDs, source channels,
 * and timestamps are preserved so a future position-aware transition can track each element.
 */
data class ChatFeedLayouts(
    val river: List<ChatMessage>,
    val rooms: List<ChannelRoom>
) {
    companion object {
        fun from(messages: List<ChatMessage>, channels: List<ChatChannel>): ChatFeedLayouts {
            val ordered = messages.sortedWith(compareByDescending<ChatMessage> { it.timestamp }.thenByDescending { it.receivedAt })
            val byChannel = ordered.groupBy { "${it.channel.platform}:${it.channel.platformChannelId}" }
            return ChatFeedLayouts(ordered, channels.filter { it.enabled }.map { channel ->
                ChannelRoom(channel, byChannel["${channel.platform}:${channel.platformChannelId}"].orEmpty())
            })
        }
    }
}

data class ChannelRoom(val channel: ChatChannel, val messages: List<ChatMessage>)

fun moveChannelKey(keys: List<String>, from: Int, to: Int): List<String> {
    if (from !in keys.indices || to !in keys.indices || from == to) return keys
    return keys.toMutableList().apply { add(to, removeAt(from)) }
}

data class MessageGroup(val messages: List<ChatMessage>) {
    init { require(messages.isNotEmpty()) }
    val newest: ChatMessage get() = messages.first()
    val stableKey: String get() = messages.joinToString(prefix = "group:", separator = ":") { it.messageId }
}

/** Groups adjacent display rows only; it never merges or discards stored messages. */
fun groupConsecutiveMessages(messages: List<ChatMessage>, maxGapMillis: Long = 120_000): List<MessageGroup> {
    val groups = mutableListOf<MutableList<ChatMessage>>()
    messages.forEach { message ->
        val current = groups.lastOrNull()
        val previous = current?.lastOrNull()
        val sameUser = previous != null && previous.channel.platform == message.channel.platform &&
            previous.channel.platformChannelId == message.channel.platformChannelId &&
            (previous.sender.platformUserId?.let { it == message.sender.platformUserId }
                ?: previous.sender.username.equals(message.sender.username, true))
        val closeInTime = previous != null && kotlin.math.abs(previous.timestamp - message.timestamp) <= maxGapMillis
        if (sameUser && closeInTime) current.add(message) else groups += mutableListOf(message)
    }
    return groups.map { MessageGroup(it) }
}

fun circularRoomIndex(virtualIndex: Int, roomCount: Int): Int {
    require(roomCount > 0)
    return Math.floorMod(virtualIndex, roomCount)
}

fun circularRoomStart(roomCount: Int): Int {
    require(roomCount > 0)
    val middle = Int.MAX_VALUE / 2
    return middle - Math.floorMod(middle, roomCount)
}

fun toggleChannelSelection(selected: Set<String>, channelKey: String): Set<String> =
    if (channelKey in selected) selected - channelKey else selected + channelKey

fun matchesChannelSelection(message: ChatMessage, selected: Set<String>, muted: Set<String> = emptySet()): Boolean {
    val channelKey = "${message.channel.platform}:${message.channel.platformChannelId}"
    return if (selected.isEmpty()) channelKey !in muted else channelKey in selected
}

/** Analytics selection deliberately does not apply presentation-only row visibility filters. */
fun filterAnalyticsMessages(messages: List<ChatMessage>, selected: Set<String>): List<ChatMessage> =
    messages.filter { matchesChannelSelection(it, selected) }

fun pulseMessagesForMode(messages: List<ChatMessage>, mode: FeedLayoutMode, riverSelected: Set<String>, muted: Set<String>, roomChannelKey: String?): List<ChatMessage> {
    val selected = if (mode == FeedLayoutMode.ROOMS) roomChannelKey?.let(::setOf).orEmpty() else riverSelected
    val applicableMuted = if (mode == FeedLayoutMode.RIVER || mode == FeedLayoutMode.RUG) muted else emptySet()
    return messages.filter { matchesChannelSelection(it, selected, applicableMuted) }
}

fun adaptiveMessageDelayMillis(pendingCount: Int): Long = when {
    pendingCount > 50 -> 28L
    pendingCount > 20 -> 48L
    pendingCount > 8 -> 85L
    pendingCount > 3 -> 125L
    else -> 180L
}

fun messageArrivalDelayMillis(mode: com.nabchat.app.data.MessageArrivalMode, pendingCount: Int): Long = when (mode) {
    com.nabchat.app.data.MessageArrivalMode.INSTANT -> 0L
    com.nabchat.app.data.MessageArrivalMode.ADAPTIVE -> adaptiveMessageDelayMillis(pendingCount)
    com.nabchat.app.data.MessageArrivalMode.STEADY -> 3_000L
    com.nabchat.app.data.MessageArrivalMode.FLOW -> 550L
}

fun rugTickerPixelsPerSecond(timestamps: List<Long>, nowMillis: Long, averageItemWidthPx: Float): Float {
    val messagesPerMinute = rugMessageRatePerMinute(timestamps, nowMillis)
    return (averageItemWidthPx * messagesPerMinute / 60f).coerceIn(9f, 520f)
}

/** Recent lane velocity used by both the RUG ticker and its fastest-first lane ordering. */
fun rugMessageRatePerMinute(timestamps: List<Long>, nowMillis: Long): Float {
    val recent = timestamps.filter { nowMillis - it in 0..120_000L }.sortedDescending().take(30)
    return when {
        recent.size >= 2 -> ((recent.size - 1) * 60_000f / (recent.first() - recent.last()).coerceAtLeast(1_000L))
        recent.size == 1 -> 4f
        else -> 1.5f
    }
}

enum class RugRateTrend(val symbol: String) { UP("↑"), STEADY("→"), DOWN("↓") }
data class RugActivity(val messagesPerMinute: Int, val trend: RugRateTrend)

/** Compact RUG header activity: current rolling rate compared with the preceding minute. */
fun rugActivity(timestamps: List<Long>, nowMillis: Long): RugActivity {
    val current = timestamps.count { nowMillis - it in 0 until 60_000L }
    val previous = timestamps.count { nowMillis - it in 60_000L until 120_000L }
    val meaningfulChange = maxOf(2, (previous * .15f).toInt())
    val trend = when {
        current >= previous + meaningfulChange -> RugRateTrend.UP
        previous >= current + meaningfulChange -> RugRateTrend.DOWN
        else -> RugRateTrend.STEADY
    }
    return RugActivity(current, trend)
}
