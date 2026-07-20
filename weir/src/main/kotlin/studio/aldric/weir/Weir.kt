package studio.aldric.weir

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import studio.aldric.weir.billing.NoopPurchaseProvider
import studio.aldric.weir.billing.PurchaseProviding
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.HapticEngine
import studio.aldric.weir.persistence.BundleManager
import studio.aldric.weir.persistence.EventQueue
import studio.aldric.weir.persistence.ProcessLifecycleForegroundTrigger
import studio.aldric.weir.persistence.WeirBundleResolution
import studio.aldric.weir.persistence.WeirIngestConfig
import studio.aldric.weir.persistence.WeirUpdateConfig
import studio.aldric.weir.persistence.WeirUpdateController
import studio.aldric.weir.bridge.PermissionRequester
import studio.aldric.weir.bridge.PermissionStatus
import studio.aldric.weir.bridge.PermissionType
import studio.aldric.weir.bridge.UnavailablePermissionRequester
import studio.aldric.weir.bridge.WeirVariable
import studio.aldric.weir.webview.WeirWebView
import java.io.File

/**
 * Outcome of a Weir flow, delivered exactly once. Faithful port of the Swift
 * `WeirFlowResult` enum (`sdk-ios/Sources/Weir/Weir.swift`).
 */
sealed class WeirFlowResult {
    /** The flow reached its end and handed back its typed variables (the
     *  `complete` bridge method). */
    data class Completed(val variables: List<WeirVariable>) : WeirFlowResult()

    /** The user backed out before completion, with the flow-supplied reason
     *  (the `dismiss` bridge method). */
    data class Dismissed(val reason: String) : WeirFlowResult()

    /** The flow could not run to a JS-driven outcome at all — navigation
     *  failure or content-process crash. Crash containment means this fires
     *  instead of taking the host down, so the host can fall back to native. */
    data class Failed(val error: Throwable) : WeirFlowResult()
}

/**
 * Handle to a presentation started by [Weir.present]. Exposes the hosted
 * [WeirWebView] (the View a Phase 3 Activity/Compose host adds to its
 * hierarchy) and a [dismiss] that fires the completion once as
 * [WeirFlowResult.Dismissed].
 */
class WeirPresentation internal constructor(
    val host: WeirWebView,
    private val finish: (WeirFlowResult) -> Unit,
) {
    /** Dismisses the flow as if the user backed out. Safe to call after the
     *  flow already completed/dismissed/failed — completion fires only once. */
    fun dismiss(reason: String = "host_dismissed") {
        finish(WeirFlowResult.Dismissed(reason))
    }
}

/**
 * Public entry point for presenting a Weir flow. Android analogue of the Swift
 * `Weir` enum's `present(...)`.
 *
 * Phase 1 provides the reusable View-level core: it constructs the
 * [WeirWebView] host, wires the bridge callbacks into a single once-only
 * [WeirFlowResult] completion, and returns a [WeirPresentation] whose
 * `host.webView` a caller adds to its own view hierarchy. The full
 * `Activity`-backed modal + real Activity permission handling is Phase 3,
 * which wraps this core.
 */
object Weir {

    /** Default SharedPreferences store name for [WeirIdentity]-backed identity. */
    const val PREFS_NAME = "weir.sdk.prefs"

    /** In-flight guard (R2-R2): true while a [presentActivity] flow is live, so a
     *  second rapid call (a double-tap, a re-entrant present) can't stack two
     *  overlapping flow Activities — two live bridges/eventSinks on screen at
     *  once. Cleared when the presentation delivers its terminal result. */
    private val presentationInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Clears the [presentationInFlight] guard — TEST ONLY, so a test of this
     *  process-wide singleton doesn't leak in-flight state into the next test. */
    @androidx.annotation.VisibleForTesting
    internal fun resetPresentationGuardForTest() {
        presentationInFlight.set(false)
    }

