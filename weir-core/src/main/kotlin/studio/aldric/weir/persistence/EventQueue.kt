package studio.aldric.weir.persistence

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.HealthEvent
import studio.aldric.weir.bridge.HealthSeq
import studio.aldric.weir.bridge.WeirJson
import studio.aldric.weir.WeirSdk
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import kotlin.math.pow

/**
 * File-backed, ordered, offline-durable queue for bridge `event` payloads.
 * Faithful Kotlin port of `sdk-ios/Sources/Weir/Persistence/EventQueue.swift`.
 * Implements [EventSink] so it slots straight into `Weir.present(eventSink=)`
 * and can be tee'd via `CompositeEventSink` alongside a host's analytics sink.
 *
 * Durability choice: each [enqueue] synchronously appends one JSON-lines record
 * to disk and `fsync`s it (`FileDescriptor.sync()`) *before* returning, while
 * holding a serial lock. A process kill immediately after [enqueue] returns
 * cannot lose that event — the bytes are already durable on disk, not merely
 * buffered in memory or handed to an async task that may not have run yet.
 *
 * On-disk format (under `<context.filesDir>/weir/`):
 *  - `event_queue.jsonl` — one [EventParams] JSON object per line (NDJSON), in
 *    enqueue order. This is the exact wire shape a flush POSTs to ingest.
 *    Append-only during normal operation; [flush] rewrites it (atomically, temp
 *    file + `ATOMIC_MOVE`) to drop exactly the events that uploaded.
 *  - `event_queue_meta.jsonl` — index-aligned sidecar, one enqueue-time epoch
 *    **milliseconds** [Long] per line, used purely for age-based retention
 *    keyed on *enqueue* wall-clock time (not the event's untrusted JS-supplied
 *    `ts`). Device-local only, so it needn't be byte-identical to iOS's sidecar
 *    (iOS stores epoch seconds); the semantics — oldest-enqueued-first eviction
 *    — are identical.
 *
 * Thread-safety: all mutable state (the in-memory mirror of pending events) is
 * touched only under [lock]. [enqueue] holds it across the fsync so writes are
 * strictly ordered and durable-before-return; [flush] captures its batch under
 * the lock, runs the (suspending) upload *without* the lock, then re-takes it
 * to remove exactly what uploaded — so events enqueued mid-flush are simply
 * left for the next flush and can never be lost.
 */
