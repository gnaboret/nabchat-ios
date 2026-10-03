import SwiftUI
import NabchatShared
import UIKit

private enum ChatMode: String, CaseIterable {
    case river = "RIVER", rooms = "ROOMS", rug = "RUG"
    var icon: String {
        switch self {
        case .river: return "water.waves"
        case .rooms: return "rectangle.split.3x1"
        case .rug: return "rectangle.compress.vertical"
        }
    }
}

private enum AppSection: String {
    case chat = "CHAT", chatters = "CHATTERS", analytics = "ANALYTICS"
    var icon: String {
        switch self {
        case .chat: return "text.bubble"
        case .chatters: return "person.2"
        case .analytics: return "waveform.path.ecg"
        }
    }
}

enum Platform: String, Codable, CaseIterable {
    case kick, twitch, youtube
    var color: Color {
        switch self {
        case .kick: return NabColors.green
        case .twitch: return NabColors.purple
        case .youtube: return NabColors.youtube
        }
    }
}

struct Channel: Identifiable, Hashable, Codable {
    let id: UUID
    let name: String
    let shortName: String
    let platform: Platform
    var isEnabled: Bool
    var isFavorite: Bool

    init(id: UUID = UUID(), name: String, shortName: String, platform: Platform, isEnabled: Bool = true, isFavorite: Bool = false) {
        self.id = id
        self.name = name
        self.shortName = shortName
        self.platform = platform
        self.isEnabled = isEnabled
        self.isFavorite = isFavorite
    }

    private enum CodingKeys: String, CodingKey { case id, name, shortName, platform, isEnabled, isFavorite }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(UUID.self, forKey: .id)
        name = try values.decode(String.self, forKey: .name)
        shortName = try values.decode(String.self, forKey: .shortName)
        platform = try values.decode(Platform.self, forKey: .platform)
        isEnabled = try values.decodeIfPresent(Bool.self, forKey: .isEnabled) ?? true
        isFavorite = try values.decodeIfPresent(Bool.self, forKey: .isFavorite) ?? false
    }
}

struct ChatMessage: Identifiable, Codable {
    let id: UUID
    let channel: Channel
    let username: String
    let text: String
    let time: String
    let badge: String?
    let sourceID: String

    init(id: UUID = UUID(), channel: Channel, username: String, text: String, time: String, badge: String?, sourceID: String = UUID().uuidString) {
        self.id = id
        self.channel = channel
        self.username = username
        self.text = text
        self.time = time
        self.badge = badge
        self.sourceID = sourceID
    }
}

private struct ChatEmote: Identifiable {
    let id: String
    let name: String
    var url: URL? { URL(string: "https://files.kick.com/emotes/\(id)/fullsize") }
}

private enum ChatMarkup {
    private static let pattern = #"\[emote:(\d+):([^\]]+)\]"#

    static func emotesOnly(in text: String) -> [ChatEmote]? {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        let matches = regex.matches(in: text, range: range)
        guard !matches.isEmpty else { return nil }
        let remainder = regex.stringByReplacingMatches(in: text, range: range, withTemplate: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard remainder.isEmpty else { return nil }
        return matches.compactMap { match in
            guard let idRange = Range(match.range(at: 1), in: text),
                  let nameRange = Range(match.range(at: 2), in: text) else { return nil }
            return ChatEmote(id: String(text[idRange]), name: String(text[nameRange]))
        }
    }

    static func readable(_ text: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
        let range = NSRange(text.startIndex..., in: text)
        return regex.stringByReplacingMatches(in: text, range: range, withTemplate: ":$2:")
    }
}

private struct ChatMessageContent: View {
    let text: String
    var body: some View {
        if let emotes = ChatMarkup.emotesOnly(in: text) {
            HStack(spacing: 4) {
                ForEach(emotes.prefix(8)) { emote in
                    AsyncImage(url: emote.url) { phase in
                        if let image = phase.image {
                            image.resizable().scaledToFit()
                        } else {
                            Text(":\(emote.name):").font(.caption)
                        }
                    }
                    .frame(width: 30, height: 30)
                    .accessibilityLabel(emote.name)
                }
            }
        } else {
            Text(ChatMarkup.readable(text))
        }
    }
}

private struct SavedChatter: Identifiable, Codable, Hashable {
    let id: UUID
    let username: String
    let platform: Platform
    var colorIndex: Int
    let savedAt: Date

    init(id: UUID = UUID(), username: String, platform: Platform, colorIndex: Int = 0, savedAt: Date = Date()) {
        self.id = id
        self.username = username
        self.platform = platform
        self.colorIndex = colorIndex
        self.savedAt = savedAt
    }
}

enum NabColors {
    static let background = Color(red: 0.035, green: 0.045, blue: 0.040)
    static let surface = Color(red: 0.075, green: 0.095, blue: 0.082)
    static let raised = Color(red: 0.145, green: 0.135, blue: 0.165)
    static let line = Color(white: 0.31)
    static let text = Color(red: 0.90, green: 0.89, blue: 0.86)
    static let secondary = Color(white: 0.55)
    static let green = Color(red: 0.31, green: 0.82, blue: 0.48)
    static let purple = Color(red: 0.48, green: 0.28, blue: 0.76)
    static let youtube = Color(red: 0.88, green: 0.22, blue: 0.24)
}

struct ContentView: View {
    private static let starterChannels: [Channel] = []

    @AppStorage("savedChannels") private var savedChannels = ""
    @AppStorage("savedChatters") private var savedChattersData = ""
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @AppStorage("keepScreenAwake") private var keepScreenAwake = false
    @AppStorage("startWithAutoScroll") private var startWithAutoScroll = true
    @AppStorage("emoteBurstEnabled") private var emoteBurstEnabled = true
    @AppStorage("emoteBurstSize") private var emoteBurstSize = 300.0
    @AppStorage("emoteBurstOpacity") private var emoteBurstOpacity = 0.65
    @AppStorage("pulseBarSize") private var pulseBarSize = 1.0
    @State private var channels = starterChannels
    @State private var savedChatters: [SavedChatter] = []
    @StateObject private var liveChat = LiveChatService()
    @StateObject private var twitchAuth = TwitchAuthService()
    @StateObject private var store = StoreManager()
    @StateObject private var ads = AdManager()
    @State private var mode: ChatMode = .river
    @State private var section: AppSection = .chat
    @State private var selectedChannels: Set<UUID> = []
    @State private var showingSettings = false
    @State private var showingAddChannel = false
    @State private var inspectedMessage: ChatMessage?
    @State private var inspectedChannel: Channel?
    @State private var inspectedChatter: SavedChatter?
    @State private var exportDocument: ExportDocument?
    @State private var burstEmoji: String?
    @State private var burstToken = UUID()
    @State private var isAutoFollowing = true
    @State private var pendingChatterDeletion: IndexSet?

