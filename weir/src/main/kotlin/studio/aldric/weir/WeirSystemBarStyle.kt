package studio.aldric.weir

/**
 * Caller-supplied system-bar (status + navigation) appearance for a
 * [WeirFlowActivity] presentation — the Android analogue of iOS's
 * `statusBarStyle: UIStatusBarStyle` on `Weir.present` (spec §7 Chrome:
 * "status-bar style handoff from SDK"). The host applies it edge-to-edge on
 * present; because the flow runs in its own Activity, backing out restores the
 * caller's own bars automatically (no explicit revert needed).
 *
 * "Light bars" here means **light-colored bar backgrounds ⇒ dark icons** — the
 * `WindowInsetsControllerCompat.isAppearanceLight*Bars` convention. Pick
 * [forLightBackground] when the flow's background is light, [forDarkBackground]
 * when it's dark.
 */
data class WeirSystemBarStyle(
    /** true ⇒ dark status-bar icons (for a light background). */
    val lightStatusBarIcons: Boolean,
    /** true ⇒ dark navigation-bar icons (for a light background). */
    val lightNavBarIcons: Boolean,
) {
    companion object {
        /** Dark icons, suited to a light flow background. */
        val forLightBackground = WeirSystemBarStyle(lightStatusBarIcons = true, lightNavBarIcons = true)

        /** Light icons, suited to a dark flow background (the onboarding default —
         *  the demo/Niyat flows are dark). */
        val forDarkBackground = WeirSystemBarStyle(lightStatusBarIcons = false, lightNavBarIcons = false)

        /** Matches the platform default (light icons); most onboarding flows
         *  are dark, so this is a reasonable no-config default. */
        val Default = forDarkBackground
    }
}
