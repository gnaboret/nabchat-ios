package com.nabchat.app

import com.nabchat.app.data.AnalyticsSnapshot
import com.nabchat.app.data.savedChatterKey
import com.nabchat.app.data.MessageArrivalMode
import com.nabchat.app.data.csvCell
import com.nabchat.app.domain.*
import com.nabchat.app.provider.normalizeKickSlug
import com.nabchat.app.provider.normalizeYouTubeTarget
import com.nabchat.app.provider.extractJsonObject
import com.nabchat.app.provider.decodeJsonString
import com.nabchat.app.provider.normalizeRumbleApiUrl
import org.junit.Assert.*
import org.junit.Test

class FeedLayoutsTest {
    @Test fun publicWatchLinksNeverExposeRumbleCreatorApiUrl() {
        assertEquals("https://kick.com/creator", ChatChannel(ChatPlatform.KICK, "1", null, "creator", "Creator").publicWatchUrl())
        assertEquals("https://www.twitch.tv/creator", ChatChannel(ChatPlatform.TWITCH, "1", null, "creator", "Creator").publicWatchUrl())
        assertEquals("https://www.youtube.com/watch?v=video123", ChatChannel(ChatPlatform.YOUTUBE, "1", "video123", "@creator", "Creator").publicWatchUrl())
        assertEquals(null, ChatChannel(ChatPlatform.RUMBLE, "1", null, "https://rumble.com/-livestream-api/get-data", "Creator").publicWatchUrl())
    }
    @Test fun `channel hides after 28 quiet minutes and returns with activity`() {
        val now = 2_000_000L
        val channel = ChatChannel(ChatPlatform.KICK, "1", null, "a", "A", dateAdded = now - CHANNEL_INACTIVITY_HIDE_MILLIS - 1)
        assertFalse(isChannelRecentlyActive(channel, null, now))
        assertTrue(isChannelRecentlyActive(channel, now - 1_000, now))
        assertFalse(isChannelRecentlyActive(channel, now - CHANNEL_INACTIVITY_HIDE_MILLIS, now))
    }
    @Test fun `chat export CSV safely quotes messages`() {
        assertEquals("\"hello, \"\"chat\"\"\nnext\"", csvCell("hello, \"chat\"\nnext"))
    }
    @Test fun `rug message rate ranks active lanes fastest first`() {
        val now = 1_000_000L
        val fast = rugMessageRatePerMinute(listOf(now, now - 5_000, now - 10_000), now)
        val slow = rugMessageRatePerMinute(listOf(now, now - 30_000, now - 60_000), now)
        val idle = rugMessageRatePerMinute(listOf(now - 180_000), now)
        assertTrue(fast > slow)
        assertTrue(slow > idle)
    }
    @Test fun `rug activity compares current and previous minute`() {
        val now = 1_000_000L
        assertEquals(RugActivity(3, RugRateTrend.UP), rugActivity(listOf(now - 1_000, now - 10_000, now - 50_000), now))
        assertEquals(RugActivity(1, RugRateTrend.DOWN), rugActivity(listOf(now - 1_000, now - 70_000, now - 80_000, now - 90_000), now))
        assertEquals(RugActivity(1, RugRateTrend.STEADY), rugActivity(listOf(now - 1_000, now - 70_000), now))
    }
    @Test fun `normalizes valid Rumble API URLs and rejects channel pages`() {
        val api = "https://rumble.com/-livestream-api/get-data?key=secret"
        assertEquals(api, normalizeRumbleApiUrl(api))
        assertTrue(runCatching { normalizeRumbleApiUrl("https://rumble.com/c/example") }.isFailure)
        assertTrue(runCatching { normalizeRumbleApiUrl("http://example.com/?key=nope") }.isFailure)
    }
    @Test fun rugTickerMovesFasterForBusierChannels() {
        val quiet = rugTickerPixelsPerSecond(listOf(90_000L, 60_000L), 100_000L, 180f)
        val busy = rugTickerPixelsPerSecond(listOf(99_000L, 98_000L, 97_000L, 96_000L), 100_000L, 180f)
        assertTrue(busy > quiet)
        assertTrue(quiet > 0f)
    }
    @Test fun arrivalModesHaveDistinctCadences() {
        assertEquals(0L, messageArrivalDelayMillis(MessageArrivalMode.INSTANT, 20))
        assertEquals(3_000L, messageArrivalDelayMillis(MessageArrivalMode.STEADY, 20))
        assertEquals(550L, messageArrivalDelayMillis(MessageArrivalMode.FLOW, 20))
        assertTrue(messageArrivalDelayMillis(MessageArrivalMode.ADAPTIVE, 20) < 550L)
    }
    @Test fun analyticsChannelsSortByCurrentRateDescending() {
        val slow = ChatChannel(ChatPlatform.KICK, "slow", null, "slow", "Slow", sortOrder = 0)
        val fast = ChatChannel(ChatPlatform.KICK, "fast", null, "fast", "Fast", sortOrder = 1)
        fun message(id: String, channel: ChatChannel, time: Long) = ChatMessage(ChatPlatform.KICK, id, channel, ChatUser(ChatPlatform.KICK, id, id, id), "hi", time, time)
        val sorted = sortChannelsByActivity(listOf(slow, fast), listOf(message("1", slow, 90), message("2", fast, 95), message("3", fast, 96)), 80)
        assertEquals(listOf(fast, slow), sorted)
    }
    @Test fun analyticsCsvIncludesSummaryAndChannelBreakdown() {
        val channel = ChatChannel(ChatPlatform.YOUTUBE, "channel", "video", "@creator", "Creator")
        val message = ChatMessage(ChatPlatform.YOUTUBE, "m", channel, ChatUser(ChatPlatform.YOUTUBE, "u", "viewer", "Viewer"), "hello", 1_000, 1_000)
        val csv = buildAnalyticsCsv(listOf(message), listOf(channel), AnalyticsSnapshot(totalReceived = 1), generatedAt = 2_000)
        assertTrue(csv.contains("\"messages_received_since_launch\",\"1\""))
        assertTrue(csv.contains("\"Creator\",\"YOUTUBE\",\"1\""))
        assertTrue(csv.contains("\"Viewer\",\"viewer\",\"YOUTUBE\",\"1\""))
    }
    @Test fun savedChatterIdentityDoesNotMergePlatforms() {
        assertNotEquals(savedChatterKey(ChatPlatform.KICK, "42", "same"), savedChatterKey(ChatPlatform.TWITCH, "42", "same"))
    }
    @Test fun normalizesYouTubeVideoAndHandleInputs() {
        assertEquals("dQw4w9WgXcQ", normalizeYouTubeTarget("https://youtu.be/dQw4w9WgXcQ").videoId)
        assertEquals("@GoogleDevelopers", normalizeYouTubeTarget("https://youtube.com/@GoogleDevelopers/live").path)
        assertEquals("Creator & Friends", decodeJsonString("Creator \\u0026 Friends"))
        assertNotNull(extractJsonObject("{\"ytInitialData\":{\"token\":\"abc\"}}", "ytInitialData"))
    }
    @Test fun kickSlugAcceptsNamesAndUrls() {
        assertEquals("gesturethejester", normalizeKickSlug("@GestureTheJester"))
        assertEquals("xqc", normalizeKickSlug("https://www.kick.com/xQc/?ref=test"))
    }
    @Test fun riverAndRoomsReferenceTheSameMessages() {
        val a = ChatChannel(ChatPlatform.KICK, "1", "11", "a", "A")
        val b = ChatChannel(ChatPlatform.KICK, "2", "22", "b", "B")
        fun message(id: String, channel: ChatChannel, time: Long) = ChatMessage(ChatPlatform.KICK, id, channel, ChatUser(ChatPlatform.KICK, id, id, id), id, time, time)
        val oldest = message("old", a, 1)
        val newest = message("new", b, 2)

        val layouts = ChatFeedLayouts.from(listOf(oldest, newest), listOf(a, b))

        assertSame(newest, layouts.river.first())
        assertSame(oldest, layouts.rooms.first { it.channel == a }.messages.single())
        assertSame(newest, layouts.rooms.first { it.channel == b }.messages.single())
    }

