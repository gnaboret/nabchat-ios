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
        isLoading = true
        defer { isLoading = false }
        do {
            product = try await Product.products(for: [Self.plusProductID]).first
            await refreshEntitlements()
        } catch {
            message = "The App Store could not load nabchat+ yet."
        }
    }

    func purchase() async {
        guard let product else {
            await prepare()
            if self.product == nil { message = "nabchat+ is not available from the App Store yet." }
            return
        }
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
}
