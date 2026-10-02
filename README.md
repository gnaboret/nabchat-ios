# nabchat

Android-first combined livestream chat. The current standalone build supports saved Kick channels, River (combined) and Rooms (per-channel) views over the same stable message objects, local user hiding, emote-only filtering, local analytics, and persistent Room history.

Twitch is optional and uses a device-code authorization dialog that never launches an embedded browser or replaces the nabchat screen. Enter the displayed code at `twitch.tv/activate` in Chrome or on another device; the dialog closes after approval. Kick, Rumble, and local history continue to work without signing in.

Rumble support is read-only and uses Rumble's documented creator Live Stream API. In Rumble, the creator generates the URL at `rumble.com/account/livestream-api`, then pastes that complete URL into Add Channel. Rumble does not currently document a public viewer API that resolves an arbitrary channel name into live chat. The API URL contains a revocable stream key, so treat channel backups containing it as sensitive and regenerate the URL if a backup is shared accidentally.

River timestamps use a subtle platform cue: Kick green and Twitch purple (with YouTube red reserved for a future provider). Floating emote bursts default to Low intensity; Low, Medium, and High caps are available in Settings without changing inline chat emotes or PULSE counting.

Likely bot and spam messages are locally filtered by default. Independent Settings switches can show either class. Classification is deliberately conservative and never sends moderation actions to Kick.

Any message sender can also be manually labeled as a bot. Manual labels are persistent, hide that account everywhere, and exclude it from displayed/user analytics while retaining raw messages for audit/debugging. Labels can be removed under Settings → Hidden users → Manage labeled bots.

Consecutive messages from the same account and channel within two minutes share a compact visual header. The messages themselves remain separate objects and database records.

Structured Kick verification badges display as a compact verified marker beside the chatter name. Tapping a chatter name opens its actions, including adding that chatter as a watched channel through the same validated channel resolver used by Add Channel; successful additions appear in Settings and the channel selectors.

The chatter action panel repeats the exact selected message in a compact preview, including its inline emotes, so moderation and channel-add actions retain their message context.

Channel filters show both broadcaster profile images and channel names, with colored initial fallbacks. Chat rows use only the source channel's broadcaster image as their avatar indicator; chatter profile images are intentionally not requested. River and each Room auto-follow new messages by default; manual upward scrolling pauses following and the bottom-right control resumes it.

The optional emoji pulse bar summarizes popular and recent Unicode emoji and structured Kick emotes independently of the emote-only message filter. Optional capped, short-lived reaction bursts place newly arriving reactions around the chat surface. Both features default on and can be disabled separately.

Emote-only chat rows default to hidden but still contribute to PULSE. PULSE uses a smooth 28-minute half-life rather than a hard reset, never displays a surviving reaction below one, refreshes decay every 15 seconds, and adds a strong 60-second momentum boost so genuine live spikes rise quickly.

In River mode, emoji pulse rankings and bursts follow the selected channel tabs; ALL uses reactions from every non-muted enabled channel.

In Rooms, PULSE and floating bursts follow the currently centered room. Swiping the room pager or tapping its channel tab updates the reaction scope together.

Kick's structured `[emote:id:name]` message fragments are normalized into ordered `TextSegment` and `EmoteSegment` content. Chat renders those images inline at compact text-relative sizes, including adjacent emotes, with textual fallback on image failure. Emote definitions are cached by platform/id and Coil provides asynchronous memory/disk image caching. PULSE consumes the same normalized segments.

The emoji pulse and River channel selector sit beneath the expanding chat feed. Analytics supports ALL and per-channel scopes, with channel-specific message totals, rate, unique chatter count, and text/emote composition plus an all-channel breakdown.

Analytics and PULSE consume the policy-filtered message stream before the presentation-only emote-row preference is applied. Emote-only rows therefore remain counted per channel even when “Show emote-only messages” is disabled.

Channel tabs stack each name beneath its broadcaster image and appear below content in both Chat and Analytics. Settings includes a persistent 12–20sp chat font-size control.

Long-press and drag a channel tab to save a new channel order. The same order is shared by Chat, Analytics, and Rooms.

Chat font defaults to 16sp. Channel tabs are multi-select quick filters: tap to add/remove a channel, tap ALL to clear the selection, and an empty selection automatically means ALL. Feeds start with auto-scroll enabled by default; a setting can change the startup behavior while manual pause/resume remains available.

Channel tabs also support an inverse quick filter. Hold until the haptic confirmation and release without dragging to mute/unmute that channel from ALL; muted tabs remain directly selectable and appear dimmed with a mute marker. Hold and move horizontally to reorder as before.

Rooms uses a centered circular, page-snapped carousel with neighboring rooms visible at both edges. Each swipe advances at most one channel, the final channel continues at the first, and swiping backward before the first continues at the last. The named profile-picture channel strip remains visible in Rooms and stays synchronized with the pager: either a room swipe or channel-tab tap moves both surfaces together. Only visual positions repeat; channel and message data do not.

Rug is an experimental third projection of the same live messages. Each enabled channel becomes a vertically stacked lane with its source avatar/name above a single horizontal chronological ticker of chatter messages. Lanes glide to newly presented items only when data changes rather than running a perpetual animation, and River-style channel selection, muting, PULSE scope, message actions, and presentation catch-up remain available.

