package studio.aldric.weir.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** Shared JSON codec for renderer bridge messages and durable event envelopes. */
val WeirJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** The event envelope persisted by [studio.aldric.weir.persistence.EventQueue]. */
@Serializable
data class EventParams(
    val flowId: String,
    val screenId: String? = null,
    val variantId: String? = null,
    val sessionId: String,
    val userId: String? = null,
    val ts: Double,
    val seq: Int,
    val payload: JsonElement,
    val screenDwellMs: Double? = null,
    val configId: String? = null,
    val configVersion: Int? = null,
    /** RFC-015 idempotency key. Default preserves legacy renderer call sites. */
    val eventId: String = UUID.randomUUID().toString(),
    /** RFC-015 process-launch identity; absent for legacy renderer events. */
    val launchId: String? = null,
    /** Milliseconds since flow_started; absent for legacy renderer events. */
    val elapsedMs: Double? = null,
)
