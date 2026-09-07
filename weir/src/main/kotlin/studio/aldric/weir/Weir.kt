package studio.aldric.weir

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import studio.aldric.weir.billing.NoopPurchaseProvider
import studio.aldric.weir.billing.PurchaseProviding
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.HealthEvent
import studio.aldric.weir.bridge.HealthSeq
import studio.aldric.weir.persistence.BundleManager
import studio.aldric.weir.persistence.ConfigFetchGatingQuery
import studio.aldric.weir.persistence.RegistryGating
import studio.aldric.weir.persistence.ProcessLifecycleForegroundTrigger
import studio.aldric.weir.persistence.WeirIngestConfig
import studio.aldric.weir.persistence.WeirDeviceContext
import studio.aldric.weir.persistence.WeirUpdateConfig
import studio.aldric.weir.persistence.WeirUpdateController
import studio.aldric.weir.persistence.WeirEmbeddedFixture
import studio.aldric.weir.registry.ComponentRegistry
import studio.aldric.weir.bridge.WeirVariable
import studio.aldric.weir.ui.WeirFlow
import java.io.File

/**
 * Outcome of a Weir flow, delivered exactly once. Faithful port of the Swift
 * `WeirFlowResult` enum (`sdk-ios/Sources/Weir/Weir.swift`).
 */
sealed class WeirFlowResult {
    /** The flow reached its end and handed back its typed variables. */
    data class Completed(val variables: List<WeirVariable>) : WeirFlowResult()

    /** The user backed out before completion, with the flow-supplied reason
     *  (the `dismiss` bridge method). */
    data class Dismissed(val reason: String) : WeirFlowResult()

    /** The native renderer could not decode or run the flow. */
    data class Failed(val error: Throwable) : WeirFlowResult()
}

/** Public entry point for configuring and presenting native Weir flows. */
object Weir {

    /** Default SharedPreferences store name for [WeirIdentity]-backed identity. */
    const val PREFS_NAME = "weir.sdk.prefs"

    /** In-flight guard (R2-R2): true while a [present] flow is live, so a
     *  second rapid call (a double-tap, a re-entrant present) can't stack two
     *  overlapping flow Activities and event sinks on screen at
     *  once. Cleared when the presentation delivers its terminal result. */
    private val presentationInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Clears the [presentationInFlight] guard — TEST ONLY, so a test of this
     *  process-wide singleton doesn't leak in-flight state into the next test. */
    @androidx.annotation.VisibleForTesting
    internal fun resetPresentationGuardForTest() {
        presentationInFlight.set(false)
    }

    /** TEST ONLY: seeds [appContext] and clears the cached fallback manager so
     *  a test can exercise [fallbackBundleManager]'s embedded-fixture path
     *  without driving a full [present]/[configure] call. Mirrors
     *  [resetPresentationGuardForTest]'s pattern. */
    @androidx.annotation.VisibleForTesting
    internal fun configureEmbeddedFixtureForTest(context: Context) {
        appContext = context.applicationContext
        synchronized(this) { _fallbackBundleManager = null }
    }

    // ---- Remote flow-bundle delivery (plan §3) — parity with iOS `Weir` ----

    /** Set by [configure]; `null` means remote delivery was never configured.
     *  Read by [present]'s resolution boundary. */
    @Volatile
    var updateController: WeirUpdateController? = null
        private set

    /** Shared no-remote-key [BundleManager] backing the embedded/placeholder
     *  tier of config resolution for callers with no config root and no
     *  remote update configured either. A bare,
     *  keyless manager already fails closed on its own. Lazily created so a
     *  host that never uses remote delivery pays nothing. */
    @Volatile
    private var _fallbackBundleManager: BundleManager? = null

    /**
     * Process-wide app context captured at [configure]/[present] so the SDK's
     * baked-in embedded fixture can be materialized from assets on first
     * fallback access. Android has no SPM `Bundle.module`; raw assets live
     * behind `AssetManager`, which needs a `Context`. `null` (before any entry
     * point has run) leaves the fallback on the empty placeholder — fail safe,
     * never crash.
     */
    @Volatile
    private var appContext: Context? = null

    internal fun fallbackBundleManager(): BundleManager =
        _fallbackBundleManager ?: synchronized(this) {
            _fallbackBundleManager ?: createFallbackBundleManager().also { _fallbackBundleManager = it }
        }

