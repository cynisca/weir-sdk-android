package studio.aldric.weir.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.HealthEvent
import studio.aldric.weir.bridge.WeirJson
import java.io.File
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports the intent of `sdk-ios/Tests/WeirTests/EventQueueTests.swift`. No
 * Robolectric needed: [EventQueue] takes an injectable directory, so these run
 * as plain JVM unit tests against real temp dirs. The detached flush coroutines
 * are pinned to [Dispatchers.Unconfined] so an ingest attempt with a
 * non-suspending [FakeUploader] runs synchronously within [triggerFlush] —
 * deterministic, no sleeps.
 */
class EventQueueTest {

    private fun tempDir(): File =
        Files.createTempDirectory("EventQueueTest-").toFile()

    private fun unconfinedScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    private fun event(seq: Int, flowId: String = "flow-a", userId: String? = null): EventParams =
        EventParams(
            flowId = flowId,
            screenId = "screen-$seq",
            variantId = null,
            sessionId = "session-1",
            userId = userId,
            ts = seq.toDouble(),
            seq = seq,
            payload = JsonObject(mapOf("n" to JsonPrimitive(seq))),
        )

    private val config = WeirIngestConfig(
        endpointURL = URL("https://example.invalid/ingest"),
        appId = "app1",
        writeToken = "tok",
    )

    private fun healthEvents(events: List<EventParams>): List<EventParams> = events.filter {
        it.payload.jsonObject["type"]?.jsonPrimitive?.content == "health_queue_flush_result"
    }

    private class FakeForegroundTrigger : ForegroundFlushTrigger {
        private var callback: (() -> Unit)? = null
        override fun register(onForeground: () -> Unit) { callback = onForeground }
        override fun unregister() { callback = null }
        fun foreground() { callback?.invoke() }
    }

    /** Canned-outcome fake uploader, mirroring the iOS `FakeUploader`. */
    private class FakeUploader(var outcomes: MutableList<EventUploadOutcome> = mutableListOf(EventUploadOutcome(true))) :
        EventIngestUploading {
        val uploadedBatches: MutableList<List<EventParams>> = mutableListOf()

        override suspend fun upload(events: List<EventParams>, config: WeirIngestConfig): EventUploadOutcome {
            uploadedBatches.add(events)
            if (outcomes.isEmpty()) return EventUploadOutcome(true)
            return if (outcomes.size > 1) outcomes.removeAt(0) else outcomes[0]
        }
    }

    // ---- userId round-trip ----

    @Test
    fun userIdRoundTripsThroughDiskReload() {
        val dir = tempDir()
        EventQueue(dir).enqueue(event(seq = 0, userId = "user-42"))

        val reloaded = EventQueue(dir)
        var flushedUserIds: List<String?> = emptyList()
        runBlocking {
            reloaded.flush { batch ->
                flushedUserIds = batch.map { it.userId }
                true
            }
        }
        assertEquals(listOf("user-42"), flushedUserIds)
    }

    @Test
    fun eventWithoutUserIdStillRoundTripsWithNullUserId() {
        val dir = tempDir()
        EventQueue(dir).enqueue(event(seq = 0, userId = null))

        val reloaded = EventQueue(dir)
        var flushedUserIds: List<String?> = emptyList()
        runBlocking {
            reloaded.flush { batch ->
                flushedUserIds = batch.map { it.userId }
                true
            }
        }
        assertEquals(listOf<String?>(null), flushedUserIds)
    }

    @Test
    fun userIdReachesTheIngestUploadBatch() {
        val dir = tempDir()
        val queue = EventQueue(dir, scope = unconfinedScope())
        queue.enqueue(event(seq = 0, userId = "user-42"))

        val uploader = FakeUploader(mutableListOf(EventUploadOutcome(true)))
        queue.configureIngest(config, uploader = uploader, flushIntervalMillis = 3_600_000)

        assertEquals(listOf("user-42"), uploader.uploadedBatches.last().map { it.userId })
    }

    // ---- Durability / ordering / rehydration (the critical regression) ----

    @Test
    fun enqueuePersistsToDiskImmediately() {
        val dir = tempDir()
        EventQueue(dir).enqueue(event(seq = 1))

        val contents = File(dir, "event_queue.jsonl").readText()
        assertEquals(1, contents.split('\n').filter { it.isNotBlank() }.size)
    }

