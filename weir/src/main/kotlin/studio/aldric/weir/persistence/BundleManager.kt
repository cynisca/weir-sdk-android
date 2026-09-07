package studio.aldric.weir.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.HealthEvent
import studio.aldric.weir.bridge.HealthSeq
import studio.aldric.weir.bridge.WeirJson
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * One file described by a remote update manifest: a path relative to the
 * bundle root, the URL to fetch its bytes from, and the lowercase SHA-256 of
 * those exact bytes. Faithful port of iOS `BundleManifestFile`.
 *
 * `url` is a plain [String] (not a parsed URL) on purpose: the signed payload
 * is built from `url` verbatim (see [BundleUpdateManifest.signingPayload]), so
 * any URL normalization/re-encoding would break byte-parity with the TS signer.
 */
@Serializable
data class BundleManifestFile(
    val path: String,
    val url: String,
    val sha256: String,
)

/**
 * Remote update manifest shape — v4 (RFC-010 §4.1/§8.1), the exact response
 * `GET /manifest/:flowId` serves (`services/api/src/app.ts`) and the exact
 * input `configSigningPayload` (`packages/spec/src/config-signing.ts`) signs
 * over. Faithful port of iOS `BundleUpdateManifest`
 * (`sdk-ios/Sources/WeirCore/Persistence/BundleManager.swift`, commit
 * 5eef3f0) — Phase C's real, live contract replaces this SDK's own prior
 * placeholder v0/v3 shape (`bundleId`, no `registryManifestVersion`):
 * greenfield, no compatibility shim for the retired shape (MISSION.md
 * working agreement 2).
 *
 * `signature` is a raw 64-byte Ed25519 signature (standard base64) over
 * [signingPayload], produced server-side by `services/api`. Verification uses
 * the host-provisioned public key (see [BundleManager]).
 */
@Serializable
data class BundleUpdateManifest(
    val specVersion: Int,
    /**
     * The published bundle's own id (`"<flowId>-v<version>"` server-side) —
     * named `configId` to match the wire field and `configSigningPayload`'s
     * parameter name exactly, not this SDK's prior `bundleId` name.
     */
    val configId: String,
    /**
     * Monotonic per-(appId, flowId) publish counter, signed. The SDK rejects a
     * manifest whose version is not strictly newer than its last-promoted one,
     * so an older validly-signed manifest can't be replayed to downgrade the
     * active bundle (R2-S1).
     */
    val version: Int,
    /**
     * The component-manifest version the server validated this served config
     * against (RFC-010 §4.3) — part of the signed payload so a replayed
     * manifest can't be paired with a different registry state than the one
     * it was actually gated against.
     */
    val registryManifestVersion: Int,
    val files: List<BundleManifestFile>,
    val signature: String,
) {
    /**
     * Canonical bytes the signature is computed over — byte-for-byte the same
     * string `configSigningPayload` (`packages/spec/src/config-signing.ts`)
     * builds (RFC-010 §5):
     *  1. `configId:<configId>`
     *  2. `specVersion:<int>`
     *  3. `version:<int>`
     *  4. `registryManifestVersion:<int>`
     *  5. one `<path>:<url>:<sha256>` line per file, **the file lines sorted
     *     lexicographically as whole strings** (header lines prepended AFTER
     *     sorting, in that fixed order)
     *  - joined with a single `\n` (LF), **no trailing newline**, UTF-8.
     *
     * Kotlin `List<String>.sorted()` uses natural `String.compareTo` (UTF-16
     * code-unit order), which agrees with the JS signer and the Swift verifier
     * for the ASCII paths/urls this contract uses.
     */
    val signingPayload: ByteArray
        get() {
            val fileLines = files.map { "${it.path}:${it.url}:${it.sha256}" }.sorted()
            val lines = listOf(
                "configId:$configId",
                "specVersion:$specVersion",
                "version:$version",
                "registryManifestVersion:$registryManifestVersion",
            ) + fileLines
            return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        }
}

/**
 * Result of checking a manifest against spec-version support and signature,
 * with no network I/O — kept separate from [BundleManager.checkForUpdate] so
 * this gating logic is unit-testable without a live server. Port of iOS
 * `ManifestVerification`.
 */
enum class ManifestVerification {
    VALID,
    INVALID_SIGNATURE,
    UNSUPPORTED_SPEC_VERSION,
    MALFORMED_SIGNATURE_ENCODING,
}