    // ---- Remote flow-bundle delivery (plan §3) — parity with iOS `Weir` ----

    /** Set by [configure]; `null` means remote delivery was never configured.
     *  Read by [present]'s resolution boundary. */
    @Volatile
    var updateController: WeirUpdateController? = null
        private set

    /** Shared no-remote-key [BundleManager] backing the embedded/placeholder
     *  tier of [WeirBundleResolution.resolve] for callers that pass no
     *  `bundleRoot` and have no remote update configured either. A bare,
     *  keyless manager already fails closed on its own. Lazily created so a
     *  host that never uses remote delivery pays nothing. */
    @Volatile
    private var _fallbackBundleManager: BundleManager? = null

    private fun fallbackBundleManager(): BundleManager =
        _fallbackBundleManager ?: synchronized(this) {
            _fallbackBundleManager ?: BundleManager().also { _fallbackBundleManager = it }
        }

    /**
     * Enables remote flow-bundle delivery (plan §3): fetches a manifest in the
     * background now and on every app-foreground, and promotes any staged
     * update at the next flow-present boundary — never mid-flow. An unset or
     * malformed `config.publicKeyRawBase64` doesn't fail this call; it just
     * means every fetched manifest fails signature verification and gets
     * ignored ([BundleManager]'s fail-closed contract), so flows keep rendering
     * from the host-supplied/embedded bundle exactly as if this were never
     * called. Call once, e.g. at app launch. Faithful port of iOS
     * `Weir.configure(updates:)`.
     *
     * @param embeddedBundleRoot optional build-time-embedded baseline bundle
     *   the controller's [BundleManager] falls back to (the Android analogue of
     *   iOS's SPM `Bundle.module` resolution, which the host supplies here).
     */
    fun configure(
        updates: WeirUpdateConfig,
        embeddedBundleRoot: File? = null,
    ) {
        val controller = WeirUpdateController(
            config = updates,
            embeddedBundleRoot = embeddedBundleRoot,
            foregroundTrigger = ProcessLifecycleForegroundTrigger(),
        )
        updateController = controller
        controller.start()
    }

    fun defaultPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Identity passthrough (mirrors the iOS `Weir.resolvedVariant` etc.) ----

    fun resolvedVariant(context: Context): WeirIdentity.ResolvedVariant =
        WeirIdentity.resolvedVariant(defaultPrefs(context))

    fun assignedVariant(context: Context): WeirIdentity.ResolvedVariant? =
        WeirIdentity.assignedVariant(defaultPrefs(context))

    fun stableUserId(context: Context): String =
        WeirIdentity.stableUserId(defaultPrefs(context))

