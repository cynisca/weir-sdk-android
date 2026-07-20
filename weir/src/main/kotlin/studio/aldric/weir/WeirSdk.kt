package studio.aldric.weir

/**
 * Placeholder entry point for the Weir Android SDK.
 *
 * Phase 1 skeleton only — this exists to prove the Gradle/Kotlin/AGP toolchain
 * compiles and runs a unit test. Real SDK logic (bridge, persistence, WebView
 * host) is ported in later phases; see docs/android-support-plan.md §3.
 */
object WeirSdk {
    const val WEIR_SDK_VERSION: String = "0.0.1-skeleton"

    /**
     * Trivial pure function so the placeholder test has something real to assert
     * against (keeps `testDebugUnitTest` from running zero tests).
     */
    fun greeting(name: String): String = "Weir Android SDK $WEIR_SDK_VERSION ready for $name"
}
