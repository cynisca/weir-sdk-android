package studio.aldric.weir

import studio.aldric.weir.bridge.PermissionRequester
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process handoff registry for [Weir.presentActivity].
 *
 * An Android Activity is started via an `Intent`, which can only carry
 * `Parcelable`/primitive extras — it cannot carry the live
 * `completion`/`eventSink`/`purchaseProvider`/callback graph a `Weir.present`
 * call owns. So `presentActivity` stashes a [WeirActivityPresentationRequest]
 * here keyed by a short opaque token, puts *only the token* in the Intent, and
 * [WeirFlowActivity] pulls the request back out by token in `onCreate`. This is
 * the "small in-process result registry keyed by a presentation token" the
 * plan (§8 Phase 3, item 1) calls for.
 *
 * Exactly-once delivery is enforced by [WeirActivityPresentationRequest.deliver]
 * (an [AtomicBoolean] gate), so the terminal [WeirFlowResult] reaches the
 * caller's completion once and only once regardless of which path fires it
 * (bridge complete/dismiss, crash-containment failure, or the Activity finishing
 * — e.g. system back). Process-death is handled by the Activity finding no
 * registry entry on recreate and finishing cleanly (see [WeirFlowActivity]).
 */
internal object WeirPresentationRegistry {
    private val requests = ConcurrentHashMap<String, WeirActivityPresentationRequest>()

    fun register(request: WeirActivityPresentationRequest): String {
        val token = UUID.randomUUID().toString()
        requests[token] = request
        return token
    }

    fun get(token: String?): WeirActivityPresentationRequest? = token?.let { requests[it] }

    fun remove(token: String?) {
        token?.let { requests.remove(it) }
    }
}

/**
 * Everything a [WeirFlowActivity] needs to stand up one presentation, captured
 * at the [Weir.presentActivity] call site (where all the generic types are in
 * scope) and retrieved by token. The [build] closure defers the actual
 * `WeirWebView`/bridge construction until the Activity exists, because that
 * construction needs the Activity as its `Context` (for the real
 * Activity-backed [PermissionRequester]) — see [Weir.presentActivity].
 */
internal class WeirActivityPresentationRequest(
    /** First-paint background (ARGB int), or null to read the flow's
     *  manifest.json `theme.backgroundColor`. Drives the window background so
     *  there's no white flash before the WebView paints. */
    val backgroundColor: Int?,
    val systemBarStyle: WeirSystemBarStyle,
    /**
     * Builds and attaches the flow host inside [activity], using the
     * Activity-backed [permissionRequester], and routes the flow's terminal
     * outcome through [finish]. Returns the [WeirPresentation] whose
     * `host.webView` the Activity sets as its content view (and whose `load()`
     * it calls once attached).
     */
    val build: (
        activity: WeirFlowActivity,
        permissionRequester: PermissionRequester,
        finish: (WeirFlowResult) -> Unit,
    ) -> WeirPresentation,
    private val completion: (WeirFlowResult) -> Unit,
) {
    private val delivered = AtomicBoolean(false)

    val hasDelivered: Boolean get() = delivered.get()

    /** Fires [completion] exactly once. Extra calls are no-ops. */
    fun deliver(result: WeirFlowResult) {
        if (delivered.compareAndSet(false, true)) {
            completion(result)
        }
    }
}
