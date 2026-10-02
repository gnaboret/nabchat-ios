package com.nabchat.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.*
import coil.compose.AsyncImage
import com.nabchat.app.data.*
import com.nabchat.app.domain.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import kotlin.random.Random
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

private val Lime = Color(0xFF46C66B)
private val Ink = Color(0xFF17201A)
private val Paper = Color(0xFFF7F8F3)
private val LocalChatFontSize = staticCompositionLocalOf { 16f }
private val LocalSavedChatterColors = staticCompositionLocalOf<Map<String, Color>> { emptyMap() }
private val LocalShowProfilePictures = staticCompositionLocalOf { true }
private val LocalShowTimestamps = staticCompositionLocalOf { true }
private val LocalDarkAppearance = staticCompositionLocalOf { true }
private val LocalTutorialStep = staticCompositionLocalOf { -1 }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Resolve consent and initialize the production ad SDK at launch so the
        // first eligible placement can load immediately instead of starting late.
        AdvertisingPrivacy.request(this)
        setContent {
            val app = application as NabchatApplication
            val vm: MainViewModel = viewModel(factory = MainViewModel.Factory(app.repository, app.monetization))
            val state by vm.state.collectAsStateWithLifecycle()
            val rootView = LocalView.current
            DisposableEffect(rootView, state.settings.keepScreenOn) {
                rootView.keepScreenOn = state.settings.keepScreenOn
                onDispose { rootView.keepScreenOn = false }
            }
            var showBrandSplash by rememberSaveable { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(700)
                showBrandSplash = false
            }
            NabchatTheme(state.settings.appearanceMode) {
                if (showBrandSplash) BrandSplash(state.billing.ownershipChecked && state.billing.isPlus)
                else NabchatRoot(vm, state)
            }
        }
    }
}

@Composable private fun BrandSplash(isPlus: Boolean) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF050505)).navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(if (isPlus) R.drawable.nabchat_plus_wordmark_icon else R.drawable.nabchat_wordmark_icon),
            contentDescription = if (isPlus) "nabchat+" else "nabchat",
            modifier = Modifier.size(320.dp)
        )
        Text(
            "powered by gnaboret",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
            color = Color(0xFFE4EAE4).copy(alpha = .55f),
            fontFamily = FontFamily.Serif,
            fontSize = 9.sp,
            letterSpacing = .35.sp
        )
    }
}

@Composable private fun NabchatTheme(mode: AppearanceMode, content: @Composable () -> Unit) {
    val resolvedMode = if (mode == AppearanceMode.SYSTEM) {
        if (isSystemInDarkTheme()) AppearanceMode.DARK else AppearanceMode.LIGHT
    } else mode
    val colors = when (resolvedMode) {
        AppearanceMode.DARK -> darkColorScheme(
            primary = Color(0xFF57DF7D), onPrimary = Color(0xFF062510),
            background = Color(0xFF101411), surface = Color(0xFF101411),
            surfaceContainer = Color(0xFF1A201B), onSurface = Color(0xFFE4EAE4)
        )
        AppearanceMode.LIGHT -> lightColorScheme(primary = Lime, onPrimary = Ink, background = Paper, surface = Paper, surfaceContainer = Color.White, onSurface = Ink)
        AppearanceMode.NEWSPAPER -> lightColorScheme(
            primary = Color(0xFF71563B), onPrimary = Color(0xFFFFF8EA),
            primaryContainer = Color(0xFFDDC5A2), onPrimaryContainer = Color(0xFF2D2116),
            background = Color(0xFFF3EAD8), surface = Color(0xFFF3EAD8),
            surfaceContainer = Color(0xFFE9DCC4), surfaceContainerHigh = Color(0xFFDFD0B5),
            onSurface = Color(0xFF2B2219), onSurfaceVariant = Color(0xFF594C3E),
            outline = Color(0xFF786A59), outlineVariant = Color(0xFFC9B99E)
        )
        AppearanceMode.SYSTEM -> error("System appearance must be resolved first")
    }
    CompositionLocalProvider(LocalDarkAppearance provides (resolvedMode == AppearanceMode.DARK)) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}

private data class Destination(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val destinations = listOf(
    Destination("chatters", "CHATTERS", Icons.Outlined.People),
    Destination("chat", "CHAT", Icons.Outlined.Chat),
    Destination("analytics", "ANALYTICS", Icons.Outlined.QueryStats)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NabchatRoot(vm: MainViewModel, state: MainUiState) {
    val context = LocalContext.current
    val guidePreferences = remember { context.getSharedPreferences("nabchat_guide", Context.MODE_PRIVATE) }
    var tutorialStep by rememberSaveable { mutableIntStateOf(if (guidePreferences.getBoolean("completed", false)) -1 else 0) }
    val finishGuide = {
        guidePreferences.edit().putBoolean("completed", true).apply()
        tutorialStep = -1
    }
    val nav = rememberNavController()
    val currentBackStack by nav.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route
    var chatResetSignal by rememberSaveable { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("nabchat", fontWeight = FontWeight.Black, letterSpacing = 0.2.sp) },
                actions = {
                    FilledTonalIconButton(
                        onClick = {
                            if (currentRoute == "settings") nav.popBackStack()
                            else nav.navigate("settings") { launchSingleTop = true }
                        },
                        modifier = Modifier.size(34.dp)
                    ) { Icon(Icons.Outlined.Settings, "Settings", Modifier.size(18.dp)) }
                    Spacer(Modifier.width(5.dp))
                    ConnectionPill(state.connection, state.settings.fakeMode)
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(56.dp),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    destinations.forEach { destination ->
                        val selected = currentBackStack?.destination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(selected = selected, onClick = {
                            if (selected && destination.route == "chat") chatResetSignal++
                            else nav.navigate(destination.route) {
                                popUpTo("chat") { inclusive = false }
                                launchSingleTop = true
                            }
                        }, icon = { Icon(destination.icon, null, Modifier.size(20.dp)) }, label = { Text(destination.title, fontSize = 9.sp) })
                    }
                }
            }
        }
    ) { padding ->
        val chatterColors = remember(state.savedChatters) { state.savedChatters.associate { it.key to Color(it.colorArgb.toInt()) } }
        CompositionLocalProvider(LocalSavedChatterColors provides chatterColors, LocalShowProfilePictures provides state.settings.showProfilePictures, LocalShowTimestamps provides state.settings.showTimestamps, LocalTutorialStep provides tutorialStep) {
            NavHost(nav, startDestination = "chat", modifier = Modifier.padding(padding)) {
                composable("chat") { ChatScreen(state, vm, chatResetSignal) }
                composable("analytics") { AnalyticsScreen(state, vm) }
                composable("chatters") { ChattersScreen(state, vm) }
                composable("settings") { SettingsScreen(state, vm) }
            }
        }
    }
    if (tutorialStep >= 0) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 76.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(when (tutorialStep) { 0 -> "Welcome to nabchat"; 1 -> "Add your first channel"; else -> "One chat, three views" }, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(when (tutorialStep) {
                    0 -> "Bring your livestream conversations together. Here’s a quick look at the basics."
                    1 -> "Tap +, choose a platform, then enter a channel name or supported link."
                    else -> "River combines your chats. Rooms separates them by channel. Rug keeps them moving across the screen."
                }, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${tutorialStep + 1} / 3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = finishGuide) { Text("SKIP") }
                    TextButton(onClick = { if (tutorialStep == 2) finishGuide() else tutorialStep++ }) { Text("NEXT") }
                }
            }
        }
    }
    }
}

@Composable private fun ConnectionPill(state: ConnectionState, fake: Boolean) {
    val color = when (state) { ConnectionState.CONNECTED -> Lime; ConnectionState.ERROR -> MaterialTheme.colorScheme.error; else -> Color(0xFFF0A638) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp).background(color.copy(alpha = .15f), RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(5.dp))
        Text(if (fake) "SIM" else state.name, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun ChatScreen(state: MainUiState, vm: MainViewModel, resetSelectionSignal: Int) {
    val tutorialStep = LocalTutorialStep.current
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var mutedKeys by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var roomChannelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var selectedRoomChannel by remember { mutableStateOf<ChatChannel?>(null) }
    var catchUpSignal by remember { mutableIntStateOf(0) }
    var rugFastestFirst by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(resetSelectionSignal) { selectedKeys = emptySet() }
    var activityClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); activityClock = System.currentTimeMillis() } }
    val activeChannels = remember(state.channels, state.channelActivity, activityClock) {
        state.channels.filter { it.enabled && isChannelRecentlyActive(it, state.channelActivity[it.key()], activityClock) }
    }
    val activeChannelSignature = activeChannels.joinToString("|") { it.key() }
    LaunchedEffect(activeChannelSignature) {
        if (roomChannelKey !in activeChannels.map { it.key() }) roomChannelKey = activeChannels.firstOrNull()?.key()
        selectedKeys = selectedKeys.intersect(activeChannels.mapTo(mutableSetOf()) { it.key() })
    }
    val presentedMessages = rememberPresentedMessages(state.messages, state.settings.messageArrivalMode, catchUpSignal)
    val hasPresentationBacklog = presentedMessages.size < state.messages.size
    val presentedLayouts = remember(presentedMessages, activeChannels) { ChatFeedLayouts.from(presentedMessages, activeChannels) }
    val filtered = remember(presentedMessages, selectedKeys, mutedKeys) { presentedMessages.filter { matchesChannelSelection(it, selectedKeys, mutedKeys) } }
    val rugRooms = remember(filtered, activeChannels, selectedKeys, mutedKeys) {
        val channels = activeChannels.filter { if (selectedKeys.isEmpty()) it.key() !in mutedKeys else it.key() in selectedKeys }
        ChatFeedLayouts.from(filtered, channels).rooms
    }
    val selectedEmojiMessages = remember(state.emojiMessages, selectedKeys, mutedKeys, roomChannelKey, state.settings.feedLayoutMode) {
        pulseMessagesForMode(state.emojiMessages, state.settings.feedLayoutMode, selectedKeys, mutedKeys, roomChannelKey)
    }
    CompositionLocalProvider(LocalChatFontSize provides state.settings.chatFontSizeSp) { Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(if (tutorialStep == 2) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .45f), RoundedCornerShape(24.dp)) else Modifier) {
                    FeedModeControl(state.settings.feedLayoutMode) { mode -> vm.updateSettings(state.settings.copy(feedLayoutMode = mode)) }
                }
                if (state.settings.feedLayoutMode == FeedLayoutMode.RUG) {
                    Spacer(Modifier.width(5.dp))
                    FilledTonalIconButton(
                        onClick = { rugFastestFirst = !rugFastestFirst },
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (rugFastestFirst) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) { Icon(Icons.Outlined.Sort, if (rugFastestFirst) "RUG sorted fastest to slowest" else "Sort RUG fastest to slowest", Modifier.size(17.dp)) }
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { addDialog = true }, modifier = Modifier.size(34.dp).then(if (tutorialStep == 1) Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .55f), CircleShape) else Modifier)) { Icon(Icons.Outlined.Add, "Add channel") }
            }
            HorizontalDivider()
            val feedWash = if (LocalDarkAppearance.current) Color.Black.copy(alpha = .08f) else Color.White.copy(alpha = .08f)
            Box(Modifier.weight(1f).drawWithContent { drawContent(); drawRect(feedWash) }) {
                if (state.channels.none { it.enabled }) EmptyChat { addDialog = true }
                else if (activeChannels.isEmpty() && state.settings.feedLayoutMode != FeedLayoutMode.RIVER) NoActiveChannels()
                else when (state.settings.feedLayoutMode) {
                    FeedLayoutMode.RIVER -> RiverFeed(filtered, state.settings.startWithAutoScroll, state.settings.messageArrivalMode == MessageArrivalMode.STEADY || state.settings.messageArrivalMode == MessageArrivalMode.FLOW, hasPresentationBacklog, { catchUpSignal++ }) { selectedMessage = it }
                    FeedLayoutMode.ROOMS -> RoomsFeed(
                        presentedLayouts.rooms,
                        state.settings.startWithAutoScroll,
                        targetChannelKey = roomChannelKey,
                        onCurrentChannel = { roomChannelKey = it },
                        animateAutoFollow = state.settings.messageArrivalMode == MessageArrivalMode.STEADY || state.settings.messageArrivalMode == MessageArrivalMode.FLOW,
                        hasPresentationBacklog = hasPresentationBacklog,
                        onCatchUp = { catchUpSignal++ },
                        onMessage = { selectedMessage = it },
                        onChannelProfile = { selectedRoomChannel = it }
                    )
                    FeedLayoutMode.RUG -> RugFeed(rugRooms, rugFastestFirst, { selectedRoomChannel = it }) { selectedMessage = it }
                }
            }
            val showChatAd = state.billing.ownershipChecked && !state.billing.isPlus
            if (showChatAd) NabchatBannerAd(dismissible = false) {}
            if (state.settings.showEmojiTracker) EmojiTrackerBar(selectedEmojiMessages, state.settings.pulseBarSize)
            HorizontalDivider()
            val roomMode = state.settings.feedLayoutMode == FeedLayoutMode.ROOMS
            ChannelFilterBar(
                activeChannels,
                if (roomMode) roomChannelKey?.let(::setOf).orEmpty() else selectedKeys,
                onSelected = { key ->
                    if (roomMode) roomChannelKey = key ?: state.channels.firstOrNull { it.enabled }?.key()
                    else selectedKeys = if (key == null) emptySet() else toggleChannelSelection(selectedKeys, key)
                },
                onReorder = vm::reorderChannels,
                focusedKey = if (roomMode) roomChannelKey else null,
                showAll = !roomMode,
                mutedKeys = mutedKeys,
                onToggleMute = { key -> mutedKeys = if (key in mutedKeys) mutedKeys - key else mutedKeys + key }
            )
        }
        EmojiBurstOverlay(
            selectedEmojiMessages,
            state.settings.showEmojiBursts,
            state.settings.emojiBurstIntensity,
            state.settings.emojiBurstSizePercent,
            state.settings.emojiBurstOpacityPercent
        )
    } }
    if (addDialog) AddChannelDialog(state.settings.fakeMode, state.twitchAuth.connected, onDismiss = { addDialog = false }, onAdd = { input, platform, callback -> vm.addChannel(input, platform) { result -> callback(result); if (result.isSuccess) addDialog = false } })
    selectedMessage?.let { message ->
        val chatterKey = savedChatterKey(message.platform, message.sender.platformUserId, message.sender.username)
        val chatterHistory = remember(state.emojiMessages, chatterKey) {
            state.emojiMessages.filter { savedChatterKey(it.platform, it.sender.platformUserId, it.sender.username) == chatterKey }
                .sortedByDescending { it.timestamp }
        }
        MessageActions(
            message,
            chatterHistory,
            onDismiss = { selectedMessage = null },
            onHide = { scope -> vm.hide(message, scope); selectedMessage = null },
            onLabelBot = { vm.labelAsBot(message); selectedMessage = null },
            onSaveChatter = { vm.saveChatter(message); selectedMessage = null },
            onLoadAllChats = { result -> vm.loadAllChatsFor(message, result) },
            alreadySaved = state.savedChatters.any { it.key == savedChatterKey(message.platform, message.sender.platformUserId, message.sender.username) }
        )
    }
    selectedRoomChannel?.let { channel ->
        val streamerUsername = channel.slug.trim('/').substringAfterLast('/').substringBefore('?').removePrefix("@").ifBlank { channel.displayName }
        val channelKey = savedChatterKey(channel.platform, channel.platformChannelId, streamerUsername)
        val streamerHistory = remember(state.emojiMessages, channelKey) {
            state.emojiMessages.filter { message ->
                message.platform == channel.platform &&
                    (message.sender.platformUserId == channel.platformChannelId ||
                        message.sender.username.removePrefix("@").equals(streamerUsername, true) ||
                        message.sender.displayName.equals(channel.displayName, true))
            }.sortedByDescending { it.timestamp }
        }
        ChannelProfileActions(
            channel = channel,
            messages = streamerHistory,
            alreadySaved = state.savedChatters.any { it.key == channelKey },
            onDismiss = { selectedRoomChannel = null },
            onSave = { vm.saveChannelAsChatter(channel); selectedRoomChannel = null },
            onLoadAllChats = { result -> vm.loadAllChatsFor(channel, result) }
        )
    }
}

