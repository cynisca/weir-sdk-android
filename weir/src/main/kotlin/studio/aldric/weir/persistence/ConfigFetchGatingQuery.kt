package studio.aldric.weir.persistence

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Whether this process has a generated component registry installed
 * (RC-P0 gating protocol v2, round-2 re-review). A sealed type rather than
 * two nullable fields so a partial pair — one of
 * `registryManifestVersion`/`registryHash` present without the other — is
 * unrepresentable, not merely disallowed by a runtime check. Mirrors
 * `sdk-react-native`'s `ConfigFetchGating` TS type, which enforces the same
 * thing at the type level via a discriminated intersection
 * (`{ registryManifestVersion: number; registryHash: string } | {
 * registryManifestVersion?: undefined; registryHash?: undefined }`) —
 * RN's strictness is the cross-platform reference for this shape.
 */
sealed interface RegistryGating {
    /** A generated registry is installed (`ComponentRegistry.registryGatingInfo()` returned one). */
    data class Registered(val registryManifestVersion: Int, val registryHash: String) : RegistryGating

    /** No generated registry installed — nothing to check `custom` screens against. */
    data object Unregistered : RegistryGating
}

/**
 * RFC-010 §4.1's build-compatibility query — v2 (RC-P0 gating protocol
 * re-review): `appVersion`/`sdkVersion`/`platform` (the base trio) are
 * always sent together; [registry] is sent as the registry pair only when
 * [RegistryGating.Registered]. [RegistryGating.Unregistered] tells the
 * server "this build has zero registered custom components" — it still
 * enforces stock-type floors and `minAppVersion`/`platforms` off the trio
 * alone, it just fails every `custom` screen's gate (follows that screen's
 * own `fallback`) instead of trying to check a registry it was never told
 * about. This replaces the prior all-5-or-none contract, which silently
 * sent NO gating at all (not even the trio) for any app with no generated
 * registry — meaning such an app's sdkVersion floor was never enforced
 * either.
 */
data class ConfigFetchGatingQuery(
    val appVersion: String,
    val sdkVersion: String,
    val platform: String,
    val registry: RegistryGating = RegistryGating.Unregistered,
) {
    /** URL-encoded query string. The base trio is always present; the registry pair is appended only when [registry] is [RegistryGating.Registered]. */
    fun toQueryString(): String {
        val pairs = mutableListOf(
            "appVersion" to appVersion,
            "sdkVersion" to sdkVersion,
            "platform" to platform,
        )
        val registered = registry
        if (registered is RegistryGating.Registered) {
            pairs += "registryManifestVersion" to registered.registryManifestVersion.toString()
            pairs += "registryHash" to registered.registryHash
        }
        return pairs.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
