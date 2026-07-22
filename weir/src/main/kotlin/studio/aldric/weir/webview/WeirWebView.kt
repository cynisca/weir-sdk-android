package studio.aldric.weir.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonPrimitive
import studio.aldric.weir.billing.NoopPurchaseProvider
import studio.aldric.weir.billing.PurchaseProviding
import studio.aldric.weir.bridge.BridgeRouter
import studio.aldric.weir.bridge.BridgeTransport
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.HapticEngine
import studio.aldric.weir.bridge.HealthEvent
import studio.aldric.weir.bridge.HealthSeq
import studio.aldric.weir.bridge.PermissionRequester
import studio.aldric.weir.bridge.PermissionStatus
import studio.aldric.weir.bridge.PermissionType
import studio.aldric.weir.bridge.UnavailablePermissionRequester
import studio.aldric.weir.bridge.WeirVariable
import java.io.File
import java.util.UUID

/**
 * Hosts the Weir flow renderer in an Android [WebView] and wires it to the
 * native bridge, purchase provider, and event sink. Android analogue of
 * `sdk-ios/Sources/Weir/WebView/WeirViewController.swift`.
 *
 * Responsibilities (parity with the iOS controller):
 *  - registers the [BridgeRouter] via `addJavascriptInterface(router, "WeirAndroid")`;
 *  - serves the bundle via [WeirAssetLoader] over the secure
 *    `https://appassets.androidplatform.net` origin;
 *  - injects `window.__weirUserId` **before the bundle's first script** using
 *    `WebViewCompat.addDocumentStartJavaScript` (the exact seam the iOS
 *    `.atDocumentStart` `WKUserScript` provides — this closes the
 *    zero-`variant_assigned` regression, see [userIdDocumentStartScript]);
 *  - wires the router's reply channel to `webView.evaluateJavascript(...)` on
 *    the UI thread via `webView.post { }` (`@JavascriptInterface` calls arrive
 *    on a binder thread);
 *  - crash containment: `onRenderProcessGone` returns `true` and surfaces
 *    [onFailure] instead of letting a content-process crash kill the app.
 *
 * The Phase 3 `WeirFlowActivity` wraps this to provide a full modal + real
 * Activity-backed permission handling; this is the reusable View-level core.
 */