@Composable private fun rememberPresentedMessages(source: List<ChatMessage>, mode: MessageArrivalMode, catchUpSignal: Int): List<ChatMessage> {
    val latestSource by rememberUpdatedState(source)
    var presented by remember { mutableStateOf(source) }
    LaunchedEffect(mode, catchUpSignal) {
        // Start each presentation mode from the current feed. Only messages arriving
        // after this point are paced, avoiding a replay/jump of retained history.
        presented = latestSource
        while (true) {
            val latest = latestSource
            // Database history arrives just after first composition. Treat that first
            // non-empty snapshot as the baseline instead of replaying thousands of
            // retained messages through the paced-arrival queue.
            if (presented.isEmpty() && latest.isNotEmpty()) {
                presented = latest
                continue
            }
            if (mode == MessageArrivalMode.INSTANT) {
                presented = latest
                snapshotFlow { latestSource }.first { it != latest }
                continue
            }
            val sourceKeys = latest.asSequence().map { "${it.platform}:${it.messageId}" }.toHashSet()
            presented = presented.filter { "${it.platform}:${it.messageId}" in sourceKeys }
                .sortedWith(compareByDescending<ChatMessage> { it.timestamp }.thenByDescending { it.receivedAt })
            val shownKeys = presented.asSequence().map { "${it.platform}:${it.messageId}" }.toHashSet()
            val pending = latest.filter { "${it.platform}:${it.messageId}" !in shownKeys }
            if (pending.isEmpty()) {
                snapshotFlow { latestSource }.first { it != latest }
                continue
            }
            delay(messageArrivalDelayMillis(mode, pending.size))
            val newestSource = latestSource
            val newestShownKeys = presented.asSequence().map { "${it.platform}:${it.messageId}" }.toHashSet()
            val next = newestSource.filter { "${it.platform}:${it.messageId}" !in newestShownKeys }
                .minWithOrNull(compareBy<ChatMessage> { it.timestamp }.thenBy { it.receivedAt }) ?: continue
            presented = (listOf(next) + presented)
                .sortedWith(compareByDescending<ChatMessage> { it.timestamp }.thenByDescending { it.receivedAt })
        }
    }
    return presented
}

@Composable private fun ChannelFilterBar(channels: List<ChatChannel>, selectedKeys: Set<String>, onSelected: (String?) -> Unit, onReorder: (List<String>) -> Unit, focusedKey: String? = null, showAll: Boolean = true, mutedKeys: Set<String> = emptySet(), onToggleMute: ((String) -> Unit)? = null) {
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var draggedVirtualIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragDistanceX by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val slotWidthPx = with(LocalDensity.current) { 88.dp.toPx() }
    val channelSignature = channels.joinToString { it.key() }
    val cycleSize = channels.size + if (showAll) 1 else 0
    if (cycleSize == 0) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val estimatedContentWidth = 16.dp + (82.dp * channels.size) + (if (showAll) 76.dp else 0.dp) + (6.dp * (cycleSize - 1).coerceAtLeast(0))
        val shouldLoop = estimatedContentWidth > maxWidth
        key(shouldLoop, cycleSize) {
            val loopMiddle = remember(cycleSize) {
                val middle = Int.MAX_VALUE / 2
                middle - Math.floorMod(middle, cycleSize)
            }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (shouldLoop) loopMiddle else 0)
            LaunchedEffect(focusedKey, channelSignature, showAll, shouldLoop) {
                val channelIndex = channels.indexOfFirst { it.key() == focusedKey }
                if (channelIndex >= 0) {
                    val viewportWidth = listState.layoutInfo.viewportSize.width
                    val offsetInCycle = channelIndex + if (showAll) 1 else 0
                    val current = listState.firstVisibleItemIndex
                    val cycleStart = if (shouldLoop) current - Math.floorMod(current, cycleSize) else 0
                    val itemIndex = if (shouldLoop) {
                        listOf(cycleStart + offsetInCycle, cycleStart - cycleSize + offsetInCycle, cycleStart + cycleSize + offsetInCycle)
                            .filter { it >= 0 }.minByOrNull { kotlin.math.abs(it - current) } ?: (loopMiddle + offsetInCycle)
                    } else offsetInCycle
                    val itemWidth = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }?.size ?: slotWidthPx.roundToInt()
                    listState.animateScrollToItem(itemIndex, -((viewportWidth - itemWidth) / 2).coerceAtLeast(0))
                }
            }
            LazyRow(state = listState, userScrollEnabled = shouldLoop, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(if (shouldLoop) Int.MAX_VALUE else cycleSize, key = { it }) { virtualIndex ->
                    val offsetInCycle = Math.floorMod(virtualIndex, cycleSize)
                    if (showAll && offsetInCycle == 0) {
                        FilterChip(selectedKeys.isEmpty(), { onSelected(null) }, { Row(verticalAlignment = Alignment.CenterVertically) { Text("ALL"); if (mutedKeys.isNotEmpty()) { Spacer(Modifier.width(3.dp)); Icon(Icons.Outlined.VolumeOff, "${mutedKeys.size} muted", Modifier.size(11.dp)) } } })
                        return@items
                    }
                    val channel = channels[offsetInCycle - if (showAll) 1 else 0]
                    val channelKey = channel.key()
                    val isDragged = draggedKey == channelKey && draggedVirtualIndex == virtualIndex
                    val selected = channelKey in selectedKeys
                    Surface(
                        modifier = Modifier.size(width = 82.dp, height = 66.dp)
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                translationX = if (isDragged) dragOffsetX else 0f
                                scaleX = if (isDragged) 1.04f else 1f
                                scaleY = scaleX
                                alpha = if (channelKey in mutedKeys && !isDragged) .52f else 1f
                            }
                            .clickable { onSelected(channelKey) }
                            .pointerInput(channelKey, channelSignature, virtualIndex) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); draggedKey = channelKey; draggedVirtualIndex = virtualIndex; dragOffsetX = 0f; dragDistanceX = 0f },
                                    onDrag = { change, amount -> change.consume(); dragOffsetX += amount.x; dragDistanceX += kotlin.math.abs(amount.x) },
                                    onDragCancel = { draggedKey = null; draggedVirtualIndex = -1; dragOffsetX = 0f; dragDistanceX = 0f },
                                    onDragEnd = {
                                        val from = channels.indexOfFirst { it.key() == channelKey }
                                        if (dragDistanceX < slotWidthPx * .25f && onToggleMute != null) onToggleMute(channelKey)
                                        else if (from >= 0) {
                                            val to = (from + (dragOffsetX / slotWidthPx).roundToInt()).coerceIn(channels.indices)
                                            if (to != from) onReorder(moveChannelKey(channels.map { it.key() }, from, to))
                                        }
                                        draggedKey = null; draggedVirtualIndex = -1; dragOffsetX = 0f; dragDistanceX = 0f
                                    }
                                )
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) platformColor(channel.platform) else MaterialTheme.colorScheme.outline.copy(alpha = .65f))
                    ) { ChannelPortraitTile(channel, selected, channelKey in mutedKeys) }
                }
            }
        }
    }
}

@Composable private fun ChannelPortraitTile(channel: ChatChannel, selected: Boolean, muted: Boolean) {
    val showPictures = LocalShowProfilePictures.current
    var failed by remember(channel.avatarUrl) { mutableStateOf(false) }
    if (!showPictures) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                val labelColor = if (selected) platformColor(channel.platform) else MaterialTheme.colorScheme.onSurface
                Text(
                    channel.displayName,
                    color = labelColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (muted) Icon(Icons.Outlined.VolumeOff, "Muted from ALL", Modifier.padding(start = 3.dp).size(11.dp), tint = labelColor)
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)).background(channelColor(channel.key())), contentAlignment = Alignment.Center) {
        if (!channel.avatarUrl.isNullOrBlank() && !failed) {
            AsyncImage(
                model = channel.avatarUrl,
                contentDescription = channel.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true }
            )
        } else Text(channel.displayName.take(1).uppercase(), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
        if (selected) Box(Modifier.matchParentSize().background(platformColor(channel.platform).copy(alpha = .24f)))
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 5.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val labelColor = if (selected) platformColor(channel.platform) else Color.White
            Text(
                channel.displayName,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = .9f), Offset(0f, 1.5f), 3f))
            )
            if (muted) Icon(Icons.Outlined.VolumeOff, "Muted from ALL", Modifier.padding(start = 2.dp).size(9.dp), tint = labelColor)
        }
    }
}