    @Test
    fun orderIsPreservedAcrossEnqueues() {
        val dir = tempDir()
        val queue = EventQueue(dir)
        for (i in 0 until 5) queue.enqueue(event(seq = i))

        val lines = File(dir, "event_queue.jsonl").readText().split('\n').filter { it.isNotBlank() }
        val decoded = lines.map { WeirJson.decodeFromString(EventParams.serializer(), it) }
        assertEquals(listOf(0, 1, 2, 3, 4), decoded.map { it.seq })
    }

    @Test
    fun reloadsUnflushedEventsAfterRelaunch() {
        val dir = tempDir()
        run {
            val queue = EventQueue(dir)
            queue.enqueue(event(seq = 0))
            queue.enqueue(event(seq = 1))
        }

        // Simulated process restart: a fresh EventQueue over the same dir must
        // rehydrate the pending events (the iOS OTA/queue regression class).
        val reloaded = EventQueue(dir)
        assertEquals(2, reloaded.pendingCount)
    }

    @Test
    fun flushRemovesEventsOnSuccess() {
        val dir = tempDir()
        val queue = EventQueue(dir)
        queue.enqueue(event(seq = 0))
        queue.enqueue(event(seq = 1))

        var uploaded: List<EventParams> = emptyList()
        runBlocking {
            queue.flush { batch ->
                uploaded = batch
                true
            }
        }

        assertEquals(listOf(0, 1), uploaded.map { it.seq })
        assertEquals(0, queue.pendingCount)
        assertTrue(File(dir, "event_queue.jsonl").readText().isEmpty())
    }

    @Test
    fun flushRetainsEventsOnFailure() {
        val dir = tempDir()
        val queue = EventQueue(dir)
        queue.enqueue(event(seq = 0))
        queue.enqueue(event(seq = 1))

        runBlocking { queue.flush { false } }

        assertEquals(2, queue.pendingCount)
        // Also durable on disk, not just in memory: a fresh queue still sees both.
        assertEquals(2, EventQueue(dir).pendingCount)
    }

