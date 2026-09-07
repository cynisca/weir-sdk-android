package studio.aldric.weir

import android.content.SharedPreferences
import java.util.UUID

/**
 * SDK-owned identity helpers so a host app doesn't have to hand-roll the
 * "show Weir vs native" coin-flip and userId minting. Faithful port of
 * `sdk-ios/Sources/Weir/Identity.swift`; `UserDefaults` maps to
 * [SharedPreferences], and the persistence keys/semantics are identical.
 *
 * A host with its own stable identity or its own remote-assigned split is free
 * to ignore these and pass its own values straight to `Weir.present`.
 */
object WeirIdentity {

    /**
     * Which arm of a host's own "show Weir vs native" split this device is on.
     * A two-arm, device-sticky assignment — not Weir's in-flow experiment
     * bucketing (that's [stableUserId]/`window.__weirUserId`, a separate
     * dimension).
     */
    enum class ResolvedVariant(val raw: String) {
        WEIR("weir"),
        NATIVE("native");

        companion object {
            fun fromRaw(raw: String?): ResolvedVariant? = entries.firstOrNull { it.raw == raw }
        }
    }

    // Same keys as iOS Identity.swift.
    const val RESOLVED_VARIANT_KEY = "weir.sdk.resolvedVariant"
    const val STABLE_USER_ID_KEY = "weir.sdk.stableUserId"

    /**
     * Coin-flips and persists this device's WEIR/NATIVE assignment exactly
     * once, the first time it's called; every subsequent call (this launch or
     * any future one, on this [prefs] store) reads the persisted value back
     * rather than re-flipping — sticky for the device's lifetime.
     */
    fun resolvedVariant(prefs: SharedPreferences): ResolvedVariant {
        ResolvedVariant.fromRaw(prefs.getString(RESOLVED_VARIANT_KEY, null))?.let { return it }
        val flipped = if (Math.random() < 0.5) ResolvedVariant.WEIR else ResolvedVariant.NATIVE
        prefs.edit().putString(RESOLVED_VARIANT_KEY, flipped.raw).apply()
        return flipped
    }

    /**
     * Reads this device's persisted variant assignment without flipping a new
     * one — `null` if [resolvedVariant] has never been called on this [prefs]
     * store.
     */
    fun assignedVariant(prefs: SharedPreferences): ResolvedVariant? =
        ResolvedVariant.fromRaw(prefs.getString(RESOLVED_VARIANT_KEY, null))

    /**
     * Stable per-device id suitable for `Weir.present(userId = …)` — minted
     * once (a UUID string), persisted, then read back on every subsequent
     * call, so the same device gets the same id across relaunches, offline
     * included, for as long as the app is installed. Feeds
     * `window.__weirUserId`, the sole input the bundled runtime's bucketer
     * uses to assign this device's arm for any experiment the flow declares.
     */
    fun stableUserId(prefs: SharedPreferences): String {
        prefs.getString(STABLE_USER_ID_KEY, null)?.let { return it }
        val minted = UUID.randomUUID().toString()
        prefs.edit().putString(STABLE_USER_ID_KEY, minted).apply()
        return minted
    }
}