@Composable private fun EmojiTrackerBar(messages: List<ChatMessage>, size: VisualSize) {
    val latestMessages by rememberUpdatedState(messages)
    val scopeSignature = remember(messages) {
        messages.asSequence().map { it.channel.key() }.distinct().sorted().joinToString("|")
    }
    var pulses by remember { mutableStateOf(emojiPulseBar(messages)) }
    LaunchedEffect(scopeSignature) { pulses = emojiPulseBar(messages) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            pulses = emojiPulseBar(latestMessages, nowMillis = System.currentTimeMillis())
        }
    }
    if (pulses.isEmpty()) return
    val barHeight = when (size) { VisualSize.SMALL -> 31.dp; VisualSize.MEDIUM -> 38.dp; VisualSize.LARGE -> 46.dp }
    val iconSize = when (size) { VisualSize.SMALL -> 16.dp; VisualSize.MEDIUM -> 20.dp; VisualSize.LARGE -> 26.dp }
    LazyRow(
        Modifier.fillMaxWidth().height(barHeight).background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .82f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item { Text("PULSE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary) }
        items(pulses, key = { it.signal.key }) { pulse ->
            PulseChip(pulse, iconSize)
        }
    }
}

@Composable private fun PulseChip(pulse: EmojiPulse, iconSize: androidx.compose.ui.unit.Dp) {
    var previousCount by remember(pulse.signal.key) { mutableIntStateOf(pulse.count) }
    var rising by remember(pulse.signal.key) { mutableStateOf(false) }
    LaunchedEffect(pulse.count) {
        val increased = pulse.count > previousCount
        previousCount = pulse.count
        if (increased) {
            rising = true
            delay(650)
            rising = false
        }
    }
    val background by animateColorAsState(
        targetValue = if (rising) Lime.copy(alpha = .28f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(if (rising) 100 else 420),
        label = "pulse increase"
    )
    Row(Modifier.background(background, RoundedCornerShape(14.dp)).padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        EmojiVisual(pulse.signal, iconSize)
        Spacer(Modifier.width(3.dp))
        Text(pulse.count.toString(), fontSize = 9.sp, color = if (rising) Lime else Color.Gray)
    }
}

private data class BurstParticle(val id: String, val signal: EmojiSignal, val x: Float, val y: Float)

private fun burstPositionAwayFrom(active: List<BurstParticle>): Pair<Float, Float> {
    if (active.isEmpty()) return Random.nextFloat() to Random.nextFloat()
    return List(12) { Random.nextFloat() to Random.nextFloat() }.maxBy { candidate ->
        active.minOf { particle ->
            val dx = candidate.first - particle.x
            val dy = candidate.second - particle.y
            dx * dx + dy * dy
        }
    }
}

@Composable private fun EmojiBurstOverlay(messages: List<ChatMessage>, enabled: Boolean, intensity: EmojiBurstIntensity, sizePercent: Float, opacityPercent: Float) {
    val particles = remember { mutableStateListOf<BurstParticle>() }
    var seenIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(messages.firstOrNull()?.messageId, enabled) {
        val recentMessages = messages.take(64)
        val currentIds = recentMessages.mapTo(mutableSetOf()) { it.messageId }
        val previous = seenIds
        if (enabled && previous != null) {
            val intensityCap = when (intensity) { EmojiBurstIntensity.LOW -> 1; EmojiBurstIntensity.MEDIUM -> 2; EmojiBurstIntensity.HIGH -> 3 }
            val sizeCap = if (sizePercent >= 500f) 2 else 3
            val availableSlots = (minOf(intensityCap, sizeCap) - particles.size).coerceAtLeast(0)
            recentMessages.filter { it.messageId !in previous }.take(availableSlots).forEach { message ->
                emojiSignals(message).take(1).forEach { signal ->
                    val (x, y) = burstPositionAwayFrom(particles)
                    particles += BurstParticle("${message.messageId}:${signal.key}", signal, x, y)
                }
            }
        }
        if (!enabled) particles.clear()
        seenIds = currentIds
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        particles.forEach { particle ->
            key(particle.id) {
                EmojiParticleView(particle, maxWidth, maxHeight, sizePercent, opacityPercent) { particles.remove(particle) }
            }
        }
    }
}

@Composable private fun EmojiParticleView(particle: BurstParticle, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, sizePercent: Float, opacityPercent: Float, onDone: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(particle.id) {
        alpha.animateTo(1f, tween(140))
        delay(700)
        alpha.animateTo(0f, tween(340))
        onDone()
    }
    val scale = (sizePercent / 100f).coerceIn(.25f, 10f)
    val particleSize = 48.dp * scale
    val visualSize = 44.dp * scale
    val opacity = (opacityPercent / 100f).coerceIn(.1f, 1f)
    // Keep the particle mostly inside the chat viewport at every configured size.
    // A small, capped bleed retains the playful edge-spawn effect without allowing
    // oversized emotes to appear as an unrecognizable off-screen fragment.
    val edgeBleed = (particleSize.value * .10f).coerceAtMost(18f).dp
    val minimumX = -edgeBleed
    val maximumX = width - particleSize + edgeBleed
    val minimumY = -edgeBleed
    val maximumY = height - particleSize + edgeBleed
    val offsetX = if (maximumX >= minimumX) minimumX + (maximumX - minimumX) * particle.x else (width - particleSize) / 2
    val offsetY = if (maximumY >= minimumY) minimumY + (maximumY - minimumY) * particle.y else (height - particleSize) / 2
    Box(
        Modifier.offset(offsetX, offsetY).size(particleSize)
            .graphicsLayer { this.alpha = alpha.value * opacity; scaleX = .72f + alpha.value * .38f; scaleY = scaleX },
        contentAlignment = Alignment.Center
    ) { EmojiVisual(particle.signal, visualSize) }
}

@Composable private fun EmojiVisual(signal: EmojiSignal, size: androidx.compose.ui.unit.Dp) {
    if (signal.imageUrl != null) AsyncImage(signal.imageUrl, signal.label, Modifier.size(size), contentScale = ContentScale.Fit)
    else Text(signal.label, fontSize = (size.value * .72f).sp)
}

@Composable private fun FeedModeControl(mode: FeedLayoutMode, onMode: (FeedLayoutMode) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        FeedLayoutMode.entries.forEachIndexed { index, value ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onMode(value) },
                shape = SegmentedButtonDefaults.itemShape(index, FeedLayoutMode.entries.size),
                label = { Text(when (value) { FeedLayoutMode.RIVER -> "RIVER"; FeedLayoutMode.ROOMS -> "ROOMS"; FeedLayoutMode.RUG -> "RUG" }, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                icon = { Icon(when (value) { FeedLayoutMode.RIVER -> Icons.Outlined.Waves; FeedLayoutMode.ROOMS -> Icons.Outlined.ViewColumn; FeedLayoutMode.RUG -> Icons.Outlined.ViewStream }, null, Modifier.size(14.dp)) }
            )
        }
    }
}

@Composable private fun RugFeed(rooms: List<ChannelRoom>, fastestFirst: Boolean, onChannelProfile: (ChatChannel) -> Unit, onMessage: (ChatMessage) -> Unit) {
    var rateClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); rateClock = System.currentTimeMillis() } }
    val orderedRooms = remember(rooms, fastestFirst, rateClock) {
        val rates = rooms.associate { room -> room.channel.key() to rugMessageRatePerMinute(room.messages.map(ChatMessage::timestamp), rateClock) }
        if (fastestFirst) rooms.withIndex().sortedWith(
            compareByDescending<IndexedValue<ChannelRoom>> { rates[it.value.channel.key()] ?: 0f }
                .thenBy { it.index }
        ).map { it.value } else rooms
    }
    if (orderedRooms.none { it.messages.isNotEmpty() }) EmptyChat {}
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items(orderedRooms, key = { it.channel.key() }) { room -> RugLane(room, rateClock, onChannelProfile, onMessage) }
    }
}

@Composable private fun RugLane(room: ChannelRoom, nowMillis: Long, onChannelProfile: (ChatChannel) -> Unit, onMessage: (ChatMessage) -> Unit) {
    val context = LocalContext.current
    val watchUrl = remember(room.channel) { room.channel.publicWatchUrl() }
    val chronological = remember(room.messages) { room.messages.sortedBy { it.timestamp } }
    val latestMessages by rememberUpdatedState(chronological)
    val timestamps = remember(chronological) { chronological.map(ChatMessage::timestamp) }
    val uniqueChatters = remember(room.messages) {
        room.messages.asSequence().map { message -> message.sender.platformUserId ?: message.sender.username.lowercase() }.distinct().count()
    }
    val activity = remember(timestamps, nowMillis) { rugActivity(timestamps, nowMillis) }
    val latestTimestamps by rememberUpdatedState(timestamps)
    val tickerState = rememberLazyListState()
    LaunchedEffect(tickerState) {
        snapshotFlow { tickerState.layoutInfo.totalItemsCount }.first { it > 0 }
        tickerState.scrollToItem((latestMessages.lastIndex - 3).coerceAtLeast(0))
        while (true) {
            val visible = tickerState.layoutInfo.visibleItemsInfo
            val averageItemWidth = visible.map { it.size }.average().takeIf { !it.isNaN() }?.toFloat() ?: 180f
            val speed = rugTickerPixelsPerSecond(latestTimestamps, System.currentTimeMillis(), averageItemWidth)
            val consumed = tickerState.animateScrollBy(speed * 1.2f, tween(durationMillis = 1_200, easing = LinearEasing))
            if (kotlin.math.abs(consumed) < 1f) delay(180)
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp) {
        Column(Modifier.padding(vertical = 7.dp)) {
            Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { onChannelProfile(room.channel) }, verticalAlignment = Alignment.CenterVertically) {
                    ChannelAvatar(room.channel, 30.dp); Spacer(Modifier.width(7.dp))
                    Text(room.channel.displayName, color = platformColor(room.channel.platform), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "$uniqueChatters CHATTERS · ${activity.messagesPerMinute}/MIN ${activity.trend.symbol}",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "WATCH NOW",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (watchUrl != null) platformColor(room.channel.platform) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (watchUrl != null) Modifier.clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl))) }
                    } else Modifier
                )
            }
            Spacer(Modifier.height(5.dp))
            if (chronological.isEmpty()) Text("Waiting for chat…", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
            else LazyRow(state = tickerState, contentPadding = PaddingValues(horizontal = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chronological, key = { messageVisualKey(it) }) { message -> RugMessage(message) { onMessage(message) } }
            }
        }
    }
}

@Composable private fun RugMessage(message: ChatMessage, onClick: () -> Unit) {
    Surface(Modifier.clickable(onClick = onClick), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (LocalShowProfilePictures.current && !message.sender.avatarUrl.isNullOrBlank()) {
                ChatterAvatar(message.sender, size = 19.dp)
                Spacer(Modifier.width(5.dp))
            }
            Text(displayChatterName(message.sender), color = savedChatterColor(message), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(":", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 3.dp))
            CompositionLocalProvider(LocalChatFontSize provides 12f) { InlineChatMessage(message) }
        }
    }
}

@Composable private fun RiverFeed(messages: List<ChatMessage>, startWithAutoScroll: Boolean, animateAutoFollow: Boolean, hasPresentationBacklog: Boolean, onCatchUp: () -> Unit, onMessage: (ChatMessage) -> Unit) {
    val groups = remember(messages) { groupConsecutiveMessages(messages) }
    AutoFollowingMessageList(groups, onMessage, Modifier.fillMaxSize(), startWithAutoScroll, animateAutoFollow, hasPresentationBacklog, onCatchUp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RoomsFeed(rooms: List<ChannelRoom>, startWithAutoScroll: Boolean, targetChannelKey: String?, onCurrentChannel: (String) -> Unit, animateAutoFollow: Boolean, hasPresentationBacklog: Boolean, onCatchUp: () -> Unit, onMessage: (ChatMessage) -> Unit, onChannelProfile: (ChatChannel) -> Unit) {
    if (rooms.isEmpty()) return
    val roomSignature = rooms.joinToString { it.channel.key() }
    key(roomSignature) {
        var fullscreenRoomKey by rememberSaveable { mutableStateOf<String?>(null) }
        val pageCount = if (rooms.size == 1) 1 else Int.MAX_VALUE
        val pagerState = rememberPagerState(initialPage = if (rooms.size == 1) 0 else circularRoomStart(rooms.size), pageCount = { pageCount })
        LaunchedEffect(pagerState, roomSignature) {
            snapshotFlow { pagerState.settledPage }.collect { page ->
                onCurrentChannel(rooms[circularRoomIndex(page, rooms.size)].channel.key())
            }
        }
        LaunchedEffect(targetChannelKey, roomSignature) {
            val targetIndex = rooms.indexOfFirst { it.channel.key() == targetChannelKey }
            if (targetIndex >= 0) {
                val currentIndex = circularRoomIndex(pagerState.currentPage, rooms.size)
                val forward = Math.floorMod(targetIndex - currentIndex, rooms.size)
                val delta = if (forward > rooms.size / 2) forward - rooms.size else forward
                if (delta != 0) pagerState.animateScrollToPage(pagerState.currentPage + delta)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sidePadding = ((maxWidth - 286.dp) / 2).coerceAtLeast(8.dp)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = sidePadding, vertical = 8.dp),
                pageSpacing = 8.dp,
                pageSize = PageSize.Fixed(286.dp),
                flingBehavior = PagerDefaults.flingBehavior(state = pagerState, pagerSnapDistance = PagerSnapDistance.atMost(1)),
                key = { it }
            ) { virtualIndex ->
                val room = rooms[circularRoomIndex(virtualIndex, rooms.size)]
                RoomPane(room, startWithAutoScroll, animateAutoFollow, hasPresentationBacklog, onCatchUp, onMessage, onChannelProfile) { fullscreenRoomKey = room.channel.key() }
            }
        }
        fullscreenRoomKey?.let { key ->
            rooms.firstOrNull { it.channel.key() == key }?.let { room ->
                FullscreenRoomDialog(room, startWithAutoScroll, animateAutoFollow, hasPresentationBacklog, onCatchUp, onMessage, onChannelProfile) { fullscreenRoomKey = null }
            }
        }
    }
}

@Composable private fun RoomPane(room: ChannelRoom, startWithAutoScroll: Boolean, animateAutoFollow: Boolean, hasPresentationBacklog: Boolean, onCatchUp: () -> Unit, onMessage: (ChatMessage) -> Unit, onChannelProfile: (ChatChannel) -> Unit, onFullscreen: () -> Unit) {
    val context = LocalContext.current
    val watchUrl = remember(room.channel) { room.channel.publicWatchUrl() }
    Surface(Modifier.width(286.dp).fillMaxHeight(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable { onChannelProfile(room.channel) }, verticalAlignment = Alignment.CenterVertically) {
                    ChannelAvatar(room.channel, 52.dp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(room.channel.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(room.channel.platform.name, color = platformColor(room.channel.platform), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            if (watchUrl != null) {
                                Spacer(Modifier.width(3.dp))
                                Icon(
                                    Icons.Outlined.Link,
                                    "Open ${room.channel.displayName} channel",
                                    Modifier.size(15.dp).clickable {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl))) }
                                    },
                                    tint = platformColor(room.channel.platform)
                                )
                            }
                        }
                    }
                }
                Text("${room.messages.size} MSGS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            }
            HorizontalDivider()
            val groups = remember(room.messages) { groupConsecutiveMessages(room.messages) }
            AutoFollowingMessageList(groups, onMessage, Modifier.weight(1f), startWithAutoScroll, animateAutoFollow, hasPresentationBacklog, onCatchUp, showChannelIdentity = false, onFullscreen = onFullscreen)
        }
    }
}

