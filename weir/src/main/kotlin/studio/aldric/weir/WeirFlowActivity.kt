package studio.aldric.weir

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CompletableDeferred
import studio.aldric.weir.bridge.ActivityPermissionRequester
import java.io.File

/**
 * The real Android presentation host (plan §8 Phase 3, item 1). A
 * [ComponentActivity] that hosts a [studio.aldric.weir.webview.WeirWebView]
 * full-screen and drives the Activity-backed runtime-permission machinery —
 * the Android analogue of iOS's `WeirViewController` modal.
 *
 * Responsibilities:
 *  - Pulls its [WeirActivityPresentationRequest] from
 *    [WeirPresentationRegistry] by the token in the launching Intent. A missing
 *    entry (process death → Activity recreated with no live registry) finishes
 *    cleanly rather than crashing.
 *  - Sets the flow background as the *window* background before the WebView
 *    paints, so there is no white flash; runs edge-to-edge with the
 *    caller-supplied [WeirSystemBarStyle]. The caller's own bars are restored
 *    automatically when this Activity finishes (it owns its own window).
 *  - Owns the `registerForActivityResult(...)` launcher for runtime
 *    permissions and hands the injected [ActivityPermissionRequester] a suspend
 *    bridge to it — so the bridge's `permission.request` suspends until the
 *    real system dialog resolves (mirrors the iOS continuation shape).
 *  - Delivers the terminal [WeirFlowResult] back through the request's
 *    exactly-once [WeirActivityPresentationRequest.deliver] and finishes. A
 *    crash-containment failure ([studio.aldric.weir.webview.WeirWebView]'s
 *    `onRenderProcessGone`) arrives here as [WeirFlowResult.Failed], so the host
 *    can fall back to native.
 */
class WeirFlowActivity : ComponentActivity() {

    private var request: WeirActivityPresentationRequest? = null
    private var token: String? = null

    /** The presented flow's WebView host, kept so `onDestroy` can cancel its
     *  per-present coroutine scope (R2-R1). */
    private var flowHost: studio.aldric.weir.webview.WeirWebView? = null

    /** Single-slot handoff between the [ActivityResultContracts.RequestMultiplePermissions]
     *  callback and the suspending [ActivityPermissionRequester]. The requester
     *  serializes launches with its own Mutex, so one slot is enough. */
    private var pendingPermissionResult: CompletableDeferred<Map<String, Boolean>>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            pendingPermissionResult?.complete(granted)
            pendingPermissionResult = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        token = intent?.getStringExtra(EXTRA_TOKEN)
        val request = WeirPresentationRegistry.get(token)
        if (request == null) {
            // No live registry entry: the process was killed and this Activity
            // was recreated, or it was launched without a valid token. There's
            // nothing to present and no completion to honor here — finish
            // cleanly (the caller, if it still exists, will re-present).
            finish()
            return
        }
        this.request = request

        // First-paint background: resolve caller-supplied → flow manifest → a
        // conservative default, and paint it as the *window* background before
        // anything else so there's no white flash while the WebView boots.
        val background = request.backgroundColor
            ?: readManifestBackgroundColor()
            ?: DEFAULT_BACKGROUND
        window.setBackgroundDrawable(ColorDrawable(background))

        applyEdgeToEdge(request.systemBarStyle)

        val permissionRequester = ActivityPermissionRequester(
            context = this,
            launchSystemRequest = ::launchSystemPermission,
        )

        val presentation = request.build(this, permissionRequester) { result ->
            deliverAndFinish(result)
        }

        this.flowHost = presentation.host
        val webView = presentation.host.webView
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.setBackgroundColor(background)
        setContentView(webView, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Attached — safe to load the entry point now.
        presentation.host.load()
    }

    /** Suspends until the runtime-permission dialog resolves; returns each
     *  requested permission's granted flag. The bridge to
     *  [ActivityPermissionRequester]. */
    private suspend fun launchSystemPermission(permissions: List<String>): Map<String, Boolean> {
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        pendingPermissionResult = deferred
        // launch() must be called on the main thread; onCreate + the requester's
        // dispatch already run on Main here, but guard by posting defensively.
        runOnUiThread { permissionLauncher.launch(permissions.toTypedArray()) }
        return deferred.await()
    }

