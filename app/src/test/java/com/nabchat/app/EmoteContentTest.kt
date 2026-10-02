package com.nabchat.app

import com.nabchat.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class EmoteContentTest {
    @Test fun mixedTextAndEmoteBecomeOrderedSegments() {
        val text = "bro WHAT [emote:37226:KEKW]"
        val start = text.indexOf('[')
        val segments = parseContentSegments(text, listOf(ChatEmote("37226", "KEKW", start, text.lastIndex)))
        assertEquals(ChatContentSegment.TextSegment("bro WHAT "), segments[0])
        assertEquals("KEKW", (segments[1] as ChatContentSegment.EmoteSegment).emote.name)
    }

    @Test fun adjacentStructuredEmotesRemainSeparate() {
        val text = "[emote:1:KEKW][emote:1:KEKW][emote:1:KEKW]"
        val regex = Regex("\\[emote:(\\d+):([^]]+)]")
        val emotes = regex.findAll(text).map { ChatEmote(it.groupValues[1], it.groupValues[2], it.range.first, it.range.last) }.toList()
        val segments = parseContentSegments(text, emotes)
        assertEquals(3, segments.filterIsInstance<ChatContentSegment.EmoteSegment>().size)
        assertTrue(isEmoteOnly(segments))
    }

    @Test fun catalogDeduplicatesDefinitionsByPlatformAndId() {
        val catalog = EmoteCatalog()
        assertSame(catalog.resolve(ChatPlatform.KICK, "1", "KEKW", "a"), catalog.resolve(ChatPlatform.KICK, "1", "KEKW", "a"))
        assertEquals(1, catalog.size)
    }
}