    /**
     * Full Activity-backed presentation (plan §8 Phase 3, item 1) — the Android
     * analogue of iOS `Weir.present(from: presentingViewController)`. Launches
     * [WeirFlowActivity], which hosts the flow full-screen, drives real runtime
     * permissions via [ActivityPermissionRequester], and delivers the terminal
     * [WeirFlowResult] to [completion] exactly once (bridge complete/dismiss,
     * crash-containment failure, or the user backing out all route through the
     * same once-gate).
     *
     * The completion/eventSink/purchaseProvider/callback graph can't ride an
     * `Intent` (only primitives/Parcelables can), so it's stashed in the
     * in-process [WeirPresentationRegistry] under a token and the Intent carries
     * only that token — see the registry's doc comment.
     *
     * The reusable View-level core is still [present] below: this method builds
     * the flow host by calling it with the Activity as `Context` and the
     * Activity-backed permission requester injected. A Compose host (Phase 4 /
     * Niyat) wraps that same core directly — see [present]'s doc comment.
     *
     * @param backgroundColor first-paint background as an ARGB int; when null,
     *   the flow bundle's manifest.json `theme.backgroundColor` is used, else a
     *   dark default. Set as the window background before the WebView paints, so
     *   there's no white flash.
     */
    fun presentActivity(
        context: Context,
        flowId: String,
        bundleRoot: File? = null,
        userId: String? = null,
        purchaseProvider: PurchaseProviding = NoopPurchaseProvider(),
        eventSink: EventSink,
        ingest: WeirIngestConfig? = null,
        hapticEngine: HapticEngine? = null,
        backgroundColor: Int? = null,
        systemBarStyle: WeirSystemBarStyle = WeirSystemBarStyle.Default,
        onPermissionResult: ((PermissionType, PermissionStatus) -> Unit)? = null,
        onPurchaseResult: ((String) -> Unit)? = null,
        completion: (WeirFlowResult) -> Unit,
    ) {
        // R2-R2: reject a re-entrant present rather than stacking a second flow
        // Activity over the live one. The second caller still gets a terminal
        // result (a dismissal) so its completion never silently hangs.
        if (!presentationInFlight.compareAndSet(false, true)) {
            completion(WeirFlowResult.Dismissed("already_presenting"))
            return
        }
        // Clear the guard exactly once, when this presentation ends.
        val guardedCompletion: (WeirFlowResult) -> Unit = { result ->
            presentationInFlight.set(false)
            completion(result)
        }
        val request = WeirActivityPresentationRequest(
            backgroundColor = backgroundColor,
            systemBarStyle = systemBarStyle,
            build = { activity, permissionRequester, finish ->
                // Reuse the View-level core (below): it runs the OTA resolution
                // boundary and constructs the WeirWebView + bridge, now with the
                // Activity as Context and the real Activity-backed requester.
                present(
                    context = activity,
                    flowId = flowId,
                    bundleRoot = bundleRoot,
                    userId = userId,
                    purchaseProvider = purchaseProvider,
                    eventSink = eventSink,
                    ingest = ingest,
                    permissionRequester = permissionRequester,
                    hapticEngine = hapticEngine,
                    onPermissionResult = onPermissionResult,
                    onPurchaseResult = onPurchaseResult,
                    completion = finish,
                )
            },
            completion = guardedCompletion,
        )
        val token = WeirPresentationRegistry.register(request)

        val intent = Intent(context, WeirFlowActivity::class.java).apply {
            putExtra(WeirFlowActivity.EXTRA_TOKEN, token)
            bundleRoot?.let { putExtra(WeirFlowActivity.EXTRA_BUNDLE_ROOT, it.absolutePath) }
            // A non-Activity Context (Application/Service) can't start an
            // Activity without its own task.
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Never leave the guard stuck if the Activity couldn't even start —
            // unregister, clear, and surface the failure to the caller.
            WeirPresentationRegistry.remove(token)
            presentationInFlight.set(false)
            completion(WeirFlowResult.Failed(e))
            return
        }
    }

