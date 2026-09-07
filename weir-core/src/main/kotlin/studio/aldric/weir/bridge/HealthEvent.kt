package studio.aldric.weir.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/**
 * Operational/health telemetry (docs/instrumentation-workstream.md §2),
 * emitted natively (not by the flow's own JS) through the same [EventSink]
 * pipeline as product events. Faithful port of the parts of
 * `sdk-ios/Sources/Weir/Persistence/HealthEvent.swift` that Phase 1 needs —
 * only `bridge_error` is wired here; the flow-render/bundle-source/flush
 * variants land with the Phase 2 WebView-host + queue work.
 *
 * `sessionId` is a native-generated UUID, not the JS runtime's `sessionId`:
 * health events can fire before any JS session exists.
 */
object HealthEvent {

    /** flowId every `health_queue_flush_result` rides under — distinct from any
     *  real flow so a funnel query can isolate (or exclude) queue-health events.
     *  Mirrors iOS `HealthEvent.queueSessionFlowId`. */
    const val QUEUE_SESSION_FLOW_ID = "_weir_queue_health"

    /** flowId every manifest/remote-bundle health event ([manifestFetchStarted],
     *  [manifestFetchFailed], [manifestRejected], [remoteStaged],
     *  [remotePromoted]) rides under — a manifest URL can describe more than one
     *  flow (see `WeirUpdateConfig.manifestURL`), so these are never scoped to a
     *  single real flowId. Mirrors iOS `HealthEvent.updateSessionFlowId`. */
    const val UPDATE_SESSION_FLOW_ID = "_weir_update_health"


    enum class EventType(val raw: String) {
        BRIDGE_ERROR("health_bridge_error"),
        FLOW_RENDER_MS("health_flow_render_ms"),
        FLOW_FALLBACK("health_flow_fallback"),
        BUNDLE_SOURCE("health_bundle_source"),
        QUEUE_FLUSH_RESULT("health_queue_flush_result"),
        SDK_HEARTBEAT("health_sdk_heartbeat"),
        MANIFEST_FETCH_STARTED("health_manifest_fetch_started"),
        MANIFEST_FETCH_FAILED("health_manifest_fetch_failed"),
        MANIFEST_REJECTED("health_manifest_rejected"),
        REMOTE_STAGED("health_remote_staged"),
        REMOTE_PROMOTED("health_remote_promoted"),
        FLAG_OFF("health_flag_off"),
        FLAG_FETCH_FAILED("health_flag_fetch_failed"),
        NATIVE_BUCKET("health_native_bucket"),
    }

    /**
     * Closed set of safe `health_manifest_rejected` reason codes — never the
     * signature, key, or raw manifest payload. Mirrors iOS
     * `HealthEvent.ManifestRejectionReason`.
     */
    enum class ManifestRejectionReason(val raw: String) {
        SIGNATURE_INVALID("signature_invalid"),
        HASH_MISMATCH("hash_mismatch"),
        ANTI_DOWNGRADE("anti_downgrade"),
        SCHEMA_INVALID("schema_invalid"),
        EXPIRED("expired"),
    }

    fun make(
        type: EventType,
        flowId: String,
        sessionId: String,
        seq: Int,
        fields: Map<String, JsonElement> = emptyMap(),
    ): EventParams {
        val payload = JsonObject(fields + ("type" to JsonPrimitive(type.raw)))
        return EventParams(
            flowId = flowId,
            screenId = null,
            variantId = null,
            sessionId = sessionId,
            userId = null,
            ts = System.currentTimeMillis().toDouble(),
            seq = seq,
            payload = payload,
        )
    }

    fun bridgeError(flowId: String, sessionId: String, seq: Int, method: String, code: String): EventParams =
        make(
            EventType.BRIDGE_ERROR,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf(
                "method" to JsonPrimitive(method),
                "code" to JsonPrimitive(code),
            ),
        )

    fun flowFallback(flowId: String, sessionId: String, seq: Int, reason: String): EventParams =
        make(
            EventType.FLOW_FALLBACK,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf("reason" to JsonPrimitive(reason)),
        )

