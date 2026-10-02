package com.nabchat.app

import android.app.Application
import com.nabchat.app.data.*
import com.nabchat.app.provider.*
import kotlinx.coroutines.*

class NabchatApplication : Application() {
    lateinit var repository: ChatRepository
        private set
    lateinit var monetization: MonetizationManager
        private set
    override fun onCreate() {
        super.onCreate()
        monetization = MonetizationManager(this)
        repository = ChatRepository(NabchatDatabase.create(this), SettingsStore(this), KickChatProvider(), TwitchChatProvider(this), YouTubeChatProvider(), RumbleChatProvider(), FakeChatProvider(), monetization.state, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