@Composable private fun FullscreenRoomDialog(room: ChannelRoom, startWithAutoScroll: Boolean, animateAutoFollow: Boolean, hasPresentationBacklog: Boolean, onCatchUp: () -> Unit, onMessage: (ChatMessage) -> Unit, onChannelProfile: (ChatChannel) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                val groups = remember(room.messages) { groupConsecutiveMessages(room.messages) }
                AutoFollowingMessageList(groups, onMessage, Modifier.weight(1f), startWithAutoScroll, animateAutoFollow, hasPresentationBacklog, onCatchUp, showChannelIdentity = false)
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider()
                        Box(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 12.dp, vertical = 7.dp)) {
                            Row(Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(end = 56.dp).clickable { onChannelProfile(room.channel) }, verticalAlignment = Alignment.CenterVertically) {
                                ChannelAvatar(room.channel, 46.dp); Spacer(Modifier.width(9.dp))
                                Column {
                                    Text(room.channel.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${room.channel.platform.name} · ${room.messages.size} MSGS", color = platformColor(room.channel.platform), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                                Icon(Icons.Outlined.FullscreenExit, "Exit fullscreen room", modifier = Modifier.size(31.dp))
                            }
                            Text("POWERED BY nabchat", fontSize = 7.sp, color = Color.Gray, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
                // Some OEM navigation modes overlay dialogs even when the window reports
                // fitted system bars. Reserve a visible navigation shelf so the entire
                // room title remains above the phone controls on those devices.
                Spacer(Modifier.fillMaxWidth().height(88.dp))
            }
        }
    }
}

@Composable private fun AutoFollowingMessageList(groups: List<MessageGroup>, onMessage: (ChatMessage) -> Unit, modifier: Modifier = Modifier, startWithAutoScroll: Boolean = true, animateAutoFollow: Boolean = false, hasPresentationBacklog: Boolean = false, onCatchUp: () -> Unit = {}, showChannelIdentity: Boolean = true, onFullscreen: (() -> Unit)? = null) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoFollow by rememberSaveable { mutableStateOf(startWithAutoScroll) }
    val awayFromNewest by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8 } }
    val userDragging by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(userDragging, awayFromNewest) {
        if (userDragging && awayFromNewest) autoFollow = false
    }
    LaunchedEffect(groups.firstOrNull()?.newest?.messageId) {
        if (autoFollow && groups.isNotEmpty()) {
            // The paced queue controls motion. Keeping the reverse-layout anchor fixed
            // prevents competing list animations from producing jumps and flicker.
            listState.scrollToItem(0)
        }
    }

    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(top = 4.dp, bottom = 54.dp)) {
            items(groups, key = { it.stableKey }) { group -> MessageGroupRow(group, showChannelIdentity, onMessage) }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (onFullscreen != null) IconButton(onClick = onFullscreen) {
                Icon(Icons.Outlined.Fullscreen, "Fullscreen this room", modifier = Modifier.size(27.dp))
            }
            IconButton(
                onClick = {
                    if (groups.isNotEmpty()) {
                        autoFollow = false
                        scope.launch { listState.scrollToItem(groups.lastIndex) }
                    }
                },
                modifier = Modifier.graphicsLayer { alpha = if (groups.isEmpty()) .45f else 1f }
            ) { Icon(Icons.Outlined.VerticalAlignTop, "Jump to oldest messages") }
            IconButton(
                onClick = {
                    autoFollow = true
                    onCatchUp()
                    if (groups.isNotEmpty()) scope.launch { listState.scrollToItem(0) }
                },
                modifier = Modifier.graphicsLayer { alpha = if (groups.isEmpty() && !hasPresentationBacklog) .45f else 1f }
            ) {
                Icon(
                    Icons.Outlined.VerticalAlignBottom,
                    if (hasPresentationBacklog) "Show queued messages and jump to newest" else "Jump to newest messages"
                )
            }
        }
    }
}

@Composable private fun ChannelAvatar(channel: ChatChannel, size: androidx.compose.ui.unit.Dp) {
    if (!LocalShowProfilePictures.current) return
    Box(
        Modifier.size(size).background(channelColor(channel.key()), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (!channel.avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = channel.avatarUrl,
                contentDescription = channel.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Text(channel.displayName.take(1).uppercase(), fontSize = (size.value * .45f).sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}

private fun messageVisualKey(message: ChatMessage) = "${message.platform}:${message.messageId}"

@Composable private fun EmptyChat(onAdd: () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.Forum, null, Modifier.size(42.dp), tint = Lime); Spacer(Modifier.height(10.dp)); Text("Your combined chat starts here", fontWeight = FontWeight.Bold); Text("Add a chat channel to begin.", color = Color.Gray); Spacer(Modifier.height(12.dp)); Button(onClick = onAdd) { Text("ADD CHANNEL") } }
}

@Composable private fun NoActiveChannels() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(34.dp), tint = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Text("Waiting for chat…", fontWeight = FontWeight.Bold)
        Text("Channels return automatically when a new message arrives.", color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable private fun MessageRow(message: ChatMessage, onActions: () -> Unit) {
    val showTimestamps = LocalShowTimestamps.current
    val time = if (showTimestamps) remember(message.timestamp) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(message.timestamp)) } else ""
    val channelColor = platformColor(message.platform)
    Row(Modifier.fillMaxWidth().pointerInput(message.messageId) { detectTapGestures(onLongPress = { onActions() }, onTap = { onActions() }) }.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        ChannelAvatar(message.channel, 36.dp)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.channel.displayName, color = channelColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(5.dp))
                message.badges.filterNot { it.equals("verified", true) || it.equals("verified_user", true) }.take(2).forEach { badge -> Text(badge.take(3).uppercase(), fontSize = 8.sp, color = Color.Gray, modifier = Modifier.padding(end = 3.dp)) }
                Text(displayChatterName(message.sender), color = savedChatterColor(message), modifier = Modifier.clickable(onClick = onActions), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                VerifiedMarker(message.sender)
                ChatterAvatar(message.sender)
                Spacer(Modifier.weight(1f)); if (showTimestamps) Text(time, fontSize = 9.sp, color = platformColor(message.platform))
            }
            InlineChatMessage(message)
        }
    }
}

