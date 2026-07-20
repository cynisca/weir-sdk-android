package studio.aldric.weir.billing

import studio.aldric.weir.bridge.ProductInfo
import studio.aldric.weir.bridge.PurchaseRestoreResult
import studio.aldric.weir.bridge.PurchaseStartResult
import studio.aldric.weir.bridge.PurchaseStatus

/**
 * A [PurchaseProviding] that never actually purchases anything — every
 * `purchase` reports `failed`, `restore` reports no restored products, and
 * `products` returns an empty catalog. Lets an integrator run a flow end to
 * end (any screen not gated on a real purchase outcome) without writing a
 * `PurchaseProviding` conformance first. Per plan D2, Play Billing is deferred;
 * this ships so the bridge's `purchase.*`/`products.list` methods answer
 * honestly until a flow actually needs a real backend. Faithful port of
 * `sdk-ios/Sources/Weir/StoreKit/NoopPurchaseProvider.swift`.
 */
class NoopPurchaseProvider : PurchaseProviding {
    override suspend fun purchase(productId: String, offerId: String?): PurchaseStartResult =
        PurchaseStartResult(
            status = PurchaseStatus.FAILED,
            error = "NoopPurchaseProvider: no purchase backend is configured",
        )

    override suspend fun restore(): PurchaseRestoreResult =
        PurchaseRestoreResult(
            status = "failed",
            productIds = emptyList(),
            error = "NoopPurchaseProvider: no purchase backend is configured",
        )

    override suspend fun products(ids: List<String>): List<ProductInfo> = emptyList()
}
