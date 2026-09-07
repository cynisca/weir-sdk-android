package studio.aldric.weir.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * kotlinx.serialization mirror of `packages/spec/src/bridge.ts` (frozen
 * contract, protocol version 1 as of spec_2 §9's bridge v1 bump). If this
 * drifts from that file, `bridge.ts` wins — update both together per the spec's
 * frozen-contract working agreement.
 *
 * Faithful port of `sdk-ios/Sources/Weir/Bridge/BridgeTypes.swift`; the Swift
 * `JSONValue` (an untyped Codable value) maps to kotlinx's [JsonElement] here,
 * so the envelope can carry `params`/`result`/`value` without committing to a
 * shape until a specific method decodes/encodes its own.
 */

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

object BridgeTransport {
    /** iOS  JS -> native: `window.webkit.messageHandlers[handlerName].postMessage(...)` */
    const val HANDLER_NAME = "weirBridge"

    /**
     * Android JS -> native: `window[ANDROID_INTERFACE_NAME].postMessage(JSON.stringify(req))`.
     * The `@JavascriptInterface` name registered via
     * `WebView.addJavascriptInterface(router, ANDROID_INTERFACE_NAME)`.
     */
    const val ANDROID_INTERFACE_NAME = "WeirAndroid"

    /** native -> JS: `window[responseGlobal](...)` (byte-identical across platforms). */
    const val RESPONSE_GLOBAL = "__weirBridgeResponse"

    /**
     * Mirrors `BRIDGE_PROTOCOL_VERSION` in packages/spec/src/bridge.ts — what
     * this SDK build answers `capabilities` with. The Android transport is
     * additive, so this stays 1 (same as iOS).
     */
    const val PROTOCOL_VERSION = 1
}

// ---------------------------------------------------------------------------
// Method enum — raw values exactly matching packages/spec/src/bridge.ts
// ---------------------------------------------------------------------------

enum class BridgeMethod(val raw: String) {
    PERMISSION_REQUEST("permission.request"),
    PURCHASE_START("purchase.start"),
    PURCHASE_RESTORE("purchase.restore"),
    HAPTIC("haptic"),
    EVENT("event"),
    COMPLETE("complete"),
    DISMISS("dismiss"),

    /** v1 — version-negotiation seam (BRIDGE.md's "Version negotiation"). */
    CAPABILITIES("capabilities"),

    /** v1 — localized product catalog. */
    PRODUCTS_LIST("products.list");

    companion object {
        /** The exhaustive raw-value list, in the same order the iOS router
         *  answers `capabilities` with. Derived from [entries] so a future
         *  method addition can't silently drift the capabilities answer. */
        val allRaws: List<String> = entries.map { it.raw }

        fun fromRaw(raw: String): BridgeMethod? = entries.firstOrNull { it.raw == raw }
    }
}

// ---------------------------------------------------------------------------
// Envelopes
// ---------------------------------------------------------------------------

@Serializable
data class BridgeRequestEnvelope(
    val id: String,
    val method: String,
    val params: JsonElement,
)

@Serializable
data class BridgeResponseEnvelope(
    val id: String,
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: String? = null,
) {
    companion object {
        fun success(id: String, result: JsonElement): BridgeResponseEnvelope =
            BridgeResponseEnvelope(id = id, ok = true, result = result, error = null)

        fun failure(id: String, error: String): BridgeResponseEnvelope =
            BridgeResponseEnvelope(id = id, ok = false, result = null, error = error)
    }
}

// ---------------------------------------------------------------------------
// permission.request
// ---------------------------------------------------------------------------

@Serializable
enum class PermissionType {
    @SerialName("notifications") NOTIFICATIONS,
    @SerialName("tracking") TRACKING,
    @SerialName("health") HEALTH,
    @SerialName("camera") CAMERA,
    @SerialName("location") LOCATION,
}

@Serializable
enum class PermissionStatus {
    @SerialName("granted") GRANTED,
    @SerialName("denied") DENIED,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
data class PermissionRequestParams(val type: PermissionType)

@Serializable
data class PermissionRequestResult(val status: PermissionStatus)

// ---------------------------------------------------------------------------
// purchase.start / purchase.restore
// ---------------------------------------------------------------------------

@Serializable
enum class PurchaseStatus {
    @SerialName("purchased") PURCHASED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED,
    @SerialName("restored") RESTORED,
}

@Serializable
data class PurchaseStartParams(
    val productId: String,
    val offerId: String? = null,
)

@Serializable
data class PurchaseStartResult(
    val status: PurchaseStatus,
    val transactionId: String? = null,
    val error: String? = null,
)

@Serializable
class PurchaseRestoreParams

@Serializable
data class PurchaseRestoreResult(
    /** "restored" | "failed" — kept as a raw string, matching iOS. */
    val status: String,
    val productIds: List<String>? = null,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// haptic
// ---------------------------------------------------------------------------

/**
 * v1 adds `soft`/`rigid` — the hold-to-seal ramp (motion-tokens.md) needs a
 * texture `light`/`medium`/`heavy` alone couldn't express.
 */
@Serializable
enum class HapticStyle {
    @SerialName("light") LIGHT,
    @SerialName("medium") MEDIUM,
    @SerialName("heavy") HEAVY,
    @SerialName("soft") SOFT,
    @SerialName("rigid") RIGID,
    @SerialName("success") SUCCESS,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

@Serializable
data class HapticParams(
    val style: HapticStyle,
    /** v1 — 0..1, only meaningful for impact styles. Optional so a v0-shaped
     *  payload (field absent) still decodes. */
    val intensity: Double? = null,
)

// ---------------------------------------------------------------------------
// capabilities (v1)
// ---------------------------------------------------------------------------

@Serializable
class CapabilitiesParams

@Serializable
data class CapabilitiesResult(
    val version: Int,
    val methods: List<String>,
)

// ---------------------------------------------------------------------------
// products.list (v1)
// ---------------------------------------------------------------------------

@Serializable
data class ProductsListParams(
    /** The Play Billing / StoreKit analogue has no "list everything" API — the
     *  caller (weir.js) must say which ids it wants. */
    val productIds: List<String>,
)

@Serializable
data class IntroOffer(
    val priceString: String,
    val periodDescription: String,
)

/**
 * Localized product data — what `weir.products()` actually returns to flow
 * code. Distinct from the spec's author-facing `PaywallProduct` (no price
 * field there at all).
 */
@Serializable
data class ProductInfo(
    val id: String,
    val priceString: String,
    val currencyCode: String? = null,
    val periodDescription: String? = null,
    val introOffer: IntroOffer? = null,
)

@Serializable
data class ProductsListResult(
    val products: List<ProductInfo>,
)

// ---------------------------------------------------------------------------
// complete
// ---------------------------------------------------------------------------

@Serializable
data class WeirVariable(
    val id: String,
    /** spec VariableType: string|number|boolean|stringArray|enum */
    val type: String,
    val value: JsonElement = JsonNull,
)

@Serializable
data class CompleteParams(
    val variables: List<WeirVariable>,
)

// ---------------------------------------------------------------------------
// dismiss
// ---------------------------------------------------------------------------

@Serializable
data class DismissParams(
    val reason: String,
)