@Composable private fun MessageGroupRow(group: MessageGroup, showChannelIdentity: Boolean, onMessage: (ChatMessage) -> Unit) {
    val newest = group.newest
    val showTimestamps = LocalShowTimestamps.current
    val time = if (showTimestamps) remember(newest.timestamp) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(newest.timestamp)) } else ""
    val channelColor = platformColor(newest.platform)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (showChannelIdentity) 3.dp else 5.dp), verticalAlignment = Alignment.Top) {
        if (showChannelIdentity) {
            ChannelAvatar(newest.channel, 36.dp)
            Spacer(Modifier.width(7.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showChannelIdentity) {
                    Text(newest.channel.displayName, color = channelColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(5.dp))
                }
                if (showChannelIdentity) newest.badges.filterNot { it.equals("verified", true) || it.equals("verified_user", true) }.take(2).forEach { badge -> Text(badge.take(3).uppercase(), fontSize = 8.sp, color = Color.Gray, modifier = Modifier.padding(end = 3.dp)) }
                if (!showChannelIdentity) Text("@", color = platformColor(newest.platform), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(displayChatterName(newest.sender), color = savedChatterColor(newest), modifier = Modifier.clickable { onMessage(newest) }, fontSize = if (showChannelIdentity) 13.sp else 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                VerifiedMarker(newest.sender)
                if (!showChannelIdentity && group.messages.size > 1) Text("  ×${group.messages.size}", fontSize = 9.sp, color = Color.Gray)
                if (!showChannelIdentity) newest.badges.filterNot { it.equals("verified", true) || it.equals("verified_user", true) }.take(2).forEach { badge -> Text(badge.take(3).uppercase(), fontSize = 8.sp, color = Color.Gray, modifier = Modifier.padding(start = 3.dp)) }
                if (showChannelIdentity && group.messages.size > 1) Text("  ×${group.messages.size}", fontSize = 9.sp, color = Color.Gray)
                Spacer(Modifier.weight(1f)); if (showTimestamps) Text(time, fontSize = 9.sp, color = channelColor)
            }
            group.messages.asReversed().forEach { message ->
                key(messageVisualKey(message)) {
                    InlineChatMessage(
                        message,
                        modifier = Modifier.fillMaxWidth().pointerInput(message.messageId) { detectTapGestures(onLongPress = { onMessage(message) }, onTap = { onMessage(message) }) }.padding(top = if (showChannelIdentity) 1.dp else 2.dp, bottom = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable private fun InlineChatMessage(message: ChatMessage, modifier: Modifier = Modifier) {
    val chatSize = LocalChatFontSize.current
    val emoteSize = if (message.isEmoteOnly) chatSize * 1.35f else chatSize * 1.12f
    val inlineContent = remember(message.messageId, message.contentSegments, emoteSize) {
        buildMap<String, InlineTextContent> {
            message.contentSegments.forEachIndexed { index, segment ->
                if (segment is ChatContentSegment.EmoteSegment) {
                    val emote = segment.emote
                    put("emote-$index-${emote.id}", InlineTextContent(
                        Placeholder(emoteSize.sp, emoteSize.sp, PlaceholderVerticalAlign.TextCenter)
                    ) {
                        var failed by remember(emote.imageUrl) { mutableStateOf(emote.imageUrl.isBlank()) }
                        if (failed) {
                            Text(emote.name, fontSize = (chatSize * .58f).sp, maxLines = 1)
                        } else {
                            AsyncImage(
                                model = emote.imageUrl,
                                contentDescription = emote.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                                onError = { failed = true }
                            )
                        }
                    })
                }
            }
        }
    }
    val annotated = remember(message.messageId, message.contentSegments) {
        buildAnnotatedString {
            message.contentSegments.forEachIndexed { index, segment ->
                when (segment) {
                    is ChatContentSegment.TextSegment -> append(segment.text)
                    is ChatContentSegment.EmoteSegment -> appendInlineContent("emote-$index-${segment.emote.id}", segment.emote.name)
                }
            }
        }
    }
    Text(
        text = annotated,
        inlineContent = inlineContent,
        modifier = modifier,
        fontSize = chatSize.sp,
        lineHeight = maxOf(chatSize + 3f, emoteSize + 2f).sp
    )
}

private fun channelColor(key: String): Color { val palette = listOf(0xFF3182CE, 0xFFD15A77, 0xFF8B5CF6, 0xFFE07A2D, 0xFF188F75); return Color(palette[(key.hashCode() and Int.MAX_VALUE) % palette.size]) }
private fun displayChatterName(user: ChatUser): String = user.displayName.trim().removePrefix("@").ifBlank { user.username.trim().removePrefix("@") }
private fun platformColor(platform: ChatPlatform) = when (platform) {
    ChatPlatform.KICK -> Color(0xFF46C66B)
    ChatPlatform.TWITCH -> Color(0xFF9146FF)
    ChatPlatform.YOUTUBE -> Color(0xFFFF4E45)
    ChatPlatform.RUMBLE -> Color.White
}

@Composable private fun savedChatterColor(message: ChatMessage): Color = LocalSavedChatterColors.current[
    savedChatterKey(message.platform, message.sender.platformUserId, message.sender.username)
] ?: MaterialTheme.colorScheme.onSurface

@Composable private fun VerifiedMarker(user: ChatUser) {
    if (user.isVerified()) Icon(Icons.Outlined.Verified, "Verified chatter", Modifier.padding(start = 3.dp).size(13.dp), tint = Color(0xFF3B82F6))
}

@Composable private fun ChatterAvatar(user: ChatUser, size: androidx.compose.ui.unit.Dp = 15.dp, modifier: Modifier = Modifier) {
    if (!LocalShowProfilePictures.current) return
    val url = user.avatarUrl ?: return
    var failed by remember(url) { mutableStateOf(false) }
    if (!failed) AsyncImage(
        model = url,
        contentDescription = "${user.displayName} profile picture",
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
        onError = { failed = true }
    )
}

@Composable private fun AddChannelDialog(fake: Boolean, twitchConnected: Boolean, onDismiss: () -> Unit, onAdd: (String, ChatPlatform, (Result<Unit>) -> Unit) -> Unit) {
    var input by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }; var loading by remember { mutableStateOf(false) }
    var platform by remember { mutableStateOf<ChatPlatform?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add channel") }, text = { Column {
        ChatPlatform.entries.filterNot { it == ChatPlatform.RUMBLE }.chunked(2).forEach { rowPlatforms ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPlatforms.forEach { value ->
                    val selected = platform == value
                    val sourceColor = platformColor(value)
                    val buttonColor = sourceColor
                    OutlinedButton(
                        onClick = { platform = value; error = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) sourceColor.copy(alpha = .18f) else Color.Transparent,
                            contentColor = buttonColor
                        ),
                        border = BorderStroke(if (selected) 2.dp else 1.dp, buttonColor.copy(alpha = if (selected) .95f else .55f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        if (selected) { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)) }
                        Text(value.name, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
        }
        if (platform == null) Text("Choose a platform to continue", fontSize = 12.sp, color = Color.Gray)
        if (platform == ChatPlatform.TWITCH && !twitchConnected) Text("You can save this channel now. Twitch authorization is required before its chat begins.", fontSize = 11.sp, color = Color.Gray)
        if (platform == ChatPlatform.YOUTUBE) Text("Paste the live-video link for the most reliable match · no sign-in required", fontSize = 12.sp, color = Color.Gray)
        if (fake) Text("Simulation mode is on", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(12.dp)); OutlinedTextField(
            input,
            { input = it; error = null },
            label = { Text(if (platform == ChatPlatform.YOUTUBE) "Channel, @handle, or live link" else "Username/channel") },
            singleLine = true,
            enabled = platform != null,
            isError = error != null,
            colors = platform?.let { selected -> OutlinedTextFieldDefaults.colors(focusedBorderColor = platformColor(selected), focusedLabelColor = platformColor(selected), cursorColor = platformColor(selected)) } ?: OutlinedTextFieldDefaults.colors()
        ); error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    } }, confirmButton = { Button(enabled = platform != null && input.isNotBlank() && !loading, onClick = { platform?.let { selected -> loading = true; onAdd(input, selected) { result -> loading = false; error = result.exceptionOrNull()?.message ?: "Could not resolve that channel." } } }) { Text(if (loading) "CHECKING…" else "ADD") } }, dismissButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("CANCEL") } })
}

@Composable private fun ChannelProfileActions(channel: ChatChannel, messages: List<ChatMessage>, alreadySaved: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, onLoadAllChats: ((Result<List<ChatMessage>>) -> Unit) -> Unit) {
    val context = LocalContext.current
    val watchUrl = remember(channel) { channel.publicWatchUrl() }
    var allChats by remember(channel.key()) { mutableStateOf<List<ChatMessage>?>(null) }
    var loadingAll by remember(channel.key()) { mutableStateOf(false) }
    var allChatsError by remember(channel.key()) { mutableStateOf<String?>(null) }
    if (allChats != null) {
        val historyState = rememberLazyListState()
        val historyScope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = onDismiss, title = { Column { Text(channel.displayName); Text("ALL CHANNEL CHATS · ${allChats!!.size}", fontSize = 9.sp, color = platformColor(channel.platform), fontWeight = FontWeight.Bold) } }, text = {
            if (allChats!!.isEmpty()) Text("No saved chat messages for this channel.", color = Color.Gray)
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), state = historyState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(allChats!!, key = { "${it.platform}:${it.messageId}" }) { historical ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(displayChatterName(historical.sender), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(SimpleDateFormat("MMM d · HH:mm:ss", Locale.getDefault()).format(Date(historical.timestamp)), fontSize = 8.sp, color = platformColor(historical.platform))
                        }
                        CompositionLocalProvider(LocalChatFontSize provides 12f) { InlineChatMessage(historical) }
                    }
                }
            }
        }, confirmButton = { Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { historyScope.launch { historyState.animateScrollToItem(0) } }, enabled = allChats!!.isNotEmpty()) { Icon(Icons.Outlined.VerticalAlignTop, "Go to newest chat") }
            IconButton(onClick = { historyScope.launch { historyState.animateScrollToItem((allChats!!.lastIndex).coerceAtLeast(0)) } }, enabled = allChats!!.isNotEmpty()) { Icon(Icons.Outlined.VerticalAlignBottom, "Go to oldest chat") }
            TextButton(onClick = onDismiss) { Text("BACK") }
        } })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ChannelAvatar(channel, 42.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(channel.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${channel.platform.name} CHANNEL", color = platformColor(channel.platform), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                if (watchUrl != null) IconButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl))) }
                }) { Icon(Icons.Outlined.OpenInNew, "Open channel") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ActionRow(
                    if (alreadySaved) Icons.Outlined.BookmarkAdded else Icons.Outlined.PersonAdd,
                    if (alreadySaved) "Saved in Chatters" else "Save ${channel.displayName} as chatter",
                    enabled = !alreadySaved,
                    onClick = onSave
                )
                ActionRow(Icons.Outlined.History, if (loadingAll) "Loading channel chats…" else "View all chats", enabled = !loadingAll) {
                    loadingAll = true; allChatsError = null
                    onLoadAllChats { result -> loadingAll = false; result.onSuccess { allChats = it }.onFailure { allChatsError = it.message ?: "Could not load channel chats." } }
                }
                allChatsError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
                if (messages.isNotEmpty()) {
                    Text("RECENT CHAT ACROSS OBSERVED CHANNELS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    messages.take(6).forEach { message ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("${message.channel.displayName} · ${message.platform.name}", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = platformColor(message.platform))
                            CompositionLocalProvider(LocalChatFontSize provides 12f) { InlineChatMessage(message) }
                        }
                    }
                    if (messages.size > 6) Text("${messages.size - 6} more retained messages", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                } else {
                    Text("No retained messages from this streamer in chats nabchat has observed yet.", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable private fun MessageActions(message: ChatMessage, chatterHistory: List<ChatMessage> = listOf(message), onDismiss: () -> Unit, onHide: (HiddenScope) -> Unit, onLabelBot: () -> Unit, onSaveChatter: () -> Unit, onLoadAllChats: ((Result<List<ChatMessage>>) -> Unit) -> Unit, alreadySaved: Boolean) {
    val context = LocalContext.current
    val watchUrl = remember(message.channel) { message.channel.publicWatchUrl() }
    var showRecent by rememberSaveable(message.messageId) { mutableStateOf(false) }
    var allChats by remember(message.messageId) { mutableStateOf<List<ChatMessage>?>(null) }
    var loadingAll by remember(message.messageId) { mutableStateOf(false) }
    var allChatsError by remember(message.messageId) { mutableStateOf<String?>(null) }
    val olderMessages = remember(chatterHistory, message.messageId) {
        chatterHistory.filterNot { it.platform == message.platform && it.messageId == message.messageId }
    }
    if (allChats != null) {
        val historyState = rememberLazyListState()
        val historyScope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = onDismiss, title = { Column { Text(displayChatterName(message.sender)); Text("ALL SAVED CHATS · ${allChats!!.size}", fontSize = 9.sp, color = platformColor(message.platform), fontWeight = FontWeight.Bold) } }, text = {
            if (allChats!!.isEmpty()) Text("No saved chat messages for this chatter.", color = Color.Gray)
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), state = historyState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(allChats!!, key = { "${it.platform}:${it.messageId}" }) { historical ->
                    Column(Modifier.fillMaxWidth()) {
                        Text("${historical.channel.displayName} · ${historical.platform.name}", color = platformColor(historical.platform), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        CompositionLocalProvider(LocalChatFontSize provides 12f) { InlineChatMessage(historical) }
                    }
                }
            }
        }, confirmButton = { Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { historyScope.launch { historyState.animateScrollToItem(0) } }, enabled = allChats!!.isNotEmpty()) { Icon(Icons.Outlined.VerticalAlignTop, "Go to newest chat") }
            IconButton(onClick = { historyScope.launch { historyState.animateScrollToItem((allChats!!.lastIndex).coerceAtLeast(0)) } }, enabled = allChats!!.isNotEmpty()) { Icon(Icons.Outlined.VerticalAlignBottom, "Go to oldest chat") }
            TextButton(onClick = onDismiss) { Text("BACK") }
        } })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(displayChatterName(message.sender)); VerifiedMarker(message.sender) }
                Text("@${message.sender.username.removePrefix("@")}", fontSize = 11.sp, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 112.dp)) {
                if (watchUrl != null) IconButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl))) }
                    onDismiss()
                }) { Icon(Icons.Outlined.OpenInNew, "Open source stream") }
                Text(
                    "${message.platform.name} · ${message.channel.displayName}",
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = platformColor(message.platform),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        ActionRow(if (alreadySaved) Icons.Outlined.BookmarkAdded else Icons.Outlined.PersonAdd, if (alreadySaved) "Saved in Chatters" else "Save @${message.sender.username} as chatter", enabled = !alreadySaved) { onSaveChatter() }
        ActionRow(Icons.Outlined.History, if (loadingAll) "Loading saved chats…" else "View all chats", enabled = !loadingAll) {
            loadingAll = true; allChatsError = null
            onLoadAllChats { result -> loadingAll = false; result.onSuccess { allChats = it }.onFailure { allChatsError = it.message ?: "Could not load saved chats." } }
        }
        allChatsError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp)) }
        ActionRow(Icons.Outlined.SmartToy, "Label as bot") { onLabelBot() }
        Text("Hides this account everywhere and excludes it from displayed/user analytics. Local only.", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 10.dp))
        ActionRow(Icons.Outlined.VisibilityOff, "Hide in ${message.channel.displayName}") { onHide(HiddenScope.THIS_CHANNEL) }
        ActionRow(Icons.Outlined.HideSource, "Hide everywhere") { onHide(HiddenScope.EVERYWHERE) }
        ActionRow(Icons.Outlined.ContentCopy, "Copy message") { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("nabchat message", message.text)); onDismiss() }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            CompositionLocalProvider(LocalChatFontSize provides 12f) {
                InlineChatMessage(message, Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
        if (olderMessages.isNotEmpty()) {
            TextButton(onClick = { showRecent = !showRecent }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Icon(if (showRecent) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (showRecent) "HIDE RECENT MESSAGES" else "SHOW RECENT MESSAGES", fontSize = 10.sp)
            }
            if (showRecent) {
                olderMessages.take(8).forEach { historical ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("${historical.channel.displayName} · ${historical.platform.name}", color = platformColor(historical.platform), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        CompositionLocalProvider(LocalChatFontSize provides 12f) { InlineChatMessage(historical) }
                    }
                }
                if (olderMessages.size > 8) Text("${olderMessages.size - 8} more retained messages in Chatters", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
            }
        }
    } }, confirmButton = {})
}

@Composable private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) = TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) { Icon(icon, null); Spacer(Modifier.width(10.dp)); Text(text, Modifier.weight(1f)) }

