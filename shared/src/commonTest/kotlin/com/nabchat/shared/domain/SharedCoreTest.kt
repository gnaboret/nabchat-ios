package com.nabchat.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedCoreTest {
    private val channel = ChatChannel(ChatPlatform.KICK, "channel-1", "nabchat", "NabChat")
    private val user = ChatUser(ChatPlatform.KICK, "user-1", "viewer", "Viewer")

    private fun message(id: String, text: String, timestamp: Long = 1_000L, sender: ChatUser = user) =
        ChatMessage(ChatPlatform.KICK, id, channel, sender, text, timestamp)

    @Test
    fun detectsKnownBotWithoutModeratingAnything() {
        assertTrue(ChatContentPolicy.isLikelyBot(message("1", "hello", sender = user.copy(username = "nightbot"))))
        assertFalse(ChatContentPolicy.isLikelyBot(message("2", "hello")))
    }

    @Test
    fun detectsRepeatedNearbySpam() {
        val candidate = message("3", "same repeated message", 30_000L)
        val nearby = listOf(message("1", "same repeated message", 10_000L), message("2", "same repeated message", 20_000L))
        assertTrue(ChatContentPolicy.isLikelySpam(candidate, nearby))
    }

    @Test
    fun computesRugTrend() {
        val now = 180_000L
        val activity = rugActivity(listOf(179_000L, 170_000L, 160_000L, 80_000L), now)
        assertEquals(3, activity.messagesPerMinute)
        assertEquals(RugRateTrend.UP, activity.trend)
    }
}