    @Test
    fun eventsEnqueuedDuringFlushAreNotLost() {
        val dir = tempDir()
        val queue = EventQueue(dir)
        queue.enqueue(event(seq = 0))

        runBlocking {
            queue.flush { _ ->
                // Simulate another event arriving while the upload is in flight.
                queue.enqueue(event(seq = 1))
                true
            }
        }

        // The flush should only have removed the batch it captured (seq 0);
        // seq 1 (enqueued mid-flush) must survive.
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun multipleFlowsAccumulateBetweenFlushes() {
        val dir = tempDir()
        val queue = EventQueue(dir)
        queue.enqueue(event(seq = 0, flowId = "flow-a"))
        queue.enqueue(event(seq = 0, flowId = "flow-b"))
        queue.enqueue(event(seq = 1, flowId = "flow-a"))

        var uploadedFlowIds: List<String> = emptyList()
        runBlocking {
            queue.flush { batch ->
                uploadedFlowIds = batch.map { it.flowId }
                true
            }
        }

        assertEquals(listOf("flow-a", "flow-b", "flow-a"), uploadedFlowIds)
        assertEquals(0, queue.pendingCount)
    }

    // ---- Retention ----

    @Test
    fun retentionEvictsEventsOlderThanMaxAge() {
        val dir = tempDir()
        var simulatedNow = System.currentTimeMillis()
        val queue = EventQueue(dir, now = { simulatedNow })

        queue.enqueue(event(seq = 0))
        simulatedNow += EventQueue.retentionMaxAgeMillis + 60_000
        queue.enqueue(event(seq = 1))

        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun retentionCapsAtMaxCount() {
        val dir = tempDir()
        val queue = EventQueue(dir)
        for (i in 0 until EventQueue.retentionMaxCount + 5) queue.enqueue(event(seq = i))
        assertEquals(EventQueue.retentionMaxCount, queue.pendingCount)
    }

    @Test
    fun retentionAppliedOnReload() {
        val dir = tempDir()
        var simulatedNow = System.currentTimeMillis()
        run {
            val queue = EventQueue(dir, now = { simulatedNow })
            queue.enqueue(event(seq = 0))
        }

        simulatedNow += EventQueue.retentionMaxAgeMillis + 60_000
        val reloaded = EventQueue(dir, now = { simulatedNow })
        assertEquals(0, reloaded.pendingCount)
    }

    @Test
    fun flushHealthReportsLaunchCumulativeEvictions() {
        val queue = EventQueue(tempDir(), scope = unconfinedScope(), maxRetentionCount = 3)
        for (i in 0 until 5) queue.enqueue(event(seq = i))
        val uploader = FakeUploader(mutableListOf(EventUploadOutcome(true, 200, accepted = 3, duplicates = 0, rejected = 0)))

        queue.configureIngest(config, uploader = uploader, flushIntervalMillis = 3_600_000)

        var health: EventParams? = null
        runBlocking { queue.flush { batch -> health = batch.single(); false } }
        val payload = health!!.payload.jsonObject
        assertEquals(2, payload.getValue("evictedTotal").jsonPrimitive.int)
        assertEquals(5, payload.getValue("enqueuedTotal").jsonPrimitive.int)
        assertEquals(3, payload.getValue("uploadedTotal").jsonPrimitive.int)
    }

    @Test
    fun failingUploaderReportsGrowingAgeFailuresAndTrueDeficit() {
        var simulatedNow = 1_000_000L
        val queue = EventQueue(tempDir(), now = { simulatedNow }, scope = unconfinedScope())
        repeat(2) { queue.enqueue(event(seq = it)) }
        val uploader = FakeUploader(mutableListOf(EventUploadOutcome(false, 500)))

        queue.configureIngest(config, uploader = uploader, flushIntervalMillis = 3_600_000)
        simulatedNow += 3_600_000
        queue.triggerFlush()

        var queued: List<EventParams> = emptyList()
        runBlocking { queue.flush { batch -> queued = batch; false } }
        val results = healthEvents(queued)
        assertEquals(2, results.size)
        val first = results[0].payload.jsonObject
        val latest = results[1].payload.jsonObject
        assertTrue(first.getValue("oldestQueuedAgeMs").jsonPrimitive.long < latest.getValue("oldestQueuedAgeMs").jsonPrimitive.long)
        assertEquals(1, first.getValue("consecutiveFailures").jsonPrimitive.int)
        assertEquals(2, latest.getValue("consecutiveFailures").jsonPrimitive.int)
        assertEquals(
            latest.getValue("queueDepth").jsonPrimitive.int,
            latest.getValue("enqueuedTotal").jsonPrimitive.int - latest.getValue("uploadedTotal").jsonPrimitive.int,
        )
    }

    @Test
    fun healthOnlyHeartbeatFlushDoesNotEmitFlushResult() {
        val queue = EventQueue(tempDir(), scope = unconfinedScope())
        queue.enqueue(
            HealthEvent.sdkHeartbeat("health", 0, "1.0.0", "2.0", "flow-v3", 3, 0),
        )
        val uploader = FakeUploader()

        queue.configureIngest(config, uploader = uploader, flushIntervalMillis = 3_600_000)

        assertEquals(1, uploader.uploadedBatches.size)
        assertEquals("health_sdk_heartbeat", uploader.uploadedBatches.single().single().payload.jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("a health-only flush must converge without another diagnostic", 0, queue.pendingCount)
    }

    @Test
    fun foregroundHeartbeatIsRateLimitedToOnePerHour() {
        var simulatedNow = 2_000_000L
        val queue = EventQueue(tempDir(), now = { simulatedNow }, scope = unconfinedScope())
        val uploader = FakeUploader()
        val foreground = FakeForegroundTrigger()
        val device = WeirDeviceContext("android", "4.2", "1.0.0", "en-US", "phone")
        val heartbeatConfig = config.copy(deviceContext = device)
        queue.configureIngest(heartbeatConfig, uploader, 3_600_000, foreground)

        foreground.foreground()
        foreground.foreground()
        assertEquals(1, uploader.uploadedBatches.size)
        val heartbeat = uploader.uploadedBatches.single().single().payload.jsonObject
        assertEquals("health_sdk_heartbeat", heartbeat.getValue("type").jsonPrimitive.content)
        assertEquals("4.2", heartbeat.getValue("appVersion").jsonPrimitive.content)

        simulatedNow += 3_600_001
        foreground.foreground()
        assertEquals(2, uploader.uploadedBatches.size)
    }

    // ---- Live ingest: retry / backoff / idempotency + health emission ----

    @Test
    fun ingestFlushDeliversAllEventsExactlyOnceAfterServerRecovers() {
        val dir = tempDir()
        var simulatedNow = System.currentTimeMillis()
        val queue = EventQueue(dir, now = { simulatedNow }, scope = unconfinedScope())
        for (i in 0 until 5) queue.enqueue(event(seq = i))

        val uploader = FakeUploader(mutableListOf(EventUploadOutcome(false, 500)))
        queue.configureIngest(config, uploader = uploader, flushIntervalMillis = 3_600_000)

        // A failed attempt retains the 5 product events AND enqueues one
        // flush-health event describing the failure (appended after the failed
        // batch was captured, so it's never part of that batch).
        assertEquals(6, queue.pendingCount)
        assertEquals(1, uploader.uploadedBatches.size)
        assertEquals((0 until 5).toSet(), uploader.uploadedBatches[0].map { it.seq }.toSet())

        // Server recovers; fast-forward past the backoff window before the next trigger.
        simulatedNow += 301_000
        uploader.outcomes = mutableListOf(EventUploadOutcome(true))
        queue.triggerFlush()

        // Steady state: exactly one flush-health event always trails one flush
        // behind (never accumulates).
        assertEquals(1, queue.pendingCount)
        assertEquals(2, uploader.uploadedBatches.size)
        val secondBatch = uploader.uploadedBatches[1]
        assertEquals(6, secondBatch.size)
        assertEquals(
            (0 until 5).toSet(),
            secondBatch.filter { it.flowId != HealthEvent.QUEUE_SESSION_FLOW_ID }.map { it.seq }.toSet(),
        )
        assertEquals(1, secondBatch.count { it.flowId == HealthEvent.QUEUE_SESSION_FLOW_ID })

        // A third, uneventful flush of just the trailing health event converges.
        queue.triggerFlush()
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun clientErrorStopsRetriesUntilIngestIsReconfigured() {
        val dir = tempDir()
        var simulatedNow = System.currentTimeMillis()
        val queue = EventQueue(dir, now = { simulatedNow }, scope = unconfinedScope())
        queue.enqueue(event(seq = 0))
        val uploader = FakeUploader(mutableListOf(EventUploadOutcome(false, 401, retryable = false)))

        queue.configureIngest(config, uploader = uploader, flushIntervalMillis = 3_600_000)
        assertEquals(1, uploader.uploadedBatches.size)

        simulatedNow += 301_000
        queue.triggerFlush()
        assertEquals("a rejected credential must not retry until configuration changes", 1, uploader.uploadedBatches.size)

        uploader.outcomes = mutableListOf(EventUploadOutcome(true, 200))
        queue.configureIngest(config, uploader = uploader, flushIntervalMillis = 3_600_000)
        assertEquals("a reconfiguration must re-enable delivery", 2, uploader.uploadedBatches.size)
    }

    @Test
    fun healthQueueFlushResultCarriesCorrectCounts() {
        val dir = tempDir()
        val queue = EventQueue(dir, scope = unconfinedScope())
        for (i in 0 until 3) queue.enqueue(event(seq = i))

        val uploader = FakeUploader(mutableListOf(EventUploadOutcome(true, 200)))
        queue.configureIngest(config, uploader = uploader, flushIntervalMillis = 3_600_000)

        // After a successful flush of 3 events, exactly one trailing health
        // event remains, describing ok=true count=3 httpStatus=200.
        assertEquals(1, queue.pendingCount)
        var health: EventParams? = null
        runBlocking { queue.flush { batch -> health = batch.single(); true } }

        val payload = health!!.payload.jsonObject
        assertEquals("health_queue_flush_result", payload["type"]!!.jsonPrimitive.content)
        assertTrue(payload["ok"]!!.jsonPrimitive.boolean)
        assertEquals(3, payload["count"]!!.jsonPrimitive.int)
        assertEquals(200, payload["httpStatus"]!!.jsonPrimitive.int)
        assertEquals(3, payload["enqueuedTotal"]!!.jsonPrimitive.int)
        assertEquals(3, payload["uploadedTotal"]!!.jsonPrimitive.int)
        assertEquals(0, payload["queueDepth"]!!.jsonPrimitive.int)
        assertEquals(0, payload["consecutiveFailures"]!!.jsonPrimitive.int)
        assertEquals(HealthEvent.QUEUE_SESSION_FLOW_ID, health!!.flowId)
    }

    @Test
    fun triggerFlushWithNoIngestConfiguredIsANoop() {
        val dir = tempDir()
        val queue = EventQueue(dir, scope = unconfinedScope())
        queue.enqueue(event(seq = 0))
        queue.triggerFlush() // no ingest configured: must be a no-op
        assertEquals(1, queue.pendingCount)
    }
}
