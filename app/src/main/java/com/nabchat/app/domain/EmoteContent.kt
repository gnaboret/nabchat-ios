package com.nabchat.app.domain

import java.util.concurrent.ConcurrentHashMap

data class EmoteDefinition(
    val platform: ChatPlatform,
    val id: String,
    val name: String,
    val imageUrl: String,
    val ownerChannelId: String?
)

class EmoteCatalog {
    private val definitions = ConcurrentHashMap<String, EmoteDefinition>()

    fun resolve(platform: ChatPlatform, id: String, name: String, ownerChannelId: String?): EmoteDefinition =
        definitions.computeIfAbsent("${platform.name}:$id") {
            EmoteDefinition(platform, id, name, "https://files.kick.com/emotes/$id/fullsize", ownerChannelId)
        }

    val size: Int get() = definitions.size
}

fun parseContentSegments(text: String, emotes: List<ChatEmote>): List<ChatContentSegment> {
    if (emotes.isEmpty()) return listOf(ChatContentSegment.TextSegment(text))
    val ordered = emotes.filter { it.start != null && it.end != null }.sortedBy { it.start }
    if (ordered.isEmpty()) return listOf(ChatContentSegment.TextSegment(text))
    val result = mutableListOf<ChatContentSegment>()
    var cursor = 0
    ordered.forEach { emote ->
        val start = emote.start!!.coerceIn(cursor, text.length)
        val endExclusive = (emote.end!! + 1).coerceIn(start, text.length)
        if (start > cursor) result += ChatContentSegment.TextSegment(text.substring(cursor, start))
        result += ChatContentSegment.EmoteSegment(emote)
        cursor = endExclusive
    }
    if (cursor < text.length) result += ChatContentSegment.TextSegment(text.substring(cursor))
    return result
}

fun isEmoteOnly(segments: List<ChatContentSegment>): Boolean =
    segments.any { it is ChatContentSegment.EmoteSegment } && segments.all { it is ChatContentSegment.EmoteSegment || (it as ChatContentSegment.TextSegment).text.isBlank() }
