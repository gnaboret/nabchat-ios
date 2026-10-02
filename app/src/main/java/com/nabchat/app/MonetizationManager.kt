package com.nabchat.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BillingState(
    val isPlus: Boolean = false,
    val price: String? = null,
    val ready: Boolean = false,
    val ownershipChecked: Boolean = false,
    val message: String = "Checking Google Play…"
)

class MonetizationManager(context: Context) : PurchasesUpdatedListener {
    private val preferences = context.getSharedPreferences("nabchat_billing", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(BillingState(isPlus = preferences.getBoolean("plus_owned", false)))
    val state = mutableState.asStateFlow()
    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init { connect() }

    private fun connect(afterConnect: (() -> Unit)? = null) {
        if (billingClient.isReady) { afterConnect?.invoke(); return }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    mutableState.value = mutableState.value.copy(ready = true, message = if (mutableState.value.isPlus) "nabchat+ owned" else "Ready")
                    restorePurchases()
                    queryProduct { afterConnect?.invoke() }
                } else mutableState.value = mutableState.value.copy(ready = false, ownershipChecked = true, message = result.debugMessage.ifBlank { "Google Play billing unavailable" })
            }
            override fun onBillingServiceDisconnected() {
                mutableState.value = mutableState.value.copy(ready = false, message = "Google Play disconnected · tap to retry")
            }
        })
    }

    fun purchase(activity: Activity) {
        if (!billingClient.isReady) { connect { purchase(activity) }; return }
        queryProduct { details ->
            if (details == null) {
                mutableState.value = mutableState.value.copy(message = "Create and activate ‘$PLUS_PRODUCT_ID’ in Play Console first")
                return@queryProduct
            }
            val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
            details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken?.let(product::setOfferToken)
            val result = billingClient.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product.build())).build())
            if (result.responseCode != BillingClient.BillingResponseCode.OK) mutableState.value = mutableState.value.copy(message = result.debugMessage)
        }
    }

    fun restorePurchases() {
        if (!billingClient.isReady) { connect(::restorePurchases); return }
        billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) processPurchases(purchases)
            else mutableState.value = mutableState.value.copy(ownershipChecked = true, message = result.debugMessage)
        }
    }

    private fun queryProduct(onResult: (ProductDetails?) -> Unit = {}) {
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(PLUS_PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
        billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, detailsResult ->
            val details = detailsResult.productDetailsList.firstOrNull()
            val price = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
            mutableState.value = mutableState.value.copy(price = price, message = when {
                mutableState.value.isPlus -> "nabchat+ owned"
                result.responseCode != BillingClient.BillingResponseCode.OK -> result.debugMessage
                details == null -> "Upgrade becomes available after Play Console setup"
                else -> "Remove ads and unlock unlimited channels"
            })
            onResult(details)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) processPurchases(purchases.orEmpty())
        else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) mutableState.value = mutableState.value.copy(message = result.debugMessage)
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val owned = purchases.filter { PLUS_PRODUCT_ID in it.products }.any { purchase ->
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return@any false
            if (!purchase.isAcknowledged) billingClient.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) {}
            true
        }
        preferences.edit().putBoolean("plus_owned", owned).apply()
        mutableState.value = mutableState.value.copy(isPlus = owned, ownershipChecked = true, message = if (owned) "nabchat+ owned" else "Remove ads and unlock unlimited channels")
    }

    companion object { const val PLUS_PRODUCT_ID = "nabchat_plus" }
}
