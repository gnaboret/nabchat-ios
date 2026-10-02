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
    private static let starterChannels = [
        Channel(name: "ChickenAndy", shortName: "CA", platform: .kick),
        Channel(name: "BinxBasilisk", shortName: "BB", platform: .twitch),
        Channel(name: "KrispyW", shortName: "KW", platform: .youtube),
        Channel(name: "Ice Poseidon", shortName: "IP", platform: .kick)
    ]

    @AppStorage("savedChannels") private var savedChannels = ""
    @AppStorage("savedChatters") private var savedChattersData = ""
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @AppStorage("keepScreenAwake") private var keepScreenAwake = false
    @State private var channels = starterChannels
    @State private var savedChatters: [SavedChatter] = []
    @StateObject private var liveChat = LiveChatService()
    @StateObject private var twitchAuth = TwitchAuthService()
    @StateObject private var store = StoreManager()
    @State private var mode: ChatMode = .river
    @State private var section: AppSection = .chat
    @State private var selectedChannels: Set<UUID> = []
    @State private var showingSettings = false
    @State private var showingAddChannel = false
    @State private var inspectedMessage: ChatMessage?

    private var messages: [ChatMessage] {
        if !liveChat.messages.isEmpty { return liveChat.messages }
        let examples: [(String, String, String?)] = [
            ("cardlo", "CJ is a man with character", "SUB ×3"),
            ("hoodneighbour", "this layout is looking clean", nil),
            ("PinkyDaP", "the whole conversation in one place", "VIP"),
            ("wraxter", "might be the best thing ever filmed", nil),
            ("2moreweeks", "we are officially building on iOS", "OG")
        ]
        guard !channels.isEmpty else { return [] }
        return examples.enumerated().map { index, example in
            ChatMessage(
                channel: channels[index % channels.count],
                username: example.0,
                text: example.1,
                time: "1:25:\(String(format: "%02d", 4 + index * 4))",
                badge: example.2
            )
        }
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
        .onChange(of: channels) {
            persistChannels($0)
            refreshProviders()
        }
        .onChange(of: twitchAuth.state) { _ in refreshProviders() }
        .onChange(of: store.isPlus) { isPlus in
            if !isPlus { enforceFreeChannelLimit() }
            refreshProviders()
        }
        .sheet(isPresented: $showingSettings) {
            SettingsView(channels: $channels, liveChat: liveChat, twitchAuth: twitchAuth, store: store, coreStatus: SharedCoreInfo.shared.status())
        }
        .sheet(isPresented: $showingAddChannel) {
            AddChannelView { channel in
                var newChannel = channel
                newChannel.isEnabled = store.isPlus || channels.filter(\.isEnabled).count < 6
                channels.append(newChannel)
                selectedChannels = [channel.id]
            }
        }
        .sheet(item: $inspectedMessage) { message in
            MessageDetailView(message: message, isSaved: savedChatters.contains { $0.username.caseInsensitiveCompare(message.username) == .orderedSame && $0.platform == message.channel.platform }) {
                saveChatter(from: message)
            }
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
                            .frame(maxWidth: .infinity).frame(height: 48)
                            .background(mode == item ? NabColors.raised : Color.clear)
                    }
                    .buttonStyle(.plain)
                    if item != .rug { Divider().overlay(NabColors.line) }
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
        .padding(.horizontal, 16).padding(.bottom, 14)
    }

    @ViewBuilder private var chatContent: some View {
        switch mode {
        case .river:
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(visibleMessages) { message in
                            MessageRow(message: message) { inspectedMessage = message }.id(message.id)
                        }
                    }
                }
                .onAppear { if let last = visibleMessages.last { proxy.scrollTo(last.id, anchor: .bottom) } }
            }
        case .rooms:
            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(enabledChannels) { channel in
                        RoomCard(channel: channel, messages: messages.filter { $0.channel == channel })
                    }
                }.padding(14)
            }
        case .rug:
            ScrollView {
                LazyVStack(spacing: 12) { ForEach(enabledChannels) { RugCard(channel: $0) } }
                    .padding(14)
            }
        }
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
                                Circle().fill(chatter.platform.color).frame(width: 10, height: 10)
                                Text(chatter.username).font(.system(size: 19, weight: .semibold, design: .serif))
                                Spacer()
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
                        savedChatters.remove(atOffsets: $0)
                        persistSavedChatters()
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
                Text(message.text).foregroundStyle(NabColors.text)
            }
            }
        }
        .buttonStyle(.plain).font(.system(size: 16, design: .serif)).padding(.horizontal, 14).padding(.vertical, 11)
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
                Text(message.text).font(.system(size: 22, design: .serif)).textSelection(.enabled)
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
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Circle().fill(channel.platform.color.opacity(0.3)).frame(width: 36, height: 36)
                    .overlay(Text(channel.shortName).font(.caption.bold()))
                Text(channel.name).font(.system(size: 19, design: .serif)).foregroundStyle(channel.platform.color)
                Spacer()
                Image(systemName: "link").foregroundStyle(channel.platform.color)
                Text("\(messages.count) MSG").font(.caption2).foregroundStyle(NabColors.secondary)
            }
            ForEach(messages) { message in
                Text("\(message.username): \(message.text)")
                    .font(.system(size: 14, design: .serif)).foregroundStyle(NabColors.text).lineLimit(1)
            }
        }.padding(13).background(NabColors.surface, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct RugCard: View {
    let channel: Channel
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(channel.name).font(.system(size: 19, design: .serif)).foregroundStyle(channel.platform.color)
                Spacer()
                Text("88 CHATTERS · 126/MIN ↑").font(.system(size: 10)).foregroundStyle(NabColors.secondary)
                Text("WATCH NOW").font(.system(size: 10, weight: .bold)).foregroundStyle(channel.platform.color)
            }.padding(13)
            HStack(spacing: 9) {
                Text("chat keeps moving"); Text("all conversations together"); Text("nabchat on iOS")
            }
            .font(.system(size: 13, design: .serif)).foregroundStyle(NabColors.text)
            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(NabColors.raised.opacity(0.65)).clipped()
        }
        .background(NabColors.surface, in: RoundedRectangle(cornerRadius: 16))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

private struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Binding var channels: [Channel]
    @ObservedObject var liveChat: LiveChatService
    @ObservedObject var twitchAuth: TwitchAuthService
    @ObservedObject var store: StoreManager
    let coreStatus: String
    @State private var confirmingClear = false
    @AppStorage("keepScreenAwake") private var keepScreenAwake = false
    @AppStorage("showTimestamps") private var showTimestamps = true
    @AppStorage("showProfilePictures") private var showProfilePictures = true
    @AppStorage("startWithAutoScroll") private var startWithAutoScroll = true
    @AppStorage("showLikelySpam") private var showLikelySpam = false
    @AppStorage("emoteBurstEnabled") private var emoteBurstEnabled = true
    @AppStorage("emoteBurstSize") private var emoteBurstSize = 300.0
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
                        }
                        Button("Restore purchase") { Task { await store.restore() } }
                    }
                    if let message = store.message {
                        Text(message).font(.caption).foregroundStyle(NabColors.secondary)
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
                Section("About") { Text(coreStatus); Text("nabchat by Gnaboret") }
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
                HStack {
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
    }

    private func platformButton(_ title: String, _ platform: Platform) -> some View {
        Button(title) { selectedPlatform = platform }
            .font(.caption.bold()).frame(maxWidth: .infinity, minHeight: 42).foregroundStyle(platform.color)
            .background(selectedPlatform == platform ? platform.color.opacity(0.2) : Color.clear)
            .overlay(Capsule().stroke(platform.color, lineWidth: selectedPlatform == platform ? 2 : 1)).clipShape(Capsule())
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

#Preview { ContentView() }
