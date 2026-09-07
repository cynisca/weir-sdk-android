package studio.aldric.weir.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.WeirJson
import java.io.BufferedReader
import java.net.HttpURLConnection

/**
 * Outcome of one flush attempt against the live ingest endpoint. Faithful port
 * of the Swift `EventUploadOutcome`.
 */
data class EventUploadOutcome(
    val success: Boolean,
    val httpStatus: Int? = null,
    /** False for HTTP 4xx: retain the batch and wait for a new ingest
     * configuration instead of retrying an unchanged rejected request. */
    val retryable: Boolean = true,
    val accepted: Int? = null,
    val duplicates: Int? = null,
    val rejected: Int? = null,
    val failureReason: String? = null,
)

@Serializable
private data class IngestAcknowledgement(
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int,
)

/**
 * Seam over the network call [EventQueue] makes when a [WeirIngestConfig] is
 * configured — lets tests substitute a fake instead of hitting a real server
 * for every retry/backoff/idempotency case (mirrors iOS injecting `URLSession`).
 *
 * Faithful port of the Swift `EventIngestUploading` protocol.
 */
interface EventIngestUploading {
    suspend fun upload(events: List<EventParams>, config: WeirIngestConfig): EventUploadOutcome
}

/**
 * Request body shape for a flush batch — v4 (`packages/spec/src/events.ts`'s
 * `EventBatch`).
 *
 * WIRE FORMAT (shared verbatim with iOS's `URLSessionEventIngestUploader` and
 * `services/api`): this wraps the `EventParams` array (the "batch of envelopes")
 * in a `{"appId", "device", "events"}` envelope so the server doesn't have to
 * infer appId from the write token alone. Each [EventParams]'s own on-the-wire
 * JSON shape (flowId/screenId/variantId/sessionId/userId/ts/seq/payload) is
 * exactly what [EventQueue] persists to disk — only this top-level wrapper is
 * added here.
 *
 * `device` rides here, once per batch, when [WeirIngestConfig.deviceContext]
 * is set — omitted entirely (not even a `null`, via [WeirJson]'s
 * `explicitNulls = false`) when it isn't, so a host that never opts in gets
 * byte-identical request bodies to before this field existed. Mirrors iOS's
 * `FlushRequestBody` in `EventIngestUploader.swift`.
 */
@Serializable
private data class FlushRequestBody(
    @SerialName("appId") val appId: String,
    @SerialName("device") val device: WeirDeviceContext? = null,
    @SerialName("events") val events: List<EventParams>,
)

/**
 * Default [EventIngestUploading]: one `HttpURLConnection` POST per flush
 * attempt — zero new dependency (the Android analogue of iOS's `URLSession`
 * default). Auth per docs/instrumentation-workstream.md §1: `Authorization:
 * Bearer <writeToken>`, one token per appId, no user system.
 */
class HttpUrlConnectionEventIngestUploader(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 15_000,
) : EventIngestUploading {

    override suspend fun upload(events: List<EventParams>, config: WeirIngestConfig): EventUploadOutcome =
        withContext(Dispatchers.IO) {
            val body: ByteArray = try {
                WeirJson.encodeToString(
                    FlushRequestBody.serializer(),
                    FlushRequestBody(appId = config.appId, device = config.deviceContext, events = events),
                ).toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                // Can't even build the request body — never a "retry later"
                // situation (the same events would fail to encode again), so
                // this collapses to a normal failed-flush outcome and the
                // caller retries per its own backoff, same as a network error.
                return@withContext EventUploadOutcome(success = false, httpStatus = null)
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (config.endpointURL.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMillis
                    readTimeout = readTimeoutMillis
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${config.writeToken}")
                }
                connection.outputStream.use { it.write(body) }

                val status = connection.responseCode
                val responseBody = readResponseQuietly(connection)
                if (status !in 200..299) {
                    EventUploadOutcome(
                        success = false,
                        httpStatus = status,
                        retryable = status !in 400..499,
                        failureReason = "http_error",
                    )
                } else {
                    val acknowledgement = try {
                        WeirJson.decodeFromString<IngestAcknowledgement>(responseBody)
                    } catch (_: Exception) {
                        null
                    }
                    if (acknowledgement == null || acknowledgement.accepted < 0 || acknowledgement.duplicates < 0 || acknowledgement.rejected < 0) {
                        EventUploadOutcome(false, status, failureReason = "invalid_acknowledgement")
                    } else {
                        val reconciled = acknowledgement.accepted + acknowledgement.duplicates
                        val fullyAcknowledged = acknowledgement.rejected == 0 && reconciled == events.size
                        EventUploadOutcome(
                            success = fullyAcknowledged,
                            httpStatus = status,
                            retryable = acknowledgement.rejected == 0,
                            accepted = acknowledgement.accepted,
                            duplicates = acknowledgement.duplicates,
                            rejected = acknowledgement.rejected,
                            failureReason = if (fullyAcknowledged) null else if (acknowledgement.rejected > 0) "events_rejected" else "incomplete_acknowledgement",
                        )
                    }
                }
            } catch (e: Exception) {
                EventUploadOutcome(success = false, httpStatus = null)
            } finally {
                connection?.disconnect()
            }
        }

    private fun readResponseQuietly(connection: HttpURLConnection): String =
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        } catch (_: Exception) {
            ""
        }
}