    @Test fun consecutiveMessagesGroupWithoutMergingData() {
        val channel = ChatChannel(ChatPlatform.KICK, "1", "11", "a", "A")
        fun message(id: String, user: String, time: Long) = ChatMessage(ChatPlatform.KICK, id, channel, ChatUser(ChatPlatform.KICK, user, user, user), id, time, time)
        val one = message("1", "alice", 10_000)
        val two = message("2", "alice", 9_000)
        val three = message("3", "bob", 8_000)

        val groups = groupConsecutiveMessages(listOf(one, two, three))

        assertEquals(2, groups.size)
        assertSame(one, groups[0].messages[0])
        assertSame(two, groups[0].messages[1])
        assertSame(three, groups[1].messages.single())
    }

    @Test fun sameUserInDifferentChannelsDoesNotGroup() {
        val a = ChatChannel(ChatPlatform.KICK, "1", "11", "a", "A")
        val b = ChatChannel(ChatPlatform.KICK, "2", "22", "b", "B")
        fun message(id: String, channel: ChatChannel) = ChatMessage(ChatPlatform.KICK, id, channel, ChatUser(ChatPlatform.KICK, "user", "user", "user"), id, 1_000, 1_000)
        assertEquals(2, groupConsecutiveMessages(listOf(message("1", a), message("2", b))).size)
    }

