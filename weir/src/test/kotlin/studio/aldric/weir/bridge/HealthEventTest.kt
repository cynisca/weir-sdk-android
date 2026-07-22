package studio.aldric.weir.bridge

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [HealthEvent.bundleSource]'s optional `version` field —
 * added so `getHealth`/adoption reads can break the `remote` tier down by
 * the resolved bundle's monotonic version, not just its tier. Backward
 * compatibility for callers that don't pass a version (older resolution
 * paths, the `bundled` tier) is the point: the field must be entirely
 * absent from the payload, not sent as an explicit `null`. Mirrors
 * `sdk-ios/Tests/WeirTests/HealthEventTests.swift`.
 */
class HealthEventTest {

    @Test
    fun bundleSourceOmitsVersionFieldEntirelyWhenNull() {
        val event = HealthEvent.bundleSource(flowId = "your_path", sessionId = "s1", seq = 0, source = "bundled")
        val fields = event.payload.jsonObject
        assertEquals("bundled", fields.getValue("source").jsonPrimitive.content)
        assertFalse("no version passed -> key must be absent, not null", fields.containsKey("version"))
    }

    @Test
    fun bundleSourceIncludesVersionWhenProvided() {
        val event = HealthEvent.bundleSource(flowId = "your_path", sessionId = "s1", seq = 0, source = "remote", version = 3)
        val fields = event.payload.jsonObject
        assertEquals("remote", fields.getValue("source").jsonPrimitive.content)
        assertTrue(fields.containsKey("version"))
        assertEquals(3, fields.getValue("version").jsonPrimitive.content.toInt())
    }
}
