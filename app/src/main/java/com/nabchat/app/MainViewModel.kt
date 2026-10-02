package com.nabchat.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nabchat.app.data.*
import com.nabchat.app.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.nabchat.app.provider.*
import java.io.Writer

data class MainUiState(
    val channels: List<ChatChannel> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val hiddenUsers: List<HiddenUserEntity> = emptyList(),
    val userLabels: List<UserLabelEntity> = emptyList(),
    val savedChatters: List<SavedChatterEntity> = emptyList(),
    val emojiMessages: List<ChatMessage> = emptyList(),
    val settings: ChatSettings = ChatSettings(),
    val analytics: AnalyticsSnapshot = AnalyticsSnapshot(),
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val twitchAuth: TwitchAuthState = TwitchAuthState(),
    val channelActivity: Map<String, Long> = emptyMap(),
    val billing: BillingState = BillingState()
)

class MainViewModel(private val repository: ChatRepository, val monetization: MonetizationManager) : ViewModel() {
    private val content = combine(repository.channels, repository.displayedMessages, repository.hiddenUsers, repository.userLabels, repository.emojiIndicatorMessages) { channels, messages, hidden, labels, emojiMessages ->
        arrayOf(channels, messages, hidden, labels, emojiMessages)
    }
    val state = combine(content, repository.settings, repository.analytics, repository.savedChatters) { values, settings, analytics, savedChatters ->
        @Suppress("UNCHECKED_CAST")
        val channels = values[0] as List<ChatChannel>
        @Suppress("UNCHECKED_CAST")
        val messages = values[1] as List<ChatMessage>
        @Suppress("UNCHECKED_CAST")
        val hidden = values[2] as List<HiddenUserEntity>
        @Suppress("UNCHECKED_CAST")
        val labels = values[3] as List<UserLabelEntity>
        @Suppress("UNCHECKED_CAST")
        val emojiMessages = values[4] as List<ChatMessage>
        MainUiState(channels, messages, hidden, labels, savedChatters, emojiMessages, settings, analytics, repository.connectionState.value)
    }.combine(repository.connectionState) { state, connection -> state.copy(connection = connection) }
        .combine(repository.twitch.authState) { state, auth -> state.copy(twitchAuth = auth) }
        .combine(repository.channelActivity) { state, activity -> state.copy(channelActivity = activity) }
        .combine(monetization.state) { state, billing -> state.copy(billing = billing) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun addChannel(input: String, platform: ChatPlatform = ChatPlatform.KICK, result: (Result<Unit>) -> Unit) = viewModelScope.launch { result(repository.addChannel(input, platform)) }
    fun startTwitchAuth(result: (Result<TwitchDeviceCode>) -> Unit) = viewModelScope.launch { result(repository.twitch.startDeviceAuthorization()) }
    fun awaitTwitchAuth(code: TwitchDeviceCode, result: (Result<Unit>) -> Unit) = viewModelScope.launch { result(repository.twitch.awaitDeviceAuthorization(code)) }
    fun disconnectTwitch() = repository.twitch.disconnectAccount()
    fun setEnabled(channel: ChatChannel, enabled: Boolean) = viewModelScope.launch { repository.setChannelEnabled(channel, enabled) }
    fun setFavorite(channel: ChatChannel, favorite: Boolean) = viewModelScope.launch { repository.setChannelFavorite(channel, favorite) }
    fun remove(channel: ChatChannel) = viewModelScope.launch { repository.removeChannel(channel) }
    fun reorderChannels(orderedEnabledKeys: List<String>) = viewModelScope.launch { repository.reorderVisibleChannels(orderedEnabledKeys) }
    fun restoreChannels(channels: List<ChatChannel>, result: (Result<Unit>) -> Unit) = viewModelScope.launch { result(runCatching { repository.restoreChannels(channels) }) }
    fun hide(message: ChatMessage, scope: HiddenScope) = viewModelScope.launch { repository.hideUser(message, scope) }
    fun unhide(hidden: HiddenUserEntity) = viewModelScope.launch { repository.unhide(hidden) }
    fun labelAsBot(message: ChatMessage) = viewModelScope.launch { repository.labelAsBot(message) }
    fun removeLabel(label: UserLabelEntity) = viewModelScope.launch { repository.removeLabel(label) }
    fun saveChatter(message: ChatMessage) = viewModelScope.launch { repository.saveChatter(message) }
    fun saveChannelAsChatter(channel: ChatChannel) = viewModelScope.launch { repository.saveChannelAsChatter(channel) }
    fun removeSavedChatter(chatter: SavedChatterEntity) = viewModelScope.launch { repository.removeSavedChatter(chatter) }
    fun setSavedChatterColor(chatter: SavedChatterEntity, colorArgb: Long) = viewModelScope.launch { repository.setSavedChatterColor(chatter, colorArgb) }
    fun loadAllChatsFor(message: ChatMessage, result: (Result<List<ChatMessage>>) -> Unit) = viewModelScope.launch { result(runCatching { repository.getAllChatsFor(message) }) }
    fun loadAllChatsFor(channel: ChatChannel, result: (Result<List<ChatMessage>>) -> Unit) = viewModelScope.launch { result(runCatching { repository.getAllChatsFor(channel) }) }
    fun updateSettings(settings: ChatSettings) = viewModelScope.launch { repository.updateSettings(settings) }
    fun resetAnalytics() = viewModelScope.launch { repository.resetAnalytics() }
    fun exportChatLog(writer: Writer, result: (Result<Int>) -> Unit) = viewModelScope.launch { result(runCatching { repository.exportChatLog(writer) }) }
    fun exportChatterChatLog(writer: Writer, chatter: SavedChatterEntity, result: (Result<Int>) -> Unit) = viewModelScope.launch { result(runCatching { repository.exportChatterChatLog(writer, chatter) }) }

    class Factory(private val repository: ChatRepository, private val monetization: MonetizationManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repository, monetization) as T
    }
}
