package studio.aldric.weir.bridge

/**
 * Fans a single [append] out to every wrapped [EventSink], in order.
 *
 * Faithful port of `sdk-ios/Sources/Weir/Bridge/CompositeEventSink.swift`:
 * lets a host compose any number of sinks (e.g. the Phase 2 `EventQueue` plus
 * its own analytics forwarder) into one value passed to `Weir.present`.
 */
class CompositeEventSink(private val sinks: List<EventSink>) : EventSink {

    /** Vararg convenience mirroring the Swift `init(_ sinks: EventSink...)`. */
    constructor(vararg sinks: EventSink) : this(sinks.toList())

    /** Order is preserved: each sink's [append] runs in the order passed here,
     *  synchronously, before returning. */
    override fun append(event: EventParams) {
        for (sink in sinks) {
            sink.append(event)
        }
    }
}

/**
 * Composes `this` with [other] into a [CompositeEventSink] forwarding to both,
 * in that order — mirrors the Swift `EventSink.chained(with:)` extension.
 */
fun EventSink.chained(other: EventSink): CompositeEventSink =
    CompositeEventSink(listOf(this, other))
