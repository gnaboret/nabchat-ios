package com.nabchat.app

import org.junit.Assert.*
import org.junit.Test

class EmoteClassificationTest {
    private val tokens = Regex("\\[emote:(\\d+):([^]]+)]")
    private fun isOnly(text: String) = tokens.containsMatchIn(text) && text.replace(tokens, "").isBlank()

    @Test fun structuredEmotesOnly() = assertTrue(isOnly("[emote:1:HYPE] [emote:2:CLAP]"))
    @Test fun emoteWithWordsIsText() = assertFalse(isOnly("[emote:1:HYPE] that was insane"))
    @Test fun punctuationIsNotAnEmote() = assertFalse(isOnly(":):):"))
}
