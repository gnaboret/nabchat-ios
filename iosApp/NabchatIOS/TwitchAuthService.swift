import Foundation
import Security

@MainActor
final class TwitchAuthService: ObservableObject {
    enum State: Equatable {
        case disconnected
        case requestingCode
        case awaitingApproval(code: String, url: URL)
        case connected(login: String)
        case failed(String)
    }

    @Published private(set) var state: State = .disconnected
    private let clientID = "4uda3hw7k1wm9m0gpw2ot2aksbh0lr"
    private var pollingTask: Task<Void, Never>?

    var accessToken: String? { KeychainStore.read("twitch-access-token") }
    var userID: String? { KeychainStore.read("twitch-user-id") }

    func validateSavedAuthorization() {
        guard let token = accessToken else { state = .disconnected; return }
        Task {
            do { try await validate(token: token) }
            catch {
                if let refresh = KeychainStore.read("twitch-refresh-token") {
                    do {
                        let token = try await refreshAccessToken(refresh)
                        try await validate(token: token)
                        return
                    } catch { }
                }
                disconnect(message: "Twitch authorization expired")
            }
        }
    }

    func connect() {
        pollingTask?.cancel()
        state = .requestingCode
        pollingTask = Task {
            do {
                let code = try await requestDeviceCode()
                guard let url = URL(string: code.verificationURI) else { throw TwitchAuthError.invalidResponse }
                state = .awaitingApproval(code: code.userCode, url: url)
                try await awaitApproval(code)
            } catch is CancellationError {
                return
            } catch {
                state = .failed(error.localizedDescription)
            }
        }
    }

    func disconnect(message: String? = nil) {
        pollingTask?.cancel()
        ["twitch-access-token", "twitch-refresh-token", "twitch-user-id"].forEach(KeychainStore.delete)
        state = message.map(State.failed) ?? .disconnected
    }

    private func requestDeviceCode() async throws -> DeviceCode {
        let data = try await formRequest(
            "https://id.twitch.tv/oauth2/device",
            values: ["client_id": clientID, "scopes": "user:read:chat"]
        )
        return try JSONDecoder().decode(DeviceCode.self, from: data)
    }

    private func awaitApproval(_ code: DeviceCode) async throws {
        let deadline = Date().addingTimeInterval(TimeInterval(code.expiresIn))
        while Date() < deadline {
            try await Task.sleep(nanoseconds: UInt64(max(3, code.interval) * 1_000_000_000))
            do {
                let data = try await formRequest(
                    "https://id.twitch.tv/oauth2/token",
                    values: [
                        "client_id": clientID,
                        "scopes": "user:read:chat",
                        "device_code": code.deviceCode,
                        "grant_type": "urn:ietf:params:oauth:grant-type:device_code"
                    ]
                )
                let token = try JSONDecoder().decode(TokenResponse.self, from: data)
                KeychainStore.save(token.accessToken, key: "twitch-access-token")
                if let refresh = token.refreshToken { KeychainStore.save(refresh, key: "twitch-refresh-token") }
                try await validate(token: token.accessToken)
                return
            } catch TwitchAuthError.pending {
                continue
            }
        }
        throw TwitchAuthError.expired
    }

    private func validate(token: String) async throws {
        var request = URLRequest(url: URL(string: "https://id.twitch.tv/oauth2/validate")!)
        request.setValue("OAuth \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw TwitchAuthError.expired }
        let profile = try JSONDecoder().decode(ValidationResponse.self, from: data)
        KeychainStore.save(profile.userID, key: "twitch-user-id")
        state = .connected(login: profile.login)
    }

    private func refreshAccessToken(_ refresh: String) async throws -> String {
        let data = try await formRequest(
            "https://id.twitch.tv/oauth2/token",
            values: ["grant_type": "refresh_token", "refresh_token": refresh, "client_id": clientID]
        )
        let token = try JSONDecoder().decode(TokenResponse.self, from: data)
        KeychainStore.save(token.accessToken, key: "twitch-access-token")
        KeychainStore.save(token.refreshToken ?? refresh, key: "twitch-refresh-token")
        return token.accessToken
    }

    private func formRequest(_ endpoint: String, values: [String: String]) async throws -> Data {
        var request = URLRequest(url: URL(string: endpoint)!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = values.map { key, value in
            "\(key.urlEncoded)=\(value.urlEncoded)"
        }.sorted().joined(separator: "&").data(using: .utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw TwitchAuthError.invalidResponse }
        if (200...299).contains(http.statusCode) { return data }
        let body = String(data: data, encoding: .utf8) ?? ""
        if body.contains("authorization_pending") { throw TwitchAuthError.pending }
        throw TwitchAuthError.server(body)
    }
}

private struct DeviceCode: Decodable {
    let deviceCode: String
    let userCode: String
    let verificationURI: String
    let expiresIn: Int
    let interval: Int
    enum CodingKeys: String, CodingKey {
        case deviceCode = "device_code", userCode = "user_code", verificationURI = "verification_uri"
        case expiresIn = "expires_in", interval
    }
}

private struct TokenResponse: Decodable {
    let accessToken: String
    let refreshToken: String?
    enum CodingKeys: String, CodingKey { case accessToken = "access_token", refreshToken = "refresh_token" }
}

private struct ValidationResponse: Decodable {
    let userID: String
    let login: String
    enum CodingKeys: String, CodingKey { case userID = "user_id", login }
}

private enum TwitchAuthError: LocalizedError {
    case pending, expired, invalidResponse, server(String)
    var errorDescription: String? {
        switch self {
        case .pending: return "Waiting for Twitch approval"
        case .expired: return "The Twitch activation code expired"
        case .invalidResponse: return "Twitch returned an invalid response"
        case .server(let message): return message.isEmpty ? "Twitch sign-in failed" : message
        }
    }
}

private enum KeychainStore {
    static func save(_ value: String, key: String) {
        delete(key)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.nabchat.app",
            kSecAttrAccount as String: key,
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    static func read(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.nabchat.app",
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.nabchat.app",
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}

private extension String {
    var urlEncoded: String { addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? self }
}
