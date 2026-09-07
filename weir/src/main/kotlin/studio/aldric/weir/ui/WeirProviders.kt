package studio.aldric.weir.ui

import androidx.compose.runtime.staticCompositionLocalOf
import studio.aldric.weir.billing.NoopPurchaseProvider
import studio.aldric.weir.billing.PurchaseProviding
import studio.aldric.weir.bridge.PermissionRequester
import studio.aldric.weir.bridge.UnavailablePermissionRequester

val LocalWeirPurchaseProvider = staticCompositionLocalOf<PurchaseProviding> { NoopPurchaseProvider() }
val LocalWeirPermissionRequester = staticCompositionLocalOf<PermissionRequester> { UnavailablePermissionRequester }