class EventQueue(
    directory: File,
    /** Injectable wall clock (epoch millis). Tests drive age-based retention
     *  and backoff without a real multi-day/second sleep; production uses the
     *  default. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Scope the detached flush/timer coroutines run on. Injectable so tests
     *  can pin them to a deterministic dispatcher. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxRetentionCount: Int = retentionMaxCount,
) : EventSink {

    companion object {
        /** Retention bound (docs/instrumentation-workstream.md §1): 7 days. */
        val retentionMaxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000

        /** Retention bound: 10,000 events. Enforced oldest-first on both
         *  [enqueue] and load-at-launch. */
        const val retentionMaxCount: Int = 10_000

        /** Matches `MAX_EVENTS_PER_REQUEST` in `services/api/src/app.ts`: a
         *  single `/events` POST is bounded, so a queue backed up past this
         *  after offline downtime is split into several bounded requests by
         *  [flush] rather than retried forever as one oversized 413'ing batch. */
        const val maxEventsPerUploadRequest: Int = 500

        /** Default on-disk location: `<context.filesDir>/weir/`. */
        fun defaultDirectory(context: Context): File = File(context.filesDir, "weir")
    }

    private val lock = ReentrantLock()
    private val fileURL: File = File(directory, "event_queue.jsonl")
    private val metaFileURL: File = File(directory, "event_queue_meta.jsonl")
    private val pending: MutableList<EventParams> = mutableListOf()
    /** Index-aligned with [pending]: enqueue wall-clock time (epoch millis). */
    private val enqueuedAt: MutableList<Long> = mutableListOf()
    private var enqueuedTotal: Int = 0
    private var uploadedTotal: Int = 0
    private var evictedTotal: Int = 0

    // ---- Ingest wiring (docs/instrumentation-workstream.md §1) ----

    private var ingestConfig: WeirIngestConfig? = null
    private var uploader: EventIngestUploading = HttpUrlConnectionEventIngestUploader()
    private var isFlushing: Boolean = false
    private var consecutiveFailures: Int = 0
    private var backoffUntil: Long? = null
    private var needsIngestReconfiguration: Boolean = false
    private var timerJob: Job? = null
    private var foregroundTrigger: ForegroundFlushTrigger? = null

    /** Identity for this queue's own `health_queue_flush_result` emissions —
     *  one per [EventQueue] instance (long-lived, one per host process), not
     *  per flush. */
    private val flushHealthSessionId: String = UUID.randomUUID().toString()
    private val flushHealthSeq = HealthSeq()
    private var lastHeartbeatAt: Long? = null
    private var latestConfigId: String? = null
    private var latestConfigVersion: Int? = null

    /**
     * Purely observational: fires after every real flush attempt (only once
     * [configureIngest] has been called), success or failure. This is *not* how
     * `health_queue_flush_result` gets emitted — that happens inside
     * [triggerFlush] itself (the queue enqueues its own flush-health event into
     * itself). This callback exists only so a host can additionally observe
     * flush outcomes (logging, a tail hook, etc.) without being the thing that
     * builds the health event ([HealthEvent] is module-internal to the SDK).
     */
    var onFlushResult: ((ok: Boolean, count: Int, httpStatus: Int?) -> Unit)? = null

    init {
        directory.mkdirs()
        val loaded = loadExisting(fileURL)
        pending.addAll(loaded)
        latestConfigId = pending.asReversed().firstNotNullOfOrNull { it.configId }
        latestConfigVersion = pending.asReversed().firstNotNullOfOrNull { it.configVersion }
        val loadedMeta = loadExistingMeta(metaFileURL)
        if (loadedMeta.size == pending.size) {
            enqueuedAt.addAll(loadedMeta)
        } else {
            // Sidecar missing, mismatched (e.g. an SDK upgrade from before this
            // sidecar existed), or corrupt: fail open rather than evict
            // anything incorrectly. Treat the existing backlog as "enqueued
            // now" — a one-time reset of the retention clock, not a loss.
            val stamp = now()
            repeat(pending.size) { enqueuedAt.add(stamp) }
        }
        val startupEvictions = applyRetention()
        evictedTotal += startupEvictions
        if (startupEvictions > 0) {
            rewriteDiskFile()
        }
    }

    // ---- EventSink ----

    /** Adapts [EventQueue] to the [EventSink] seam `FlowEngine` depends on. */
    override fun append(event: EventParams) {
        enqueue(event)
    }

    /**
     * Appends [event] to the durable, ordered queue. Preserves enqueue order on
     * disk (independent of the `seq` field the event already carries, which is
     * per-flow/session and not a global ordering key).
     */
    fun enqueue(event: EventParams) {
        lock.withLock {
            val enqueueTime = now()
            pending.add(event)
            enqueuedAt.add(enqueueTime)
            enqueuedTotal += 1
            event.configId?.let { latestConfigId = it }
            event.configVersion?.let { latestConfigVersion = it }
            appendToDisk(event, enqueueTime)
            val evictions = applyRetention()
            evictedTotal += evictions
            if (evictions > 0) {
                rewriteDiskFile()
            }
        }
    }

    /** Test/diagnostic helper: current count of durable, unflushed events. */
    val pendingCount: Int
        get() = lock.withLock { pending.size }

    // ---- Retention ----

    /** Must only be called while holding [lock]. Trims [pending]/[enqueuedAt] in
     *  lockstep to the retention bound (oldest-first by enqueue time, then by
     *  count), returning the number evicted for launch-cumulative telemetry. */
    private fun applyRetention(): Int {
        val originalCount = pending.size
        val cutoff = now() - retentionMaxAgeMillis
        val firstFresh = enqueuedAt.indexOfFirst { it >= cutoff }
        if (firstFresh > 0) {
            repeat(firstFresh) {
                pending.removeAt(0)
                enqueuedAt.removeAt(0)
            }
        } else if (firstFresh == -1 && pending.isNotEmpty()) {
            pending.clear()
            enqueuedAt.clear()
        }
        if (pending.size > maxRetentionCount) {
            val overflow = pending.size - maxRetentionCount
            repeat(overflow) {
                pending.removeAt(0)
                enqueuedAt.removeAt(0)
            }
        }
        return originalCount - pending.size
    }

    // ---- Disk I/O (all callers hold [lock]) ----

    private fun loadExisting(file: File): List<EventParams> {
        if (!file.exists()) return emptyList()
        val result = mutableListOf<EventParams>()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return emptyList()
        }
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            try {
                result.add(WeirJson.decodeFromString(EventParams.serializer(), line))
            } catch (_: Exception) {
                // Fail-soft on a malformed line (hand-rolled append-on-disk
                // state, not a database) rather than throwing away the batch.
            }
        }
        return result
    }

    private fun loadExistingMeta(file: File): List<Long> {
        if (!file.exists()) return emptyList()
        val result = mutableListOf<Long>()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return emptyList()
        }
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            trimmed.toLongOrNull()?.let { result.add(it) }
        }
        return result
    }

    private fun appendToDisk(event: EventParams, enqueueTime: Long) {
        val encoded = try {
            WeirJson.encodeToString(EventParams.serializer(), event)
        } catch (e: Exception) {
            return
        }
        appendLine("$encoded\n".toByteArray(Charsets.UTF_8), fileURL)
        appendLine("$enqueueTime\n".toByteArray(Charsets.UTF_8), metaFileURL)
    }

    /** Shared append-with-fsync helper. Best-effort: if the fsync'd write fails
     *  the event stays in the in-memory [pending] list for this process and
     *  will be retried on the next flush, but survival across an immediate kill
     *  isn't guaranteed for that one write. */
    private fun appendLine(line: ByteArray, file: File) {
        try {
            FileOutputStream(file, /* append = */ true).use { out ->
                out.write(line)
                out.flush()
                out.fd.sync()
            }
        } catch (e: Exception) {
            // Best-effort; see doc comment.
        }
    }

    /** Rewrites both on-disk files to exactly the current in-memory arrays, each
     *  via a temp file + atomic replace so a kill mid-rewrite can't leave a
     *  half-written queue file. */
    private fun rewriteDiskFile() {
        val contents = StringBuilder()
        for (event in pending) {
            val encoded = try {
                WeirJson.encodeToString(EventParams.serializer(), event)
            } catch (e: Exception) {
                continue
            }
            contents.append(encoded).append('\n')
        }
        replaceFile(fileURL, contents.toString().toByteArray(Charsets.UTF_8))

        val meta = StringBuilder()
        for (stamp in enqueuedAt) {
            meta.append(stamp).append('\n')
        }
        replaceFile(metaFileURL, meta.toString().toByteArray(Charsets.UTF_8))
    }

    private fun replaceFile(file: File, contents: ByteArray) {
        val tmp = File(file.parentFile, "${file.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(contents)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                // Some filesystems don't support ATOMIC_MOVE + REPLACE together;
                // fall back to a plain replacing move (still durable, just not
                // atomic across the two flags).
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    // ---- Flush ----

    /**
     * Batches all queued-but-unflushed events and hands them to [upload] in
     * sequential sub-batches of at most [maxEventsPerUploadRequest], removing
     * exactly the sub-batches that succeeded, in order, from the durable store.
     * Stops at the first sub-batch [upload] returns `false` for: that sub-batch
     * and every later one are left for the next flush attempt, preserving
     * delivery order. Events enqueued *during* the flush are never included in
     * any sub-batch and are never removed, so nothing added mid-flush is lost.
     */
    suspend fun flush(upload: suspend (List<EventParams>) -> Boolean) {
        val batch: List<EventParams> = lock.withLock { pending.toList() }
        if (batch.isEmpty()) return

        var uploadedCount = 0
        var start = 0
        while (start < batch.size) {
            val end = min(start + maxEventsPerUploadRequest, batch.size)
            val success = upload(batch.subList(start, end).toList())
            if (!success) break
            uploadedCount = end
            start = end
        }

        if (uploadedCount == 0) return
        lock.withLock {
            val removeCount = min(uploadedCount, pending.size)
            repeat(removeCount) { pending.removeAt(0) }
            val metaRemoveCount = min(removeCount, enqueuedAt.size)
            repeat(metaRemoveCount) { enqueuedAt.removeAt(0) }
            rewriteDiskFile()
        }
    }

    // ---- Live ingest wiring ----

    /**
     * Attaches a live ingest endpoint to this queue. Idempotent to call again
     * (e.g. to swap tokens); starts the timer/foreground-trigger scheduling and
     * makes one immediate flush attempt so events already queued from a previous
     * session don't wait a full [flushIntervalMillis] before their first try.
     *
     * Flush triggers, all funneling into [triggerFlush]:
     *  1. **App foreground** — via [foregroundTrigger] (production wiring passes
     *     [ProcessLifecycleForegroundTrigger]; tests pass none).
     *  2. **Flow completion** — `Weir.present`'s finish path calls
     *     [triggerFlush].
     *  3. **Timer** — while the queue is alive.
     *
     * @param flushIntervalMillis periodic flush cadence while configured
     *   (default 30s: frequent enough to feel near-live, infrequent enough not
     *   to matter for battery/data at these event volumes).
     */
    fun configureIngest(
        config: WeirIngestConfig,
        uploader: EventIngestUploading = HttpUrlConnectionEventIngestUploader(),
        flushIntervalMillis: Long = 30_000,
        foregroundTrigger: ForegroundFlushTrigger? = null,
    ) {
        lock.withLock {
            this.ingestConfig = config
            this.uploader = uploader
            this.needsIngestReconfiguration = false
        }
        foregroundTrigger?.let {
            this.foregroundTrigger?.unregister()
            this.foregroundTrigger = it
            it.register { handleForeground() }
        }
        startTimer(flushIntervalMillis)
        triggerFlush()
    }

    /** Foreground lifecycle hook shared by production and unit tests. */
    internal fun handleForeground() {
        val heartbeat = lock.withLock {
            val current = now()
            if (lastHeartbeatAt?.let { current - it < 60 * 60 * 1000 } == true) return@withLock null
            lastHeartbeatAt = current
            val device = ingestConfig?.deviceContext
                ?: WeirDeviceContext("android", "unknown", WeirSdk.WEIR_SDK_VERSION, "unknown", "unknown")
            HealthEvent.sdkHeartbeat(
                sessionId = flushHealthSessionId,
                seq = flushHealthSeq.next(),
                sdkVersion = device.sdkVersion,
                appVersion = device.appVersion,
                configId = latestConfigId ?: "unknown",
                configVersion = latestConfigVersion ?: 0,
                queueDepth = pending.size,
            )
        }
        if (heartbeat != null) enqueue(heartbeat)
        triggerFlush()
    }

    private fun startTimer(intervalMillis: Long) {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                triggerFlush()
            }
        }
    }

    /**
     * Attempts one flush against the configured ingest endpoint, if any is
     * configured and the exponential-backoff window (after a prior failure) has
     * elapsed. Idempotent and safe to call from any trigger concurrently —
     * [isFlushing] guards against two overlapping attempts uploading the same
     * batch. **No-ops when no ingest is configured.**
     *
     * Backoff on failure: 5s, 10s, 20s, ... capped at 5 minutes
     * (`5s * 2^(failures-1)`, capped at 300s). Resets on the next success.
     *
     * `health_queue_flush_result` emission lives here (not the `Weir.present`
     * call site) so any host that calls [configureIngest] gets flush health for
     * free regardless of how `eventSink` is composed (a tee never fires the
     * present-time auto-wiring). Feedback-loop safety: [enqueue] below runs
     * *after* [flush]'s batch was already captured, so this event lands in
     * [pending] for the *next* flush. A batch containing only `health_*`
     * events drains without emitting another flush result, so heartbeat and
     * diagnostic delivery converges instead of self-perpetuating.
     */
    fun triggerFlush() {
        val config: WeirIngestConfig = lock.withLock {
            val c = ingestConfig
            when {
                c == null -> null
                isFlushing -> null
                needsIngestReconfiguration -> null
                backoffUntil?.let { now() < it } == true -> null
                else -> {
                    isFlushing = true
                    c
                }
            }
        } ?: return

        scope.launch {
            // `flush` may call this closure more than once (one call per
            // `maxEventsPerUploadRequest`-sized sub-batch). Accumulate across
            // all of them. `uploadedCount` is the total size of every sub-batch
            // *attempted* this cycle, success or failure (the health event
            // describes "how much did we try"; `outcome` carries the pass/fail
            // verdict, ending up describing the last sub-batch attempted).
            var outcome = EventUploadOutcome(success = true, httpStatus = null)
            var uploadedCount = 0
            var attemptedProductEvents = false
            var acceptedThisFlush = 0
            flush { batch ->
                outcome = uploader.upload(batch, config)
                uploadedCount += batch.size
                attemptedProductEvents = attemptedProductEvents || batch.any { !isHealthEvent(it) }
                if (outcome.success) acceptedThisFlush += outcome.accepted ?: batch.size
                outcome.success
            }

            val telemetry = lock.withLock {
                isFlushing = false
                uploadedTotal += acceptedThisFlush
                if (outcome.success) {
                    consecutiveFailures = 0
                    backoffUntil = null
                } else {
                    if (outcome.retryable) {
                        consecutiveFailures += 1
                        val delayMillis = min(300_000.0, 5_000.0 * 2.0.pow(consecutiveFailures - 1)).toLong()
                        backoffUntil = now() + delayMillis
                    } else {
                        needsIngestReconfiguration = true
                        backoffUntil = null
                    }
                }
                val oldestAge = enqueuedAt.firstOrNull()?.let { (now() - it).coerceAtLeast(0) } ?: 0
                TelemetrySnapshot(
                    enqueuedTotal = enqueuedTotal,
                    uploadedTotal = uploadedTotal,
                    evictedTotal = evictedTotal,
                    queueDepth = pending.size,
                    oldestQueuedAgeMs = oldestAge,
                    consecutiveFailures = consecutiveFailures,
                )
            }

            // Only report a result for a flush that actually had something to
            // upload — `flush` no-ops on an empty queue without calling this
            // closure, so `uploadedCount == 0` means there was nothing to report.
            if (uploadedCount > 0 && attemptedProductEvents) {
                enqueue(
                    HealthEvent.queueFlushResult(
                        sessionId = flushHealthSessionId,
                        seq = flushHealthSeq.next(),
                        ok = outcome.success,
                        count = uploadedCount,
                        httpStatus = outcome.httpStatus,
                        accepted = outcome.accepted,
                        duplicates = outcome.duplicates,
                        rejected = outcome.rejected,
                        failureReason = outcome.failureReason,
                        enqueuedTotal = telemetry.enqueuedTotal,
                        uploadedTotal = telemetry.uploadedTotal,
                        evictedTotal = telemetry.evictedTotal,
                        queueDepth = telemetry.queueDepth,
                        oldestQueuedAgeMs = telemetry.oldestQueuedAgeMs,
                        consecutiveFailures = telemetry.consecutiveFailures,
                    ),
                )
                onFlushResult?.invoke(outcome.success, uploadedCount, outcome.httpStatus)
            }
        }
    }

    private data class TelemetrySnapshot(
        val enqueuedTotal: Int,
        val uploadedTotal: Int,
        val evictedTotal: Int,
        val queueDepth: Int,
        val oldestQueuedAgeMs: Long,
        val consecutiveFailures: Int,
    )

    private fun isHealthEvent(event: EventParams): Boolean =
        event.payload.let { payload ->
            (payload as? kotlinx.serialization.json.JsonObject)
                ?.get("type")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.content
                ?.startsWith("health_") == true
        }

    /** Stops the periodic timer and foreground observer (idempotent). Call when
     *  a host tears down the queue; the on-disk state is untouched. */
    fun shutdown() {
        timerJob?.cancel()
        timerJob = null
        foregroundTrigger?.unregister()
        foregroundTrigger = null
    }
}
