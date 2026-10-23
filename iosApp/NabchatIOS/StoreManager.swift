import Foundation
import StoreKit

@MainActor
final class StoreManager: ObservableObject {
    static let plusProductID = "nabchat_plus"

    @Published private(set) var product: Product?
    @Published private(set) var isPlus = false
    @Published private(set) var isLoading = false
    @Published var message: String?

    private var transactionUpdates: Task<Void, Never>?

    init() {
        transactionUpdates = Task { [weak self] in
            for await update in Transaction.updates {
                guard let self else { return }
                if case .verified(let transaction) = update {
                    await transaction.finish()
                    await self.refreshEntitlements()
                }
            }
        }
        Task { await prepare() }
    }

    deinit { transactionUpdates?.cancel() }

    func prepare() async {
        guard !isLoading else { return }
        isLoading = true
        defer { isLoading = false }

        product = await loadProduct()
        await refreshEntitlements()

        if product == nil {
            message = "nabchat+ is temporarily unavailable. Check your connection, then tap Reload."
        } else if message?.contains("temporarily unavailable") == true {
            message = nil
        }
    }

    func purchase() async {
        if product == nil {
            await prepare()
        }
        guard let product else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            switch try await product.purchase() {
            case .success(let verification):
                guard case .verified(let transaction) = verification else {
                    message = "The purchase could not be verified."
                    return
                }
                await transaction.finish()
                await refreshEntitlements()
            case .pending:
                message = "The purchase is awaiting approval."
            case .userCancelled:
                break
            @unknown default:
                break
            }
        } catch {
            message = "The purchase could not be completed."
        }
    }

    func restore() async {
        isLoading = true
        defer { isLoading = false }
        do {
            try await AppStore.sync()
            await refreshEntitlements()
            message = isPlus ? "nabchat+ restored." : "No nabchat+ purchase was found for this Apple ID."
        } catch {
            message = "Purchases could not be restored."
        }
    }

    private func refreshEntitlements() async {
        var entitled = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  transaction.productID == Self.plusProductID,
                  transaction.revocationDate == nil else { continue }
            entitled = true
        }
        isPlus = entitled
    }

    /// App Store product metadata can briefly be unavailable immediately after
    /// launch or a storefront refresh. Retry before presenting an unavailable
    /// purchase state so App Review and customers get a resilient purchase flow.
    private func loadProduct() async -> Product? {
        for attempt in 0..<3 {
            do {
                if let product = try await Product.products(for: [Self.plusProductID]).first {
                    return product
                }
            } catch {
                // A later attempt may succeed after StoreKit refreshes its state.
            }

            if attempt < 2 {
                try? await Task.sleep(nanoseconds: UInt64(attempt + 1) * 750_000_000)
            }
        }
        return nil
    }
}
