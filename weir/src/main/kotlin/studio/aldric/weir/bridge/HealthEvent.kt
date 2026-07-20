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

    enum class EventType(val raw: String) {
        BRIDGE_ERROR("health_bridge_error"),
        FLOW_RENDER_MS("health_flow_render_ms"),
        FLOW_FALLBACK("health_flow_fallback"),
        BUNDLE_SOURCE("health_bundle_source"),
        QUEUE_FLUSH_RESULT("health_queue_flush_result"),
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

    fun bundleSource(flowId: String, sessionId: String, seq: Int, source: String): EventParams =
        make(
            EventType.BUNDLE_SOURCE,
            flowId = flowId,
            sessionId = sessionId,
            seq = seq,
            fields = mapOf("source" to JsonPrimitive(source)),
        )

    /**
     * Native queue-flush telemetry, emitted by `EventQueue.triggerFlush` after
     * every real flush attempt (success/failure + how many envelopes were
     * attempted, and the HTTP status when there was one). Rides under
     * [QUEUE_SESSION_FLOW_ID], not any product flow. Mirrors iOS
     * `HealthEvent.queueFlushResult` field shape (`ok`, `count`, `httpStatus`).
     */
    fun queueFlushResult(sessionId: String, seq: Int, ok: Boolean, count: Int, httpStatus: Int?): EventParams {
        val fields = buildMap<String, JsonElement> {
            put("ok", JsonPrimitive(ok))
            put("count", JsonPrimitive(count))
            if (httpStatus != null) put("httpStatus", JsonPrimitive(httpStatus))
        }
        return make(
            EventType.QUEUE_FLUSH_RESULT,
            flowId = QUEUE_SESSION_FLOW_ID,
            sessionId = sessionId,
            seq = seq,
            fields = fields,
        )
    }
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
