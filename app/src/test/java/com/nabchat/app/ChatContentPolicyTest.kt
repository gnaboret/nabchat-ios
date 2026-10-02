package com.nabchat.app

import com.nabchat.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class ChatContentPolicyTest {
    private val channel = ChatChannel(ChatPlatform.KICK, "1", "1", "test", "Test")
    private fun message(id: String, username: String, text: String, time: Long = 1_000, badges: List<String> = emptyList()) =
        ChatMessage(ChatPlatform.KICK, id, channel, ChatUser(ChatPlatform.KICK, username, username, username, badges), text, time, time, badges = badges)

    @Test fun botBadgeAndKnownBotNamesAreDetected() {
        assertTrue(ChatContentPolicy.isLikelyBot(message("1", "helper", "hi", badges = listOf("bot"))))
        assertTrue(ChatContentPolicy.isLikelyBot(message("2", "Nightbot", "hi")))
        assertFalse(ChatContentPolicy.isLikelyBot(message("3", "robotfan", "hi")))
    }

    @Test fun verifiedStatusComesFromStructuredUserBadges() {
        assertTrue(ChatUser(ChatPlatform.KICK, "1", "verified", "Verified", listOf("verified")).isVerified())
        assertFalse(ChatUser(ChatPlatform.KICK, "2", "viewer", "Viewer", listOf("subscriber")).isVerified())
    }

    @Test fun obviousNoiseAndLinkFloodAreSpam() {
        assertTrue(ChatContentPolicy.isLikelySpam(message("1", "user", "aaaaaaaaaaaaaa"), emptyList()))
        assertTrue(ChatContentPolicy.isLikelySpam(message("2", "user", "https://a.test https://b.test"), emptyList()))
        assertFalse(ChatContentPolicy.isLikelySpam(message("3", "user", "that was amazing"), emptyList()))
    }

    @Test fun thirdDuplicateWithinThirtySecondsIsSpam() {
        val current = message("3", "user", "follow me", 20_000)
        val nearby = listOf(message("1", "user", "follow me", 1_000), message("2", "user", "FOLLOW   ME", 10_000), current)
        assertTrue(ChatContentPolicy.isLikelySpam(current, nearby))
    }
}