    private var messages: [ChatMessage] {
        liveChat.messages
    }

    private var visibleMessages: [ChatMessage] {
        selectedChannels.isEmpty ? messages : messages.filter { selectedChannels.contains($0.channel.id) }
    }

    private var enabledChannels: [Channel] { channels.filter(\.isEnabled) }

    var body: some View {
        ZStack {
            NabColors.background.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                modePicker
                Divider().overlay(NabColors.line)
                Group {
                    switch section {
                    case .chat: chatContent
                    case .chatters: savedChattersView
                    case .analytics: analyticsView
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay {
                    if let burstEmoji {
                        EmojiBurstView(
                            emoji: burstEmoji,
                            token: burstToken,
                            scale: emoteBurstSize / 100,
                            opacity: emoteBurstOpacity
                        )
                        .id(burstToken)
                        .allowsHitTesting(false)
                    }
                }
                if !store.isPlus && ads.canRequestAds {
                    NabchatBannerAd()
                }
                pulseBar
                channelStrip
                bottomNavigation
            }
        }
        .preferredColorScheme(.dark)
        .onChange(of: keepScreenAwake) { UIApplication.shared.isIdleTimerDisabled = $0 }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = keepScreenAwake
            restoreChannels()
            restoreSavedChatters()
            twitchAuth.validateSavedAuthorization()
            refreshProviders()
        }
        .task {
            // Let the window and root view controller finish attaching before
            // UMP presents consent UI or the ads SDK initializes.
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            await ads.configure()
        }
        .onChange(of: channels) {
            persistChannels($0)
            refreshProviders()
        }
        .onChange(of: twitchAuth.state) { _ in refreshProviders() }
        .onChange(of: liveChat.messages.count) { _ in triggerEmojiBurst() }
        .onChange(of: store.isPlus) { isPlus in
            if !isPlus { enforceFreeChannelLimit() }
            refreshProviders()
        }
        .sheet(isPresented: $showingSettings) {
            SettingsView(channels: $channels, liveChat: liveChat, twitchAuth: twitchAuth, store: store, ads: ads, coreStatus: SharedCoreInfo.shared.status())
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showingAddChannel) {
            AddChannelView { channel in
                var newChannel = channel
                newChannel.isEnabled = store.isPlus || channels.filter(\.isEnabled).count < 6
                channels.append(newChannel)
                selectedChannels = [channel.id]
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .sheet(item: $inspectedMessage) { message in
            MessageDetailView(message: message, isSaved: savedChatters.contains { $0.username.caseInsensitiveCompare(message.username) == .orderedSame && $0.platform == message.channel.platform }) {
                saveChatter(from: message)
            }
        }
        .sheet(item: $exportDocument) { document in
            ShareSheet(items: [document.url])
        }
        .sheet(item: $inspectedChannel) { channel in
            ChannelDetailView(
                channel: channel,
                messages: liveChat.messages.filter { $0.channel.id == channel.id || ($0.channel.name == channel.name && $0.channel.platform == channel.platform) }
            )
        }
        .sheet(item: $inspectedChatter) { chatter in
            ChatterHistoryView(
                chatter: chatter,
                messages: liveChat.messages.filter {
                    $0.username.caseInsensitiveCompare(chatter.username) == .orderedSame && $0.channel.platform == chatter.platform
                }
            )
        }
        .confirmationDialog("Delete this saved chatter?", isPresented: Binding(
            get: { pendingChatterDeletion != nil },
            set: { if !$0 { pendingChatterDeletion = nil } }
        ), titleVisibility: .visible) {
            Button("Delete saved chatter", role: .destructive) {
                if let offsets = pendingChatterDeletion {
                    savedChatters.remove(atOffsets: offsets)
                    persistSavedChatters()
                }
                pendingChatterDeletion = nil
            }
            Button("Cancel", role: .cancel) { pendingChatterDeletion = nil }
        } message: {
            Text("Their saved label and highlight color will be removed. Stored chat history is not deleted.")
        }
        .fullScreenCover(isPresented: Binding(
            get: { !hasCompletedOnboarding },
            set: { if !$0 { hasCompletedOnboarding = true } }
        )) {
            OnboardingView { hasCompletedOnboarding = true }
        }
    }

    private func restoreChannels() {
        guard !savedChannels.isEmpty,
              let data = savedChannels.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([Channel].self, from: data)
        else { return }
        channels = decoded
    }

    private func persistChannels(_ value: [Channel]) {
        guard let data = try? JSONEncoder().encode(value),
              let encoded = String(data: data, encoding: .utf8)
        else { return }
        savedChannels = encoded
    }

    private func restoreSavedChatters() {
        guard let data = savedChattersData.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([SavedChatter].self, from: data) else { return }
        savedChatters = decoded
    }

    private func persistSavedChatters() {
        guard let data = try? JSONEncoder().encode(savedChatters),
              let encoded = String(data: data, encoding: .utf8) else { return }
        savedChattersData = encoded
    }

    private func saveChatter(from message: ChatMessage) {
        guard !savedChatters.contains(where: { $0.username.caseInsensitiveCompare(message.username) == .orderedSame && $0.platform == message.channel.platform }) else { return }
        savedChatters.insert(SavedChatter(username: message.username, platform: message.channel.platform), at: 0)
        persistSavedChatters()
    }

    private func refreshProviders() {
        liveChat.update(channels: enabledChannels, twitchToken: twitchAuth.accessToken, twitchUserID: twitchAuth.userID)
    }

    private func enforceFreeChannelLimit() {
        var enabledCount = 0
        for index in channels.indices where channels[index].isEnabled {
            enabledCount += 1
            if enabledCount > 6 { channels[index].isEnabled = false }
        }
    }

    private var pulseItems: [(emoji: String, count: Int)] {
        var counts: [String: Int] = [:]
        for message in visibleMessages.suffix(500) {
            for scalar in message.text.unicodeScalars where scalar.properties.isEmojiPresentation {
                counts[String(scalar), default: 0] += 1
            }
        }
        var ranked: [(emoji: String, count: Int)] = counts.map { entry in
            (emoji: entry.key, count: entry.value)
        }
        ranked.sort { left, right in
            if left.count == right.count { return left.emoji < right.emoji }
            return left.count > right.count
        }
        return Array(ranked.prefix(7))
    }

    @ViewBuilder private var pulseBar: some View {
        if !pulseItems.isEmpty {
            HStack(spacing: 8) {
                Text("PULSE")
                    .font(.system(size: 11 * pulseBarSize, weight: .bold, design: .serif))
                    .foregroundStyle(NabColors.green)
                ForEach(Array(pulseItems.enumerated()), id: \.offset) { _, item in
                    HStack(spacing: 4) {
                        Text(item.emoji).font(.system(size: 18 * pulseBarSize))
                        Text("\(item.count)").font(.system(size: 10 * pulseBarSize)).foregroundStyle(NabColors.secondary)
                    }
                    .padding(.horizontal, 8).frame(height: 34 * pulseBarSize)
                    .background(NabColors.raised, in: Capsule())
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14).padding(.vertical, 5)
            .frame(maxWidth: .infinity).background(NabColors.background)
            .overlay(alignment: .top) { Divider().overlay(NabColors.line) }
        }
    }

    private func triggerEmojiBurst() {
        guard emoteBurstEnabled,
              let message = liveChat.messages.last,
              selectedChannels.isEmpty || selectedChannels.contains(message.channel.id),
              let emoji = message.text.unicodeScalars.first(where: { $0.properties.isEmojiPresentation }) else { return }
        burstEmoji = String(emoji)
        burstToken = UUID()
        let token = burstToken
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.15) {
            if burstToken == token { burstEmoji = nil }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Text("nabchat")
                .font(.system(size: 32, weight: .bold, design: .serif))
                .foregroundStyle(NabColors.text)
            Spacer()
            Button { showingSettings = true } label: {
                Image(systemName: "gearshape")
                    .font(.system(size: 18, weight: .medium))
                    .frame(width: 42, height: 42)
                    .background(NabColors.raised, in: Circle())
            }
            HStack(spacing: 7) {
                Circle().fill(liveChat.isConnected ? NabColors.green : NabColors.secondary).frame(width: 9, height: 9)
                Text(liveChat.isConnected ? "CONNECTED" : "CONNECTING")
                    .font(.system(size: 12, weight: .medium, design: .serif))
            }
            .foregroundStyle(NabColors.text)
            .padding(.horizontal, 14)
            .frame(height: 42)
            .background(NabColors.green.opacity(0.13), in: Capsule())
        }
        .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 14)
    }

    private var modePicker: some View {
        HStack(spacing: 10) {
            HStack(spacing: 0) {
                ForEach(ChatMode.allCases, id: \.self) { item in
                    Button {
                        mode = item
                        section = .chat
                    } label: {
                        Label(item.rawValue, systemImage: item.icon)
                            .font(.system(size: 12, weight: .semibold, design: .serif))
                            .frame(maxWidth: .infinity, minHeight: 48, maxHeight: 48)
                            .background(mode == item ? NabColors.raised : Color.clear)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if item != .rug { Divider().frame(height: 48).overlay(NabColors.line) }
                }
            }
            .foregroundStyle(NabColors.text)
            .overlay(Capsule().stroke(NabColors.line, lineWidth: 1))
            .clipShape(Capsule())
            Button { showingAddChannel = true } label: {
                Image(systemName: "plus")
                    .font(.system(size: 24, weight: .medium))
                    .frame(width: 48, height: 48)
                    .background(NabColors.raised, in: Circle())
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: 820)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16).padding(.bottom, 14)
    }

    @ViewBuilder private var chatContent: some View {
        switch mode {
        case .river:
            if enabledChannels.isEmpty {
                emptyChatState
            } else {
                ScrollViewReader { proxy in
                    ZStack(alignment: .bottomTrailing) {
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                ForEach(visibleMessages) { message in
                                    MessageRow(message: message) { inspectedMessage = message }.id(message.id)
                                }
                            }
                        }
                        .simultaneousGesture(DragGesture().onChanged { _ in isAutoFollowing = false })
                        HStack(spacing: 8) {
                            Button {
                                isAutoFollowing = false
                                if let first = visibleMessages.first { withAnimation { proxy.scrollTo(first.id, anchor: .top) } }
                            } label: {
                                Image(systemName: "arrow.up.to.line").frame(width: 44, height: 44)
                            }
                            Button {
                                isAutoFollowing = true
                                if let last = visibleMessages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                            } label: {
                                Image(systemName: "arrow.down.to.line").frame(width: 44, height: 44)
                            }
                        }
                        .foregroundStyle(NabColors.text).background(NabColors.raised, in: Capsule()).padding(12)
                    }
                    .onAppear {
                        isAutoFollowing = startWithAutoScroll
                        if isAutoFollowing, let last = visibleMessages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                    .onChange(of: visibleMessages.count) { _ in
                        if isAutoFollowing, let last = visibleMessages.last {
                            withAnimation(.easeOut(duration: 0.18)) { proxy.scrollTo(last.id, anchor: .bottom) }
                        }
                    }
                }
            }
        case .rooms:
            if enabledChannels.isEmpty {
                emptyChatState
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(enabledChannels) { channel in
                            RoomCard(channel: channel, messages: messages.filter { $0.channel == channel }) {
                                inspectedChannel = channel
                            }
                        }
                    }.padding(14)
                }
            }
        case .rug:
            if enabledChannels.isEmpty {
                emptyChatState
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(enabledChannels) { channel in
                            RugCard(channel: channel, messages: messages.filter { $0.channel == channel }) { inspectedChannel = channel }
                        }
                    }
                        .padding(14)
                }
            }
        }
    }

    private var emptyChatState: some View {
        VStack(spacing: 16) {
            Image(systemName: "text.bubble.fill").font(.system(size: 44)).foregroundStyle(NabColors.green)
            Text("Your combined chat starts here")
                .font(.system(size: 21, weight: .semibold, design: .serif)).foregroundStyle(NabColors.text)
            Text("Add a chat channel to begin.").foregroundStyle(NabColors.secondary)
            Button("ADD CHANNEL") { showingAddChannel = true }
                .buttonStyle(.borderedProminent).tint(NabColors.green).foregroundStyle(.black)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var channelStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ChannelPill(title: "ALL", initials: nil, color: NabColors.raised, selected: selectedChannels.isEmpty) {
                    selectedChannels.removeAll()
                }
                ForEach(enabledChannels) { channel in
                    ChannelPill(title: channel.name, initials: channel.shortName, color: channel.platform.color, selected: selectedChannels.contains(channel.id)) {
                        if selectedChannels.contains(channel.id) { selectedChannels.remove(channel.id) }
                        else { selectedChannels.insert(channel.id) }
                    }
                }
            }.padding(.horizontal, 14).padding(.vertical, 9)
        }
        .overlay(alignment: .top) { Divider().overlay(NabColors.line) }
    }

    private var bottomNavigation: some View {
        HStack {
            navButton(.chatters); Spacer(); navButton(.chat); Spacer(); navButton(.analytics)
        }
        .padding(.horizontal, 34).padding(.top, 8).padding(.bottom, 7)
    }

    private var savedChattersView: some View {
        Group {
            if savedChatters.isEmpty {
                placeholder(title: "Saved Chatters", icon: "person.2")
            } else {
                List {
                    ForEach($savedChatters) { $chatter in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Button { inspectedChatter = chatter } label: {
                                    HStack {
                                        Circle().fill(chatter.platform.color).frame(width: 10, height: 10)
                                        Text(chatter.username).font(.system(size: 19, weight: .semibold, design: .serif))
                                        Image(systemName: "chevron.right").font(.caption).foregroundStyle(NabColors.secondary)
                                    }
                                }.buttonStyle(.plain)
                                Spacer()
                                Button {
                                    exportChats(for: chatter)
                                } label: {
                                    Image(systemName: "square.and.arrow.down")
                                }
                                .buttonStyle(.plain).foregroundStyle(chatter.platform.color)
                                Text(chatter.platform.rawValue.uppercased()).font(.caption2).foregroundStyle(chatter.platform.color)
                            }
                            let count = liveChat.messages.filter { $0.username.caseInsensitiveCompare(chatter.username) == .orderedSame && $0.channel.platform == chatter.platform }.count
                            Text("\(count) saved messages on this device")
                                .font(.caption).foregroundStyle(NabColors.secondary)
                            HStack(spacing: 12) {
                                ForEach(0..<6) { index in
                                    Circle().fill(chatterColor(index)).frame(width: 22, height: 22)
                                        .overlay(Circle().stroke(Color.white, lineWidth: chatter.colorIndex == index ? 2 : 0))
                                        .onTapGesture {
                                            chatter.colorIndex = index
                                            persistSavedChatters()
                                        }
                                }
                            }
                        }
                        .padding(.vertical, 7)
                        .listRowBackground(NabColors.surface)
                    }
                    .onDelete {
                        pendingChatterDeletion = $0
                    }
                    Section {
                        Button {
                            exportAllSavedChats()
                        } label: {
                            Label("Export all saved chatter chats", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
    }

    private var analyticsView: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                HStack {
                    metricCard("MESSAGES", value: "\(liveChat.messages.count)", icon: "text.bubble")
                    metricCard("CHATTERS", value: "\(Set(liveChat.messages.map { $0.username.lowercased() }).count)", icon: "person.2")
                }
                ForEach(enabledChannels) { channel in
                    let channelMessages = liveChat.messages.filter { $0.channel.id == channel.id || ($0.channel.name == channel.name && $0.channel.platform == channel.platform) }
                    let unique = Set(channelMessages.map { $0.username.lowercased() }).count
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Circle().fill(channel.platform.color).frame(width: 10, height: 10)
                            Text(channel.name).font(.system(size: 18, design: .serif))
                            Spacer()
                            Text(channel.platform.rawValue.uppercased()).font(.caption2).foregroundStyle(channel.platform.color)
                        }
                        HStack {
                            Label("\(unique) chatters", systemImage: "person.2")
                            Spacer()
                            Label("\(ChatRate.messagesPerMinute(channelMessages))/min", systemImage: "speedometer")
                            Spacer()
                            Label("\(channelMessages.count) messages", systemImage: "text.bubble")
                        }
                        .font(.caption).foregroundStyle(NabColors.secondary)
                        GeometryReader { geometry in
                            RoundedRectangle(cornerRadius: 3).fill(NabColors.raised).frame(height: 6)
                            RoundedRectangle(cornerRadius: 3).fill(channel.platform.color)
                                .frame(width: geometry.size.width * activityFraction(channelMessages.count), height: 6)
                        }.frame(height: 6)
                    }
                    .padding(14).background(NabColors.surface, in: RoundedRectangle(cornerRadius: 15))
                }
            }.padding(14)
        }
    }

    private func metricCard(_ title: String, value: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: icon).foregroundStyle(NabColors.green)
            Text(value).font(.title2.bold())
            Text(title).font(.caption2).foregroundStyle(NabColors.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(14)
        .background(NabColors.surface, in: RoundedRectangle(cornerRadius: 15))
    }

    private func activityFraction(_ count: Int) -> CGFloat {
        let maximum = max(1, enabledChannels.map { channel in
            liveChat.messages.filter { $0.channel.name == channel.name && $0.channel.platform == channel.platform }.count
        }.max() ?? 1)
        return CGFloat(count) / CGFloat(maximum)
    }

    private func chatterColor(_ index: Int) -> Color {
        [NabColors.green, NabColors.purple, NabColors.youtube, .orange, .cyan, .pink][index % 6]
    }

    private func exportChats(for chatter: SavedChatter) {
        let matching = liveChat.messages.filter {
            $0.username.caseInsensitiveCompare(chatter.username) == .orderedSame && $0.channel.platform == chatter.platform
        }
        exportDocument = ChatExporter.makeDocument(messages: matching, filename: "nabchat-\(chatter.username)-chats.csv")
    }

    private func exportAllSavedChats() {
        let identities = Set(savedChatters.map { "\($0.platform.rawValue):\($0.username.lowercased())" })
        let matching = liveChat.messages.filter { identities.contains("\($0.channel.platform.rawValue):\($0.username.lowercased())") }
        exportDocument = ChatExporter.makeDocument(messages: matching, filename: "nabchat-saved-chatters.csv")
    }

    private func navButton(_ item: AppSection) -> some View {
        Button {
            if item == .chat && section == .chat { selectedChannels.removeAll() }
            section = item
        } label: {
            VStack(spacing: 4) {
                Image(systemName: item.icon).font(.system(size: 20, weight: .medium))
                    .frame(width: 54, height: 34)
                    .background(section == item ? NabColors.raised : Color.clear, in: Capsule())
                Text(item.rawValue).font(.system(size: 10, weight: .medium, design: .serif))
            }
            .frame(minWidth: 88, minHeight: 52)
            .contentShape(Rectangle())
            .foregroundStyle(section == item ? NabColors.text : NabColors.secondary)
        }.buttonStyle(.plain)
    }

    private func placeholder(title: String, icon: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 42)).foregroundStyle(NabColors.green)
            Text(title).font(.title3.weight(.semibold)).foregroundStyle(NabColors.text)
            Text("The iOS foundation is ready for this section.").font(.caption).foregroundStyle(NabColors.secondary)
        }
    }
}

