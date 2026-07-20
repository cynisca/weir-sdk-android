package studio.aldric.weir.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.aldric.weir.persistence.EventQueue
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ports the intent of `sdk-ios/Tests/WeirTests/CompositeEventSinkTests.swift`. */
class CompositeEventSinkTest {

    private class RecordingSink : EventSink {
        val received: MutableList<EventParams> = mutableListOf()
        override fun append(event: EventParams) {
            received.add(event)
        }
    }

    private fun event(seq: Int): EventParams =
        EventParams(
            flowId = "flow-a",
            screenId = "screen-$seq",
            variantId = null,
            sessionId = "session-1",
            userId = null,
            ts = seq.toDouble(),
            seq = seq,
            payload = JsonObject(mapOf("n" to JsonPrimitive(seq))),
        )

    @Test
    fun appendFansOutToEverySink() {
        val a = RecordingSink()
        val b = RecordingSink()
        CompositeEventSink(listOf(a, b)).append(event(seq = 1))

        assertEquals(1, a.received.size)
        assertEquals(1, b.received.size)
        assertEquals(1, a.received.first().seq)
        assertEquals(1, b.received.first().seq)
    }

    @Test
    fun appendPreservesSinkOrder() {
        val order = mutableListOf<String>()
        class BoxedSink(val name: String) : EventSink {
            override fun append(event: EventParams) {
                order.add(name)
            }
        }
        CompositeEventSink(listOf(BoxedSink("first"), BoxedSink("second"))).append(event(seq = 1))
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun varargInitializerMatchesListInitializer() {
        val a = RecordingSink()
        val b = RecordingSink()
        CompositeEventSink(a, b).append(event(seq = 1))
        assertEquals(1, a.received.size)
        assertEquals(1, b.received.size)
    }

    @Test
    fun chainedComposesTwoSinks() {
        val a = RecordingSink()
        val b = RecordingSink()
        a.chained(b).append(event(seq = 1))
        assertEquals(1, a.received.size)
        assertEquals(1, b.received.size)
    }

    @Test
    fun chainedComposesWithEventQueue() {
        val dir: File = Files.createTempDirectory("CompositeEventSinkTest-").toFile()
        val queue = EventQueue(dir)
        val forwarder = RecordingSink()

        forwarder.chained(queue).append(event(seq = 1))

        assertEquals(1, forwarder.received.size)
        // EventQueue conforms to EventSink via append -> enqueue; its own tests
        // cover on-disk durability, so here it's enough that composing it
        // through the chain delivered to both.
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun emptyCompositeIsANoop() {
        CompositeEventSink(emptyList()).append(event(seq = 1)) // must not crash
    }
}
