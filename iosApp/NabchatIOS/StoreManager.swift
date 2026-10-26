import Foundation
import StoreKit

@MainActor
final class StoreManager: ObservableObject {
    static let plusProductID = "nabchat_plus"

    @Published private(set) var product: Product?
    @Published private(set) var isPlus = false
    @Published private(set) var isLoadingProduct = false
    @Published private(set) var isPurchasing = false
    @Published private(set) var isRestoring = false
    @Published var message: String?

    var isBusy: Bool { isPurchasing || isRestoring }

    private var transactionUpdates: Task<Void, Never>?

    init() {
        transactionUpdates = observeTransactions()
        Task { await refresh() }
    }

    deinit {
        transactionUpdates?.cancel()
    }

    /// Loads the product Apple currently exposes for this app and storefront,
    /// then reconciles the user's non-consumable entitlement.
    func refresh() async {
        guard !isLoadingProduct else { return }
        isLoadingProduct = true
        defer { isLoadingProduct = false }

        do {
            product = try await fetchPlusProduct()
            message = nil
        } catch {
            product = nil
            message = purchaseAvailabilityMessage
        }
        await refreshEntitlement()
    }

    /// Always performs a fresh App Store lookup before purchasing. This avoids
    /// relying on product metadata cached during app launch or a prior storefront.
    func purchase() async {
        guard !isBusy else { return }
        isPurchasing = true
        message = nil
        defer { isPurchasing = false }

        do {
            let currentProduct = try await fetchPlusProduct()
            product = currentProduct

            switch try await currentProduct.purchase() {
            case .success(let verification):
                let transaction = try verified(verification)
                await transaction.finish()
                await refreshEntitlement()
                message = isPlus ? "nabchat+ is active." : "The purchase completed, but the entitlement has not refreshed yet."

            case .pending:
                message = "The purchase is pending approval."

            case .userCancelled:
                break

            @unknown default:
                message = "The App Store returned an unknown purchase result."
            }
        } catch StoreError.productUnavailable {
            product = nil
            message = purchaseAvailabilityMessage
        } catch StoreError.failedVerification {
            message = "Apple completed the purchase, but its signature could not be verified."
        } catch {
            message = "The App Store could not complete the purchase: \(error.localizedDescription)"
        }
    }

    func restore() async {
        guard !isBusy else { return }
        isRestoring = true
        message = nil
        defer { isRestoring = false }

        do {
            try await AppStore.sync()
            await refreshEntitlement()
            message = isPlus
                ? "nabchat+ restored."
                : "No nabchat+ purchase was found for this Apple Account."
        } catch {
            message = "The App Store could not restore purchases: \(error.localizedDescription)"
        }
    }

    private func fetchPlusProduct() async throws -> Product {
        let products = try await Product.products(for: [Self.plusProductID])
        guard let product = products.first(where: { $0.id == Self.plusProductID }) else {
            throw StoreError.productUnavailable
        }
        return product
    }

    private func refreshEntitlement() async {
        var hasPlus = false

        for await result in Transaction.currentEntitlements {
            guard let transaction = try? verified(result),
                  transaction.productID == Self.plusProductID,
                  transaction.revocationDate == nil else { continue }
            hasPlus = true
            break
        }

        isPlus = hasPlus
    }

    private func observeTransactions() -> Task<Void, Never> {
        Task { [weak self] in
            for await result in Transaction.updates {
                guard let self else { return }
                guard let transaction = try? self.verified(result) else { continue }
                await transaction.finish()
                await self.refreshEntitlement()
            }
        }
    }

    private func verified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .verified(let value):
            return value
        case .unverified:
            throw StoreError.failedVerification
        }
    }

    private var purchaseAvailabilityMessage: String {
        "nabchat+ is not available from Apple right now. TestFlight uses Apple's sandbox automatically; no separate tester purchase is required. Confirm the Paid Apps Agreement is Active and that nabchat+ is included with this app version in App Store Connect."
    }

    private enum StoreError: Error {
        case productUnavailable
        case failedVerification
    }
}
