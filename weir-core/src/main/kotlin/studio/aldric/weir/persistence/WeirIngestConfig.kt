package studio.aldric.weir.persistence

import java.net.URL

/**
 * Configuration for wiring the SDK's file-backed [EventQueue] to a live Weir
 * ingest endpoint (docs/instrumentation-workstream.md §1, D1: Weir ingest as a
 * second sink alongside the host's existing GA4 forwarding).
 *
 * Faithful port of `sdk-ios/Sources/Weir/Persistence/WeirIngestConfig.swift`.
 *
 * Passing this to [studio.aldric.weir.Weir.present] attaches it to the call's
 * `eventSink` (only when that sink is a plain [EventQueue] — see
 * [EventQueue.configureIngest]); when `null` (the default), the queue behaves
 * exactly as it did before this existed: durable on-disk storage, no network
 * flush. The host app agent wires the real values later.
 */
data class WeirIngestConfig(
    /** The ingest endpoint that accepts a batch-of-envelopes POST — see
     *  [EventIngestUploading] for the exact body shape. */
    val endpointURL: URL,
    /** Sent both as part of the request body and implicitly via which
     *  `writeToken` is used — the ingest side authenticates the token to an
     *  appId server-side ("static per-app write token"), but including it in
     *  the body too keeps a misconfigured token/appId pairing visible in the
     *  payload during debugging. */
    val appId: String,
    /** Sent as `Authorization: Bearer <writeToken>`. */
    val writeToken: String,
    /** v4 (`packages/spec/src/events.ts`'s `EventBatch.device`) — sent once
     *  per flush batch, never per event. `null` (the default) omits the
     *  `device` field from the request body entirely, matching this type's
     *  exact pre-v4 wire shape for every existing caller. Mirrors iOS
     *  `WeirIngestConfig.deviceContext`. */
    val deviceContext: WeirDeviceContext? = null,
)
