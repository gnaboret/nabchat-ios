import SwiftUI
import NabchatShared

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

    init(id: UUID = UUID(), name: String, shortName: String, platform: Platform) {
        self.id = id
        self.name = name
        self.shortName = shortName
        self.platform = platform
    }
}

struct ChatMessage: Identifiable {
    let id = UUID()
    let channel: Channel
    let username: String
    let text: String
    let time: String
    let badge: String?
    let sourceID: String

    init(channel: Channel, username: String, text: String, time: String, badge: String?, sourceID: String = UUID().uuidString) {
        self.channel = channel
        self.username = username
        self.text = text
        self.time = time
        self.badge = badge
        self.sourceID = sourceID
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
    @State private var channels = starterChannels
    @StateObject private var liveChat = LiveChatService()
    @State private var mode: ChatMode = .river
    @State private var section: AppSection = .chat
    @State private var selectedChannels: Set<UUID> = []
    @State private var showingSettings = false
    @State private var showingAddChannel = false

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
                    case .chatters: placeholder(title: "Saved Chatters", icon: "person.2")
                    case .analytics: placeholder(title: "Channel Analytics", icon: "chart.xyaxis.line")
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                channelStrip
                bottomNavigation
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            restoreChannels()
            liveChat.update(channels: channels)
        }
        .onChange(of: channels) {
            persistChannels($0)
            liveChat.update(channels: $0)
        }
        .sheet(isPresented: $showingSettings) {
            SettingsView(channels: $channels, coreStatus: SharedCoreInfo.shared.status())
        }
        .sheet(isPresented: $showingAddChannel) {
            AddChannelView { channel in
                channels.append(channel)
                selectedChannels = [channel.id]
            }
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
                        ForEach(visibleMessages) { MessageRow(message: $0).id($0.id) }
                    }
                }
                .onAppear { if let last = visibleMessages.last { proxy.scrollTo(last.id, anchor: .bottom) } }
            }
        case .rooms:
            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(channels) { channel in
                        RoomCard(channel: channel, messages: messages.filter { $0.channel == channel })
                    }
                }.padding(14)
            }
        case .rug:
            ScrollView {
                LazyVStack(spacing: 12) { ForEach(channels) { RugCard(channel: $0) } }
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
                ForEach(channels) { channel in
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
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Circle().fill(message.channel.platform.color.opacity(0.28)).frame(width: 39, height: 39)
                .overlay(Text(message.channel.shortName).font(.caption.bold()))
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    Text(message.username).foregroundStyle(message.channel.platform.color)
                    if let badge = message.badge { Text(badge).font(.system(size: 9)).foregroundStyle(NabColors.secondary) }
                    Spacer()
                    Text(message.time).font(.system(size: 10)).foregroundStyle(message.channel.platform.color.opacity(0.72))
                }
                Text(message.text).foregroundStyle(NabColors.text)
            }
        }
        .font(.system(size: 16, design: .serif)).padding(.horizontal, 14).padding(.vertical, 11)
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
    @Binding var channels: [Channel]
    let coreStatus: String
    var body: some View {
        NavigationView {
            Form {
                Section("Platforms") {
                    Label("Kick", systemImage: "checkmark.circle.fill").foregroundStyle(NabColors.green)
                    Label("Twitch", systemImage: "circle").foregroundStyle(NabColors.purple)
                    Label("YouTube", systemImage: "circle").foregroundStyle(NabColors.youtube)
                }
                Section("Channels") {
                    if channels.isEmpty {
                        Text("No channels added").foregroundStyle(NabColors.secondary)
                    }
                    ForEach(channels) { channel in
                        HStack {
                            Circle().fill(channel.platform.color).frame(width: 9, height: 9)
                            Text(channel.name)
                            Spacer()
                            Text(channel.platform.rawValue.uppercased())
                                .font(.caption2).foregroundStyle(channel.platform.color)
                        }
                    }
                    .onDelete { channels.remove(atOffsets: $0) }
                }
                Section("About") { Text(coreStatus); Text("nabchat by Gnaboret") }
            }
            .navigationTitle("Settings")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
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

#Preview { ContentView() }