@Composable private fun AnalyticsScreen(state: MainUiState, vm: MainViewModel) {
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var confirmReset by remember { mutableStateOf(false) }
    var selectedChatterMessages by remember { mutableStateOf<List<ChatMessage>?>(null) }
    val context = LocalContext.current
    // emojiMessages is the policy-filtered source before the display-only emote-row preference.
    val selectedMessages = remember(state.emojiMessages, selectedKeys) { filterAnalyticsMessages(state.emojiMessages, selectedKeys) }
    val cutoff = System.currentTimeMillis() - 60_000
    val fiveMinuteCutoff = System.currentTimeMillis() - 300_000
    val emoteUses = selectedMessages.sumOf { emojiSignals(it).size }
    val activeSpanMinutes = selectedMessages.takeIf { it.size > 1 }?.let {
        ((it.maxOf { message -> message.timestamp } - it.minOf { message -> message.timestamp }).coerceAtLeast(60_000L) / 60_000.0)
    } ?: 1.0
    val scopedValues = listOf(
        "Messages displayed" to selectedMessages.size,
        "Messages / minute" to selectedMessages.count { it.timestamp >= cutoff },
        "Last 5 minutes" to selectedMessages.count { it.timestamp >= fiveMinuteCutoff },
        "Average / minute" to String.format(Locale.US, "%.1f", selectedMessages.size / activeSpanMinutes),
        "Unique chatters" to selectedMessages.map { it.sender.platformUserId ?: it.sender.username.lowercase() }.toSet().size,
        "Emote-only" to selectedMessages.count { it.isEmoteOnly },
        "Text messages" to selectedMessages.count { !it.isEmoteOnly },
        "Emote uses" to emoteUses
    )
    val globalValues = listOf("Messages received" to state.analytics.totalReceived, "Messages filtered" to state.analytics.filtered, "Database messages" to state.analytics.databaseMessages, "Reconnects" to state.analytics.reconnects, "Parse failures" to state.analytics.parseFailures)
    val exportCsv = remember(state.emojiMessages, state.channels, selectedKeys, state.analytics) {
        buildAnalyticsCsv(selectedMessages, state.channels.filter { it.enabled && (selectedKeys.isEmpty() || it.key() in selectedKeys) }, state.analytics)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(exportCsv) } }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live / local analytics", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { exportLauncher.launch("nabchat-analytics-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.csv") }) { Icon(Icons.Outlined.FileDownload, "Export analytics") }
            IconButton(onClick = { confirmReset = true }) { Icon(Icons.Outlined.Refresh, "Reset analytics") }
        }
        Text(if (selectedKeys.isEmpty()) "All enabled channels" else state.channels.filter { it.key() in selectedKeys }.joinToString { it.displayName }, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scopedValues.chunked(2)) { row -> MetricRow(row) }
            if (state.billing.ownershipChecked && !state.billing.isPlus) item { NabchatBannerAd(dismissible = false) {} }
            item { SectionTitle("Chatter activity") }
            val chatterGroups = selectedMessages.groupBy { savedChatterKey(it.platform, it.sender.platformUserId, it.sender.username) }
                .values.sortedByDescending { it.size }.take(20)
            if (chatterGroups.isEmpty()) item { Text("No chatter activity yet.", color = Color.Gray, fontSize = 12.sp) }
            items(chatterGroups, key = { group -> savedChatterKey(group.first().platform, group.first().sender.platformUserId, group.first().sender.username) }) { group ->
                ChatterAnalyticsRow(group) { selectedChatterMessages = group.sortedByDescending { it.timestamp } }
            }
            if (selectedKeys.isEmpty()) {
                item { SectionTitle("Transport / storage") }
                items(globalValues.chunked(2)) { row -> MetricRow(row) }
                item { SectionTitle("Channel breakdown") }
                items(sortChannelsByActivity(state.channels.filter { it.enabled }, state.messages, cutoff), key = { it.key() }) { channel ->
                    val messages = state.messages.filter { it.channel.key() == channel.key() }
                    ChannelAnalyticsRow(channel, messages, cutoff) { selectedKeys = toggleChannelSelection(selectedKeys, channel.key()) }
                }
                if (state.billing.ownershipChecked && !state.billing.isPlus) item { NabchatBannerAd(dismissible = false) {} }
            }
        }
        HorizontalDivider()
        ChannelFilterBar(
            state.channels.filter { it.enabled }, selectedKeys,
            onSelected = { key -> selectedKeys = if (key == null) emptySet() else toggleChannelSelection(selectedKeys, key) },
            onReorder = vm::reorderChannels
        )
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        icon = { Icon(Icons.Outlined.DeleteSweep, null) },
        title = { Text("Reset analytics?") },
        text = { Text("This permanently clears all saved messages and analytics counters. Your channels, settings, hidden users, and bot labels will stay.") },
        confirmButton = { Button(onClick = { vm.resetAnalytics(); confirmReset = false }) { Text("YES, RESET") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("CANCEL") } }
    )
    selectedChatterMessages?.let { history ->
        val message = history.first()
        MessageActions(
            message = message,
            chatterHistory = history,
            onDismiss = { selectedChatterMessages = null },
            onHide = { scope -> vm.hide(message, scope); selectedChatterMessages = null },
            onLabelBot = { vm.labelAsBot(message); selectedChatterMessages = null },
            onSaveChatter = { vm.saveChatter(message); selectedChatterMessages = null },
            onLoadAllChats = { result -> vm.loadAllChatsFor(message, result) },
            alreadySaved = state.savedChatters.any { it.key == savedChatterKey(message.platform, message.sender.platformUserId, message.sender.username) }
        )
    }
}

internal fun sortChannelsByActivity(channels: List<ChatChannel>, messages: List<ChatMessage>, cutoff: Long): List<ChatChannel> {
    val activity = messages.groupBy { it.channel.key() }
    return channels.sortedWith(
        compareByDescending<ChatChannel> { channel -> activity[channel.key()].orEmpty().count { it.timestamp >= cutoff } }
            .thenByDescending { channel -> activity[channel.key()].orEmpty().size }
            .thenBy { it.sortOrder }
    )
}

@Composable private fun ChatterAnalyticsRow(messages: List<ChatMessage>, onClick: () -> Unit) {
    val latest = messages.maxBy { it.timestamp }
    val channels = messages.map { it.channel.displayName }.distinct()
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(latest.sender.displayName, color = savedChatterColor(latest), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${latest.sender.username} · ${channels.size} ${if (channels.size == 1) "channel" else "channels"}", fontSize = 10.sp, color = Color.Gray)
                Text(channels.take(3).joinToString(), fontSize = 10.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(latest.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(messages.size.toString(), fontWeight = FontWeight.Black, color = platformColor(latest.platform))
        }
    }
}

@Composable private fun MetricRow(row: List<Pair<String, Any>>) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    row.forEach { (label, value) -> Metric(label, value.toString(), Modifier.weight(1f)) }
    if (row.size == 1) Spacer(Modifier.weight(1f))
}

internal fun buildAnalyticsCsv(messages: List<ChatMessage>, channels: List<ChatChannel>, analytics: AnalyticsSnapshot, generatedAt: Long = System.currentTimeMillis()): String {
    fun csv(value: Any?): String = "\"${value.toString().replace("\"", "\"\"")}\""
    val rows = mutableListOf(
        listOf("nabchat analytics export", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(generatedAt))),
        listOf("summary", "value"),
        listOf("messages_received_since_launch", analytics.totalReceived),
        listOf("messages_in_export_scope", messages.size),
        listOf("unique_chatters", messages.map { it.sender.platformUserId ?: it.sender.username.lowercase() }.toSet().size),
        listOf("emote_only_messages", messages.count { it.isEmoteOnly }),
        listOf("emote_uses", messages.sumOf { emojiSignals(it).size }),
        listOf("filtered_messages", analytics.filtered),
        listOf("reconnects", analytics.reconnects),
        listOf("parse_failures", analytics.parseFailures),
        emptyList(),
        listOf("channel", "platform", "messages", "unique_chatters", "emote_only", "emote_uses", "first_message", "last_message")
    )
    channels.forEach { channel ->
        val scoped = messages.filter { it.channel.key() == channel.key() }
        rows += listOf(channel.displayName, channel.platform.name, scoped.size,
            scoped.map { it.sender.platformUserId ?: it.sender.username.lowercase() }.toSet().size,
            scoped.count { it.isEmoteOnly }, scoped.sumOf { emojiSignals(it).size },
            scoped.minOfOrNull { it.timestamp }?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it)) } ?: "",
            scoped.maxOfOrNull { it.timestamp }?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it)) } ?: "")
    }
    rows.add(emptyList())
    rows += listOf("chatter", "username", "platform", "messages", "channels", "channel_names", "latest_message")
    messages.groupBy { savedChatterKey(it.platform, it.sender.platformUserId, it.sender.username) }.values
        .sortedByDescending { it.size }.forEach { scoped ->
            val latest = scoped.maxBy { it.timestamp }
            val channelNames = scoped.map { it.channel.displayName }.distinct()
            rows += listOf(latest.sender.displayName, latest.sender.username, latest.platform.name, scoped.size, channelNames.size, channelNames.joinToString(" | "), latest.text)
        }
    return rows.joinToString("\n") { row -> row.joinToString(",") { csv(it) } } + "\n"
}

private val chatterPalette = listOf(
    0xFFFFB74DL, 0xFF57DF7DL, 0xFF64B5F6L, 0xFFBA68C8L, 0xFFFF6B6BL, 0xFFFFD54FL, 0xFF4DD0E1L,
    0xFFF06292L, 0xFF9575CDL, 0xFF7986CBL, 0xFF26A69AL, 0xFF9CCC65L, 0xFFFF8A65L, 0xFFA1887FL,
    0xFF90A4AEL, 0xFFFFFFFFL, 0xFFE0E0E0L, 0xFFEF5350L, 0xFFAB47BCL, 0xFF29B6F6L, 0xFF66BB6AL
)

@Composable private fun ChattersScreen(state: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    var exportTarget by remember { mutableStateOf<SavedChatterEntity?>(null) }
    var exportAll by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val exportChat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val target = exportTarget
        if (uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter() ?: error("Could not open that file.") }
                .onSuccess { writer ->
                    if (exportAll) vm.exportChatLog(writer) { result -> exportStatus = result.fold({ "Exported $it saved messages." }, { it.message ?: "Chat export failed." }) }
                    else if (target != null) vm.exportChatterChatLog(writer, target) { result -> exportStatus = result.fold({ "Exported $it messages from ${target.displayName}." }, { it.message ?: "Chat export failed." }) }
                }
                .onFailure { exportStatus = it.message ?: "Chat export failed." }
        }
        exportTarget = null
        exportAll = false
    }
    Column(Modifier.fillMaxSize()) {
        Text("Saved chatters", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
        Text("Follow what specific people say across channels", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        if (state.savedChatters.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.People, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp)); Text("No saved chatters yet", fontWeight = FontWeight.Bold)
                    Text("Tap a message, then choose Save chatter.", fontSize = 12.sp, color = Color.Gray)
                }
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.savedChatters, key = { it.key }) { chatter ->
                val messages = state.emojiMessages.filter { savedChatterKey(it.platform, it.sender.platformUserId, it.sender.username) == chatter.key }
                SavedChatterCard(chatter, messages, onColor = { vm.setSavedChatterColor(chatter, it) }, onRemove = { vm.removeSavedChatter(chatter) }, onExport = {
                    exportTarget = chatter
                    exportChat.launch("nabchat-${chatter.username}-chat-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.csv")
                })
            }
            item {
                OutlinedButton(
                    onClick = { exportAll = true; exportChat.launch("nabchat-all-saved-chat-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.csv") },
                    enabled = state.analytics.databaseMessages > 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                ) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(6.dp)); Text("EXPORT ALL SAVED CHAT") }
                exportStatus?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp)) }
            }
        }
        if (state.billing.ownershipChecked && !state.billing.isPlus) NabchatBannerAd(dismissible = false) {}
    }
}

@Composable private fun SavedChatterCard(chatter: SavedChatterEntity, messages: List<ChatMessage>, onColor: (Long) -> Unit, onRemove: () -> Unit, onExport: () -> Unit) {
    var expanded by rememberSaveable(chatter.key) { mutableStateOf(true) }
    var paletteExpanded by rememberSaveable(chatter.key) { mutableStateOf(false) }
    var confirmRemove by rememberSaveable(chatter.key) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(Color(chatter.colorArgb.toInt()), CircleShape), contentAlignment = Alignment.Center) { Text(chatter.displayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black) }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) { Text(chatter.displayName, color = Color(chatter.colorArgb.toInt()), fontWeight = FontWeight.Bold); Text("@${chatter.username} · ${chatter.platform.lowercase().replaceFirstChar { it.uppercase() }}", fontSize = 10.sp, color = Color.Gray) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "HIDE" else "SHOW", fontSize = 9.sp) }
                IconButton(onClick = { confirmRemove = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Delete, "Remove saved chatter", Modifier.size(18.dp)) }
            }
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val visibleColors = if (paletteExpanded) chatterPalette else chatterPalette.take(6)
                visibleColors.chunked(7).forEachIndexed { rowIndex, colors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        colors.forEach { argb ->
                            val selected = chatter.colorArgb == argb
                            Box(Modifier.size(if (selected) 24.dp else 20.dp).clip(CircleShape).background(Color(argb.toInt())).clickable { onColor(argb) }
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier))
                        }
                        if ((!paletteExpanded && rowIndex == 0) || (paletteExpanded && rowIndex == visibleColors.chunked(7).lastIndex)) {
                            FilledTonalIconButton(onClick = { paletteExpanded = !paletteExpanded }, modifier = Modifier.size(25.dp)) {
                                Icon(if (paletteExpanded) Icons.Outlined.Remove else Icons.Outlined.Add, if (paletteExpanded) "Show fewer colors" else "Show more colors", Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(7.dp)); HorizontalDivider()
                if (messages.isEmpty()) Text("No retained messages from this chatter yet.", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                else messages.take(8).forEach { message ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                        Text(message.channel.displayName, color = platformColor(message.platform), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(max = 90.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(7.dp)); InlineChatMessage(message, Modifier.weight(1f))
                    }
                }
                if (messages.size > 8) Text("${messages.size - 8} more retained messages", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onExport, modifier = Modifier.size(30.dp)) { Icon(Icons.Outlined.FileDownload, "Download this chatter's chat log", Modifier.size(17.dp)) }
            }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove saved chatter?") },
        text = { Text("Remove @${chatter.username} from your saved Chatters? Their retained chat history will not be deleted.") },
        confirmButton = { Button(onClick = { confirmRemove = false; onRemove() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("REMOVE") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("CANCEL") } }
    )
}

@Composable private fun ChannelAnalyticsRow(channel: ChatChannel, messages: List<ChatMessage>, cutoff: Long, onClick: () -> Unit) {
    val unique = messages.map { it.sender.platformUserId ?: it.sender.username.lowercase() }.toSet().size
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(channel, 38.dp); Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) { Text(channel.displayName, fontWeight = FontWeight.Bold); Text("${messages.size} messages · $unique chatters", fontSize = 11.sp, color = Color.Gray) }
            Text("${messages.count { it.timestamp >= cutoff }}/min", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) = Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp) { Column(Modifier.padding(14.dp)) { Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary); Text(label, fontSize = 11.sp, color = Color.Gray) } }

