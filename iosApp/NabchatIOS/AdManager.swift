import AppTrackingTransparency
import GoogleMobileAds
import SwiftUI

@MainActor
final class AdManager: ObservableObject {
    @Published private(set) var canRequestAds = false
    @Published private(set) var privacyOptionsRequired = false

    private var isConfiguring = false
    private var hasStartedSDK = false

    func configure() async {
        guard !isConfiguring, !hasStartedSDK else { return }
        isConfiguring = true
        defer { isConfiguring = false }

        await requestTrackingPermissionIfNeeded()
        await GADMobileAds.sharedInstance().start()
        hasStartedSDK = true
        canRequestAds = true
    }

    func presentPrivacyOptions() async {
        // Consent UI is temporarily unavailable while Google's UMP component is
        // isolated from a pre-launch crash on iPadOS 26.4.
    }

    private func requestTrackingPermissionIfNeeded() async {
        guard ATTrackingManager.trackingAuthorizationStatus == .notDetermined else { return }
        await withCheckedContinuation { continuation in
            ATTrackingManager.requestTrackingAuthorization { _ in continuation.resume() }
        }
    }

}

struct NabchatBannerAd: View {
    var body: some View {
        BannerViewContainer()
            .frame(width: 320, height: 50)
            .frame(maxWidth: .infinity)
            .background(NabColors.background)
            .accessibilityLabel("Advertisement")
    }
}

private struct BannerViewContainer: UIViewRepresentable {
    func makeUIView(context: Context) -> GADBannerView {
        let banner = GADBannerView(adSize: GADAdSizeBanner)
#if DEBUG
        banner.adUnitID = "ca-app-pub-3940256099942544/2435281174"
#else
        banner.adUnitID = "ca-app-pub-5870784629837288/4364469537"
#endif
        return banner
    }

    func updateUIView(_ banner: GADBannerView, context: Context) {
        guard banner.rootViewController == nil,
              let root = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)?
                .rootViewController else { return }
        banner.rootViewController = root
        banner.load(GADRequest())
    }
}
