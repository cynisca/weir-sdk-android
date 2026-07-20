package studio.aldric.weir.bridge

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import studio.aldric.weir.billing.PurchaseProviding
import java.util.UUID

/**
 * Dispatches decoded `BridgeRequestEnvelope`s from the WebView to the native
 * capability behind each [BridgeMethod], and hands the matching
 * `BridgeResponseEnvelope` (as a JSON string) back through the injected
 * [respond] callback.
 *
 * Faithful port of `sdk-ios/Sources/Weir/Bridge/BridgeRouter.swift`. Two
 * deliberate divergences from the Swift original, both to keep this
 * WebView-agnostic and JVM-unit-testable:
 *
 *  1. Instead of holding a `WKWebView` and calling `evaluateJavaScript`
 *     directly, the reply channel is the injected [respond] lambda — the exact
 *     `dispatch(...)` seam the iOS tests drive, but pushed one level out so the
 *     JVM tests never need a real WebView. The [WeirWebView] host wires
 *     [respond] to `webView.post { evaluateJavascript(...) }` on the UI thread.
 *  2. The Swift `Task { }` blocks (permission/purchase/products) become
 *     coroutines launched on the injected [scope]. `@JavascriptInterface`
 *     calls arrive on a binder thread; the async handlers hop onto [scope] and
 *     the UI-thread marshalling of the reply lives in [respond].
 *
 * This sits directly on the untrusted-web↔native security boundary: every
 * decode is defensive (no force-cast on JS-supplied data) and every failure
 * resolves to an `ok:false` response plus a `bridge_error` health event,
 * never a crash.
 */