    @Test fun circularRoomsWrapInBothDirections() {
        assertEquals(0, circularRoomIndex(3, 3))
        assertEquals(2, circularRoomIndex(-1, 3))
        assertEquals(0, circularRoomIndex(circularRoomStart(3), 3))
    }

    @Test fun channelQuickSelectionTogglesAndEmptyMeansAll() {
        val channel = ChatChannel(ChatPlatform.KICK, "1", "11", "a", "A")
        val message = ChatMessage(ChatPlatform.KICK, "m", channel, ChatUser(ChatPlatform.KICK, "u", "u", "u"), "hi", 1, 1)
        val key = "${channel.platform}:${channel.platformChannelId}"
        val selected = toggleChannelSelection(emptySet(), key)
        assertTrue(matchesChannelSelection(message, selected))
        assertTrue(toggleChannelSelection(selected, key).isEmpty())
        assertTrue(matchesChannelSelection(message, emptySet()))
        assertFalse(matchesChannelSelection(message, emptySet(), setOf(key)))
        assertTrue(matchesChannelSelection(message, setOf(key), setOf(key)))
    }

    @Test fun analyticsSelectionRetainsEmoteOnlyMessagesHiddenFromChatRows() {
        val channel = ChatChannel(ChatPlatform.KICK, "1", "11", "a", "A")
        val emoteOnly = ChatMessage(ChatPlatform.KICK, "e", channel, ChatUser(ChatPlatform.KICK, "u", "u", "u"), "[emote:1:KEKW]", 1, 1, isEmoteOnly = true)
        assertSame(emoteOnly, filterAnalyticsMessages(listOf(emoteOnly), setOf("KICK:1")).single())
    }

    @Test fun roomsPulseFollowsOnlyTheActiveRoom() {
        val a = ChatChannel(ChatPlatform.KICK, "1", "11", "a", "A")
        val b = ChatChannel(ChatPlatform.KICK, "2", "22", "b", "B")
        fun message(id: String, channel: ChatChannel) = ChatMessage(ChatPlatform.KICK, id, channel, ChatUser(ChatPlatform.KICK, "u", "u", "u"), "🔥", 1, 1)
        val scoped = pulseMessagesForMode(listOf(message("a", a), message("b", b)), FeedLayoutMode.ROOMS, emptySet(), emptySet(), "KICK:2")
        assertEquals(listOf("b"), scoped.map { it.messageId })
    }

    @Test fun rugPulseUsesRiverStyleChannelSelectionAndMute() {
        val a = ChatChannel(ChatPlatform.KICK, "1", null, "a", "A")
        val b = ChatChannel(ChatPlatform.KICK, "2", null, "b", "B")
        fun message(id: String, channel: ChatChannel) = ChatMessage(ChatPlatform.KICK, id, channel, ChatUser(ChatPlatform.KICK, "u", "u", "u"), "hi", 1, 1)
        val scoped = pulseMessagesForMode(listOf(message("a", a), message("b", b)), FeedLayoutMode.RUG, emptySet(), setOf("KICK:1"), null)
        assertEquals(listOf("b"), scoped.map { it.messageId })
    }

    @Test fun messageArrivalDelayAcceleratesWithBacklog() {
        assertTrue(adaptiveMessageDelayMillis(1) > adaptiveMessageDelayMillis(10))
        assertTrue(adaptiveMessageDelayMillis(10) > adaptiveMessageDelayMillis(60))
    }

    @Test fun channelReorderMovesExactlyOneEntryAndPreservesTheOthers() {
        assertEquals(listOf("b", "c", "a", "d"), moveChannelKey(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), moveChannelKey(listOf("a", "b", "c", "d"), 3, 1))
    }

    @Test fun burstSizePlacesNormalAtSliderMidpointAndPreservesAbsurdMaximum() {
        assertEquals(0f, burstSizeSliderPosition(25f), .001f)
        assertEquals(.5f, burstSizeSliderPosition(100f), .001f)
        assertEquals(1f, burstSizeSliderPosition(1000f), .001f)
        assertEquals(100f, burstSizePercent(.5f), .001f)
        assertEquals(1000f, burstSizePercent(1f), .001f)
    }
}