class WeirWebView(
    context: Context,
    private val flowId: String,
    bundleRoot: File,
    private val userId: String? = null,
    purchaseProvider: PurchaseProviding = NoopPurchaseProvider(),
    private val eventSink: EventSink,
    permissionRequester: PermissionRequester = UnavailablePermissionRequester,
    hapticEngine: HapticEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val bundleSource: String = "bundled",
    /** The resolved bundle's monotonic version, per `WeirBundleResolution
     *  .resolve`'s `version` — only meaningful alongside `bundleSource ==
     *  "remote"`, `null` for `"bundled"`. Reported verbatim in
     *  `health_bundle_source` below. */
    private val bundleVersion: Int? = null,
    private val onComplete: ((List<WeirVariable>) -> Unit)? = null,
    private val onDismiss: ((String) -> Unit)? = null,
    private val onFailure: ((Throwable) -> Unit)? = null,
    private val onPermissionResult: ((PermissionType, PermissionStatus) -> Unit)? = null,
    private val onPurchaseResult: ((String) -> Unit)? = null,
    /** Injection seam over the `WebViewFeature.DOCUMENT_START_SCRIPT` support
     *  check, purely so tests can exercise the unsupported branch without a
     *  real WebView-backed device/emulator to report the feature as missing
     *  on. Defaults to the real androidx WebKit check. */
    private val isDocumentStartScriptSupported: () -> Boolean =
        { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) },
) {
    val webView: WebView = WebView(context.applicationContext ?: context)
    private val assetLoader: WeirAssetLoader = WeirAssetLoader.create(bundleRoot)
    private val healthSessionId: String = UUID.randomUUID().toString()
    private val healthSeq = HealthSeq()

    /** Guards single-fire of the main-frame navigation-failure path (a failed
     *  load can emit multiple `onReceivedError`s). */
    private var mainFrameFailed = false

    private val router: BridgeRouter = BridgeRouter(
        purchaseProvider = purchaseProvider,
        eventSink = eventSink,
        permissionRequester = permissionRequester,
        hapticEngine = hapticEngine ?: HapticEngine(context),
        healthFlowId = flowId,
        healthSessionId = healthSessionId,
        scope = scope,
        onComplete = onComplete,
        onDismiss = onDismiss,
        onFailure = onFailure,
        onPermissionResult = onPermissionResult,
        onPurchaseResult = onPurchaseResult,
        respond = { json ->
            // @JavascriptInterface calls arrive on a binder thread; the reply
            // must marshal to the UI thread before touching the WebView.
            webView.post {
                webView.evaluateJavascript("window.${BridgeTransport.RESPONSE_GLOBAL}($json)", null)
            }
        },
    )

    init {
        configureWebView()
    }

    /**
     * Cancels the per-present coroutine [scope] and tears down the WebView. The
     * host MUST call this from its `onDestroy` (R2-R1): a bridge coroutine parked
     * on an in-flight `permission.request` — e.g. the user back-pressed while the
     * OS permission dialog was still up, so the launcher callback never fired —
     * would otherwise `await()` forever, strong-holding this WebView and the
     * destroyed Activity for the whole process lifetime. Idempotent.
     */
    fun destroy() {
        scope.cancel()
        webView.destroy()
    }

    /** Loads the flow's entry point. Call after the WebView is attached. */
    fun load() {
        // health_bundle_source — reflects which resolution tier produced this
        // bundle root; emitted once per presentation.
        eventSink.append(
            HealthEvent.bundleSource(
                flowId = flowId,
                sessionId = healthSessionId,
                seq = healthSeq.next(),
                source = bundleSource,
                version = bundleVersion,
            ),
        )
        webView.loadUrl(assetLoader.entryUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Bundle is fully local; no plaintext network needed.
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.addJavascriptInterface(router, BridgeTransport.ANDROID_INTERFACE_NAME)

        // RFC-004 §4.2 step 1: `window.__weirUserId` must exist before
        // weir.js/dom.ts run their first line, or every experiment in the
        // bundle silently renders as holdout. Document-start injection is the
        // Android analogue of the iOS `.atDocumentStart` WKUserScript.
        val script = userIdDocumentStartScript(userId)
        if (script != null) {
            if (isDocumentStartScriptSupported()) {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    script,
                    setOf("https://${WeirAssetLoader.DOMAIN}"),
                )
            } else {
                // No document-start injection seam on this WebView provider
                // means `window.__weirUserId` never gets set before the
                // bundle's first script runs — the exact zero-`variant_assigned`
                // regression this class exists to close, silently reopened for
                // whatever fraction of devices lack this WebViewFeature. Make
                // that measurable instead of a silent gap.
                eventSink.append(
                    HealthEvent.bridgeError(
                        flowId = flowId,
                        sessionId = healthSessionId,
                        seq = healthSeq.next(),
                        method = "userIdDocumentStartScript",
                        code = "userid_injection_unavailable",
                    ),
                )
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                return assetLoader.assetLoader.shouldInterceptRequest(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // Only a *main-frame* load failure ends the flow — a missing
                // sub-resource (favicon, an optional asset) must not. This is
                // the Android analogue of iOS's `didFail`/
                // `didFailProvisionalNavigation` → onFailure, and it's what makes
                // a missing/unresolvable bundle surface as WeirFlowResult.Failed
                // so the host falls back to native (plan §8 item 1).
                if (request?.isForMainFrame != true) return
                if (mainFrameFailed) return
                mainFrameFailed = true
                val description = error?.description?.toString() ?: "unknown"
                eventSink.append(
                    HealthEvent.flowFallback(
                        flowId = flowId,
                        sessionId = healthSessionId,
                        seq = healthSeq.next(),
                        reason = "navigation_failed: $description",
                    ),
                )
                onFailure?.invoke(WeirWebViewError.NavigationFailed(description))
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                // Content-process crashes must never take the host app down.
                // Surface via onFailure so the caller can fall back to native,
                // and return true to signal we've handled it (app survives).
                eventSink.append(
                    HealthEvent.flowFallback(
                        flowId = flowId,
                        sessionId = healthSessionId,
                        seq = healthSeq.next(),
                        reason = "web_render_process_gone",
                    ),
                )
                onFailure?.invoke(WeirWebViewError.RenderProcessGone)
                return true
            }
        }
    }

    companion object {
        /**
         * Builds the `window.__weirUserId` document-start script, or `null`
         * when [userId] is `null` (nothing injected — the flow still renders,
         * just excluded from any declared experiment, matching iOS).
         *
         * Pure and side-effect-free so it can be unit-tested directly — this
         * is the regression guard for the zero-`variant_assigned` bug: the
         * injection string must be present-and-JSON-escaped for a real userId
         * and absent for `null`. [JsonPrimitive].toString() emits a properly
         * quoted/escaped JS string literal (parity with the iOS
         * `JSONEncoder`-escaped literal).
         */
        fun userIdDocumentStartScript(userId: String?): String? {
            if (userId == null) return null
            val literal = JsonPrimitive(userId).toString()
            return "window.__weirUserId = $literal;"
        }
    }
}

/** Crash-containment failure surfaced through [WeirWebView]'s onFailure. */
sealed class WeirWebViewError(message: String) : Exception(message) {
    data object RenderProcessGone : WeirWebViewError("web content render process terminated")

    /** Main-frame navigation failed (unresolvable/missing bundle entry point,
     *  network error on the flow origin). Carries the platform error
     *  description for the host's fallback logging. */
    data class NavigationFailed(val description: String) :
        WeirWebViewError("flow navigation failed: $description")
}
