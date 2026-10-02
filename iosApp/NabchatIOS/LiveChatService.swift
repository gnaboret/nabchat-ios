import Foundation

@MainActor
final class LiveChatService: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var isConnected = false

    private var pollingTask: Task<Void, Never>?
    private var seenMessageIDs = Set<String>()
    private var channelIDs: [UUID: String] = [:]

    init() {
        loadHistory()
    }

    deinit {
        pollingTask?.cancel()
    }

    func update(channels: [Channel]) {
        pollingTask?.cancel()
        let kickChannels = channels.filter { $0.platform == .kick }
        guard !kickChannels.isEmpty else {
            isConnected = channels.isEmpty
            return
        }

        pollingTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                var completedRequest = false
                for channel in kickChannels where !Task.isCancelled {
                    do {
                        let channelID = try await resolveKickID(for: channel)
                        let incoming = try await fetchKickMessages(channel: channel, channelID: channelID)
                        appendNew(incoming)
                        completedRequest = true
                    } catch is CancellationError {
                        return
                    } catch {
                        // One unavailable channel should not stop the other rooms.
                    }
                }
                isConnected = completedRequest
                try? await Task.sleep(nanoseconds: completedRequest ? 4_000_000_000 : 8_000_000_000)
            }
        }
    }

    func clearHistory() {
        messages.removeAll()
        seenMessageIDs.removeAll()
        try? FileManager.default.removeItem(at: historyURL)
    }

    private func resolveKickID(for channel: Channel) async throws -> String {
        if let cached = channelIDs[channel.id] { return cached }
        let slug = normalizeSlug(channel.name)
        let url = try endpoint("https://kick.com/api/v2/channels/\(slug)")
        let json = try await requestObject(url)
        guard let value = json["id"] as? NSNumber else { throw LiveChatError.invalidChannel }
        let channelID = value.stringValue
        channelIDs[channel.id] = channelID
        return channelID
    }

    private func fetchKickMessages(channel: Channel, channelID: String) async throws -> [ChatMessage] {
        let url = try endpoint("https://web.kick.com/api/v1/chat/\(channelID)/history")
        let root = try await requestObject(url)
        let rawMessages = (root["messages"] as? [[String: Any]])
            ?? (root["data"] as? [[String: Any]])
            ?? ((root["data"] as? [String: Any])?["messages"] as? [[String: Any]])
            ?? []

        return rawMessages.compactMap { raw in
            guard let messageID = string(raw["id"] ?? raw["message_id"]),
                  let text = string(raw["content"] ?? raw["message"]),
                  !messageID.isEmpty else { return nil }
            let sender = (raw["sender"] as? [String: Any]) ?? (raw["user"] as? [String: Any]) ?? [:]
            let username = string(sender["username"] ?? sender["slug"]) ?? "unknown"
            let badge = kickBadge(from: sender)
            return ChatMessage(
                channel: channel,
                username: username,
                text: text,
                time: formattedTime(raw["created_at"]),
                badge: badge,
                sourceID: "kick:\(messageID)"
            )
        }
    }

    private func appendNew(_ incoming: [ChatMessage]) {
        let fresh = incoming.filter { seenMessageIDs.insert($0.sourceID).inserted }
        guard !fresh.isEmpty else { return }
        messages.append(contentsOf: fresh)
        if messages.count > 2_000 { messages.removeFirst(messages.count - 2_000) }
        if seenMessageIDs.count > 5_000 { seenMessageIDs = Set(messages.map(\.sourceID)) }
        saveHistory()
    }

    private func loadHistory() {
        guard let data = try? Data(contentsOf: historyURL),
              let saved = try? JSONDecoder().decode([ChatMessage].self, from: data)
        else { return }
        messages = Array(saved.suffix(2_000))
        seenMessageIDs = Set(messages.map(\.sourceID))
    }

    private func saveHistory() {
        guard let data = try? JSONEncoder().encode(messages) else { return }
        try? FileManager.default.createDirectory(at: historyURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: historyURL, options: .atomic)
    }

    private var historyURL: URL {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return root.appendingPathComponent("Nabchat", isDirectory: true).appendingPathComponent("chat-history.json")
    }

    private func requestObject(_ url: URL) async throws -> [String: Any] {
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148", forHTTPHeaderField: "User-Agent")
        request.setValue("https://kick.com", forHTTPHeaderField: "Origin")
        request.setValue("https://kick.com/", forHTTPHeaderField: "Referer")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw LiveChatError.requestFailed
        }
        guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw LiveChatError.invalidResponse
        }
        return value
    }

    private func normalizeSlug(_ input: String) -> String {
        var value = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        value = value.replacingOccurrences(of: "@", with: "")
        if let range = value.range(of: "kick.com/") { value = String(value[range.upperBound...]) }
        return value.split(whereSeparator: { $0 == "/" || $0 == "?" || $0 == "#" }).first.map(String.init) ?? value
    }

    private func kickBadge(from sender: [String: Any]) -> String? {
        guard let identity = sender["identity"] as? [String: Any],
              let badges = identity["badges"] as? [[String: Any]] else { return nil }
        return badges.compactMap { string($0["type"]) }.map { $0.uppercased() }.joined(separator: " · ").nilIfEmpty
    }

    private func formattedTime(_ value: Any?) -> String {
        guard let raw = string(value), let date = ISO8601DateFormatter().date(from: raw) else {
            return DateFormatter.chatTime.string(from: Date())
        }
        return DateFormatter.chatTime.string(from: date)
    }

    private func string(_ value: Any?) -> String? {
        if let value = value as? String { return value }
        if let value = value as? NSNumber { return value.stringValue }
        return nil
    }

    private func endpoint(_ value: String) throws -> URL {
        guard let url = URL(string: value) else { throw LiveChatError.invalidResponse }
        return url
    }
}

private enum LiveChatError: Error {
    case invalidChannel, requestFailed, invalidResponse
}

private extension DateFormatter {
    static let chatTime: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "h:mm:ss"
        return formatter
    }()
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
