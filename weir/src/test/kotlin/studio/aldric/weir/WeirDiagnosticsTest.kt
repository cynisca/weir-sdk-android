package studio.aldric.weir

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.aldric.weir.bridge.HealthEvent
import studio.aldric.weir.bridge.InMemoryEventSink
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 3 structured diagnostics — the app-owned trio
 * ([Weir.reportFlagOff], [Weir.reportFlagFetchFailed],
 * [Weir.reportNativeBucket]). The SDK never sees the host's own feature-flag
 * or bucketing decision; these entry points exist so a host adapter can still
 * emit the same machine-readable `health_flag_off`/`health_flag_fetch_failed`/
 * `health_native_bucket` shape the SDK-owned types use, without the caller
 * having to manage its own health sessionId/seq.
 */
class WeirDiagnosticsTest {

    @Test
    fun reportFlagOff_appendsHealthFlagOff_underTheHostsRealFlowId() {
        val sink = InMemoryEventSink()
        Weir.reportFlagOff(sink, flowId = "cob_intake", flagSource = "remote-config")

        val event = sink.events.last()
        assertEquals("cob_intake", event.flowId)
        val fields = event.payload.jsonObject
        assertEquals("health_flag_off", fields.getValue("type").jsonPrimitive.content)
        assertEquals("remote-config", fields.getValue("flagSource").jsonPrimitive.content)
    }

    @Test
    fun reportFlagFetchFailed_appendsHealthFlagFetchFailed() {
        val sink = InMemoryEventSink()
        Weir.reportFlagFetchFailed(sink, flowId = "cob_intake", flagSource = "firestore", reason = "read_timeout")

        val event = sink.events.last()
        assertEquals("cob_intake", event.flowId)
        val fields = event.payload.jsonObject
        assertEquals("health_flag_fetch_failed", fields.getValue("type").jsonPrimitive.content)
        assertEquals("firestore", fields.getValue("flagSource").jsonPrimitive.content)
        assertEquals("read_timeout", fields.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun reportNativeBucket_appendsHealthNativeBucket() {
        val sink = InMemoryEventSink()
        Weir.reportNativeBucket(sink, flowId = "cob_intake", arm = "native_control")

        val event = sink.events.last()
        assertEquals("cob_intake", event.flowId)
        val fields = event.payload.jsonObject
        assertEquals("health_native_bucket", fields.getValue("type").jsonPrimitive.content)
        assertEquals("native_control", fields.getValue("arm").jsonPrimitive.content)
    }

    @Test
    fun successiveDiagnosticReports_getIncreasingSeq_sameSession() {
        val sink = InMemoryEventSink()
        Weir.reportFlagOff(sink, flowId = "cob_intake", flagSource = "remote-config")
        Weir.reportNativeBucket(sink, flowId = "cob_intake", arm = "native_control")

        val (first, second) = sink.events.takeLast(2)
        assertEquals(first.sessionId, second.sessionId)
        assertEquals(first.seq + 1, second.seq)
    }
}
