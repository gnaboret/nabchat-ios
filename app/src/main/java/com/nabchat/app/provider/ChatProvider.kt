package com.nabchat.app.provider

import com.nabchat.app.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ChatProvider {
    val platform: ChatPlatform
    val connectionState: StateFlow<ConnectionState>
    var autoReconnect: Boolean
    suspend fun resolveChannel(input: String): Result<ChatChannel>
    fun observeMessages(channels: Set<ChatChannel>): Flow<ChatEvent>
    suspend fun connect()
    suspend fun disconnect()
}
