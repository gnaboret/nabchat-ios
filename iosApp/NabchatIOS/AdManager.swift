import AppTrackingTransparency
import GoogleMobileAds
import UserMessagingPlatform
import SwiftUI

@MainActor
final class AdManager: ObservableObject {
    @Published private(set) var canRequestAds = false
    @Published private(set) var privacyOptionsRequired = false

    private var hasStartedSDK = false

    func configure() async {
        do {
            try await ConsentInformation.shared.requestConsentInfoUpdate(with: RequestParameters())
            try await ConsentForm.loadAndPresentIfRequired(from: nil)
        } catch {
            // A previous valid consent result can still permit ads when an update fails.
        }

        privacyOptionsRequired = ConsentInformation.shared.privacyOptionsRequirementStatus == .required
        canRequestAds = ConsentInformation.shared.canRequestAds
        guard canRequestAds, !hasStartedSDK else { return }

        await requestTrackingPermissionIfNeeded()
        MobileAds.shared.start()
        hasStartedSDK = true
    }

    func presentPrivacyOptions() async {
        do {
            try await ConsentForm.presentPrivacyOptionsForm(from: nil)
            canRequestAds = ConsentInformation.shared.canRequestAds
        } catch {
            // Keep the current consent state when the form is temporarily unavailable.
        }
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
    func makeUIView(context: Context) -> BannerView {
        let banner = BannerView(adSize: AdSizeBanner)
#if DEBUG
        banner.adUnitID = "ca-app-pub-3940256099942544/2435281174"
#else
        banner.adUnitID = "ca-app-pub-5870784629837288/4364469537"
#endif
        banner.rootViewController = nil
        banner.load(Request())
        return banner
    }

    func updateUIView(_ uiView: BannerView, context: Context) {}
}
