package com.nabchat.app

import com.nabchat.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class EmojiSignalsTest {
    private val channel = ChatChannel(ChatPlatform.KICK, "1", "1", "test", "Test")
    private fun message(id: String, text: String, time: Long = 1_000, emotes: List<ChatEmote> = emptyList()) =
        ChatMessage(ChatPlatform.KICK, id, channel, ChatUser(ChatPlatform.KICK, "u", "u", "u"), text, time, time, emotes = emotes)

    @Test fun extractsUnicodeAndStructuredKickEmotes() {
        val signals = emojiSignals(message("1", "wow 💀 [emote:25:HYPE]", emotes = listOf(ChatEmote("25", "HYPE", 6, 20))))
        assertTrue(signals.any { it.key == "unicode:💀" })
        assertTrue(signals.any { it.key == "kick:25" && it.imageUrl != null })
    }

    @Test fun pulseBarRanksFrequentSignals() {
        val messages = listOf(message("1", "💀", 3), message("2", "💀", 2), message("3", "🔥", 1))
        val pulses = emojiPulseBar(messages, nowMillis = 3)
        assertEquals("💀", pulses.first().signal.label)
        assertTrue(pulses.first().count >= 2)
    }

    @Test fun pulseUsesSmoothTwentyEightMinuteHalfLifeAndLiveMomentum() {
        val now = 2_000_000L
        val fresh = emojiPulseBar(listOf(message("fresh", "🔥", now)), nowMillis = now).single()
        val aged = emojiPulseBar(listOf(message("aged", "🔥", now - PULSE_HALF_LIFE_MILLIS)), nowMillis = now).single()
        assertTrue(fresh.count > aged.count)
        assertTrue(aged.count >= 1)
    }
}