/**
 * Injectable seam over the two HTTP GETs [BundleManager] issues (manifest
 * fetch + per-file byte download) so tests substitute a fake without a live
 * server — the Android analogue of iOS injecting `URLSession`, mirroring
 * [EventIngestUploading]. Implementations throw on any transport failure;
 * [BundleManager] catches and fails closed.
 */
interface BundleHttpClient {
    /**
     * Fetch [url], reading at most [maxBytes] and throwing if the body exceeds
     * it (R2-S2 memory-exhaustion cap). The bound is enforced INCREMENTALLY so a
     * malicious server can't force an unbounded allocation before the check.
     */
    suspend fun get(url: String, maxBytes: Long): ByteArray
}

/**
 * Thrown by a [BundleHttpClient] when the response came back but with a
 * non-2xx status, so [BundleManager] can report the real [status] on
 * `health_manifest_fetch_failed` instead of collapsing every failure to `0`.
 * Every other [BundleHttpClient] failure (timeout, DNS, connection refused,
 * body-too-large) has no HTTP status at all, so it stays a plain
 * [java.io.IOException] and reports `httpStatus = 0`.
 */
class BundleHttpStatusException(val status: Int, message: String) : java.io.IOException(message)

/**
 * Default [BundleHttpClient]: one `HttpURLConnection` GET per call — zero new
 * dependency, the Android analogue of iOS's `URLSession.data(from:)`.
 */
class HttpUrlConnectionBundleHttpClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 15_000,
) : BundleHttpClient {
    override suspend fun get(url: String, maxBytes: Long): ByteArray =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw BundleHttpStatusException(status, "HTTP $status fetching $url")
                }
                connection.inputStream.use { readCapped(it, maxBytes, url) }
            } finally {
                connection.disconnect()
            }
        }

    private fun readCapped(input: java.io.InputStream, maxBytes: Long, url: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw java.io.IOException("response body for $url exceeds $maxBytes bytes")
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }
}

/**
 * Manages which flow-bundle directory is "active" (what the WebView should
 * serve assets from), bundled-first with a background remote-update check that
 * never swaps the active bundle mid-flight. Faithful port of iOS
 * `BundleManager` (`sdk-ios/Sources/WeirCore/Persistence/BundleManager.swift`,
 * commit 5eef3f0).
 *
 * ## On-disk layout under [rootDirectory]
 * ```
 * <root>/
 *   staged/<bundleId>/     downloaded + verified, not yet active
 *                          (+ .weir-staged-manifest.json sidecar)
 *   promoted/<bundleId>/   the currently-active remote bundle, once promoted
 *   active.json            { "bundleId": "...", "kind": "promoted" }
 *   embedded-placeholder/  empty; used only if no embedded bundle is supplied
 * ```
 *
 * ## Fail-closed
 * A `null`/invalid [publicKeyRaw] ⇒ every manifest rejected ⇒ flows keep
 * rendering from the embedded/host bundle. Verification never trusts a staged
 * directory found on disk; [checkForUpdate] and the init-time rehydration both
 * re-run the Ed25519 signature check AND every per-file SHA-256.
 */