    /**
     * @param source `"bundled"` (host-supplied or embedded bundle) or
     *   `"remote"` (a promoted remote bundle, per [studio.aldric.weir.persistence.WeirBundleResolution]).
     * @param version the resolved bundle's monotonic version (per
     *   `WeirBundleResolution.resolve`'s `version`), when known. Optional and
     *   backward-compatible: omitted entirely (not sent as `null`) when
     *   `null`, so older servers that don't know this field still see
     *   exactly the payload shape they always have.
     */
    fun bundleSource(flowId: String, sessionId: String, seq: Int, source: String, version: Int? = null): EventParams {
        val fields = buildMap<String, JsonElement> {
            put("source", JsonPrimitive(source))
            if (version != null) put("version", JsonPrimitive(version))
        }
        return make(
            EventType.BUNDLE_SOURCE,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = fields,
        )
    }

    /**
     * Native queue-flush telemetry, emitted by `EventQueue.triggerFlush` after
     * every real flush attempt (success/failure + how many envelopes were
     * attempted, and the HTTP status when there was one). Rides under
     * [QUEUE_SESSION_FLOW_ID], not any product flow. Mirrors iOS
     * `HealthEvent.queueFlushResult` field shape (`ok`, `count`, `httpStatus`).
     */
    fun queueFlushResult(
        sessionId: String,
        seq: Int,
        ok: Boolean,
        count: Int,
        httpStatus: Int?,
        accepted: Int? = null,
        duplicates: Int? = null,
        rejected: Int? = null,
        failureReason: String? = null,
        enqueuedTotal: Int,
        uploadedTotal: Int,
        evictedTotal: Int,
        queueDepth: Int,
        oldestQueuedAgeMs: Long,
        consecutiveFailures: Int,
    ): EventParams {
        val fields = buildMap<String, JsonElement> {
            put("ok", JsonPrimitive(ok))
            put("count", JsonPrimitive(count))
            if (httpStatus != null) put("httpStatus", JsonPrimitive(httpStatus))
            if (accepted != null) put("accepted", JsonPrimitive(accepted))
            if (duplicates != null) put("duplicates", JsonPrimitive(duplicates))
            if (rejected != null) put("rejected", JsonPrimitive(rejected))
            if (failureReason != null) put("reason", JsonPrimitive(failureReason))
            put("enqueuedTotal", JsonPrimitive(enqueuedTotal))
            put("uploadedTotal", JsonPrimitive(uploadedTotal))
            put("evictedTotal", JsonPrimitive(evictedTotal))
            put("queueDepth", JsonPrimitive(queueDepth))
            put("oldestQueuedAgeMs", JsonPrimitive(oldestQueuedAgeMs))
            put("consecutiveFailures", JsonPrimitive(consecutiveFailures))
        }
        return make(
            EventType.QUEUE_FLUSH_RESULT,
            flowId = QUEUE_SESSION_FLOW_ID,
            sessionId = sessionId,
            seq = seq,
            fields = fields,
        )
    }

    fun sdkHeartbeat(
        sessionId: String,
        seq: Int,
        sdkVersion: String,
        appVersion: String,
        configId: String,
        configVersion: Int,
        queueDepth: Int,
    ): EventParams = make(
        EventType.SDK_HEARTBEAT,
        flowId = QUEUE_SESSION_FLOW_ID,
        sessionId = sessionId,
        seq = seq,
        fields = mapOf(
            "sdkVersion" to JsonPrimitive(sdkVersion),
            "appVersion" to JsonPrimitive(appVersion),
            "configId" to JsonPrimitive(configId),
            "configVersion" to JsonPrimitive(configVersion),
            "queueDepth" to JsonPrimitive(queueDepth),
        ),
    )

    // ---- SDK-owned: manifest fetch / remote bundle lifecycle ----
    // Emitted from the real [studio.aldric.weir.persistence.BundleManager] call
    // sites (fetch, verify, stage, promote), always under
    // [UPDATE_SESSION_FLOW_ID]. Mirrors iOS `HealthEvent.manifestFetchStarted`
    // etc.