Rug ticker lanes now move continuously whenever content remains ahead. Each lane estimates its own pace from up to 30 messages in the preceding two minutes and the measured width of visible message cards, with bounded minimum and maximum speeds. Busy chats move faster, quiet chats creep, and a lane rests briefly when it reaches the end rather than wasting animation work against a fixed edge.

The Android activity is portrait-only; landscape rotation is intentionally disabled.

Saved chat history has no automatic deletion and remains in Room indefinitely for future archive/search controls. To prevent extended-session memory exhaustion, the active UI observes a rolling window of the newest 1,000 saved messages per channel. This limit is per channel rather than global, so a busy channel cannot evict every visible message from a quieter room. A composite channel/time database index keeps the live queries bounded as the retained archive grows. When history saving is deliberately disabled, the temporary in-memory buffer is also bounded.

The compact app navigation reserves Android's bottom system inset separately from its 56dp Chat/Analytics/Settings controls. Content is measured above both surfaces and is never drawn beneath the Android navigation controls.

Smooth message arrival is enabled by default. Provider batches are persisted and counted immediately, while River and Rooms present new rows individually at an adaptive cadence (180ms in calm chat down to 28ms for a backlog over 50). Existing history appears immediately, and Settings can disable smoothing for instant batch rendering.

Message arrival is selectable: Now displays batches immediately, Auto retains the adaptive default, 3 Sec releases one message every three seconds without trying to catch up, and Flow uses a steady 550ms cadence with animated auto-follow. The feed button fast-forwards any presentation backlog and jumps to the newest message.

Message headers use one consistent source color for both channel name and timestamp: Kick green, Twitch purple, and YouTube red. YouTube public live chat is available without sign-in by adding a handle, channel URL, or live-video URL. The public web transport is experimental and isolated because YouTube can change that format independently of nabchat.

For YouTube, an active live-video URL is the recommended and most exact input. Handle resolution is accepted only when YouTube's player data confirms an active live stream; the resolver no longer guesses from unrelated video IDs embedded elsewhere on a channel page.

YouTube message polling initializes from the dedicated public live-chat page, supports both legacy and bracketed `ytInitialData` assignments, and carries the page's visitor context plus origin/referrer headers into continuation requests. If chat is disabled, the provider reports YouTube's status text instead of silently producing an empty feed.

Analytics includes recent rate, five-minute activity, average rate, unique chatters, text/emote-only volume, and total emote uses for the active channel selection. The export button writes a CSV summary with per-channel breakdowns through Android's document picker. Reset requires an explicit confirmation and clears saved messages plus runtime analytics counters while preserving channels, settings, hidden users, and bot labels.

The Analytics channel breakdown is ranked by current messages per minute from highest to lowest. Total retained messages and the saved channel order provide stable tie-breakers.

Chat rows use a larger 36dp source-channel avatar centered across the header and message body. When a platform supplies a chatter profile image, a compact 15dp avatar appears after the chatter name; Kick and YouTube use message metadata directly, while Twitch performs bounded asynchronous lookups with an in-memory cache. Missing images do not reserve space.

Settings includes channel backup export and import. The versioned JSON file preserves platform/channel identifiers, chatroom identifiers, names, avatars, enabled state, and ordering so channels can be restored without being live. Imports update matching channels rather than duplicating them; account tokens and sign-in data are never exported.

Chatters is a fourth bottom destination. A message action can save its author by stable platform identity; saved chatters persist locally, show retained messages across source channels, and support a custom highlight color used for that person's username in River and Rooms. Analytics and CSV exports include chatter message counts, channel overlap, and recent message context.

Extended-session heat is reduced by making smooth arrival event-driven instead of continuously polling Compose state, coalescing rapid database emissions, refreshing PULSE on a controlled cadence, bounding emoji-burst bookkeeping, and relaxing Kick polling to four seconds. These changes do not reduce saved history.

## Build

```powershell
.\\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Kick connectivity status

Kick's official developer API delivers `chat.message.sent` through OAuth-authorized webhooks. A standalone local Android client cannot receive those webhooks and arbitrary broadcasters would need to authorize the app. The current Kick adapter is therefore explicitly experimental: it resolves public channel metadata and polls Kick's web chat-history endpoint every 2.5 seconds. This endpoint is undocumented and may be changed, blocked, rate-limited, or omit message classes. All such details are contained in `KickChatProvider`.

Use **Settings → Simulation mode** for deterministic UI testing. Simulation mode never pretends to be Kick connectivity.

## Explicit TODOs

- Replace the experimental polling adapter if Kick offers an official client-initiated read-only transport.
- Verify multi-channel behavior on physical-device mobile network transitions.
- Render emote images rather than normalized names.
- Add bounded/exportable history retention controls and migration tests.
- Add instrumented Compose/Room persistence tests.
- Add the signature position-aware River/Rooms scatter-and-converge transition. The current switch intentionally avoids a whole-screen crossfade; `ChatFeedLayouts` and stable message keys provide the identity foundation for shared-position animation.