    /**
     * Builds the no-remote, no-host-key [BundleManager] used as config
     * resolution's last tier. When an app context is available, the SDK's own
     * baked-in embedded fixture ([WeirEmbeddedFixture]) is materialized to disk
     * and used as the [BundleManager]'s embedded root, so a host that supplies
     * no config and no remote still renders a real flow instead of the empty
     * placeholder (the iOS parity gap — `sdk-ios` resolves the same fixture via
     * SPM `Bundle.module`). If no context is available yet (no entry point has
     * run) or the asset copy fails, this falls back to a bare [BundleManager]
     * — the empty placeholder — and never throws.
     */
    private fun createFallbackBundleManager(): BundleManager {
        val context = appContext ?: return BundleManager()
        val embeddedDir = File(context.filesDir, "weir/embedded-fixture")
        return if (WeirEmbeddedFixture.materialize(context, embeddedDir) && embeddedDir.isDirectory) {
            BundleManager(embeddedBundleRoot = embeddedDir)
        } else {
            BundleManager()
        }
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
     * @param eventSink optional structured-diagnostics sink (Phase 3): when
     *   set, every manifest fetch/verify/stage/promote this controller does —
     *   in the background, independent of any single [present] call — emits a
     *   `health_manifest_*`/`health_remote_*` event here (see [HealthEvent]).
     *   `null` (the default) is silent, matching pre-Phase-3 behavior. Pass the
     *   same [EventSink] (or an [studio.aldric.weir.bridge.CompositeEventSink]
     *   tee) a host also uses for [present]'s `eventSink` so both product and
     *   health telemetry land in one place.
     */
    fun configure(
        context: Context,
        updates: WeirUpdateConfig,
        embeddedBundleRoot: File? = null,
        eventSink: EventSink? = null,
    ) {
        val appContext = context.applicationContext
        this.appContext = appContext
        val controller = WeirUpdateController(
            config = updates,
            embeddedBundleRoot = embeddedBundleRoot,
            foregroundTrigger = ProcessLifecycleForegroundTrigger(),
            eventSink = eventSink,
            gatingProvider = { gatingQuery(appContext) },
        )
        updateController = controller
        controller.start()
    }

    /**
     * RC-P0 (gating protocol v2, release-gate re-review): the base trio
     * (`appVersion`/`sdkVersion`/`platform`) is ALWAYS sent — an app with
     * zero registered custom components still needs its `sdkVersion` floor
     * enforced (the prior all-5-or-none contract sent NO gating at all in
     * that case, silently skipping floor enforcement too). The registry
     * pair rides along only when [ComponentRegistry] actually has a
     * generated registry installed; [RegistryGating] makes a partial pair
     * unrepresentable, not merely disallowed by a runtime check (round-2
     * re-review: matches `sdk-react-native`'s `ConfigFetchGating` TS type,
     * the cross-platform reference for this strictness). Extracted from
     * [configure]'s `gatingProvider` closure so it's directly unit-testable
     * (a real network fetch is not — see `WeirGatingQueryTest`).
     */
    internal fun gatingQuery(appContext: Context): ConfigFetchGatingQuery {
        val registryInfo = ComponentRegistry.registryGatingInfo()
        val registry = registryInfo?.let { (manifestVersion, hash) -> RegistryGating.Registered(manifestVersion, hash) }
            ?: RegistryGating.Unregistered
        return ConfigFetchGatingQuery(
            appVersion = WeirDeviceContext.current(appContext, WeirSdk.WEIR_SDK_VERSION).appVersion,
            sdkVersion = WeirSdk.WEIR_SDK_VERSION,
            platform = "android",
            registry = registry,
        )
    }

    fun defaultPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- App-owned structured diagnostics (Phase 3) ----
    //
    // The SDK never sees the host app's own feature-flag resolution or
    // bucketing decision — that logic lives entirely in the host adapter (e.g.
    // CutOrBulk's/pokestealordeal's remote-config or Firestore read). These
    // three entry points let that adapter still emit the exact same
    // machine-readable `health_flag_off` / `health_flag_fetch_failed` /
    // `health_native_bucket` shape [HealthEvent]'s SDK-owned types use, on a
    // session this object owns so callers never have to manage their own
    // sessionId/seq.
    //
    // These carry the host's REAL flowId, unlike the SDK-owned manifest/remote
    // types which ride the synthetic [HealthEvent.UPDATE_SESSION_FLOW_ID]. That
    // asymmetry is deliberate: a manifest URL is not scoped to one flow, but a
    // feature flag and a bucketing decision are per-flow facts, and attributing
    // them to the real flow is what makes "how many users were gated out of
    // cob_intake" answerable. Matches iOS's WeirDiagnostics entry points, which
    // take flowId for the same reason — the two platforms must emit the same
    // shape or a cross-platform query returns different answers per OS.

    private val diagnosticHealthSessionId: String = java.util.UUID.randomUUID().toString()
    private val diagnosticHealthSeq = HealthSeq()

    /** The host app's feature flag resolved off — the user was gated out of
     *  the Weir-driven flow before it ever rendered. [flagSource] is a short
     *  label for where the flag came from, e.g. `"remote-config"`,
     *  `"firestore"`, `"build-settings"` — never the flag's own key/value. */
    fun reportFlagOff(eventSink: EventSink, flowId: String, flagSource: String) {
        eventSink.append(
            HealthEvent.flagOff(
                flowId = flowId,
                sessionId = diagnosticHealthSessionId,
                seq = diagnosticHealthSeq.next(),
                flagSource = flagSource,
            ),
        )
    }

    /** The host app couldn't read its feature flag and fell back to a default.
     *  [reason] is a short safe code the host chooses, never a raw exception
     *  message that could carry unrelated user/request data. */
    fun reportFlagFetchFailed(eventSink: EventSink, flowId: String, flagSource: String, reason: String) {
        eventSink.append(
            HealthEvent.flagFetchFailed(
                flowId = flowId,
                sessionId = diagnosticHealthSessionId,
                seq = diagnosticHealthSeq.next(),
                flagSource = flagSource,
                reason = reason,
            ),
        )
    }

    /** The host app's own bucketing sent this user to the native (non-Weir)
     *  onboarding arm. [arm] is the host's own arm label. */
    fun reportNativeBucket(eventSink: EventSink, flowId: String, arm: String) {
        eventSink.append(
            HealthEvent.nativeBucket(
                flowId = flowId,
                sessionId = diagnosticHealthSessionId,
                seq = diagnosticHealthSeq.next(),
                arm = arm,
            ),
        )
    }

    // ---- Identity passthrough (mirrors the iOS `Weir.resolvedVariant` etc.) ----

    fun resolvedVariant(context: Context): WeirIdentity.ResolvedVariant =
        WeirIdentity.resolvedVariant(defaultPrefs(context))

    fun assignedVariant(context: Context): WeirIdentity.ResolvedVariant? =
        WeirIdentity.assignedVariant(defaultPrefs(context))

    fun stableUserId(context: Context): String =
        WeirIdentity.stableUserId(defaultPrefs(context))

    /** Launches the native Compose renderer in a full-screen Activity. */
    fun present(
        context: Context,
        flowId: String,
        configRoot: File? = null,
        userId: String? = null,
        purchaseProvider: PurchaseProviding = NoopPurchaseProvider(),
        eventSink: EventSink,
        ingest: WeirIngestConfig? = null,
        systemBarStyle: WeirSystemBarStyle = WeirSystemBarStyle.Default,
        completion: (WeirFlowResult) -> Unit,
    ) {
        appContext = context.applicationContext
        if (!presentationInFlight.compareAndSet(false, true)) {
            completion(WeirFlowResult.Dismissed("already_presenting"))
            return
        }
        val guardedCompletion: (WeirFlowResult) -> Unit = { result ->
            presentationInFlight.set(false)
            completion(result)
        }
        val request = WeirPresentationRequest(
            systemBarStyle = systemBarStyle,
            content = { permissionRequester, finish ->
                WeirFlow(
                    flowId = flowId,
                    configRoot = configRoot,
                    userId = userId,
                    purchaseProvider = purchaseProvider,
                    permissionRequester = permissionRequester,
                    eventSink = eventSink,
                    ingest = ingest,
                    onResult = finish,
                )
            },
            completion = guardedCompletion,
        )
        val token = WeirPresentationRegistry.register(request)
        val intent = Intent(context, WeirFlowActivity::class.java).apply {
            putExtra(WeirFlowActivity.EXTRA_TOKEN, token)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            WeirPresentationRegistry.remove(token)
            presentationInFlight.set(false)
            completion(WeirFlowResult.Failed(error))
        }
    }
}
