import SwiftUI

@MainActor
final class AdManager: ObservableObject {
    @Published private(set) var canRequestAds = false
    @Published private(set) var privacyOptionsRequired = false

    // Ads are temporarily disabled while isolating an iOS startup crash.
    func configure() async {}
    func presentPrivacyOptions() async {}
}

struct NabchatBannerAd: View {
    var body: some View {
        EmptyView()
    }
}