    /**
     * Before the update-check HTTP request. [url] must already be reduced to
     * origin + path (no query string, no credentials) — callers build it with
     * `BundleManager`'s own URL-scrubbing helper, never the raw configured URL.
     */
    fun manifestFetchStarted(flowId: String, sessionId: String, seq: Int, url: String): EventParams =
        make(
            EventType.MANIFEST_FETCH_STARTED,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf("url" to JsonPrimitive(url)),
        )

    /**
     * The manifest request failed transport-level or returned a non-2xx.
     * [httpStatus] is `0` when there was no response at all (timeout, DNS,
     * connection refused). [reason] is a short safe code (e.g. `"timeout"`,
     * `"network_error"`, `"http_error"`) — never the raw exception message,
     * which can embed the request URL.
     */
    fun manifestFetchFailed(flowId: String, sessionId: String, seq: Int, httpStatus: Int, reason: String): EventParams =
        make(
            EventType.MANIFEST_FETCH_FAILED,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf(
                "httpStatus" to JsonPrimitive(httpStatus),
                "reason" to JsonPrimitive(reason),
            ),
        )

    /**
     * A successfully-fetched manifest failed verification. [reason] is always
     * one of [ManifestRejectionReason] — never the signature, key, or raw
     * manifest payload.
     */
    fun manifestRejected(flowId: String, sessionId: String, seq: Int, reason: ManifestRejectionReason): EventParams =
        make(
            EventType.MANIFEST_REJECTED,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf("reason" to JsonPrimitive(reason.raw)),
        )

    /** A verified bundle finished staging for the next launch/present boundary. */
    fun remoteStaged(flowId: String, sessionId: String, seq: Int, version: Int, bundleId: String): EventParams =
        make(
            EventType.REMOTE_STAGED,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf(
                "version" to JsonPrimitive(version),
                "bundleId" to JsonPrimitive(bundleId),
            ),
        )

    /** A staged bundle was promoted and is now the active bundle. */
    fun remotePromoted(flowId: String, sessionId: String, seq: Int, version: Int, bundleId: String): EventParams =
        make(
            EventType.REMOTE_PROMOTED,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf(
                "version" to JsonPrimitive(version),
                "bundleId" to JsonPrimitive(bundleId),
            ),
        )

    // ---- App-owned: host-reported diagnostics ----
    // The SDK never sees the host's feature flag or bucketing decision itself —
    // these builders exist so the host adapter (via `Weir.reportFlagOff` /
    // `reportFlagFetchFailed` / `reportNativeBucket`) can still emit the exact
    // same machine-readable shape as the SDK-owned types above. Mirrors iOS
    // `HealthEvent.flagOff` etc.

    /** The host app's feature flag resolved off. [flagSource] is a short label
     *  for where the flag came from, e.g. `"remote-config"`, `"firestore"`,
     *  `"build-settings"` — never the flag's own key/value. */
    fun flagOff(flowId: String, sessionId: String, seq: Int, flagSource: String): EventParams =
        make(
            EventType.FLAG_OFF,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf("flagSource" to JsonPrimitive(flagSource)),
        )

    /** The host app couldn't read its feature flag and fell back. [reason] is a
     *  short safe code, not a raw exception message. */
    fun flagFetchFailed(flowId: String, sessionId: String, seq: Int, flagSource: String, reason: String): EventParams =
        make(
            EventType.FLAG_FETCH_FAILED,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf(
                "flagSource" to JsonPrimitive(flagSource),
                "reason" to JsonPrimitive(reason),
            ),
        )

    /** The host app's own bucketing sent this user to the native arm. */
    fun nativeBucket(flowId: String, sessionId: String, seq: Int, arm: String): EventParams =
        make(
            EventType.NATIVE_BUCKET,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf("arm" to JsonPrimitive(arm)),
        )
}

/**
 * Thread-safe monotonic counter for `seq` within one health `sessionId` — the
 * native-emitted health-event counter, distinct from the JS runtime's per-flow
 * seq. Mirrors the Swift `HealthSeq`.
 */
class HealthSeq {
    private val value = AtomicInteger(0)
    fun next(): Int = value.getAndIncrement()
}
