import Foundation

@MainActor
final class LiveChatService: ObservableObject {
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var isConnected = false

    private var pollingTask: Task<Void, Never>?
    private var twitchTask: Task<Void, Never>?
    private var twitchSocket: URLSessionWebSocketTask?
    private var seenMessageIDs = Set<String>()
    private var channelIDs: [UUID: String] = [:]
    private var kickConnected = false
    private var twitchConnected = false
    private let twitchClientID = "4uda3hw7k1wm9m0gpw2ot2aksbh0lr"

    init() {
        loadHistory()
    }

    deinit {
        pollingTask?.cancel()
        twitchTask?.cancel()
        twitchSocket?.cancel(with: .goingAway, reason: nil)
    }

    func update(channels: [Channel], twitchToken: String? = nil, twitchUserID: String? = nil) {
        pollingTask?.cancel()
        kickConnected = false
        let kickChannels = channels.filter { $0.platform == .kick }
        if kickChannels.isEmpty {
            kickConnected = false
            refreshConnectionState(channels: channels)
        } else {
            startKickPolling(kickChannels, allChannels: channels)
        }

        startTwitch(channels.filter { $0.platform == .twitch }, token: twitchToken, userID: twitchUserID, allChannels: channels)
    }

    private func startKickPolling(_ kickChannels: [Channel], allChannels: [Channel]) {
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
                kickConnected = completedRequest
                refreshConnectionState(channels: allChannels)
                try? await Task.sleep(nanoseconds: completedRequest ? 4_000_000_000 : 8_000_000_000)
            }
        }
    }

    private func startTwitch(_ channels: [Channel], token: String?, userID: String?, allChannels: [Channel]) {
        twitchTask?.cancel()
        twitchSocket?.cancel(with: .goingAway, reason: nil)
        guard !channels.isEmpty, let token, let userID else {
            twitchConnected = false
            refreshConnectionState(channels: allChannels)
            return
        }
        twitchTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                do {
                    let resolved = try await resolveTwitchChannels(channels, token: token)
                    try await runTwitchSocket(channelsByID: resolved, token: token, userID: userID)
                } catch is CancellationError {
                    return
                } catch {
                    twitchConnected = false
                    refreshConnectionState(channels: allChannels)
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                }
            }
        }
    }

    private func resolveTwitchChannels(_ channels: [Channel], token: String) async throws -> [String: Channel] {
        var result: [String: Channel] = [:]
        for channel in channels {
            let login = normalizeTwitchLogin(channel.name)
            let encoded = login.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? login
            var request = URLRequest(url: URL(string: "https://api.twitch.tv/helix/users?login=\(encoded)")!)
            request.setValue(twitchClientID, forHTTPHeaderField: "Client-Id")
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let root = try await requestObject(request)
            guard let user = (root["data"] as? [[String: Any]])?.first,
                  let id = string(user["id"]) else { continue }
            result[id] = channel
        }
        return result
    }

    private func runTwitchSocket(channelsByID: [String: Channel], token: String, userID: String) async throws {
        guard !channelsByID.isEmpty else { throw LiveChatError.invalidChannel }
        let socket = URLSession.shared.webSocketTask(with: URL(string: "wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30")!)
        twitchSocket = socket
        socket.resume()
        defer { socket.cancel(with: .goingAway, reason: nil) }
        while !Task.isCancelled {
            let frame = try await socket.receive()
            let text: String
            switch frame {
            case .string(let value): text = value
            case .data(let data): text = String(data: data, encoding: .utf8) ?? ""
            @unknown default: continue
            }
            guard let data = text.data(using: .utf8),
                  let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let metadata = root["metadata"] as? [String: Any],
                  let type = metadata["message_type"] as? String else { continue }
            if type == "session_welcome",
               let payload = root["payload"] as? [String: Any],
               let session = payload["session"] as? [String: Any],
               let sessionID = session["id"] as? String {
                for channelID in channelsByID.keys {
                    try await subscribeTwitch(channelID: channelID, userID: userID, sessionID: sessionID, token: token)
                }
                twitchConnected = true
                isConnected = true
            } else if type == "notification", let message = parseTwitchMessage(root, channelsByID: channelsByID) {
                appendNew([message])
            } else if type == "session_reconnect" {
                throw LiveChatError.reconnect
            }
        }
    }

    private func subscribeTwitch(channelID: String, userID: String, sessionID: String, token: String) async throws {
        let body: [String: Any] = [
            "type": "channel.chat.message",
            "version": "1",
            "condition": ["broadcaster_user_id": channelID, "user_id": userID],
            "transport": ["method": "websocket", "session_id": sessionID]
        ]
        var request = URLRequest(url: URL(string: "https://api.twitch.tv/helix/eventsub/subscriptions")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(twitchClientID, forHTTPHeaderField: "Client-Id")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { throw LiveChatError.requestFailed }
    }

    private func parseTwitchMessage(_ root: [String: Any], channelsByID: [String: Channel]) -> ChatMessage? {
        guard let payload = root["payload"] as? [String: Any],
              let event = payload["event"] as? [String: Any],
              let channelID = string(event["broadcaster_user_id"]),
              let channel = channelsByID[channelID],
              let messageID = string(event["message_id"]),
              let username = string(event["chatter_user_name"] ?? event["chatter_user_login"]),
              let message = event["message"] as? [String: Any],
              let text = string(message["text"]) else { return nil }
        let badges = (event["badges"] as? [[String: Any]])?.compactMap { string($0["set_id"]) }.map { $0.uppercased() }.joined(separator: " · ")
        return ChatMessage(channel: channel, username: username, text: text, time: DateFormatter.chatTime.string(from: Date()), badge: badges?.nilIfEmpty, sourceID: "twitch:\(messageID)")
    }

    private func refreshConnectionState(channels: [Channel]) {
        isConnected = channels.isEmpty || kickConnected || twitchConnected
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
        return try await requestObject(request)
    }

    private func requestObject(_ request: URLRequest) async throws -> [String: Any] {
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

    private func normalizeTwitchLogin(_ input: String) -> String {
        var value = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().replacingOccurrences(of: "@", with: "")
        if let range = value.range(of: "twitch.tv/") { value = String(value[range.upperBound...]) }
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
    case invalidChannel, requestFailed, invalidResponse, reconnect
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