    private fun applyEdgeToEdge(style: WeirSystemBarStyle) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Transparent bars so the flow's own background shows through edge-to-edge.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = style.lightStatusBarIcons
            isAppearanceLightNavigationBars = style.lightNavBarIcons
        }
    }

    private fun deliverAndFinish(result: WeirFlowResult) {
        request?.deliver(result)
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        // R2-R1: release an in-flight permission wait + the per-present coroutine
        // scope FIRST. If the user back-pressed while the OS permission dialog was
        // up, the launcher callback never fires, so the bridge coroutine is parked
        // on `pendingPermissionResult.await()` — cancelling the deferred unblocks
        // it (await throws) and cancelling the scope drops the coroutine, so the
        // destroyed Activity + WebView aren't strong-held for the process lifetime.
        pendingPermissionResult?.cancel()
        pendingPermissionResult = null
        flowHost?.destroy()
        flowHost = null

        // Guarantee exactly-once: if the Activity is going away (system back,
        // caller-driven finish) before the flow produced a terminal result,
        // report it as a dismissal so the caller's completion always fires.
        if (isFinishing) {
            request?.let { if (!it.hasDelivered) it.deliver(WeirFlowResult.Dismissed("activity_finished")) }
            WeirPresentationRegistry.remove(token)
        }
        super.onDestroy()
    }

    /** Reads `theme.backgroundColor` from the flow bundle's manifest.json,
     *  best-effort (a missing/old bundle just means no fallback, never a
     *  crash). Parity with iOS `WeirViewController.readManifestBackgroundColor`. */
    private fun readManifestBackgroundColor(): Int? {
        val root = intent?.getStringExtra(EXTRA_BUNDLE_ROOT) ?: return null
        return readManifestBackgroundColor(File(root))
    }

    companion object {
        internal const val EXTRA_TOKEN = "studio.aldric.weir.TOKEN"
        internal const val EXTRA_BUNDLE_ROOT = "studio.aldric.weir.BUNDLE_ROOT"

        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

        /** A neutral dark default (onboarding flows are overwhelmingly dark);
         *  only used when neither the caller nor the bundle manifest supplies
         *  a background. */
        private const val DEFAULT_BACKGROUND = 0xFF000000.toInt()

        /**
         * Reads `theme.backgroundColor` from a bundle root's manifest.json.
         * Pure (java.io only) and internal so it's unit-testable without an
         * Activity. Returns null on any missing/malformed input.
         */
        internal fun readManifestBackgroundColor(bundleRoot: File): Int? {
            val manifest = File(bundleRoot, "manifest.json")
            if (!manifest.isFile) return null
            return try {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(manifest.readText())
                val theme = (json as? kotlinx.serialization.json.JsonObject)?.get("theme")
                    as? kotlinx.serialization.json.JsonObject ?: return null
                val hex = (theme["backgroundColor"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content ?: return null
                parseWeirHexColor(hex)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * `#rgb` / `#rrggbb` / `#rrggbbaa` hex → ARGB int. Mirrors
         * packages/spec's `Color` token format and the iOS `UIColor(weirHex:)`
         * parser exactly. Pure and internal for unit testing. Returns null on
         * a malformed string.
         */
        internal fun parseWeirHexColor(hex: String): Int? {
            var s = hex.removePrefix("#")
            when (s.length) {
                3 -> s = s.map { "$it$it" }.joinToString("")
                6, 8 -> Unit
                else -> return null
            }
            val value = s.toLongOrNull(16) ?: return null
            val r: Long; val g: Long; val b: Long; val a: Long
            if (s.length == 8) {
                r = (value shr 24) and 0xFF
                g = (value shr 16) and 0xFF
                b = (value shr 8) and 0xFF
                a = value and 0xFF
            } else {
                r = (value shr 16) and 0xFF
                g = (value shr 8) and 0xFF
                b = value and 0xFF
                a = 0xFF
            }
            return ((a shl 24) or (r shl 16) or (g shl 8) or b).toInt()
        }
    }
}
