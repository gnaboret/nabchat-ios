import Foundation

struct YouTubeRawMessage {
    let id: String
    let username: String
    let text: String
    let badge: String?
    let date: Date
}

struct YouTubeChatSession {
    let apiKey: String
    let clientVersion: String
    var continuation: String
    let visitorData: String?
    let videoID: String
}

struct YouTubePollResult {
    let messages: [YouTubeRawMessage]
    let continuation: String?
    let delay: TimeInterval
}

final class YouTubeChatClient {
    private let userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    func discoverLiveVideoID(channelInput: String) async throws -> String? {
        if let direct = directVideoID(channelInput) { return direct }
        let path = normalizedChannelPath(channelInput)
        let page = try await getText("https://www.youtube.com/\(path)/live")
        guard let player = extractJSONObject(page, variable: "ytInitialPlayerResponse"),
              let details = player["videoDetails"] as? [String: Any],
              details["isLiveContent"] as? Bool == true else { return nil }
        return details["videoId"] as? String
    }

    func openSession(videoID: String) async throws -> YouTubeChatSession {
        let watchPage = try await getText("https://www.youtube.com/watch?v=\(videoID)")
        let chatPage = try await getText("https://www.youtube.com/live_chat?v=\(videoID)&is_popout=1")
        let combined = chatPage + watchPage
        guard let key = firstMatch(#""INNERTUBE_API_KEY":"([^"]+)""#, in: combined) else { throw YouTubeError.noAPIKey }
        let version = firstMatch(#""INNERTUBE_CLIENT_VERSION":"([^"]+)""#, in: combined) ?? "2.20260901.00.00"
        guard let initial = extractJSONObject(chatPage, variable: "ytInitialData") ?? extractJSONObject(watchPage, variable: "ytInitialData"),
              let liveChat = findObject(initial, keys: ["liveChatContinuation", "liveChatRenderer"]),
              let continuation = findContinuation(liveChat) else { throw YouTubeError.chatUnavailable }
        let visitor = firstMatch(#""VISITOR_DATA":"([^"]+)""#, in: combined) ?? findString(initial, key: "visitorData")
        return YouTubeChatSession(apiKey: key, clientVersion: version, continuation: continuation, visitorData: visitor, videoID: videoID)
    }

    func poll(_ session: YouTubeChatSession) async throws -> YouTubePollResult {
        var client: [String: Any] = ["clientName": "WEB", "clientVersion": session.clientVersion]
        if let visitor = session.visitorData { client["visitorData"] = visitor }
        let body: [String: Any] = ["context": ["client": client], "continuation": session.continuation]
        var request = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=\(session.apiKey)")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        request.setValue("https://www.youtube.com/watch?v=\(session.videoID)", forHTTPHeaderField: "Referer")
        request.setValue("1", forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(session.clientVersion, forHTTPHeaderField: "X-YouTube-Client-Version")
        if let visitor = session.visitorData { request.setValue(visitor, forHTTPHeaderField: "X-Goog-Visitor-Id") }
        let root = try await requestJSON(request)
        let renderers = collectRenderers(root)
        let messages = renderers.compactMap(parseRenderer)
        let timeoutMilliseconds = findNumber(root, key: "timeoutMs")?.doubleValue ?? 5_000
        return YouTubePollResult(messages: messages, continuation: findContinuation(root), delay: min(10, max(2, timeoutMilliseconds / 1_000)))
    }

    private func parseRenderer(_ renderer: [String: Any]) -> YouTubeRawMessage? {
        guard let id = renderer["id"] as? String, !id.isEmpty else { return nil }
        let author = (((renderer["authorName"] as? [String: Any])?["simpleText"] as? String) ?? "YouTube user").replacingOccurrences(of: "@", with: "")
        let runs = ((renderer["message"] as? [String: Any])?["runs"] as? [[String: Any]]) ?? []
        let text = runs.compactMap { run -> String? in
            if let value = run["text"] as? String { return value }
            if let emoji = run["emoji"] as? [String: Any] {
                return (emoji["shortcuts"] as? [String])?.first ?? emoji["emojiId"] as? String
            }
            return nil
        }.joined()
        let badgeKeys = collectBadgeKeys(renderer["authorBadges"])
        let micros = (renderer["timestampUsec"] as? String).flatMap(Double.init) ?? Date().timeIntervalSince1970 * 1_000_000
        return YouTubeRawMessage(id: id, username: author, text: text, badge: badgeKeys.joined(separator: " · ").nilIfEmpty, date: Date(timeIntervalSince1970: micros / 1_000_000))
    }

    private func getText(_ value: String) async throws -> String {
        var request = URLRequest(url: URL(string: value)!)
        request.timeoutInterval = 15
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        request.setValue("CONSENT=YES+cb", forHTTPHeaderField: "Cookie")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { throw YouTubeError.requestFailed }
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func requestJSON(_ request: URLRequest) async throws -> [String: Any] {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw YouTubeError.requestFailed }
        return root
    }

    private func directVideoID(_ input: String) -> String? {
        firstMatch(#"(?:youtu\.be/|[?&]v=|/live/)([A-Za-z0-9_-]{11})"#, in: input)
    }

    private func normalizedChannelPath(_ input: String) -> String {
        var value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        value = value.replacingOccurrences(of: #"^https?://(www\.)?youtube\.com/"#, with: "", options: [.regularExpression, .caseInsensitive])
        value = value.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if value.hasSuffix("/live") { value.removeLast(5) }
        if value.hasPrefix("@") || value.hasPrefix("channel/") || value.hasPrefix("c/") || value.hasPrefix("user/") { return value }
        return "@\(value.replacingOccurrences(of: "@", with: ""))"
    }

    private func extractJSONObject(_ source: String, variable: String) -> [String: Any]? {
        let escaped = NSRegularExpression.escapedPattern(for: variable)
        let patterns = [#"(?:(?:var\s+)?\#(escaped)|window\["\#(escaped)"\])\s*=\s*"#, #""\#(escaped)"\s*:\s*"#]
        let ranges = patterns.compactMap { pattern -> NSRange? in
            try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]).firstMatch(in: source, range: NSRange(source.startIndex..., in: source))?.range
        }
        guard let marker = ranges.min(by: { $0.location < $1.location }),
              let markerRange = Range(marker, in: source),
              let start = source[markerRange.upperBound...].firstIndex(of: "{") else { return nil }
        var depth = 0, quoted = false, escapedCharacter = false
        var index = start
        while index < source.endIndex {
            let character = source[index]
            if quoted {
                if escapedCharacter { escapedCharacter = false }
                else if character == "\\" { escapedCharacter = true }
                else if character == "\"" { quoted = false }
            } else if character == "\"" { quoted = true }
            else if character == "{" { depth += 1 }
            else if character == "}" {
                depth -= 1
                if depth == 0 {
                    let json = String(source[start...index]).data(using: .utf8)!
                    return (try? JSONSerialization.jsonObject(with: json)) as? [String: Any]
                }
            }
            index = source.index(after: index)
        }
        return nil
    }

    private func firstMatch(_ pattern: String, in source: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let match = regex.firstMatch(in: source, range: NSRange(source.startIndex..., in: source)), match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: source) else { return nil }
        return String(source[range])
    }

    private func findObject(_ value: Any, keys: [String]) -> [String: Any]? {
        if let object = value as? [String: Any] {
            for key in keys { if let found = object[key] as? [String: Any] { return found } }
            for child in object.values { if let found = findObject(child, keys: keys) { return found } }
        } else if let array = value as? [Any] {
            for child in array { if let found = findObject(child, keys: keys) { return found } }
        }
        return nil
    }

    private func findContinuation(_ value: Any) -> String? {
        if let object = value as? [String: Any] {
            for key in ["timedContinuationData", "invalidationContinuationData", "reloadContinuationData"] {
                if let continuation = (object[key] as? [String: Any])?["continuation"] as? String { return continuation }
            }
            for child in object.values { if let found = findContinuation(child) { return found } }
        } else if let array = value as? [Any] {
            for child in array { if let found = findContinuation(child) { return found } }
        }
        return nil
    }

    private func findString(_ value: Any, key: String) -> String? {
        if let object = value as? [String: Any] {
            if let found = object[key] as? String, !found.isEmpty { return found }
            for child in object.values { if let found = findString(child, key: key) { return found } }
        } else if let array = value as? [Any] {
            for child in array { if let found = findString(child, key: key) { return found } }
        }
        return nil
    }

    private func findNumber(_ value: Any, key: String) -> NSNumber? {
        if let object = value as? [String: Any] {
            if let number = object[key] as? NSNumber { return number }
            if let string = object[key] as? String, let number = Double(string) { return NSNumber(value: number) }
            for child in object.values { if let found = findNumber(child, key: key) { return found } }
        } else if let array = value as? [Any] {
            for child in array { if let found = findNumber(child, key: key) { return found } }
        }
        return nil
    }

    private func collectRenderers(_ value: Any, result: inout [[String: Any]]) {
        if let object = value as? [String: Any] {
            for key in ["liveChatTextMessageRenderer", "liveChatPaidMessageRenderer", "liveChatMembershipItemRenderer"] {
                if let renderer = object[key] as? [String: Any] { result.append(renderer) }
            }
            object.values.forEach { collectRenderers($0, result: &result) }
        } else if let array = value as? [Any] { array.forEach { collectRenderers($0, result: &result) } }
    }

    private func collectRenderers(_ value: Any) -> [[String: Any]] {
        var result: [[String: Any]] = []
        collectRenderers(value, result: &result)
        return result
    }

    private func collectBadgeKeys(_ value: Any?) -> [String] {
        guard let value else { return [] }
        var result: [String] = []
        func walk(_ item: Any) {
            if let object = item as? [String: Any] {
                for (key, child) in object {
                    if key.hasSuffix("BadgeRenderer") { result.append(String(key.dropLast("Renderer".count)).uppercased()) }
                    walk(child)
                }
            } else if let array = item as? [Any] { array.forEach(walk) }
        }
        walk(value)
        return result
    }
}

private enum YouTubeError: LocalizedError {
    case requestFailed, noAPIKey, chatUnavailable
    var errorDescription: String? {
        switch self {
        case .requestFailed: return "YouTube could not be reached"
        case .noAPIKey: return "YouTube did not provide live-chat configuration"
        case .chatUnavailable: return "YouTube live chat is unavailable or disabled"
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
