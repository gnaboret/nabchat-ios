import SwiftUI

@MainActor
final class AdManager: ObservableObject {
    @Published private(set) var canRequestAds = false
    @Published private(set) var privacyOptionsRequired = false

    // The Google ads binary crashes during process startup on the current
    // TestFlight/iPad environment, before delayed initialization can run.
    func configure() async {}
    func presentPrivacyOptions() async {}
}

struct NabchatBannerAd: View {
    var body: some View { EmptyView() }
}
