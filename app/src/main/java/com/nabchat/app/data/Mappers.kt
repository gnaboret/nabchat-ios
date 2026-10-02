package com.nabchat.app.data

import com.nabchat.app.domain.*
import org.json.JSONArray
import org.json.JSONObject

fun ChatChannel.key() = "${platform.name}:$platformChannelId"
fun ChatChannel.toEntity() = ChannelEntity(key(), platform.name, platformChannelId, chatroomId, slug, displayName, avatarUrl, enabled, dateAdded, sortOrder, favorite)
fun ChannelEntity.toDomain() = ChatChannel(ChatPlatform.valueOf(platform), platformChannelId, chatroomId, slug, displayName, avatarUrl, enabled, dateAdded, sortOrder, favorite)

private fun stringsJson(values: List<String>) = JSONArray(values).toString()
private fun stringsFromJson(value: String) = runCatching { JSONArray(value).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())

fun ChatMessage.toEntity() = MessageEntity(
    key = "${platform.name}:$messageId", platform = platform.name, messageId = messageId,
    channelKey = channel.key(), platformUserId = sender.platformUserId, username = sender.username,
    displayName = sender.displayName, senderAvatarUrl = sender.avatarUrl, text = text, timestamp = timestamp, receivedAt = receivedAt,
    emotesJson = JSONArray().also { a -> emotes.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("start", it.start).put("end", it.end).put("imageUrl", it.imageUrl).put("platform", it.platform.name).put("ownerChannelId", it.ownerChannelId)) } }.toString(),
    badgesJson = stringsJson(badges), replyJson = reply?.let { JSONObject().put("messageId", it.messageId).put("sender", it.sender).put("text", it.text).toString() },
    isEmoteOnly = isEmoteOnly, rawMetadata = rawMetadata
)

fun MessageEntity.toDomain(channel: ChatChannel): ChatMessage {
    val emotes = runCatching { JSONArray(emotesJson).let { a -> List(a.length()) { i -> a.getJSONObject(i).let {
        val id = it.optString("id")
        val emotePlatform = runCatching { ChatPlatform.valueOf(it.optString("platform", platform)) }.getOrDefault(ChatPlatform.valueOf(platform))
        ChatEmote(id, it.optString("name"), it.optInt("start").takeIf { _ -> it.has("start") && !it.isNull("start") }, it.optInt("end").takeIf { _ -> it.has("end") && !it.isNull("end") }, it.optString("imageUrl").ifBlank { if (emotePlatform == ChatPlatform.KICK) "https://files.kick.com/emotes/$id/fullsize" else "" }, emotePlatform, it.optString("ownerChannelId").takeIf { value -> value.isNotBlank() })
    } } } }.getOrDefault(emptyList())
    val reply = replyJson?.let { runCatching { JSONObject(it).let { o -> ReplyInfo(o.optString("messageId"), o.optString("sender"), o.optString("text")) } }.getOrNull() }
    return ChatMessage(ChatPlatform.valueOf(platform), messageId, channel, ChatUser(ChatPlatform.valueOf(platform), platformUserId, username, displayName, stringsFromJson(badgesJson), senderAvatarUrl), text, timestamp, receivedAt, emotes, stringsFromJson(badgesJson), reply, isEmoteOnly, rawMetadata)
}