private enum class LegalDocument(val title: String, val body: String) {
    PRIVACY(
        "Privacy Policy",
        """
        Effective September 25, 2026

        nabchat is an independent Android application developed by gnaboret. This policy explains how information is handled when you use the app.

        LOCAL APP DATA
        Channels, settings, saved chatters, labels, analytics, and retained chat messages are stored in nabchat's private storage on your device. This information is not uploaded to a nabchat server. You may export channel lists and chat logs; exported files are controlled by you and by any service with which you share them. You can delete saved chat history in the app, clear the app's storage, or uninstall nabchat to remove local information.

        THIRD-PARTY CHAT SERVICES
        nabchat connects to third-party services such as Twitch, YouTube, Kick, and Rumble to retrieve channel information and public live-chat content. Requests necessarily provide those services with network information such as your IP address and device connection details. Their own privacy policies govern their processing. Public chat content, usernames, badges, profile images, emotes, and identifiers may be retained locally when chat-history saving is enabled.

        TWITCH AUTHORIZATION
        Twitch authorization tokens and the connected account identity are stored in the app's private local storage so the connection can be restored. nabchat never receives or stores your Twitch password. Disconnect Twitch in Settings to remove the locally saved authorization.

        ADVERTISING
        The free experience uses Google AdMob. Google and its advertising partners may process advertising identifiers, IP addresses, device information, consent choices, and ad interactions to provide, measure, limit, and personalize advertising where permitted. Use Privacy choices in Settings to review available consent controls. Learn more at policies.google.com/privacy and policies.google.com/technologies/partner-sites.

        PURCHASES
        nabchat+ purchases and ownership checks are processed by Google Play. nabchat receives purchase status but not your complete payment-card details. Google's privacy policy governs payment processing.

        CHILDREN
        nabchat is not designed for children under 13. Third-party live chat may contain mature or offensive language.

        CONTACT
        Privacy questions may be sent to gnaboret@gmail.com.
        """.trimIndent()
    ),
    TERMS(
        "Terms of Use",
        """
        Effective September 25, 2026

        By using nabchat, you agree to use it lawfully and in accordance with the rules of each connected platform.

        nabchat is a read-only multi-platform chat viewer and analytics tool. It does not control or endorse messages, usernames, links, emotes, or other content supplied by third-party platforms or their users. Live chat may be inaccurate, offensive, unavailable, delayed, changed, or removed without notice. Filtering and bot/spam labeling are convenience features and are not guaranteed to identify every unwanted message correctly.

        Do not use nabchat to harass others, violate privacy, infringe intellectual-property rights, circumvent platform restrictions, or access content you are not authorized to access. You are responsible for protecting exported chat logs and for complying with applicable laws and platform terms.

        nabchat+ is a one-time purchase processed by Google Play. It removes advertisements and unlocks unlimited enabled channels for the purchasing Google Play account, subject to Google Play availability and restoration. Refunds and payment disputes are handled under Google Play's applicable policies.

        The app is provided as available without a guarantee that every platform, channel, message, image, or connection will remain available. Third-party platforms may change or discontinue interfaces at any time. To the extent permitted by law, the developer is not liable for third-party content, service interruptions, lost exports, or decisions made using app analytics.

        These terms may be updated when the app or applicable requirements change. Questions may be sent to gnaboret@gmail.com.
        """.trimIndent()
    ),
    THIRD_PARTY(
        "Third-party notices",
        """
        nabchat is an independent application and is not affiliated with, endorsed by, or sponsored by Twitch, YouTube, Kick, Rumble, Google, or their parent companies.

        Twitch, YouTube, Kick, Rumble, Google Play, AdMob, and their respective logos, service names, emotes, images, and other marks belong to their respective owners. Content retrieved from those services remains subject to the originating platform's terms, policies, and rights.

        Selecting Watch now or another external link leaves nabchat and opens the relevant third-party service. nabchat is not responsible for external pages or their availability.
        """.trimIndent()
    ),
    OPEN_SOURCE(
        "Open-source licenses",
        """
        nabchat is built with open-source software including AndroidX, Jetpack Compose, Kotlin Coroutines, Room, DataStore, OkHttp, and Coil.

        These components are distributed under their respective licenses. The listed projects are primarily licensed under the Apache License 2.0. A copy of that license is available at:

        https://www.apache.org/licenses/LICENSE-2.0

        Copyright and license notices remain the property of their respective authors. Google Play services, Google Mobile Ads, the User Messaging Platform, and Google Play Billing are provided under Google's applicable SDK terms.
        """.trimIndent()
    )
}

