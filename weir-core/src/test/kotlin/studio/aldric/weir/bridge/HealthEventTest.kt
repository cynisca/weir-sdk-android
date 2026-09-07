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
    fun queueFlushResultAndHeartbeatCarryFrozenTelemetryFields() {
        val flush = HealthEvent.queueFlushResult(
            sessionId = "s1", seq = 0, ok = false, count = 2, httpStatus = 500,
            enqueuedTotal = 10, uploadedTotal = 8, evictedTotal = 1,
            queueDepth = 2, oldestQueuedAgeMs = 500, consecutiveFailures = 3,
        ).payload.jsonObject
        assertEquals(10, flush.getValue("enqueuedTotal").jsonPrimitive.content.toInt())
        assertEquals(8, flush.getValue("uploadedTotal").jsonPrimitive.content.toInt())
        assertEquals(1, flush.getValue("evictedTotal").jsonPrimitive.content.toInt())
        assertEquals(500L, flush.getValue("oldestQueuedAgeMs").jsonPrimitive.content.toLong())
        assertEquals(3, flush.getValue("consecutiveFailures").jsonPrimitive.content.toInt())

        val heartbeat = HealthEvent.sdkHeartbeat("s1", 1, "1.0.0", "4.2", "onboarding-v7", 7, 12).payload.jsonObject
        assertEquals(setOf("type", "sdkVersion", "appVersion", "configId", "configVersion", "queueDepth"), heartbeat.keys)
        assertEquals("health_sdk_heartbeat", heartbeat.getValue("type").jsonPrimitive.content)
    }

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

    // ---- Phase 3 structured diagnostics: SDK-owned manifest/remote types ----

    @Test
    fun manifestFetchStartedCarriesTypeAndUrl() {
        val event = HealthEvent.manifestFetchStarted(
            flowId = HealthEvent.UPDATE_SESSION_FLOW_ID, sessionId = "s1", seq = 0,
            url = "https://cdn.example.com/manifest.json",
        )
        val fields = event.payload.jsonObject
        assertEquals("health_manifest_fetch_started", fields.getValue("type").jsonPrimitive.content)
        assertEquals("https://cdn.example.com/manifest.json", fields.getValue("url").jsonPrimitive.content)
        assertEquals(HealthEvent.UPDATE_SESSION_FLOW_ID, event.flowId)
    }

    @Test
    fun manifestFetchFailedCarriesHttpStatusAndReason() {
        val event = HealthEvent.manifestFetchFailed(
            flowId = HealthEvent.UPDATE_SESSION_FLOW_ID, sessionId = "s1", seq = 0,
            httpStatus = 503, reason = "http_error",
        )
        val fields = event.payload.jsonObject
        assertEquals("health_manifest_fetch_failed", fields.getValue("type").jsonPrimitive.content)
        assertEquals(503, fields.getValue("httpStatus").jsonPrimitive.content.toInt())
        assertEquals("http_error", fields.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun manifestFetchFailedAllowsZeroHttpStatus_whenNoResponseAtAll() {
        val event = HealthEvent.manifestFetchFailed(
            flowId = HealthEvent.UPDATE_SESSION_FLOW_ID, sessionId = "s1", seq = 0,
            httpStatus = 0, reason = "timeout",
        )
        assertEquals(0, event.payload.jsonObject.getValue("httpStatus").jsonPrimitive.content.toInt())
    }

    @Test
    fun manifestRejectedCarriesOnlyTheClosedSetReasonCode() {
        for (reason in HealthEvent.ManifestRejectionReason.entries) {
            val event = HealthEvent.manifestRejected(
                flowId = HealthEvent.UPDATE_SESSION_FLOW_ID, sessionId = "s1", seq = 0, reason = reason,
            )
            val fields = event.payload.jsonObject
            assertEquals("health_manifest_rejected", fields.getValue("type").jsonPrimitive.content)
            assertEquals(reason.raw, fields.getValue("reason").jsonPrimitive.content)
            // Only `type` and `reason` — no room for a signature/key/payload field to sneak in.
            assertEquals(setOf("type", "reason"), fields.keys)
        }
    }

    @Test
    fun remoteStagedCarriesVersionAndBundleId() {
        val event = HealthEvent.remoteStaged(
            flowId = HealthEvent.UPDATE_SESSION_FLOW_ID, sessionId = "s1", seq = 0,
            version = 5, bundleId = "2026-07-29-fixture",
        )
        val fields = event.payload.jsonObject
        assertEquals("health_remote_staged", fields.getValue("type").jsonPrimitive.content)
        assertEquals(5, fields.getValue("version").jsonPrimitive.content.toInt())
        assertEquals("2026-07-29-fixture", fields.getValue("bundleId").jsonPrimitive.content)
    }

    @Test
    fun remotePromotedCarriesVersionAndBundleId() {
        val event = HealthEvent.remotePromoted(
            flowId = HealthEvent.UPDATE_SESSION_FLOW_ID, sessionId = "s1", seq = 0,
            version = 5, bundleId = "2026-07-29-fixture",
        )
        val fields = event.payload.jsonObject
        assertEquals("health_remote_promoted", fields.getValue("type").jsonPrimitive.content)
        assertEquals(5, fields.getValue("version").jsonPrimitive.content.toInt())
        assertEquals("2026-07-29-fixture", fields.getValue("bundleId").jsonPrimitive.content)
    }

    // ---- Phase 3 structured diagnostics: app-owned types ----

    @Test
    fun flagOffCarriesFlagSource() {
        val event = HealthEvent.flagOff(
            flowId = "cob_intake", sessionId = "s1", seq = 0, flagSource = "remote-config",
        )
        val fields = event.payload.jsonObject
        assertEquals("health_flag_off", fields.getValue("type").jsonPrimitive.content)
        assertEquals("remote-config", fields.getValue("flagSource").jsonPrimitive.content)
        assertEquals("cob_intake", event.flowId)
    }

    @Test
    fun flagFetchFailedCarriesFlagSourceAndReason() {
        val event = HealthEvent.flagFetchFailed(
            flowId = "cob_intake", sessionId = "s1", seq = 0,
            flagSource = "firestore", reason = "read_timeout",
        )
        val fields = event.payload.jsonObject
        assertEquals("health_flag_fetch_failed", fields.getValue("type").jsonPrimitive.content)
        assertEquals("firestore", fields.getValue("flagSource").jsonPrimitive.content)
        assertEquals("read_timeout", fields.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun nativeBucketCarriesArm() {
        val event = HealthEvent.nativeBucket(
            flowId = "cob_intake", sessionId = "s1", seq = 0, arm = "native_control",
        )
        val fields = event.payload.jsonObject
        assertEquals("health_native_bucket", fields.getValue("type").jsonPrimitive.content)
        assertEquals("native_control", fields.getValue("arm").jsonPrimitive.content)
    }

    @Test
    fun updateAndDiagnosticSessionFlowIds_areDistinctFromEachOtherAndFromQueue() {
        val ids = setOf(
            HealthEvent.QUEUE_SESSION_FLOW_ID,
            HealthEvent.UPDATE_SESSION_FLOW_ID,
            "cob_intake",
        )
        assertEquals(3, ids.size)
    }
}
