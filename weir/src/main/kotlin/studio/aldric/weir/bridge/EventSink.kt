package studio.aldric.weir.bridge

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives decoded `event` bridge calls for durable storage.
 *
 * Phase 2 owns the concrete file-backed offline queue and its flush policy
 * (see BRIDGE.md's "native-side responsibilities" for `event`). [BridgeRouter]
 * only needs this seam so it can hand off decoded events without depending on
 * that persistence implementation. Faithful port of
 * `sdk-ios/Sources/Weir/Bridge/EventSink.swift`.
 */
interface EventSink {
    fun append(event: EventParams)
}

/**
 * In-memory sink — captures appended events in order for tests and for a host
 * that just wants to observe the stream. Thread-safe (events arrive on the
 * router's dispatch path plus native health emissions).
 */
class InMemoryEventSink : EventSink {
    private val backing = CopyOnWriteArrayList<EventParams>()

    val events: List<EventParams> get() = backing.toList()

    override fun append(event: EventParams) {
        backing.add(event)
    }
}

/**
 * Logging sink — writes each event's flowId/seq to logcat. A trivial default
 * for early integration before the Phase 2 queue exists.
 */
class LoggingEventSink(private val tag: String = "WeirEvent") : EventSink {
    override fun append(event: EventParams) {
        Log.d(tag, "event flowId=${event.flowId} seq=${event.seq} session=${event.sessionId}")
    }
}
