import AppTrackingTransparency
import GoogleMobileAds
import SwiftUI
import UserMessagingPlatform

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

        do {
            try await ConsentInformation.shared.requestConsentInfoUpdate(with: RequestParameters())
            try await ConsentForm.loadAndPresentIfRequired(from: Self.presentingViewController)
        } catch {
            // A previous consent decision can still permit an ad request.
        }

        privacyOptionsRequired = ConsentInformation.shared.privacyOptionsRequirementStatus == .required
        canRequestAds = ConsentInformation.shared.canRequestAds
        guard canRequestAds, !hasStartedSDK else { return }

        await requestTrackingPermissionIfNeeded()
        await MobileAds.shared.start()
        hasStartedSDK = true
    }

    func presentPrivacyOptions() async {
        do {
            try await ConsentForm.presentPrivacyOptionsForm(from: Self.presentingViewController)
            canRequestAds = ConsentInformation.shared.canRequestAds
        } catch {
            // Keep the current consent state if the form is unavailable.
        }
    }

    private func requestTrackingPermissionIfNeeded() async {
        guard ATTrackingManager.trackingAuthorizationStatus == .notDetermined else { return }
        await withCheckedContinuation { continuation in
            ATTrackingManager.requestTrackingAuthorization { _ in continuation.resume() }
        }
    }

    private static var presentingViewController: UIViewController? {
        let root = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
        var presented = root
        while let next = presented?.presentedViewController { presented = next }
        return presented
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
        return banner
    }

    func updateUIView(_ banner: BannerView, context: Context) {
        guard banner.rootViewController == nil,
              let root = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)?
                .rootViewController else { return }
        banner.rootViewController = root
        banner.load(Request())
    }
}
