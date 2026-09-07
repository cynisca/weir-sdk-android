package studio.aldric.weir.bridge

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventEnvelopeTest {
    @Test
    fun legacyEventWithoutNewFieldsStillDecodes() {
        val params = WeirJson.decodeFromString(
            EventParams.serializer(),
            """{"flowId":"f","sessionId":"s","ts":1000,"seq":0,"payload":{}}""",
        )
        assertNull(params.userId)
        assertNull(params.launchId)
        assertNull(params.elapsedMs)
    }

    @Test
    fun userIdAndObserveFieldsRoundTrip() {
        val event = EventParams(
            flowId = "f",
            sessionId = "s",
            userId = "user-42",
            ts = 1000.0,
            seq = 0,
            payload = buildJsonObject {},
            launchId = "launch",
            elapsedMs = 12.0,
        )
        val decoded = WeirJson.decodeFromString(
            EventParams.serializer(),
            WeirJson.encodeToString(EventParams.serializer(), event),
        )
        assertEquals("user-42", decoded.userId)
        assertEquals("launch", decoded.launchId)
        assertEquals(12.0, decoded.elapsedMs!!, 0.0)
        assertNotEquals("", decoded.eventId)
    }
}
