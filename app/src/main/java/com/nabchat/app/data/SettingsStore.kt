package com.nabchat.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("nabchat_settings")
enum class EmojiBurstIntensity { LOW, MEDIUM, HIGH }
enum class VisualSize { SMALL, MEDIUM, LARGE }
enum class MessageArrivalMode { INSTANT, ADAPTIVE, STEADY, FLOW }
enum class AppearanceMode { DARK, LIGHT, NEWSPAPER, SYSTEM }
data class ChatSettings(
    val showEmoteOnly: Boolean = false,
    val saveHistory: Boolean = true,
    val autoReconnect: Boolean = true,
    val fakeMode: Boolean = false,
    val feedLayoutMode: com.nabchat.app.domain.FeedLayoutMode = com.nabchat.app.domain.FeedLayoutMode.RIVER,
    val appearanceMode: AppearanceMode = AppearanceMode.DARK,
    val showLikelyBots: Boolean = false,
    val showLikelySpam: Boolean = false,
    val showEmojiBursts: Boolean = true,
    val showEmojiTracker: Boolean = true,
    val chatFontSizeSp: Float = 16f,
    val startWithAutoScroll: Boolean = true,
    val keepScreenOn: Boolean = false,
    val emojiBurstIntensity: EmojiBurstIntensity = EmojiBurstIntensity.LOW,
    val emojiBurstSize: VisualSize = VisualSize.MEDIUM,
    val emojiBurstSizePercent: Float = 100f,
    val emojiBurstOpacityPercent: Float = 100f,
    val pulseBarSize: VisualSize = VisualSize.MEDIUM,
    val messageArrivalMode: MessageArrivalMode = MessageArrivalMode.ADAPTIVE,
    val showProfilePictures: Boolean = true,
    val showTimestamps: Boolean = true
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val showEmoteOnly = booleanPreferencesKey("show_emote_only")
        val saveHistory = booleanPreferencesKey("save_history")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val fakeMode = booleanPreferencesKey("fake_provider_mode")
        val feedLayoutMode = stringPreferencesKey("feed_layout_mode")
        val darkMode = booleanPreferencesKey("dark_mode")
        val appearanceMode = stringPreferencesKey("appearance_mode")
        val showLikelyBots = booleanPreferencesKey("show_likely_bots")
        val showLikelySpam = booleanPreferencesKey("show_likely_spam")
        val showEmojiBursts = booleanPreferencesKey("show_emoji_bursts")
        val showEmojiTracker = booleanPreferencesKey("show_emoji_tracker")
        val chatFontSizeSp = floatPreferencesKey("chat_font_size_sp")
        val startWithAutoScroll = booleanPreferencesKey("start_with_auto_scroll")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val behaviorDefaultsVersion = intPreferencesKey("behavior_defaults_version")
        val emojiBurstIntensity = stringPreferencesKey("emoji_burst_intensity")
        val emojiBurstSize = stringPreferencesKey("emoji_burst_size")
        val emojiBurstSizePercent = floatPreferencesKey("emoji_burst_size_percent")
        val emojiBurstOpacityPercent = floatPreferencesKey("emoji_burst_opacity_percent")
        val pulseBarSize = stringPreferencesKey("pulse_bar_size")
        val smoothMessageArrival = booleanPreferencesKey("smooth_message_arrival")
        val messageArrivalMode = stringPreferencesKey("message_arrival_mode")
        val showProfilePictures = booleanPreferencesKey("show_profile_pictures")
        val showTimestamps = booleanPreferencesKey("show_timestamps")
    }
    val settings: Flow<ChatSettings> = context.dataStore.data.map { p ->
        val currentDefaults = (p[Keys.behaviorDefaultsVersion] ?: 0) >= 1
        ChatSettings(
        showEmoteOnly = if (currentDefaults) p[Keys.showEmoteOnly] ?: false else false,
        saveHistory = p[Keys.saveHistory] ?: true,
        autoReconnect = p[Keys.autoReconnect] ?: true,
        fakeMode = false,
        feedLayoutMode = runCatching { com.nabchat.app.domain.FeedLayoutMode.valueOf(p[Keys.feedLayoutMode] ?: "RIVER") }.getOrDefault(com.nabchat.app.domain.FeedLayoutMode.RIVER),
        appearanceMode = runCatching { AppearanceMode.valueOf(p[Keys.appearanceMode] ?: "") }.getOrElse {
            if (p[Keys.darkMode] ?: true) AppearanceMode.DARK else AppearanceMode.LIGHT
        },
        showLikelyBots = p[Keys.showLikelyBots] ?: false,
        showLikelySpam = p[Keys.showLikelySpam] ?: false,
        showEmojiBursts = p[Keys.showEmojiBursts] ?: true,
        showEmojiTracker = p[Keys.showEmojiTracker] ?: true,
        chatFontSizeSp = (p[Keys.chatFontSizeSp] ?: 16f).coerceIn(12f, 20f),
        startWithAutoScroll = if (currentDefaults) p[Keys.startWithAutoScroll] ?: true else true,
        keepScreenOn = p[Keys.keepScreenOn] ?: false,
        emojiBurstIntensity = runCatching { EmojiBurstIntensity.valueOf(p[Keys.emojiBurstIntensity] ?: "LOW") }.getOrDefault(EmojiBurstIntensity.LOW),
        emojiBurstSize = runCatching { VisualSize.valueOf(p[Keys.emojiBurstSize] ?: "MEDIUM") }.getOrDefault(VisualSize.MEDIUM),
        emojiBurstSizePercent = (p[Keys.emojiBurstSizePercent] ?: 100f).coerceIn(25f, 1000f),
        emojiBurstOpacityPercent = (p[Keys.emojiBurstOpacityPercent] ?: 100f).coerceIn(10f, 100f),
        pulseBarSize = runCatching { VisualSize.valueOf(p[Keys.pulseBarSize] ?: "MEDIUM") }.getOrDefault(VisualSize.MEDIUM),
        messageArrivalMode = runCatching { MessageArrivalMode.valueOf(p[Keys.messageArrivalMode] ?: "") }.getOrElse {
            if (p[Keys.smoothMessageArrival] == false) MessageArrivalMode.INSTANT else MessageArrivalMode.ADAPTIVE
        },
        showProfilePictures = p[Keys.showProfilePictures] ?: true,
        showTimestamps = p[Keys.showTimestamps] ?: true
    ) }
    suspend fun update(value: ChatSettings) = context.dataStore.edit { p ->
        p[Keys.showEmoteOnly] = value.showEmoteOnly
        p[Keys.saveHistory] = value.saveHistory
        p[Keys.autoReconnect] = value.autoReconnect
        p[Keys.fakeMode] = false
        p[Keys.feedLayoutMode] = value.feedLayoutMode.name
        p[Keys.appearanceMode] = value.appearanceMode.name
        p[Keys.darkMode] = value.appearanceMode == AppearanceMode.DARK
        p[Keys.showLikelyBots] = value.showLikelyBots
        p[Keys.showLikelySpam] = value.showLikelySpam
        p[Keys.showEmojiBursts] = value.showEmojiBursts
        p[Keys.showEmojiTracker] = value.showEmojiTracker
        p[Keys.chatFontSizeSp] = value.chatFontSizeSp.coerceIn(12f, 20f)
        p[Keys.startWithAutoScroll] = value.startWithAutoScroll
        p[Keys.keepScreenOn] = value.keepScreenOn
        p[Keys.behaviorDefaultsVersion] = 1
        p[Keys.emojiBurstIntensity] = value.emojiBurstIntensity.name
        p[Keys.emojiBurstSize] = value.emojiBurstSize.name
        p[Keys.emojiBurstSizePercent] = value.emojiBurstSizePercent.coerceIn(25f, 1000f)
        p[Keys.emojiBurstOpacityPercent] = value.emojiBurstOpacityPercent.coerceIn(10f, 100f)
        p[Keys.pulseBarSize] = value.pulseBarSize.name
        p[Keys.messageArrivalMode] = value.messageArrivalMode.name
        p[Keys.smoothMessageArrival] = value.messageArrivalMode != MessageArrivalMode.INSTANT
        p[Keys.showProfilePictures] = value.showProfilePictures
        p[Keys.showTimestamps] = value.showTimestamps
    }
}