private struct MessageRow: View {
    let message: ChatMessage
    let action: () -> Void
    @AppStorage("showTimestamps") private var showTimestamps = true
    @AppStorage("showProfilePictures") private var showProfilePictures = true
    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
            if showProfilePictures {
                Circle().fill(message.channel.platform.color.opacity(0.28)).frame(width: 39, height: 39)
                    .overlay(Text(message.channel.shortName).font(.caption.bold()))
            }
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    Text(message.username).foregroundStyle(message.channel.platform.color)
                    if let badge = message.badge { Text(badge).font(.system(size: 9)).foregroundStyle(NabColors.secondary) }
                    Spacer()
                    if showTimestamps {
                        Text(message.time).font(.system(size: 10)).foregroundStyle(message.channel.platform.color.opacity(0.72))
                    }
                }
                ChatMessageContent(text: message.text).foregroundStyle(NabColors.text)
            }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain).font(.system(size: 16, design: .serif)).padding(.horizontal, 14).padding(.vertical, 11)
    }
}

private struct EmojiBurstView: View {
    let emoji: String
    let token: UUID
    let scale: Double
    let opacity: Double
    @State private var visible = false

    var body: some View {
        GeometryReader { geometry in
            let seed = abs(token.uuidString.hashValue)
            let directionX: CGFloat = seed.isMultiple(of: 2) ? 1 : -1
            let directionY: CGFloat = seed.isMultiple(of: 3) ? 1 : -1
            let fontSize = 30 * scale
            let safeX = max(0, geometry.size.width / 2 - min(110, fontSize * 0.32))
            let safeY = max(0, geometry.size.height / 2 - min(140, fontSize * 0.36))
            Text(emoji)
                .font(.system(size: fontSize))
                .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
                .offset(x: directionX * min(safeX, geometry.size.width * 0.18), y: directionY * min(safeY, geometry.size.height * 0.16))
                .scaleEffect(visible ? 1 : 0.22)
                .opacity(visible ? opacity : 0)
                .onAppear {
                    withAnimation(.spring(response: 0.34, dampingFraction: 0.72)) { visible = true }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.72) {
                        withAnimation(.easeOut(duration: 0.38)) { visible = false }
                    }
                }
        }
    }
}