class BundleManager(
    rootDirectory: File? = null,
    /** Raw 32-byte Ed25519 public key. `null`/wrong-length ⇒ fail closed. */
    publicKeyRaw: ByteArray? = null,
    /** Build-time-embedded baseline bundle root, if the host supplies one.
     *  iOS resolves this via SPM `Bundle.module`; on Android the host passes
     *  it (or `null` ⇒ empty placeholder). */
    private val embeddedBundleRoot: File? = null,
    private val httpClient: BundleHttpClient = HttpUrlConnectionBundleHttpClient(),
    private val logger: (String) -> Unit = {},
    /** Manifest/remote-bundle health telemetry (Phase 3 structured
     *  diagnostics) — `null` (the default) means every call site below is a
     *  no-op, so existing callers that construct a [BundleManager] with no
     *  sink behave exactly as before. Rides under
     *  [HealthEvent.UPDATE_SESSION_FLOW_ID] with this instance's own
     *  [healthSessionId]/[healthSeq], never a real flow's session. */
    private val eventSink: EventSink? = null,
) {
    private val rootDirectory: File =
        rootDirectory ?: defaultRootDirectory()

    /** Native-generated session id for every health event this instance emits
     *  — distinct from any JS runtime session, mirrors [HealthSeq]'s existing
     *  per-component-instance pattern (see `EventQueue.flushHealthSessionId`). */
    private val healthSessionId: String = UUID.randomUUID().toString()
    private val healthSeq = HealthSeq()

    private fun emitHealth(event: EventParams) {
        eventSink?.append(event)
    }

    /** Validated Ed25519 public-key params, or `null` (fail closed). */
    private val publicKey: Ed25519PublicKeyParameters? =
        publicKeyRaw?.takeIf { it.size == 32 }?.let {
            try {
                Ed25519PublicKeyParameters(it, 0)
            } catch (_: Exception) {
                null
            }
        }

    private val lock = Any()
    private var _activeBundleURL: File
    private var stagedBundleId: String? = null
    private var stagedBundleURL: File? = null
    private var stagedVersion: Int? = null

    /** Last-promoted monotonic version, persisted in `active.json` (0 before any
     *  remote bundle has ever promoted). A fetched manifest is rejected unless
     *  its version is strictly greater than this (and any newer already-staged
     *  version) — the anti-downgrade gate (R2-S1). */
    private var activeVersion: Int = 0

    init {
        File(this.rootDirectory, "staged").mkdirs()
        File(this.rootDirectory, "promoted").mkdirs()

        _activeBundleURL = resolveInitialActiveURL(this.rootDirectory, embeddedBundleRoot, logger)
        activeVersion = resolveInitialActiveVersion(this.rootDirectory)

        // Rehydrate any bundle staged by a *previous* process instance and
        // re-verify it from scratch (signature + per-file SHA-256) — never
        // just trust the directory. This is the iOS regression that silently
        // broke OTA: without it, staged state reset to null every relaunch, so
        // a bundle staged in launch N never promoted at launch N+1's boundary.
        val (rehydratedId, rehydratedURL, rehydratedVersion) = rehydrateStagedState(this.rootDirectory, publicKey, logger)
        stagedBundleId = rehydratedId
        stagedBundleURL = rehydratedURL
        stagedVersion = rehydratedVersion
    }

    /**
     * Directory the WebView should serve assets from *right now*. Always
     * returns immediately (never awaits network); reflects whatever was
     * resolved at init or the last [promoteStagedUpdateIfAny].
     */
    val activeBundleURL: File
        get() = synchronized(lock) { _activeBundleURL }

    /**
     * Monotonic version of whatever [activeBundleURL] currently resolves to —
     * 0 before any remote bundle has ever promoted (embedded/placeholder
     * bundle, no manifest to version), otherwise the last-promoted
     * `BundleUpdateManifest.version`. Stamped onto `health_bundle_source`
     * telemetry ([HealthEvent.bundleSource]) alongside the `REMOTE` tier via
     * [WeirBundleResolution.resolve], so adoption can be tracked
     * per-version, not just per-tier. Mirrors iOS
     * `BundleManager.activeBundleVersion`.
     */
    val activeBundleVersion: Int
        get() = synchronized(lock) { activeVersion }

    /**
     * Whether [activeBundleURL] actually contains a spec for [flowId] — used at
     * the present boundary to decide whether a promoted remote bundle is safe
     * to render from. Three on-disk shapes recognized, v4 first (port of iOS
     * `activeBundleContainsFlow`, `sdk-ios/Sources/WeirCore/Persistence/BundleManager.swift`):
     *
     * 1. `config.json`'s own top-level `"id"` — what `/publish` actually
     *    writes today (RFC-010 §2/§3.3, greenfield-replaced the v3 shape
     *    below, MISSION.md working agreement 2): one config per staged/
     *    promoted directory, so its own declared flow id is authoritative.
     * 2. What `weir_deploy` published under the retired v3 HTML-bundle path: a
     *    flat `manifest.json` whose top-level `flowId` names the single flow
     *    the bundle renders. Kept as a fallback (not removed outright) purely
     *    because it costs nothing and an already-promoted v3 bundle on a real
     *    device must not suddenly stop resolving the moment this SDK build
     *    updates.
     * 3. The multi-flow `flows/<flowId>.json` convention, kept for the same
     *    reason as (2).
     */
    fun activeBundleContainsFlow(flowId: String): Boolean {
        val root = activeBundleURL

        val configFile = File(root, "config.json")
        if (configFile.isFile) {
            try {
                val parsed = LENIENT_JSON.parseToJsonElement(configFile.readText()) as? JsonObject
                val configFlowId = parsed?.get("id")?.jsonPrimitive?.content
                if (configFlowId == flowId) return true
            } catch (_: Exception) {
                // fall through to the manifest.json/flows/<flowId>.json shapes
            }
        }

        val manifestFile = File(root, "manifest.json")
        if (manifestFile.isFile) {
            try {
                val parsed = LENIENT_JSON.parseToJsonElement(manifestFile.readText()) as? JsonObject
                val manifestFlowId = parsed?.get("flowId")?.jsonPrimitive?.content
                if (manifestFlowId == flowId) return true
            } catch (_: Exception) {
                // fall through to the flows/<flowId>.json shape
            }
        }
        return File(File(root, "flows"), "$flowId.json").isFile
    }

    /**
     * Fetches the manifest, verifies spec version + Ed25519 signature, and on
     * success downloads its files into a staged directory (hash-checking each).
     * Never swaps [activeBundleURL] — that only happens in
     * [promoteStagedUpdateIfAny]. Any failure (network, decode, signature,
     * unsupported spec, hash mismatch) is swallowed and logged. Port of iOS
     * `checkForUpdate`.
     */
    suspend fun checkForUpdate(manifestURL: String) {
        val safeManifestUrl = safeDiagnosticUrl(manifestURL)
        emitHealth(
            HealthEvent.manifestFetchStarted(
                flowId = HealthEvent.UPDATE_SESSION_FLOW_ID,
                sessionId = healthSessionId,
                seq = healthSeq.next(),
                url = safeManifestUrl,
            ),
        )

        val rawManifest: ByteArray = try {
            httpClient.get(manifestURL, MAX_MANIFEST_BYTES)
        } catch (e: Exception) {
            logger("BundleManager: manifest fetch failed: $e")
            emitHealth(
                HealthEvent.manifestFetchFailed(
                    flowId = HealthEvent.UPDATE_SESSION_FLOW_ID,
                    sessionId = healthSessionId,
                    seq = healthSeq.next(),
                    httpStatus = (e as? BundleHttpStatusException)?.status ?: 0,
                    reason = safeFetchFailureReason(e),
                ),
            )
            return
        }

        val manifest: BundleUpdateManifest = try {
            WeirJson.decodeFromString(BundleUpdateManifest.serializer(), rawManifest.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            logger("BundleManager: manifest decode failed: $e")
            rejectManifest(HealthEvent.ManifestRejectionReason.SCHEMA_INVALID)
            return
        }

        // Bound the file COUNT before doing any per-file work (R2-S2): a manifest
        // is signed, but the signer could be tricked, or a huge count could still
        // be a resource-exhaustion vector on its own.
        if (manifest.files.size > MAX_MANIFEST_FILES) {
            logger("BundleManager: manifest lists ${manifest.files.size} files (> $MAX_MANIFEST_FILES); ignoring")
            rejectManifest(HealthEvent.ManifestRejectionReason.SCHEMA_INVALID)
            return
        }

        when (verify(manifest, publicKey)) {
            ManifestVerification.UNSUPPORTED_SPEC_VERSION -> {
                logger("BundleManager: manifest specVersion ${manifest.specVersion} not in $supportedSpecVersions; ignoring")
                rejectManifest(HealthEvent.ManifestRejectionReason.SCHEMA_INVALID)
                return
            }
            ManifestVerification.INVALID_SIGNATURE -> {
                logger("BundleManager: manifest signature invalid for configId ${manifest.configId}; ignoring")
                rejectManifest(HealthEvent.ManifestRejectionReason.SIGNATURE_INVALID)
                return
            }
            ManifestVerification.MALFORMED_SIGNATURE_ENCODING -> {
                logger("BundleManager: manifest signature not valid base64; ignoring")
                rejectManifest(HealthEvent.ManifestRejectionReason.SIGNATURE_INVALID)
                return
            }
            ManifestVerification.VALID -> Unit
        }

        if (!isSafePathSegment(manifest.configId)) {
            logger("BundleManager: manifest configId '${manifest.configId}' is not a safe path segment; ignoring")
            rejectManifest(HealthEvent.ManifestRejectionReason.SCHEMA_INVALID)
            return
        }

        // Anti-downgrade (R2-S1): reject any manifest whose version is not
        // strictly newer than what's already promoted OR already staged. The
        // version is inside the signed payload, so a MITM/compromised box can't
        // forge a newer number — it can only replay an old, validly-signed
        // manifest, which this rejects. Compared against the max of active and
        // staged so a replay can't undercut a newer stage awaiting promotion.
        val currentVersion = synchronized(lock) { maxOf(activeVersion, stagedVersion ?: 0) }
        if (manifest.version <= currentVersion) {
            logger("BundleManager: manifest version ${manifest.version} not newer than current $currentVersion; ignoring (anti-downgrade)")
            rejectManifest(HealthEvent.ManifestRejectionReason.ANTI_DOWNGRADE)
            return
        }

        val stagedRoot = File(rootDirectory, "staged")
        val stagedDir = File(stagedRoot, manifest.configId)
        try {
            stagedDir.mkdirs()
            var totalBytes = 0L
            for (file in manifest.files) {
                // Per-file cap bounds a single malicious download; the running
                // total caps the whole update (R2-S2). Both throw → the partial
                // stage is discarded below, failing closed.
                val remaining = MAX_TOTAL_BYTES - totalBytes
                val bytes = httpClient.get(file.url, minOf(MAX_FILE_BYTES, remaining))
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw IllegalStateException("bundle exceeds $MAX_TOTAL_BYTES total bytes")
                }
                if (sha256Hex(bytes) != file.sha256) {
                    throw HashMismatchException(file.path)
                }
                val destination = resolveSafeChild(stagedDir, file.path)
                    ?: throw IllegalStateException("unsafe manifest file path: ${file.path}")
                destination.parentFile?.mkdirs()
                destination.writeBytes(bytes)
            }
            // Persist the already-verified manifest alongside the staged files,
            // written LAST — its presence signals "this stage completed" and is
            // the only way a future process instance can re-verify (not trust)
            // this directory during init-time rehydration.
            File(stagedDir, STAGED_MANIFEST_FILENAME).writeBytes(rawManifest)

            // R2-S5: keep only this newest stage — prune any sibling staged dirs
            // (older configIds that staged earlier this session) so staged/ can't
            // accumulate one directory per superseded version between relaunches.
            stagedRoot.listFiles()?.forEach { sibling ->
                if (sibling.name != manifest.configId) sibling.deleteRecursively()
            }
        } catch (e: HashMismatchException) {
            logger("BundleManager: staging download failed: $e; discarding partial stage")
            stagedDir.deleteRecursively()
            rejectManifest(HealthEvent.ManifestRejectionReason.HASH_MISMATCH)
            return
        } catch (e: Exception) {
            logger("BundleManager: staging download failed: $e; discarding partial stage")
            stagedDir.deleteRecursively()
            return
        }

        synchronized(lock) {
            stagedBundleId = manifest.configId
            stagedBundleURL = stagedDir
            stagedVersion = manifest.version
        }
        emitHealth(
            HealthEvent.remoteStaged(
                flowId = HealthEvent.UPDATE_SESSION_FLOW_ID,
                sessionId = healthSessionId,
                seq = healthSeq.next(),
                version = manifest.version,
                // HealthEvent's param/JSON-key name stays `bundleId` — this is
                // device-local telemetry, not the signed wire contract, so it
                // isn't part of the RFC-010 §5 rename (matches iOS's
                // `HealthEvent.remoteStaged(... bundleId: manifest.configId)`).
                bundleId = manifest.configId,
            ),
        )
    }

    /** Thrown internally when a staged file's SHA-256 doesn't match the signed
     *  manifest, so [checkForUpdate] can report
     *  [HealthEvent.ManifestRejectionReason.HASH_MISMATCH] specifically instead
     *  of the generic "staging download failed" path. Never carries file bytes
     *  or the digest itself — just the (non-secret) manifest-relative path. */
    private class HashMismatchException(path: String) : Exception("SHA-256 mismatch for $path")

    private fun rejectManifest(reason: HealthEvent.ManifestRejectionReason) {
        emitHealth(
            HealthEvent.manifestRejected(
                flowId = HealthEvent.UPDATE_SESSION_FLOW_ID,
                sessionId = healthSessionId,
                seq = healthSeq.next(),
                reason = reason,
            ),
        )
    }

    /**
     * Reduces [url] to origin + path for `health_manifest_fetch_started` —
     * never the query string (which could carry a signed-URL token) or
     * userinfo credentials. Returns `""` (never the raw, possibly-credentialed
     * string) if [url] doesn't even parse as a URI.
     */
    private fun safeDiagnosticUrl(url: String): String = try {
        val uri = java.net.URI(url)
        buildString {
            uri.scheme?.let { append(it); append("://") }
            uri.host?.let { append(it) }
            if (uri.port != -1) { append(':'); append(uri.port) }
            append(uri.path ?: "")
        }
    } catch (_: Exception) {
        ""
    }

    /** Short, safe `health_manifest_fetch_failed` reason code — never the raw
     *  exception message, which (e.g. [HttpUrlConnectionBundleHttpClient]'s own
     *  messages) can embed the full request URL. */
    private fun safeFetchFailureReason(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "timeout"
        is BundleHttpStatusException -> "http_error"
        is java.io.IOException -> "network_error"
        else -> "unknown_error"
    }

    /**
     * Promotes the currently-staged update (if any) to active. Must only be
     * called *between* flows (never mid-flow) so a live WebView never has its
     * asset root change under it. Persists the choice (`active.json`) so the
     * promoted bundle survives relaunches, and prunes the previously promoted
     * bundle. Port of iOS `promoteStagedUpdateIfAny`.
     */
    fun promoteStagedUpdateIfAny() {
        synchronized(lock) {
            val stagedId = stagedBundleId ?: return
            val stagedURL = stagedBundleURL ?: return

            val promotedRoot = File(rootDirectory, "promoted")
            val destination = File(promotedRoot, stagedId)

            try {
                promotedRoot.listFiles()?.forEach { old ->
                    if (old.name != stagedId) old.deleteRecursively()
                }
                if (destination.exists()) destination.deleteRecursively()
                promotedRoot.mkdirs()
                if (!stagedURL.renameTo(destination)) {
                    // renameTo can fail across filesystems; fall back to a copy.
                    stagedURL.copyRecursively(destination, overwrite = true)
                    stagedURL.deleteRecursively()
                }
            } catch (e: Exception) {
                logger("BundleManager: promotion move failed: $e; keeping previous active bundle")
                return
            }

            val promotedVersion = stagedVersion ?: activeVersion
            try {
                val marker = ActiveMarker(bundleId = stagedId, kind = "promoted", version = promotedVersion)
                File(rootDirectory, "active.json")
                    .writeText(WeirJson.encodeToString(ActiveMarker.serializer(), marker))
            } catch (e: Exception) {
                logger("BundleManager: active.json write failed: $e")
            }

            _activeBundleURL = destination
            activeVersion = promotedVersion
            stagedBundleId = null
            stagedBundleURL = null
            stagedVersion = null

            emitHealth(
                HealthEvent.remotePromoted(
                    flowId = HealthEvent.UPDATE_SESSION_FLOW_ID,
                    sessionId = healthSessionId,
                    seq = healthSeq.next(),
                    version = promotedVersion,
                    bundleId = stagedId,
                ),
            )
        }
    }

    // ---- Initial resolution / rehydration ----

    @Serializable
    private data class ActiveMarker(
        @SerialName("bundleId") val bundleId: String,
        @SerialName("kind") val kind: String,
        /** Last-promoted monotonic version (R2-S1). Nullable for backward
         *  compatibility: an `active.json` written before this field existed
         *  decodes with `version == null`, treated as 0 so the promoted bundle
         *  stays active and the next real manifest (version ≥ 1) still promotes. */
        @SerialName("version") val version: Int? = null,
    )

    /** Reads the last-promoted version from `active.json` (0 if none / a
     *  pre-versioning marker) — the floor the anti-downgrade gate compares against. */
    private fun resolveInitialActiveVersion(root: File): Int {
        val markerFile = File(root, "active.json")
        if (!markerFile.isFile) return 0
        return try {
            val marker = WeirJson.decodeFromString(ActiveMarker.serializer(), markerFile.readText())
            if (marker.kind == "promoted") marker.version ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }

    private enum class RehydrationVerdict { VALID, CORRUPT_OR_TAMPERED, CANNOT_JUDGE }

    private fun rehydrateStagedState(
        root: File,
        publicKey: Ed25519PublicKeyParameters?,
        logger: (String) -> Unit,
    ): Triple<String?, File?, Int?> {
        val stagedRoot = File(root, "staged")
        val candidates = stagedRoot.listFiles()?.filter { it.isDirectory } ?: return Triple(null, null, null)

        // Most-recently-modified first, so a newer stage wins over an older one.
        val sorted = candidates.sortedByDescending { it.lastModified() }

        var winner: Triple<String, File, Int>? = null
        for (candidate in sorted) {
            val (verdict, manifest) = verifyStagedManifestOnDisk(candidate, publicKey)
            when (verdict) {
                RehydrationVerdict.VALID -> {
                    if (winner == null) {
                        winner = Triple(manifest!!.configId, candidate, manifest.version)
                    } else {
                        logger("BundleManager: discarding stale staged bundle at ${candidate.name} in favor of a newer one")
                        candidate.deleteRecursively()
                    }
                }
                RehydrationVerdict.CORRUPT_OR_TAMPERED -> {
                    logger("BundleManager: discarding staged bundle at ${candidate.name} — failed re-verification on rehydrate")
                    candidate.deleteRecursively()
                }
                RehydrationVerdict.CANNOT_JUDGE -> {
                    // No key to condemn it; another correctly-keyed instance
                    // sharing this root may own it. Leave it exactly as found.
                }
            }
        }
        return Triple(winner?.first, winner?.second, winner?.third)
    }

    /**
     * Re-verifies a staged directory from current bytes: signature (against the
     * trust anchor), `configId == directory name` (anti-replay), and every
     * per-file SHA-256. Distinguishes "genuinely bad" from "this instance has
     * no key to judge with" (the latter must never destroy another instance's
     * legitimate stage). Port of iOS `verifyStagedManifestOnDisk`.
     */
    private fun verifyStagedManifestOnDisk(
        directory: File,
        publicKey: Ed25519PublicKeyParameters?,
    ): Pair<RehydrationVerdict, BundleUpdateManifest?> {
        val manifestFile = File(directory, STAGED_MANIFEST_FILENAME)
        val manifest: BundleUpdateManifest = try {
            WeirJson.decodeFromString(BundleUpdateManifest.serializer(), manifestFile.readText())
        } catch (_: Exception) {
            // No/unreadable sidecar: an incomplete stage (the sidecar is
            // written last), never a legitimate one regardless of key.
            return RehydrationVerdict.CORRUPT_OR_TAMPERED to null
        }

        if (publicKey == null) return RehydrationVerdict.CANNOT_JUDGE to null
        if (verify(manifest, publicKey) != ManifestVerification.VALID) {
            return RehydrationVerdict.CORRUPT_OR_TAMPERED to null
        }
        if (manifest.configId != directory.name) {
            return RehydrationVerdict.CORRUPT_OR_TAMPERED to null
        }
        for (file in manifest.files) {
            val safeFile = resolveSafeChild(directory, file.path)
                ?: return RehydrationVerdict.CORRUPT_OR_TAMPERED to null
            val fileBytes = try {
                safeFile.readBytes()
            } catch (_: Exception) {
                return RehydrationVerdict.CORRUPT_OR_TAMPERED to null
            }
            if (sha256Hex(fileBytes) != file.sha256) {
                return RehydrationVerdict.CORRUPT_OR_TAMPERED to null
            }
        }
        return RehydrationVerdict.VALID to manifest
    }

    private fun resolveInitialActiveURL(
        root: File,
        embeddedBundleRoot: File?,
        logger: (String) -> Unit,
    ): File {
        val markerFile = File(root, "active.json")
        if (markerFile.isFile) {
            val marker = try {
                WeirJson.decodeFromString(ActiveMarker.serializer(), markerFile.readText())
            } catch (_: Exception) {
                null
            }
            if (marker != null && marker.kind == "promoted") {
                val promoted = File(File(root, "promoted"), marker.bundleId)
                if (promoted.exists()) return promoted
                logger("BundleManager: promoted bundle ${marker.bundleId} referenced by active.json is missing on disk; falling back")
            }
        }

        if (embeddedBundleRoot != null && embeddedBundleRoot.isDirectory) {
            return embeddedBundleRoot
        }

        logger("BundleManager: no embedded bundle supplied; using empty placeholder")
        val placeholder = File(root, "embedded-placeholder")
        placeholder.mkdirs()
        return placeholder
    }

    companion object {
        /**
         * Spec versions this SDK understands, gating remote manifests. Mirrors
         * iOS `supportedSpecVersions = [4]` (`sdk-ios` `BundleManager.swift`,
         * commit 5eef3f0): v4 only, greenfield — the placeholder v0/v3 shape
         * (`bundleId`, no `registryManifestVersion`) is retired with no
         * compatibility shim (MISSION.md working agreement 2). `BundleManager`
         * never parses spec *content*, it only gates on this set.
         */
        val supportedSpecVersions: Set<Int> = setOf(4)

        /** Sidecar the verified manifest is written into inside a staged dir —
         *  the only way a future process instance can re-verify (not trust) it. */
        const val STAGED_MANIFEST_FILENAME = ".weir-staged-manifest.json"

        // Download DoS ceilings (R2-S2). A compromised/MITM box must not be able
        // to exhaust memory or disk with an oversized manifest, file, or file
        // count. Bundles budget ~350KB gz, so these are generous safety limits,
        // not tight quotas. Mirrored in iOS BundleManager.
        const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024      // 1 MB
        const val MAX_FILE_BYTES = 8L * 1024 * 1024          // 8 MB per file
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024        // 16 MB across all files
        const val MAX_MANIFEST_FILES = 256

        private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Pure gating logic (spec-version + Ed25519 signature), no network I/O,
         * directly unit-testable. THE R1 verify path. Uses the BouncyCastle
         * **lightweight API** (`Ed25519Signer` + `Ed25519PublicKeyParameters`)
         * — pure Ed25519 (not Ed25519ph), no JCA/JCE provider registration
         * (avoids Android's old conflicting BC, and `java.security` Ed25519 is
         * API 33+ while minSdk is 26). Signature is decoded as **standard**
         * base64 (not url-safe). Port of iOS `verify`.
         */
        fun verify(manifest: BundleUpdateManifest, publicKey: Ed25519PublicKeyParameters?): ManifestVerification {
            if (!supportedSpecVersions.contains(manifest.specVersion)) {
                return ManifestVerification.UNSUPPORTED_SPEC_VERSION
            }
            if (publicKey == null) return ManifestVerification.INVALID_SIGNATURE
            val signatureBytes: ByteArray = try {
                Base64.getDecoder().decode(manifest.signature)
            } catch (_: IllegalArgumentException) {
                return ManifestVerification.MALFORMED_SIGNATURE_ENCODING
            }
            val payload = manifest.signingPayload
            val verifier = Ed25519Signer().apply { init(false, publicKey) }
            verifier.update(payload, 0, payload.size)
            val valid = try {
                verifier.verifySignature(signatureBytes)
            } catch (_: Exception) {
                // BouncyCastle throws on a wrong-length signature rather than
                // returning false — treat as an invalid signature, not a crash.
                false
            }
            return if (valid) ManifestVerification.VALID else ManifestVerification.INVALID_SIGNATURE
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /**
         * A [bundleId] is used directly as a single path segment (`staged/<id>`,
         * `promoted/<id>`). Reject anything that isn't a plain segment — no
         * separators, no `.`/`..`, non-empty — so a malicious `bundleId` can
         * never escape the staging/promoted root or hide as a hidden dotfile.
         */
        fun isSafePathSegment(id: String): Boolean {
            if (id.isEmpty()) return false
            if (id == "." || id == "..") return false
            if (id.contains('/') || id.contains('\\')) return false
            return true
        }

        /**
         * Resolves [relativePath] (a manifest file's `path`, attacker-controlled
         * even though the manifest is signed — signing proves authenticity of
         * *content*, not that the path is safe) against [parent], rejecting any
         * absolute path, any `..`/empty segment, or any result whose canonical
         * form escapes [parent]. Returns `null` (never a `File`) on rejection so
         * callers fail closed by construction. Same contract as iOS
         * `BundleManager.resolveSafeChild`.
         */
        fun resolveSafeChild(parent: File, relativePath: String): File? {
            if (relativePath.isEmpty()) return null
            if (File(relativePath).isAbsolute) return null
            val segments = relativePath.split('/', '\\')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null

            val parentCanonical = try {
                parent.canonicalFile
            } catch (_: Exception) {
                return null
            }
            val candidate = File(parent, relativePath)
            val candidateCanonical = try {
                candidate.canonicalFile
            } catch (_: Exception) {
                return null
            }
            val withinRoot = candidateCanonical == parentCanonical ||
                candidateCanonical.path.startsWith(parentCanonical.path + File.separator)
            return if (withinRoot) candidate else null
        }

        private fun defaultRootDirectory(): File {
            val base = System.getProperty("java.io.tmpdir") ?: "."
            return File(File(base), "Weir/bundles")
        }
    }
}
