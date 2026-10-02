package com.nabchat.shared.domain

/** Local display-only classification shared by Android and iOS. */
object ChatContentPolicy {
    private val knownBotNames = setOf("kickbot", "streamlabs", "streamelements", "nightbot", "moobot", "fossabot")
    private val urlPattern = Regex("(?i)https?://|www\\.")
    private val repeatedCharacter = Regex("(.)\\1{11,}", RegexOption.IGNORE_CASE)

    fun isLikelyBot(message: ChatMessage): Boolean {
        val username = message.sender.username.lowercase()
        val hasBotBadge = message.sender.badges.any { it.equals("bot", true) }
        return hasBotBadge || username in knownBotNames || (username.endsWith("bot") && username.length >= 6)
    }

    fun isLikelySpam(message: ChatMessage, nearbyMessages: List<ChatMessage>): Boolean {
        val text = message.text.trim()
        if (text.isEmpty()) return false
        if (urlPattern.findAll(text).count() >= 2) return true
        if (repeatedCharacter.containsMatchIn(text)) return true

        val normalized = text.lowercase().replace(Regex("\\s+"), " ")
        if (normalized.length < 5) return false
        return nearbyMessages.count { other ->
            other.messageId != message.messageId &&
                (other.sender.platformUserId?.let { it == message.sender.platformUserId }
                    ?: other.sender.username.equals(message.sender.username, true)) &&
                kotlin.math.abs(other.timestamp - message.timestamp) <= 30_000 &&
                other.text.trim().lowercase().replace(Regex("\\s+"), " ") == normalized
        } >= 2
    }
}