private struct MessageDetailView: View {
    @Environment(\.dismiss) private var dismiss
    let message: ChatMessage
    let isSaved: Bool
    let onSave: () -> Void

    var body: some View {
        NavigationView {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Circle().fill(message.channel.platform.color.opacity(0.3)).frame(width: 46, height: 46)
                        .overlay(Text(message.channel.shortName).font(.caption.bold()))
                    VStack(alignment: .leading) {
                        Text(message.username).font(.title3.bold()).foregroundStyle(message.channel.platform.color)
                        Text("\(message.channel.platform.rawValue.capitalized) · \(message.channel.name)")
                            .font(.caption).foregroundStyle(NabColors.secondary)
                    }
                }
                ChatMessageContent(text: message.text).font(.system(size: 22, design: .serif))
                HStack {
                    Text(message.time).font(.caption).foregroundStyle(NabColors.secondary)
                    Spacer()
                    Button {
                        UIPasteboard.general.string = message.text
                    } label: { Label("Copy", systemImage: "doc.on.doc") }
                }
                Button {
                    onSave()
                } label: {
                    Label(isSaved ? "Saved chatter" : "Save chatter", systemImage: isSaved ? "star.fill" : "star")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(message.channel.platform.color).disabled(isSaved)
                Spacer()
            }
            .padding(22).background(NabColors.background.ignoresSafeArea())
            .navigationTitle("Chat Message").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

private struct ChannelPill: View {
    let title: String
    let initials: String?
    let color: Color
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                if let initials = initials {
                    Circle().fill(color.opacity(0.35)).frame(width: 35, height: 35)
                        .overlay(Text(initials).font(.system(size: 10, weight: .bold)))
                }
                Text(title).font(.system(size: 12, weight: .medium, design: .serif)).lineLimit(1)
            }
            .padding(.horizontal, initials == nil ? 18 : 7).frame(height: 46)
            .background(selected ? color.opacity(0.32) : NabColors.background)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? color : NabColors.line, lineWidth: selected ? 2 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain)
    }
}

