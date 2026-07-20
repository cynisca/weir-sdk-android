package studio.aldric.weir.persistence

import java.util.Base64

/**
 * Configuration for enabling remote flow-bundle delivery
 * (docs/android-support-plan.md §3, remote-delivery-workstream Pillar 1) —
 * faithful port of `sdk-ios/Sources/Weir/Persistence/WeirUpdateConfig.swift`.
 * Mirrors [WeirIngestConfig]'s style (a small value type of everything a host
 * needs, resolved once at configure time).
 *
 * Passed to [studio.aldric.weir.Weir.configure]. Missing this call, or an
 * unparseable [publicKeyRawBase64], both mean the same thing: remote updates
 * are disabled and every present renders from the host-supplied bundle root or
 * the embedded baseline bundle only — never an unverified remote one.
 * [BundleManager] is the component that actually enforces the fail-closed
 * behavior (a `null` public key makes every signature check fail); this type
 * only decodes the string, it does no additional validation.
 */
data class WeirUpdateConfig(
    /** Full URL string to fetch the signed `BundleUpdateManifest` from — a
     *  complete, ready-to-fetch URL (like `WeirIngestConfig.endpointURL`), not
     *  a base URL this SDK templates a path onto. Which flow(s) a given
     *  manifest URL describes is entirely up to the server side. */
    val manifestURL: String,
    /** Base64-encoded raw 32-byte Ed25519 public key — the exact format
     *  `services/api/src/signing.ts`'s `publicKeyRawBase64` emits (standard
     *  base64, RFC 4648, `+`/`/` with `=` padding — NOT base64url), so a host
     *  can paste that value here directly with no re-encoding step. */
    val publicKeyRawBase64: String,
) {
    /**
     * Raw 32-byte Ed25519 public key decoded from [publicKeyRawBase64], or
     * `null` when the string isn't valid **standard** base64 (or doesn't decode
     * to 32 bytes). Passed straight through to [BundleManager]'s
     * `publicKeyRaw`, whose fail-closed handling already covers the `null`
     * case, so this type does no extra validation of its own beyond decoding.
     *
     * Standard (not url-safe) base64 to match the signer — the committed R1
     * fixture key contains `+` and `/`, which a url-safe decoder would mangle.
     */
    val publicKeyRaw: ByteArray?
        get() = try {
            val decoded = Base64.getDecoder().decode(publicKeyRawBase64.trim())
            if (decoded.size == 32) decoded else null
        } catch (_: IllegalArgumentException) {
            null
        }
}
