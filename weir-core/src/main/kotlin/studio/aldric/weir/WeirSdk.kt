package studio.aldric.weir

/**
 * SDK build metadata shared with diagnostics and remote-update gating.
 *
 * [WEIR_SDK_VERSION] is the single source of truth threaded through
 * [studio.aldric.weir.persistence.ConfigFetchGatingQuery.sdkVersion] (see
 * `Weir.kt`'s `configure()`), which is what the server's stock-type floor
 * (RFC-010 §4.3, `packages/spec/src/component-manifest.ts`'s
 * `V4_FLOOR_SDK_VERSION`) gates against. RC-P0 (release-gate re-review):
 * this constant sat at `0.1.1` — BELOW the server's `1.0.0` floor — meaning
 * every gated stock-screen config was unservable to Android (the SAME class
 * of bug task #48 already caught and fixed on `sdk-react-native`; pinned
 * there by `version.test.ts`, mirrored here by [WeirSdkTest]). Bump both
 * together on every release; a mismatch with the server floor silently
 * strands every gated build on its embedded/cached baseline forever.
 */
object WeirSdk {
    const val WEIR_SDK_VERSION: String = "1.0.0"

    /** Human-readable readiness string retained for compatibility. */
    fun greeting(name: String): String = "Weir Android SDK $WEIR_SDK_VERSION ready for $name"
}