class BridgeRouter(
    private val purchaseProvider: PurchaseProviding,
    private val eventSink: EventSink,
    private val permissionRequester: PermissionRequester = UnavailablePermissionRequester,
    private val hapticEngine: HapticEngine? = null,
    private val healthFlowId: String = "_bridge",
    private val healthSessionId: String = UUID.randomUUID().toString(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onComplete: ((List<WeirVariable>) -> Unit)? = null,
    private val onDismiss: ((String) -> Unit)? = null,
    private val onFailure: ((Throwable) -> Unit)? = null,
    private val onPermissionResult: ((PermissionType, PermissionStatus) -> Unit)? = null,
    private val onPurchaseResult: ((String) -> Unit)? = null,
    /** native -> JS reply channel: receives the encoded `BridgeResponseEnvelope`
     *  JSON string. The WebView host wraps it in
     *  `window.__weirBridgeResponse(<json>)` on the UI thread. Defaults to a
     *  no-op so a router can be constructed before its channel is wired. */
    private val respond: (json: String) -> Unit = {},
) {
    private val healthSeq = HealthSeq()

    // -----------------------------------------------------------------------
    // JS -> native entry point (binder thread)
    // -----------------------------------------------------------------------

    /**
     * The single `@JavascriptInterface` method the renderer posts into via
     * `window.WeirAndroid.postMessage(JSON.stringify(request))`. Only strings
     * cross this boundary, so the body is parsed here rather than received as
     * an object (the iOS `WKScriptMessageHandler` receives a live object).
     * Never throws back into the WebView — every failure becomes an
     * `ok:false` response.
     */
    @JavascriptInterface
    fun postMessage(message: String) {
        handleMessage(message)
    }

    /**
     * Internal so JVM tests can drive decode/dispatch behavior directly with a
     * raw string, exactly as the iOS tests drive `dispatch(...)`.
     */
    internal fun handleMessage(message: String) {
        val root: JsonObject = try {
            WeirJson.parseToJsonElement(message) as? JsonObject
                ?: throw IllegalArgumentException("body is not a JSON object")
        } catch (e: Exception) {
            emitBridgeError("unknown", "malformed_body")
            respond(BridgeResponseEnvelope.failure("unknown", "malformed bridge message: ${e.message}").encoded())
            return
        }

        // Best-effort id extraction so a failed envelope decode still replies
        // to the right request id where possible (mirrors iOS bestEffortId).
        val bestEffortId = (root["id"] as? JsonPrimitive)?.contentOrNull ?: "unknown"

        val envelope: BridgeRequestEnvelope = try {
            WeirJson.decodeFromJsonElement(BridgeRequestEnvelope.serializer(), root)
        } catch (e: Exception) {
            emitBridgeError("unknown", "envelope_decode_failed")
            respond(BridgeResponseEnvelope.failure(bestEffortId, "failed to decode request envelope: ${e.message}").encoded())
            return
        }

        val method = BridgeMethod.fromRaw(envelope.method)
        if (method == null) {
            emitBridgeError(envelope.method, "unknown_method")
            respond(BridgeResponseEnvelope.failure(envelope.id, "unknown bridge method: ${envelope.method}").encoded())
            return
        }

        dispatch(method, envelope)
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    /**
     * Internal (not private) so tests can drive dispatch/decode directly, the
     * same reason the iOS `dispatch(...)` is internal.
     */
    internal fun dispatch(method: BridgeMethod, envelope: BridgeRequestEnvelope) {
        when (method) {
            BridgeMethod.PERMISSION_REQUEST -> handlePermissionRequest(envelope)
            BridgeMethod.PURCHASE_START -> handlePurchaseStart(envelope)
            BridgeMethod.PURCHASE_RESTORE -> handlePurchaseRestore(envelope)
            BridgeMethod.HAPTIC -> handleHaptic(envelope)
            BridgeMethod.EVENT -> handleEvent(envelope)
            BridgeMethod.COMPLETE -> handleComplete(envelope)
            BridgeMethod.DISMISS -> handleDismiss(envelope)
            BridgeMethod.CAPABILITIES -> handleCapabilities(envelope)
            BridgeMethod.PRODUCTS_LIST -> handleProductsList(envelope)
        }
    }

    /** v1 — every host answers this (BRIDGE.md's "Version negotiation").
     *  `methods` is the exhaustive [BridgeMethod.allRaws], so a future method
     *  addition can't silently drift the capabilities answer. */
    private fun handleCapabilities(envelope: BridgeRequestEnvelope) {
        decode(envelope, CapabilitiesParams.serializer()) ?: return
        respondSuccess(
            envelope.id,
            CapabilitiesResult.serializer(),
            CapabilitiesResult(version = BridgeTransport.PROTOCOL_VERSION, methods = BridgeMethod.allRaws),
        )
    }

    private fun handleProductsList(envelope: BridgeRequestEnvelope) {
        val params = decode(envelope, ProductsListParams.serializer()) ?: return
        scope.launch {
            val products = purchaseProvider.products(params.productIds)
            respondSuccess(envelope.id, ProductsListResult.serializer(), ProductsListResult(products))
        }
    }

    private fun handlePermissionRequest(envelope: BridgeRequestEnvelope) {
        val params = decode(envelope, PermissionRequestParams.serializer()) ?: return
        scope.launch {
            val status = permissionRequester.requestPermission(params.type)
            onPermissionResult?.invoke(params.type, status)
            respondSuccess(envelope.id, PermissionRequestResult.serializer(), PermissionRequestResult(status))
        }
    }

    private fun handlePurchaseStart(envelope: BridgeRequestEnvelope) {
        val params = decode(envelope, PurchaseStartParams.serializer()) ?: return
        scope.launch {
            val result = purchaseProvider.purchase(params.productId, params.offerId)
            onPurchaseResult?.invoke(
                "purchase.start status=${result.status.name.lowercase()}" +
                    (result.error?.let { " error=$it" } ?: ""),
            )
            respondSuccess(envelope.id, PurchaseStartResult.serializer(), result)
        }
    }

    private fun handlePurchaseRestore(envelope: BridgeRequestEnvelope) {
        // No fields, but still decode defensively so a non-object params
        // payload is rejected consistently with every other method.
        decode(envelope, PurchaseRestoreParams.serializer()) ?: return
        scope.launch {
            val result = purchaseProvider.restore()
            onPurchaseResult?.invoke("purchase.restore status=${result.status} productIds=${result.productIds ?: emptyList<String>()}")
            respondSuccess(envelope.id, PurchaseRestoreResult.serializer(), result)
        }
    }

    private fun handleHaptic(envelope: BridgeRequestEnvelope) {
        val params = decode(envelope, HapticParams.serializer()) ?: return
        // Fire-and-forget per BRIDGE.md: ack immediately, don't block on it.
        hapticEngine?.fire(params.style, params.intensity)
        respondEmpty(envelope.id)
    }

    private fun handleEvent(envelope: BridgeRequestEnvelope) {
        val params = decode(envelope, EventParams.serializer()) ?: return
        eventSink.append(params)
        respondEmpty(envelope.id)
    }

    private fun handleComplete(envelope: BridgeRequestEnvelope) {
        val params = decode(envelope, CompleteParams.serializer()) ?: return
        onComplete?.invoke(params.variables)
        respondEmpty(envelope.id)
    }

    private fun handleDismiss(envelope: BridgeRequestEnvelope) {
        val params = decode(envelope, DismissParams.serializer()) ?: return
        onDismiss?.invoke(params.reason)
        respondEmpty(envelope.id)
    }

    // -----------------------------------------------------------------------
    // Decode / respond helpers
    // -----------------------------------------------------------------------

    private fun <T> decode(envelope: BridgeRequestEnvelope, deserializer: DeserializationStrategy<T>): T? =
        try {
            WeirJson.decodeFromJsonElement(deserializer, envelope.params)
        } catch (e: Exception) {
            emitBridgeError(envelope.method, "params_decode_failed")
            respond(BridgeResponseEnvelope.failure(envelope.id, "failed to decode params for ${envelope.method}: ${e.message}").encoded())
            null
        }

    private fun <T> respondSuccess(id: String, serializer: SerializationStrategy<T>, result: T) {
        val element: JsonElement = try {
            WeirJson.encodeToJsonElement(serializer, result)
        } catch (e: Exception) {
            respond(BridgeResponseEnvelope.failure(id, "failed to encode result: ${e.message}").encoded())
            return
        }
        respond(BridgeResponseEnvelope.success(id, element).encoded())
    }

    private fun respondEmpty(id: String) {
        respond(BridgeResponseEnvelope.success(id, JsonObject(emptyMap())).encoded())
    }

    /**
     * Health telemetry: `bridge_error` — any decode/handler failure that gets
     * an `ok:false` response back to JS. Distinct from [onFailure], which is
     * for host-level/crash-containment failures that end the flow; a rejected
     * bridge call is routine (JS sent something malformed) and the flow keeps
     * running, but it's still worth counting.
     */
    private fun emitBridgeError(method: String, code: String) {
        eventSink.append(
            HealthEvent.bridgeError(
                flowId = healthFlowId,
                sessionId = healthSessionId,
                seq = healthSeq.next(),
                method = method,
                code = code,
            ),
        )
    }

    private fun BridgeResponseEnvelope.encoded(): String =
        WeirJson.encodeToString(BridgeResponseEnvelope.serializer(), this)
}
