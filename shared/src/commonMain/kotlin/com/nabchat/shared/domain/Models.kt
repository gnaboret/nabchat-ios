package com.nabchat.shared.domain

enum class ChatPlatform { KICK, TWITCH, YOUTUBE, RUMBLE }

data class ChatChannel(
    val platform: ChatPlatform,
    val platformChannelId: String,
    val slug: String,
    val displayName: String,
)

data class ChatUser(
    val platform: ChatPlatform,
    val platformUserId: String?,
    val username: String,
    val displayName: String,
    val badges: List<String> = emptyList(),
)

data class ChatEmote(
    val id: String,
    val name: String,
    val imageUrl: String,
    val platform: ChatPlatform,
)

sealed interface ChatContentSegment {
    data class Text(val text: String) : ChatContentSegment
    data class Emote(val emote: ChatEmote) : ChatContentSegment
}

data class ChatMessage(
    val platform: ChatPlatform,
    val messageId: String,
    val channel: ChatChannel,
    val sender: ChatUser,
    val text: String,
    val timestamp: Long,
    val contentSegments: List<ChatContentSegment> = listOf(ChatContentSegment.Text(text)),
)
