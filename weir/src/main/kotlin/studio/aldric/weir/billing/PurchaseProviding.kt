package studio.aldric.weir.billing

import studio.aldric.weir.bridge.ProductInfo
import studio.aldric.weir.bridge.PurchaseRestoreResult
import studio.aldric.weir.bridge.PurchaseStartResult

/**
 * Abstraction over the purchase backend so a Noop (the Phase 1 default) and a
 * future Play Billing adapter can sit behind the same bridge router. Faithful
 * port of `sdk-ios/Sources/Weir/StoreKit/PurchaseProviding.swift`; the Swift
 * `async` methods map to `suspend` here.
 */
interface PurchaseProviding {
    suspend fun purchase(productId: String, offerId: String?): PurchaseStartResult

    suspend fun restore(): PurchaseRestoreResult

    /** Bridge v1's `products.list` — localized product data for [ids]. Omit
     *  (don't error the whole call for) any id that isn't found/loaded. */
    suspend fun products(ids: List<String>): List<ProductInfo>
}