private struct RoomCard: View {
    let channel: Channel
    let messages: [ChatMessage]
    let action: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: action) { HStack {
                Circle().fill(channel.platform.color.opacity(0.3)).frame(width: 36, height: 36)
                    .overlay(Text(channel.shortName).font(.caption.bold()))
                Text(channel.name).font(.system(size: 19, design: .serif)).foregroundStyle(channel.platform.color)
                Spacer()
                Image(systemName: "link").foregroundStyle(channel.platform.color)
                Text("\(messages.count) MSG").font(.caption2).foregroundStyle(NabColors.secondary)
            }}.buttonStyle(.plain)
            ForEach(messages) { message in
                HStack(spacing: 4) {
                    Text("\(message.username):")
                    ChatMessageContent(text: message.text)
                }
                .font(.system(size: 15, design: .serif)).foregroundStyle(NabColors.text).lineLimit(1)
            }
        }.padding(13).background(NabColors.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct RugCard: View {
    let channel: Channel
    let messages: [ChatMessage]
    let action: () -> Void
    var body: some View {
        VStack(spacing: 0) {
            Button(action: action) { HStack {
                Text(channel.name).font(.system(size: 19, design: .serif)).foregroundStyle(channel.platform.color)
                Spacer()
                Text("\(Set(messages.map { $0.username.lowercased() }).count) CHATTERS · \(ChatRate.messagesPerMinute(messages))/MIN")
                    .font(.system(size: 10)).foregroundStyle(NabColors.secondary)
                Text("WATCH NOW").font(.system(size: 10, weight: .bold)).foregroundStyle(channel.platform.color)
            }}.buttonStyle(.plain).padding(13)
            HStack(spacing: 9) {
                ForEach(messages.suffix(3)) { message in
                    HStack(spacing: 3) {
                        Text("\(message.username):")
                        ChatMessageContent(text: message.text)
                    }
                }
            }
            .font(.system(size: 15, design: .serif)).foregroundStyle(NabColors.text)
            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(NabColors.raised.opacity(0.65)).clipped()
        }
        .background(NabColors.surface, in: RoundedRectangle(cornerRadius: 16))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

private struct ChannelDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let channel: Channel
    let messages: [ChatMessage]

    private var channelURL: URL? {
        if let direct = URL(string: channel.name), direct.scheme != nil { return direct }
        let value = channel.name.trimmingCharacters(in: CharacterSet(charactersIn: "@"))
            .addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? channel.name
        switch channel.platform {
        case .kick: return URL(string: "https://kick.com/\(value)")
        case .twitch: return URL(string: "https://twitch.tv/\(value)")
        case .youtube: return URL(string: "https://youtube.com/@\(value)")
        }
    }

    var body: some View {
        NavigationView {
            ScrollViewReader { proxy in
                VStack(spacing: 0) {
                    HStack(spacing: 10) {
                        Circle().fill(channel.platform.color.opacity(0.3)).frame(width: 40, height: 40)
                            .overlay(Text(channel.shortName).font(.caption.bold()))
                        VStack(alignment: .leading) {
                            Text(channel.name).font(.title3.bold()).foregroundStyle(channel.platform.color)
                            Text("\(channel.platform.rawValue.capitalized) · \(messages.count) messages")
                                .font(.caption).foregroundStyle(NabColors.secondary)
                        }
                        Spacer()
                        if let channelURL {
                            Button { openURL(channelURL) } label: { Label("Watch", systemImage: "arrow.up.right.square") }
                                .font(.caption).foregroundStyle(channel.platform.color)
                        }
                    }
                    .padding(14)
                    Divider().overlay(NabColors.line)
                    if messages.isEmpty {
                        Spacer()
                        Text("No saved chats for this channel yet.").foregroundStyle(NabColors.secondary)
                        Spacer()
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                ForEach(messages) { message in
                                    MessageRow(message: message, action: {}).id(message.id)
                                }
                            }
                        }
                        HStack {
                            Button { if let first = messages.first { proxy.scrollTo(first.id, anchor: .top) } } label: {
                                Image(systemName: "arrow.up.to.line").frame(width: 44, height: 38)
                            }
                            Button { if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) } } label: {
                                Image(systemName: "arrow.down.to.line").frame(width: 44, height: 38)
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 12).background(NabColors.surface)
                    }
                }
                .background(NabColors.background.ignoresSafeArea())
            }
            .navigationTitle("All Chats")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Back") { dismiss() } } }
        }
    }
}

