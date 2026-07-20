package studio.aldric.weir.persistence

import java.io.File

/**
 * Health-event source tag for `HealthEvent.bundleSource` — reflects which tier
 * of [WeirBundleResolution]'s order produced the bundle root a flow rendered
 * from. Faithful port of iOS `WeirBundleSource`.
 */
enum class WeirBundleSource(val raw: String) {
    BUNDLED("bundled"),
    REMOTE("remote"),
}

/**
 * The render-path side of remote delivery M3/M4: where a presentation's bundle
 * root actually comes from. A pure function (no reach into `Weir`'s static
 * state) so it's directly unit-testable against hand-built fixtures. Faithful
 * port of iOS `WeirBundleResolution`.
 */
object WeirBundleResolution {

    data class Resolved(val root: File, val source: WeirBundleSource)

    /**
     * Resolution order (M4):
     *  1. **Promoted remote bundle** — `updateController.bundleManager
     *     .activeBundleURL`, but only if it actually contains a spec for
     *     [flowId] (`activeBundleContainsFlow`); a promoted bundle that only
     *     ever shipped a different flow is never treated as valid for this one.
     *  2. **Host-supplied [hostBundleRoot]** — whatever the caller passed.
     *  3. **Embedded/placeholder bundle** — [fallbackBundleManager]'s own
     *     `activeBundleURL`, for callers that pass no [hostBundleRoot] and have
     *     no remote update configured either.
     *
     * This IS the "next flow present boundary" M3 promotion is scoped to: it
     * calls [BundleManager.promoteStagedUpdateIfAny] first (no-op if nothing is
     * staged), before resolving anything, and is called exactly once per
     * presentation — never while a flow's WebView is already live.
     */
    fun resolve(
        flowId: String,
        hostBundleRoot: File?,
        updateController: WeirUpdateController?,
        fallbackBundleManager: BundleManager,
    ): Resolved {
        if (updateController != null) {
            updateController.bundleManager.promoteStagedUpdateIfAny()
            if (updateController.bundleManager.activeBundleContainsFlow(flowId)) {
                return Resolved(updateController.bundleManager.activeBundleURL, WeirBundleSource.REMOTE)
            }
        }
        if (hostBundleRoot != null) {
            return Resolved(hostBundleRoot, WeirBundleSource.BUNDLED)
        }
        return Resolved(fallbackBundleManager.activeBundleURL, WeirBundleSource.BUNDLED)
    }
}