    /**
     * Builds and wires a flow host for [flowId] over [bundleRoot], delivering
     * the outcome to [completion] exactly once. The returned
     * [WeirPresentation.host] `.webView` is the View to attach; call
     * [WeirWebView.load] once attached (or let the Phase 3 host do it).
     *
     * This is the **reusable View-level core** — [presentActivity] wraps it for
     * the standard Activity modal, and a **Compose host** (Phase 4 / Niyat)
     * wraps it directly, e.g.:
     *
     * ```
     * val presentation = remember {
     *     Weir.present(context, flowId = "niyat_onboarding", eventSink = queue) { result -> … }
     * }
     * AndroidView(factory = { presentation.host.webView.also { presentation.host.load() } })
     * ```
     *
     * Keeping this public and Context-based (not Activity-based) is the
     * deliberate Compose seam: nothing here needs an Activity except the real
     * [PermissionRequester], which the caller injects. A Compose host that wants
     * live permissions passes an [ActivityPermissionRequester] built from its
     * own `rememberLauncherForActivityResult`; one that doesn't leaves the
     * honest [UnavailablePermissionRequester] default.
     */
    fun present(
        context: Context,
        flowId: String,
        /**
         * Host-supplied bundle root. Now the **middle tier** of the M4
         * resolution order (see [WeirBundleResolution.resolve]): a promoted
         * remote bundle that actually contains [flowId] takes precedence, and
         * `null` (no static bundle) falls through to the embedded/placeholder
         * tier. Existing callers that always pass a concrete [File] behave
         * exactly as before when no remote update is configured.
         */
        bundleRoot: File? = null,
        userId: String? = null,
        purchaseProvider: PurchaseProviding = NoopPurchaseProvider(),
        eventSink: EventSink,
        /**
         * When set, and when [eventSink] is (or wraps down to) a plain
         * [EventQueue], attaches a live ingest endpoint to it — see
         * [EventQueue.configureIngest]. `null` (the default) leaves the queue
         * exactly as before: durable on-disk only, no network flush. If
         * [eventSink] is some other [EventSink] entirely (a tee, a host sink),
         * this is a no-op — the host owns wiring its own ingest in that case
         * (calling `configureIngest` on its `EventQueue` directly).
         */
        ingest: WeirIngestConfig? = null,
        permissionRequester: PermissionRequester = UnavailablePermissionRequester,
        hapticEngine: HapticEngine? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        onPermissionResult: ((PermissionType, PermissionStatus) -> Unit)? = null,
        onPurchaseResult: ((String) -> Unit)? = null,
        completion: (WeirFlowResult) -> Unit,
    ): WeirPresentation {
        // Live ingest wiring (docs/instrumentation-workstream.md §1): only
        // meaningful when the caller's eventSink is a plain EventQueue — see the
        // `ingest` param's doc comment. A host that composes eventSink as a tee
        // never fires this branch and wires configureIngest itself.
        val ingestQueue = eventSink as? EventQueue
        if (ingest != null && ingestQueue != null) {
            ingestQueue.configureIngest(ingest, foregroundTrigger = ProcessLifecycleForegroundTrigger())
        }

        // completion fires exactly once, from whichever terminal bridge signal
        // (complete/dismiss) or crash-containment failure lands first — parity
        // with the iOS `didFinish` guard.
        var finished = false
        val finish: (WeirFlowResult) -> Unit = { result ->
            if (!finished) {
                finished = true
                completion(result)
                // Flush trigger #2 (docs/instrumentation-workstream.md §1): flow
                // completion. No-ops if `ingest` was never configured.
                ingestQueue?.triggerFlush()
            }
        }

        // M3/M4 resolution boundary (parity with iOS `Weir.present`): promotes
        // any staged remote update and decides where this presentation's bundle
        // root actually comes from — promoted-remote-if-contains-flow →
        // host-supplied → embedded/placeholder. Must happen here, before the
        // WebView host (and its asset loader) is constructed, never after. The
        // resolved `source` drives the `health_bundle_source` event WeirWebView
        // emits (via the existing HealthEvent.bundleSource builder).
        val resolved = WeirBundleResolution.resolve(
            flowId = flowId,
            hostBundleRoot = bundleRoot,
            updateController = updateController,
            fallbackBundleManager = fallbackBundleManager(),
        )

        val host = WeirWebView(
            context = context,
            flowId = flowId,
            bundleRoot = resolved.root,
            userId = userId,
            purchaseProvider = purchaseProvider,
            eventSink = eventSink,
            permissionRequester = permissionRequester,
            hapticEngine = hapticEngine,
            scope = scope,
            bundleSource = resolved.source.raw,
            onComplete = { variables -> finish(WeirFlowResult.Completed(variables)) },
            onDismiss = { reason -> finish(WeirFlowResult.Dismissed(reason)) },
            onFailure = { error -> finish(WeirFlowResult.Failed(error)) },
            onPermissionResult = onPermissionResult,
            onPurchaseResult = onPurchaseResult,
        )

        return WeirPresentation(host, finish)
    }
}