private struct ChatterHistoryView: View {
    @Environment(\.dismiss) private var dismiss
    let chatter: SavedChatter
    let messages: [ChatMessage]

    var body: some View {
        NavigationView {
            ScrollViewReader { proxy in
                VStack(spacing: 0) {
                    HStack {
                        Circle().fill(chatter.platform.color).frame(width: 11, height: 11)
                        Text(chatter.username).font(.title3.bold()).foregroundStyle(chatter.platform.color)
                        Spacer()
                        Text("\(messages.count) CHATS").font(.caption).foregroundStyle(NabColors.secondary)
                    }
                    .padding(14)
                    Divider().overlay(NabColors.line)
                    if messages.isEmpty {
                        Spacer()
                        Text("No saved chats for this chatter yet.").foregroundStyle(NabColors.secondary)
                        Spacer()
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                ForEach(messages) { message in
                                    MessageRow(message: message, action: {}).id(message.id)
                                }
                            }
                        }
                        HStack(spacing: 8) {
                            Button { if let first = messages.first { proxy.scrollTo(first.id, anchor: .top) } } label: {
                                Image(systemName: "arrow.up.to.line").frame(width: 44, height: 38)
                            }
                            Button { if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) } } label: {
                                Image(systemName: "arrow.down.to.line").frame(width: 44, height: 38)
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 12).background(NabColors.surface)
                    }
                }
                .background(NabColors.background.ignoresSafeArea())
            }
            .navigationTitle("All Chats")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Back") { dismiss() } } }
        }
    }
}

private struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Binding var channels: [Channel]
    @ObservedObject var liveChat: LiveChatService
    @ObservedObject var twitchAuth: TwitchAuthService
    @ObservedObject var store: StoreManager
    @ObservedObject var ads: AdManager
    let coreStatus: String
    @State private var confirmingClear = false
    @AppStorage("keepScreenAwake") private var keepScreenAwake = false
    @AppStorage("showTimestamps") private var showTimestamps = true
    @AppStorage("showProfilePictures") private var showProfilePictures = true
    @AppStorage("startWithAutoScroll") private var startWithAutoScroll = true
    @AppStorage("showLikelySpam") private var showLikelySpam = false
    @AppStorage("emoteBurstEnabled") private var emoteBurstEnabled = true
    @AppStorage("emoteBurstSize") private var emoteBurstSize = 300.0
    @AppStorage("emoteBurstOpacity") private var emoteBurstOpacity = 0.65
    @AppStorage("pulseBarSize") private var pulseBarSize = 1.0
    var body: some View {
        NavigationView {
            Form {
                Section("Platforms") {
                    Label("Kick", systemImage: "checkmark.circle.fill").foregroundStyle(NabColors.green)
                    twitchConnectionRow
                    Label("YouTube", systemImage: "circle").foregroundStyle(NabColors.youtube)
                }
                Section("Channels") {
                    if channels.isEmpty {
                        Text("No channels added").foregroundStyle(NabColors.secondary)
                    }
                    ForEach($channels) { $channel in
                        HStack {
                            Circle().fill(channel.platform.color).frame(width: 9, height: 9)
                            Text(channel.name)
                            Spacer()
                            Button {
                                channel.isFavorite.toggle()
                            } label: {
                                Image(systemName: channel.isFavorite ? "star.fill" : "star")
                                    .foregroundStyle(channel.isFavorite ? Color.yellow : NabColors.secondary)
                            }
                            .buttonStyle(.plain)
                            Toggle("", isOn: Binding(
                                get: { channel.isEnabled },
                                set: { requested in setEnabled(requested, channelID: channel.id) }
                            ))
                            .labelsHidden()
                            Text(channel.platform.rawValue.uppercased())
                                .font(.caption2).foregroundStyle(channel.platform.color)
                        }
                    }
                    .onDelete { channels.remove(atOffsets: $0) }
                    if !store.isPlus {
                        Text("Free mode supports up to six enabled channels. Favorites remain saved even when disabled.")
                            .font(.caption).foregroundStyle(NabColors.secondary)
                    }
                }
                Section("Message Arrival") {
                    Toggle("Start with auto-scroll", isOn: $startWithAutoScroll)
                    Toggle("Keep screen on", isOn: $keepScreenAwake)
                    Toggle("Show likely spam messages", isOn: $showLikelySpam)
                }
                Section("Appearance") {
                    Toggle("Show timestamps", isOn: $showTimestamps)
                    Toggle("Show profile pictures", isOn: $showProfilePictures)
                    Toggle("Emote bursts", isOn: $emoteBurstEnabled)
                    VStack(alignment: .leading) {
                        Text("Burst size · \(Int(emoteBurstSize))%")
                        Slider(value: $emoteBurstSize, in: 100...1000, step: 50)
                    }
                    VStack(alignment: .leading) {
                        Text("Burst transparency · \(Int((1 - emoteBurstOpacity) * 100))%")
                        Slider(value: $emoteBurstOpacity, in: 0.1...1.0, step: 0.05)
                    }
                    VStack(alignment: .leading) {
                        Text("PULSE size · \(Int(pulseBarSize * 100))%")
                        Slider(value: $pulseBarSize, in: 0.5...2.0, step: 0.1)
                    }
                }
                Section("Chat History") {
                    HStack {
                        Text("Stored messages")
                        Spacer()
                        Text("\(liveChat.messages.count)").foregroundStyle(NabColors.secondary)
                    }
                    Button("Delete all stored messages", role: .destructive) { confirmingClear = true }
                        .disabled(liveChat.messages.isEmpty)
                }
                Section("nabchat+") {
                    if store.isPlus {
                        Label("nabchat+ active", systemImage: "checkmark.seal.fill").foregroundStyle(NabColors.green)
                        Text("Ads are removed and enabled channels are unlimited.")
                            .font(.caption).foregroundStyle(NabColors.secondary)
                    } else {
                        Button {
                            Task { await store.purchase() }
                        } label: {
                            HStack {
                                Label("Upgrade to nabchat+", systemImage: "plus.circle.fill")
                                Spacer()
                                if store.isLoading { ProgressView() }
                                else if let product = store.product { Text(product.displayPrice) }
                            }
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        Button("Restore purchase") { Task { await store.restore() } }
                            .frame(minHeight: 44)
                    }
                    if let message = store.message {
                        Text(message).font(.caption).foregroundStyle(NabColors.secondary)
                    }
                }
                Section("About") {
                    Text(coreStatus)
                    if ads.privacyOptionsRequired {
                        Button("Privacy choices") { Task { await ads.presentPrivacyOptions() } }
                    }
                    Link("Privacy Policy", destination: URL(string: "https://sites.google.com/view/nabchatprivacypolicy/home")!)
                    Link("Support & nabchat website", destination: URL(string: "https://gnaboret.ca/nabchat")!)
                    HStack {
                        Text("Version")
                        Spacer()
                        Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Development")
                            .foregroundStyle(NabColors.secondary)
                    }
                    Text("nabchat by Gnaboret · the g and t are silent")
                        .font(.caption).foregroundStyle(NabColors.secondary)
                }
            }
            .navigationTitle("Settings")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Delete all saved chat messages?", isPresented: $confirmingClear, titleVisibility: .visible) {
                Button("Delete all messages", role: .destructive) { liveChat.clearHistory() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This cannot be undone.")
            }
        }
        .navigationViewStyle(.stack)
    }

    private func setEnabled(_ requested: Bool, channelID: UUID) {
        guard let index = channels.firstIndex(where: { $0.id == channelID }) else { return }
        if requested && !store.isPlus && channels.filter(\.isEnabled).count >= 6 {
            store.message = "Free mode can enable six channels. Upgrade to nabchat+ for unlimited channels."
            return
        }
        channels[index].isEnabled = requested
    }

    @ViewBuilder private var twitchConnectionRow: some View {
        switch twitchAuth.state {
        case .disconnected:
            Button { twitchAuth.connect() } label: {
                HStack { Label("Twitch", systemImage: "link"); Spacer(); Text("CONNECT") }
            }.foregroundStyle(NabColors.purple)
        case .requestingCode:
            HStack { Label("Twitch", systemImage: "clock"); Spacer(); ProgressView() }.foregroundStyle(NabColors.purple)
        case .awaitingApproval(let code, let url):
            VStack(alignment: .leading, spacing: 10) {
                Label("Twitch activation", systemImage: "link").foregroundStyle(NabColors.purple)
                Text(code).font(.title2.monospaced().bold()).textSelection(.enabled)
                HStack {
                    Button("Copy code") { UIPasteboard.general.string = code }
                    Spacer()
                    Button("Open Twitch activation") { openURL(url) }
                }.font(.caption)
            }
        case .connected(let login):
            HStack {
                Label("Twitch", systemImage: "checkmark.circle.fill").foregroundStyle(NabColors.purple)
                Spacer()
                Text("@\(login)").font(.caption)
                Button("Disconnect", role: .destructive) { twitchAuth.disconnect() }.font(.caption)
            }
        case .failed(let message):
            VStack(alignment: .leading, spacing: 8) {
                Label("Twitch authorization required", systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                Text(message).font(.caption).foregroundStyle(NabColors.secondary)
                Button("Connect again") { twitchAuth.connect() }.foregroundStyle(NabColors.purple)
            }
        }
    }
}

private struct AddChannelView: View {
    @Environment(\.dismiss) private var dismiss
    let onAdd: (Channel) -> Void
    @State private var selectedPlatform: Platform?
    @State private var channelName = ""
    var body: some View {
        NavigationView {
            VStack(spacing: 22) {
                Text("Add channel").font(.system(size: 34, design: .serif)).frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: 12) {
                    platformButton("KICK", .kick); platformButton("TWITCH", .twitch); platformButton("YOUTUBE", .youtube)
                }
                TextField("Username/channel", text: $channelName).textFieldStyle(.roundedBorder).disabled(selectedPlatform == nil)
                Button("ADD CHANNEL") {
                    guard let platform = selectedPlatform else { return }
                    let cleaned = channelName.trimmingCharacters(in: .whitespacesAndNewlines)
                    let initials = cleaned.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined().uppercased()
                    onAdd(Channel(name: cleaned, shortName: initials.isEmpty ? "?" : initials, platform: platform))
                    dismiss()
                }
                    .buttonStyle(.borderedProminent).tint(selectedPlatform?.color ?? NabColors.secondary)
                    .disabled(selectedPlatform == nil || channelName.trimmingCharacters(in: .whitespaces).isEmpty)
                Spacer()
            }
            .padding(24).background(NabColors.background.ignoresSafeArea())
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .navigationViewStyle(.stack)
    }

    private func platformButton(_ title: String, _ platform: Platform) -> some View {
        Button { selectedPlatform = platform } label: {
            Text(title)
                .font(.caption.bold())
                .frame(maxWidth: .infinity, minHeight: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(platform.color)
        .background(selectedPlatform == platform ? platform.color.opacity(0.2) : Color.clear)
        .overlay(Capsule().stroke(platform.color, lineWidth: selectedPlatform == platform ? 2 : 1))
        .clipShape(Capsule())
    }
}

private struct OnboardingView: View {
    let complete: () -> Void
    @State private var page = 0

    private let pages = [
        ("Welcome to nabchat", "Bring multiple livestream conversations together in one lightweight viewer.", "text.bubble.fill"),
        ("Add your channels", "Tap the plus button, choose Kick, Twitch, or YouTube, then enter the channel name.", "plus.circle.fill"),
        ("Choose your view", "RIVER combines everything. ROOMS separates channels. RUG follows activity at a glance.", "rectangle.3.group.fill"),
        ("Make it yours", "Save chatters, compare channel activity, and tune the experience in Settings.", "slider.horizontal.3")
    ]

    var body: some View {
        ZStack {
            NabColors.background.ignoresSafeArea()
            VStack(spacing: 28) {
                HStack {
                    Spacer()
                    Button("Skip", action: complete).foregroundStyle(NabColors.secondary)
                }
                Spacer()
                Image(systemName: pages[page].2)
                    .font(.system(size: 62)).foregroundStyle(page == 2 ? NabColors.purple : NabColors.green)
                Text(pages[page].0)
                    .font(.system(size: 34, weight: .bold, design: .serif)).foregroundStyle(NabColors.text)
                Text(pages[page].1)
                    .font(.body).multilineTextAlignment(.center).foregroundStyle(NabColors.secondary)
                    .padding(.horizontal, 18)
                HStack(spacing: 8) {
                    ForEach(pages.indices, id: \.self) { index in
                        Capsule().fill(index == page ? NabColors.green : NabColors.line)
                            .frame(width: index == page ? 26 : 8, height: 8)
                    }
                }
                Spacer()
                Button(page == pages.count - 1 ? "START CHATTING" : "NEXT") {
                    if page == pages.count - 1 { complete() } else { withAnimation { page += 1 } }
                }
                .font(.headline).frame(maxWidth: .infinity).frame(height: 52)
                .background(NabColors.green, in: Capsule()).foregroundStyle(Color.black)
            }
            .padding(24)
        }
    }
}

private struct ExportDocument: Identifiable {
    let id = UUID()
    let url: URL
}

private enum ChatRate {
    static func messagesPerMinute(_ messages: [ChatMessage], now: Date = Date()) -> Int {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "H:mm:ss"
        let calendar = Calendar.current
        let current = calendar.dateComponents([.hour, .minute, .second], from: now)
        let currentSeconds = (current.hour ?? 0) * 3600 + (current.minute ?? 0) * 60 + (current.second ?? 0)
        return messages.filter { message in
            guard let date = formatter.date(from: message.time) else { return false }
            let value = calendar.dateComponents([.hour, .minute, .second], from: date)
            let messageSeconds = (value.hour ?? 0) * 3600 + (value.minute ?? 0) * 60 + (value.second ?? 0)
            return (currentSeconds - messageSeconds + 86_400) % 86_400 <= 60
        }.count
    }
}

private enum ChatExporter {
    static func makeDocument(messages: [ChatMessage], filename: String) -> ExportDocument? {
        var lines = ["platform,channel,username,time,message"]
        lines.append(contentsOf: messages.map {
            [$0.channel.platform.rawValue, $0.channel.name, $0.username, $0.time, $0.text]
                .map(csvField)
                .joined(separator: ",")
        })
        let safeName = filename.replacingOccurrences(of: "/", with: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(safeName)
        do {
            guard let data = lines.joined(separator: "\n").data(using: .utf8) else { return nil }
            try data.write(to: url, options: .atomic)
            return ExportDocument(url: url)
        } catch {
            return nil
        }
    }

    private static func csvField(_ value: String) -> String {
        "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\""
    }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        controller.popoverPresentationController?.sourceView = controller.view
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

#Preview { ContentView() }