@Composable private fun SettingsScreen(state: MainUiState, vm: MainViewModel) {
    var manageHidden by remember { mutableStateOf(false) }
    var manageBots by remember { mutableStateOf(false) }
    var twitchCode by remember { mutableStateOf<com.nabchat.app.provider.TwitchDeviceCode?>(null) }
    var twitchError by remember { mutableStateOf<String?>(null) }
    var channelBackupStatus by remember { mutableStateOf<String?>(null) }
    var chatExportStatus by remember { mutableStateOf<String?>(null) }
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val privacyOptionsRequired by AdvertisingPrivacy.privacyOptionsRequired
    val channelBackup = remember(state.channels) { buildChannelBackup(state.channels) }
    val exportChannels = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            runCatching { context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer -> writer.write(channelBackup) } ?: error("Could not open that file.") }
                .onSuccess { channelBackupStatus = "Channel backup exported." }
                .onFailure { channelBackupStatus = it.message ?: "Channel export failed." }
        }
    }
    val importChannels = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> parseChannelBackup(reader.readText()) } ?: error("Could not open that file.") }
                .onSuccess { restored -> vm.restoreChannels(restored) { result -> channelBackupStatus = if (result.isSuccess) "Restored ${restored.size} channels." else result.exceptionOrNull()?.message ?: "Channel restore failed." } }
                .onFailure { channelBackupStatus = it.message ?: "That is not a valid nabchat channel backup." }
        }
    }
    val exportChat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            runCatching { context.contentResolver.openOutputStream(it)?.bufferedWriter() ?: error("Could not open that file.") }
                .onSuccess { writer -> vm.exportChatLog(writer) { result -> chatExportStatus = result.fold({ count -> "Exported $count saved messages." }, { error -> error.message ?: "Chat export failed." }) } }
                .onFailure { chatExportStatus = it.message ?: "Chat export failed." }
        }
    }
    LaunchedEffect(state.twitchAuth.connected) { if (state.twitchAuth.connected) twitchCode = null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { SectionTitle("Platforms") }
        item { ListItem(headlineContent = { Text(if (state.twitchAuth.connected) "Twitch · @${state.twitchAuth.login}" else "Connect Twitch") }, supportingContent = { Text(if (state.twitchAuth.connected) "Authorization verified · tap to disconnect" else state.twitchAuth.message ?: "Authorization required for chat · tap to connect") }, leadingContent = { Icon(if (state.twitchAuth.connected) Icons.Outlined.Verified else Icons.Outlined.LiveTv, null, tint = Color(0xFF9146FF)) }, modifier = Modifier.clickable { if (state.twitchAuth.connected) vm.disconnectTwitch() else vm.startTwitchAuth { result -> result.onSuccess { code -> twitchCode = code; twitchError = null; vm.awaitTwitchAuth(code) { auth -> auth.onFailure { twitchError = it.message } } }.onFailure { twitchError = it.message } } }) }
        item { SectionTitle("Chat") }
        item { SettingSwitch("Show profile pictures", state.settings.showProfilePictures, "Channel and chatter pictures throughout the app") { vm.updateSettings(state.settings.copy(showProfilePictures = it)) } }
        item { SettingSwitch("Show timestamps", state.settings.showTimestamps, "Message times in River and Rooms") { vm.updateSettings(state.settings.copy(showTimestamps = it)) } }
        item { SettingSwitch("Show emote-only messages", state.settings.showEmoteOnly) { vm.updateSettings(state.settings.copy(showEmoteOnly = it)) } }
        item { SettingSwitch("Show likely bot messages", state.settings.showLikelyBots, "Off by default; filtering is local only") { vm.updateSettings(state.settings.copy(showLikelyBots = it)) } }
        item { SettingSwitch("Show likely spam messages", state.settings.showLikelySpam, "Off by default; filtering is local only") { vm.updateSettings(state.settings.copy(showLikelySpam = it)) } }
        item {
            ListItem(headlineContent = { Text("Message arrival") }, supportingContent = {
                Column {
                    Text(when (state.settings.messageArrivalMode) { MessageArrivalMode.INSTANT -> "Show provider batches immediately"; MessageArrivalMode.ADAPTIVE -> "One at a time, faster when chat is busy"; MessageArrivalMode.STEADY -> "One message every three seconds; backlog may grow"; MessageArrivalMode.FLOW -> "Gentle, consistently paced upward flow" })
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                        MessageArrivalMode.entries.forEachIndexed { index, value -> SegmentedButton(selected = state.settings.messageArrivalMode == value, onClick = { vm.updateSettings(state.settings.copy(messageArrivalMode = value)) }, shape = SegmentedButtonDefaults.itemShape(index, MessageArrivalMode.entries.size), label = { Text(when(value) { MessageArrivalMode.INSTANT -> "NOW"; MessageArrivalMode.ADAPTIVE -> "AUTO"; MessageArrivalMode.STEADY -> "3 SEC"; MessageArrivalMode.FLOW -> "FLOW" }, fontSize = 8.sp) }) }
                    }
                }
            }, leadingContent = { Icon(Icons.Outlined.DynamicFeed, null) })
        }
        item {
            ListItem(headlineContent = { Text("Appearance") }, supportingContent = {
                Column {
                    Text("Dark is the default · System follows your device with nabchat’s colors")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        AppearanceMode.entries.forEachIndexed { index, value ->
                            SegmentedButton(
                                selected = state.settings.appearanceMode == value,
                                onClick = { vm.updateSettings(state.settings.copy(appearanceMode = value)) },
                                shape = SegmentedButtonDefaults.itemShape(index, AppearanceMode.entries.size),
                                label = { Text(value.name, fontSize = 9.sp, maxLines = 1) }
                            )
                        }
                    }
                }
            }, leadingContent = { Icon(Icons.Outlined.Palette, null) })
        }
        item { SettingSwitch("Emoji bursts", state.settings.showEmojiBursts, "Brief reactions appear around the chat as they arrive") { vm.updateSettings(state.settings.copy(showEmojiBursts = it)) } }
        item { ListItem(headlineContent = { Text("Emote burst intensity") }, supportingContent = { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) { EmojiBurstIntensity.entries.forEachIndexed { index, value -> SegmentedButton(selected = state.settings.emojiBurstIntensity == value, onClick = { vm.updateSettings(state.settings.copy(emojiBurstIntensity = value)) }, shape = SegmentedButtonDefaults.itemShape(index, EmojiBurstIntensity.entries.size), label = { Text(value.name, fontSize = 9.sp) }) } } }, leadingContent = { Icon(Icons.Outlined.AutoAwesome, null) }) }
        item {
            ListItem(headlineContent = { Text("Emoji burst size") }, supportingContent = {
                Column {
                    Text("${state.settings.emojiBurstSizePercent.roundToInt()}%${if (state.settings.emojiBurstSizePercent >= 600f) " · MYSPACE" else if (state.settings.emojiBurstSizePercent >= 300f) " · HUGE" else ""}")
                    Slider(
                        value = burstSizeSliderPosition(state.settings.emojiBurstSizePercent),
                        onValueChange = { vm.updateSettings(state.settings.copy(emojiBurstSizePercent = burstSizePercent(it))) },
                        valueRange = 0f..1f,
                        steps = 30
                    )
                }
            }, leadingContent = { Icon(Icons.Outlined.PhotoSizeSelectLarge, null) })
        }
        item {
            ListItem(headlineContent = { Text("Emoji burst transparency") }, supportingContent = {
                Column {
                    Text("${state.settings.emojiBurstOpacityPercent.roundToInt()}% visible")
                    Slider(
                        value = state.settings.emojiBurstOpacityPercent,
                        onValueChange = { vm.updateSettings(state.settings.copy(emojiBurstOpacityPercent = it)) },
                        valueRange = 10f..100f,
                        steps = 17
                    )
                }
            }, leadingContent = { Icon(Icons.Outlined.Opacity, null) })
        }
        item { SettingSwitch("Emoji pulse bar", state.settings.showEmojiTracker, "Shows popular and recently used reactions") { vm.updateSettings(state.settings.copy(showEmojiTracker = it)) } }
        item { SizeSetting("Pulse bar size", Icons.Outlined.ViewDay, state.settings.pulseBarSize) { vm.updateSettings(state.settings.copy(pulseBarSize = it)) } }
        item {
            ListItem(
                headlineContent = { Text("Chat font size") },
                supportingContent = {
                    Column {
                        Text("${state.settings.chatFontSizeSp.toInt()} sp")
                        Slider(
                            value = state.settings.chatFontSizeSp,
                            onValueChange = { vm.updateSettings(state.settings.copy(chatFontSizeSp = it)) },
                            valueRange = 12f..20f,
                            steps = 7
                        )
                    }
                },
                leadingContent = { Icon(Icons.Outlined.FormatSize, null) }
            )
        }
        item { SettingSwitch("Save chat history", state.settings.saveHistory) { vm.updateSettings(state.settings.copy(saveHistory = it)) } }
        item { SettingSwitch("Auto reconnect", state.settings.autoReconnect) { vm.updateSettings(state.settings.copy(autoReconnect = it)) } }
        item { SettingSwitch("Start with auto-scroll", state.settings.startWithAutoScroll, "You can still pause by scrolling up or tapping the feed button") { vm.updateSettings(state.settings.copy(startWithAutoScroll = it)) } }
        item { SettingSwitch("Keep screen on", state.settings.keepScreenOn, "Prevents the display from timing out while nabchat is open") { vm.updateSettings(state.settings.copy(keepScreenOn = it)) } }
        item { SectionTitle("Channels") }
        if (state.channels.isEmpty()) item { Text("No channels added", color = Color.Gray, modifier = Modifier.padding(12.dp)) }
        items(state.channels, key = { it.key() }) { channel -> ChannelSetting(channel, { vm.setEnabled(channel, it) }, { vm.setFavorite(channel, it) }, { vm.remove(channel) }) }
        item { SectionTitle("Channel backup") }
        item {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportChannels.launch("nabchat-channels-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.json") }, enabled = state.channels.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(5.dp)); Text("EXPORT") }
                    OutlinedButton(onClick = { importChannels.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(5.dp)); Text("IMPORT") }
                }
                Text("Backs up channel identities, order, and enabled state. Account sign-ins are never included.", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp))
                channelBackupStatus?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
            }
        }
        item { SectionTitle("Chat logs") }
        item {
            Column {
                OutlinedButton(
                    onClick = { exportChat.launch("nabchat-chat-logs-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.csv") },
                    enabled = state.analytics.databaseMessages > 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(5.dp)); Text("EXPORT ALL SAVED CHAT") }
                Text("Exports every message retained in local history as CSV. Hidden and emote-only messages are included.", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp))
                chatExportStatus?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
            }
        }
        item { SectionTitle("Hidden users") }
        item { ListItem(headlineContent = { Text("Manage hidden users") }, supportingContent = { Text("${state.hiddenUsers.size} local rule(s)") }, leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) }, modifier = Modifier.clickable { manageHidden = true }) }
        item { ListItem(headlineContent = { Text("Manage labeled bots") }, supportingContent = { Text("${state.userLabels.count { it.label == "BOT" }} manually labeled account(s)") }, leadingContent = { Icon(Icons.Outlined.SmartToy, null) }, modifier = Modifier.clickable { manageBots = true }) }
        if (!state.billing.isPlus) item {
            ListItem(
                headlineContent = { Text("Privacy choices") },
                supportingContent = { Text(if (privacyOptionsRequired) "Review advertising consent choices" else "Advertising privacy information") },
                leadingContent = { Icon(Icons.Outlined.PrivacyTip, null) },
                modifier = Modifier.clickable { activity?.let(AdvertisingPrivacy::showPrivacyOptions) }
            )
        }
        if (state.billing.ownershipChecked && !state.billing.isPlus) item { NabchatBannerAd(dismissible = false) {} }
        item {
            ListItem(
                headlineContent = { Text(if (state.billing.isPlus) "nabchat+ unlocked" else "Upgrade to nabchat+") },
                supportingContent = { Text(if (state.billing.isPlus) "No ads · unlimited enabled channels" else "${state.billing.message}${state.billing.price?.let { " · $it once" }.orEmpty()}") },
                leadingContent = { Icon(if (state.billing.isPlus) Icons.Outlined.Verified else Icons.Outlined.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { if (!state.billing.isPlus) TextButton(onClick = { activity?.let(vm.monetization::purchase) }) { Text("UPGRADE") } }
            )
            if (!state.billing.isPlus) TextButton(onClick = vm.monetization::restorePurchases, modifier = Modifier.fillMaxWidth()) { Text("RESTORE PURCHASE") }
        }
        item { SectionTitle("About & legal") }
        item { ListItem(headlineContent = { Text("Privacy Policy") }, supportingContent = { Text("How nabchat handles local data, connected services, and advertising") }, leadingContent = { Icon(Icons.Outlined.Policy, null) }, modifier = Modifier.clickable { legalDocument = LegalDocument.PRIVACY }) }
        item { ListItem(headlineContent = { Text("Terms of Use") }, supportingContent = { Text("App use, third-party chat, and nabchat+ purchase terms") }, leadingContent = { Icon(Icons.Outlined.Gavel, null) }, modifier = Modifier.clickable { legalDocument = LegalDocument.TERMS }) }
        item { ListItem(headlineContent = { Text("Third-party notices") }, supportingContent = { Text("Platform ownership and non-affiliation") }, leadingContent = { Icon(Icons.Outlined.Info, null) }, modifier = Modifier.clickable { legalDocument = LegalDocument.THIRD_PARTY }) }
        item { ListItem(headlineContent = { Text("Open-source licenses") }, supportingContent = { Text("Software and license acknowledgements") }, leadingContent = { Icon(Icons.Outlined.Code, null) }, modifier = Modifier.clickable { legalDocument = LegalDocument.OPEN_SOURCE }) }
        item {
            ListItem(
                headlineContent = { Text("Contact") },
                supportingContent = { Text("gnaboret@gmail.com") },
                leadingContent = { Icon(Icons.Outlined.Email, null) },
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:gnaboret@gmail.com?subject=nabchat%20support"))) }
                }
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Forum, "nabchat logo", Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(5.dp))
                Text(if (state.billing.isPlus) "nabchat+" else "nabchat", fontWeight = FontWeight.Black)
                Text("by gnaboret", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("the g and t are silent", fontSize = 9.sp, color = Color.Gray)
                Text("version ${BuildConfig.VERSION_NAME}", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
    if (manageHidden) AlertDialog(onDismissRequest = { manageHidden = false }, title = { Text("Hidden users") }, text = { if (state.hiddenUsers.isEmpty()) Text("Nobody is hidden.") else LazyColumn { items(state.hiddenUsers, key = { it.id }) { hidden -> ListItem(headlineContent = { Text(hidden.username) }, supportingContent = { Text(if (hidden.scope == HiddenScope.EVERYWHERE.name) "Everywhere" else "This channel") }, trailingContent = { IconButton(onClick = { vm.unhide(hidden) }) { Icon(Icons.Outlined.Delete, "Unhide") } }) } } }, confirmButton = { TextButton(onClick = { manageHidden = false }) { Text("DONE") } })
    if (manageBots) AlertDialog(onDismissRequest = { manageBots = false }, title = { Text("Labeled bots") }, text = { val bots = state.userLabels.filter { it.label == "BOT" }; if (bots.isEmpty()) Text("No accounts are manually labeled as bots.") else LazyColumn { items(bots, key = { it.key }) { bot -> ListItem(headlineContent = { Text(bot.displayName) }, supportingContent = { Text("@${bot.username} · local bot label") }, trailingContent = { IconButton(onClick = { vm.removeLabel(bot) }) { Icon(Icons.Outlined.Delete, "Remove label") } }) } } }, confirmButton = { TextButton(onClick = { manageBots = false }) { Text("DONE") } })
    legalDocument?.let { document ->
        AlertDialog(
            onDismissRequest = { legalDocument = null },
            title = { Text(document.title) },
            text = { Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) { LazyColumn { item { Text(document.body, fontSize = 13.sp, lineHeight = 19.sp) } } } },
            confirmButton = { TextButton(onClick = { legalDocument = null }) { Text("DONE") } },
            dismissButton = {
                if (document == LegalDocument.PRIVACY) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sites.google.com/view/nabchatprivacypolicy/home"))) }
                }) { Text("VIEW ONLINE") }
            }
        )
    }
    twitchCode?.let { code -> AlertDialog(onDismissRequest = { twitchCode = null }, title = { Text("Connect Twitch") }, text = { Column {
        Text("In Chrome or on another device, open twitch.tv/activate and enter:")
        Text(code.userCode, fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF9146FF), modifier = Modifier.padding(vertical = 10.dp))
        ActionRow(Icons.Outlined.ContentCopy, "Copy code") { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Twitch device code", code.userCode)) }
        ActionRow(Icons.Outlined.OpenInNew, "Open Twitch activation") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri))) }
        }
        Text("Keep this dialog open. It closes automatically after approval. Nabchat never receives or stores your Twitch password.", fontSize = 12.sp)
        twitchError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    } }, confirmButton = {}, dismissButton = { TextButton(onClick = { twitchCode = null }) { Text("CANCEL") } }) }
}

internal fun buildChannelBackup(channels: List<ChatChannel>): String = JSONObject()
    .put("format", "nabchat-channels")
    .put("version", 1)
    .put("exportedAt", System.currentTimeMillis())
    .put("channels", JSONArray().also { array -> channels.sortedBy { it.sortOrder }.forEach { channel -> array.put(JSONObject()
        .put("platform", channel.platform.name).put("platformChannelId", channel.platformChannelId)
        .put("chatroomId", channel.chatroomId).put("slug", channel.slug).put("displayName", channel.displayName)
        .put("avatarUrl", channel.avatarUrl).put("enabled", channel.enabled).put("dateAdded", channel.dateAdded).put("sortOrder", channel.sortOrder).put("favorite", channel.favorite)) } })
    .toString(2)

internal fun parseChannelBackup(text: String): List<ChatChannel> {
    val root = JSONObject(text)
    require(root.optString("format") == "nabchat-channels" && root.optInt("version") == 1) { "That is not a supported nabchat channel backup." }
    val array = root.optJSONArray("channels") ?: error("The channel list is missing.")
    require(array.length() <= 500) { "That backup contains too many channels." }
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        val platform = ChatPlatform.valueOf(item.getString("platform"))
        val platformChannelId = item.getString("platformChannelId").also { require(it.isNotBlank()) { "A channel ID is missing." } }
        val slug = item.getString("slug").also { require(it.isNotBlank()) { "A channel name is missing." } }
        ChatChannel(platform, platformChannelId, item.optString("chatroomId").takeIf { it.isNotBlank() && it != "null" }, slug,
            item.optString("displayName", slug).ifBlank { slug }, item.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
            item.optBoolean("enabled", true), item.optLong("dateAdded", System.currentTimeMillis()), index, item.optBoolean("favorite", false))
    }.distinctBy { it.key() }
}

@Composable private fun SectionTitle(text: String) = Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Lime, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
@Composable private fun SettingSwitch(title: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) = ListItem(headlineContent = { Text(title) }, supportingContent = description?.let { { Text(it) } }, trailingContent = { Switch(checked, onChange) })
internal fun burstSizeSliderPosition(percent: Float): Float = if (percent <= 100f) {
    ((percent.coerceIn(25f, 100f) - 25f) / 75f) * .5f
} else {
    .5f + ((percent.coerceIn(100f, 1000f) - 100f) / 900f) * .5f
}

internal fun burstSizePercent(position: Float): Float = if (position <= .5f) {
    25f + (position.coerceIn(0f, .5f) / .5f) * 75f
} else {
    100f + ((position.coerceIn(.5f, 1f) - .5f) / .5f) * 900f
}


@Composable private fun SizeSetting(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: VisualSize, onSelected: (VisualSize) -> Unit) = ListItem(
    headlineContent = { Text(title) },
    supportingContent = {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            VisualSize.entries.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, VisualSize.entries.size),
                    label = { Text(value.name, fontSize = 9.sp) }
                )
            }
        }
    },
    leadingContent = { Icon(icon, null) }
)
@Composable private fun ChannelSetting(channel: ChatChannel, onEnabled: (Boolean) -> Unit, onFavorite: (Boolean) -> Unit, onRemove: () -> Unit) {
    val accent = if (channel.platform == ChatPlatform.RUMBLE) MaterialTheme.colorScheme.onSurface else platformColor(channel.platform)
    ListItem(
        headlineContent = { Text(channel.displayName, color = accent, fontWeight = FontWeight.Bold) },
        supportingContent = {
            Text(
                when (channel.platform) { ChatPlatform.KICK -> "kick.com/${channel.slug}"; ChatPlatform.TWITCH -> "twitch.tv/${channel.slug}" + if (channel.platformChannelId.startsWith("pending:")) " · authorization required" else ""; ChatPlatform.YOUTUBE -> "youtube.com/${channel.slug}"; ChatPlatform.RUMBLE -> "Rumble chat connection" },
                color = accent.copy(alpha = .78f)
            )
        },
        leadingContent = {
            Switch(
                channel.enabled,
                onEnabled,
                colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.surface, checkedTrackColor = accent)
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = { onFavorite(!channel.favorite) }) {
                    Icon(if (channel.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, if (channel.favorite) "Remove favorite" else "Add favorite", tint = if (channel.favorite) Color(0xFFFFC44D) else accent.copy(alpha = .72f))
                }
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "Remove", tint = accent.copy(alpha = .72f)) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = accent.copy(alpha = .09f)),
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
    )
}
