package com.nabchat.app.domain

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class EmojiSignal(val key: String, val label: String, val imageUrl: String? = null)
data class EmojiPulse(val signal: EmojiSignal, val count: Int)

const val PULSE_HALF_LIFE_MILLIS: Long = 28L * 60L * 1_000L
private const val MOMENTUM_WINDOW_MILLIS: Long = 60_000L

private val kickEmoteToken = Regex("\\[emote:(\\d+):([^]]+)]")
private val unicodeEmoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}](?:\\uFE0F|\\uFE0E)?")

fun emojiSignals(message: ChatMessage): List<EmojiSignal> {
    val structured = message.contentSegments.filterIsInstance<ChatContentSegment.EmoteSegment>().map { EmojiSignal("${it.emote.platform.name.lowercase()}:${it.emote.id}", it.emote.name, it.emote.imageUrl.takeIf(String::isNotBlank)) }
    val plainText = message.text.replace(kickEmoteToken, "")
    val unicode = unicodeEmoji.findAll(plainText).map { EmojiSignal("unicode:${it.value}", it.value) }.toList()
    return structured + unicode
}

fun emojiPulseBar(messages: List<ChatMessage>, limit: Int = 7, nowMillis: Long = System.currentTimeMillis()): List<EmojiPulse> {
    val occurrences = messages.flatMap { message -> emojiSignals(message).map { message.timestamp to it } }
    fun pulse(group: List<Pair<Long, EmojiSignal>>): EmojiPulse {
        val decayed = group.sumOf { (timestamp, _) ->
            val age = max(0L, nowMillis - timestamp)
            0.5.pow(age.toDouble() / PULSE_HALF_LIFE_MILLIS)
        }
        val momentum = group.count { (timestamp, _) -> nowMillis - timestamp in 0..MOMENTUM_WINDOW_MILLIS } * 2.5
        return EmojiPulse(group.maxBy { it.first }.second, max(1, (decayed + momentum).roundToInt()))
    }
    val grouped = occurrences.groupBy { it.second.key }.values
    val latestByKey = occurrences.groupingBy { it.second.key }.fold(0L) { latest, item -> max(latest, item.first) }
    return grouped.map(::pulse)
        .sortedWith(compareByDescending<EmojiPulse> { it.count }.thenByDescending { latestByKey[it.signal.key] ?: 0L })
        .take(limit)
}
