package studio.aldric.weir.observe

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.aldric.weir.WeirSdk
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.persistence.EventQueue
import studio.aldric.weir.persistence.ProcessLifecycleForegroundTrigger
import studio.aldric.weir.persistence.WeirDeviceContext
import studio.aldric.weir.persistence.WeirIngestConfig
import java.net.URL
import java.util.UUID

@Serializable
enum class PurchaseOutcome(val wireValue: String) {
    @SerialName("purchased") PURCHASED("purchased"),
    @SerialName("cancelled") CANCELLED("cancelled"),
    @SerialName("failed") FAILED("failed"),
}

/** Compose-free, process-wide facade for hand-instrumented onboarding. */
object Weir {
    private val launchId: String by lazy { UUID.randomUUID().toString() }
    private var runtime: ObserveRuntime? = null

    @Synchronized
    fun observe(
        context: Context,
        appId: String,
        writeToken: String,
        endpoint: URL,
        flow: String = "onboarding",
        screens: List<String>,
        userId: String? = null,
        flushIntervalMs: Long = 30_000,
        sessionTimeoutMs: Long = 1_800_000,
        clock: () -> Long = { System.currentTimeMillis() },
    ) {
        val appContext = context.applicationContext
        val shouldCheckAbandonment = runtime == null
        runtime?.shutdown()
        val queue = EventQueue(EventQueue.defaultDirectory(appContext), now = clock)
        queue.configureIngest(
            config = WeirIngestConfig(
                endpointURL = endpoint,
                appId = appId,
                writeToken = writeToken,
                deviceContext = WeirDeviceContext.current(appContext, WeirSdk.WEIR_SDK_VERSION),
            ),
            flushIntervalMillis = flushIntervalMs,
            foregroundTrigger = ProcessLifecycleForegroundTrigger(),
        )
        runtime = ObserveRuntime(
            flowId = flow,
            declaredScreens = screens.toList(),
            initialUserId = userId,
            sessionTimeoutMs = sessionTimeoutMs,
            clock = clock,
            launchId = launchId,
            sink = queue,
            store = ObserveSessionStore(
                appContext.getSharedPreferences("weir.observe.$appId.$flow", Context.MODE_PRIVATE),
            ),
            flushAction = queue::triggerFlush,
            shutdownAction = queue::shutdown,
            checkAbandonmentOnStart = shouldCheckAbandonment,
        )
    }

    @Synchronized fun screen(name: String, index: Int? = null, properties: Map<String, JsonElement> = emptyMap()) =
        requireRuntime().screen(name, index, properties)
    @Synchronized fun completed(properties: Map<String, JsonElement> = emptyMap()) = requireRuntime().completed(properties)
    @Synchronized fun paywallShown(paywallId: String? = null, properties: Map<String, JsonElement> = emptyMap()) =
        requireRuntime().paywallShown(paywallId, properties)
    @Synchronized fun purchaseIntent(product: String, properties: Map<String, JsonElement> = emptyMap()) =
        requireRuntime().purchaseIntent(product, properties)
    @Synchronized fun purchaseResult(product: String, outcome: PurchaseOutcome, error: String? = null) =
        requireRuntime().purchaseResult(product, outcome, error)
    @Synchronized fun track(name: String, properties: Map<String, JsonElement> = emptyMap()) =
        requireRuntime().track(name, properties)
    @Synchronized fun setUserId(id: String?) = requireRuntime().setUserId(id)
    @Synchronized fun flush() = requireRuntime().flush()

    private fun requireRuntime(): ObserveRuntime =
        checkNotNull(runtime) { "Weir.observe(...) must be called before recording events" }
}

internal class ObserveRuntime(
    private val flowId: String,
    private val declaredScreens: List<String>,
    initialUserId: String?,
    private val sessionTimeoutMs: Long,
    private val clock: () -> Long,
    private val launchId: String,
    private val sink: EventSink,
    private val store: ObserveSessionStore,
    private val flushAction: () -> Unit = {},
    private val shutdownAction: () -> Unit = {},
    checkAbandonmentOnStart: Boolean = true,
) {
    private var userId: String? = initialUserId
    private var session: ObserveSession? = store.load()

    init {
        if (checkAbandonmentOnStart) deriveAbandonmentIfNeeded()
        session?.let { if (initialUserId != it.userId) save(it.copy(userId = initialUserId)) }
    }

    fun screen(name: String, indexOverride: Int?, properties: Map<String, JsonElement>) {
        val now = clock()
        var current = session ?: startSession(now)
        if (current.openScreen != null) {
            current = emitScreenExit(current, now, ExitReason.ADVANCE)
        }
        val declaredIndex = declaredScreens.indexOf(name).takeIf { it >= 0 }
        val index = indexOverride ?: declaredIndex
        val declared = declaredIndex != null
        current = emit(
            current,
            now,
            payload = payload("screen_impression") {
                put("screen", JsonPrimitive(name))
                index?.let { put("index", JsonPrimitive(it)) }
                put("declared", JsonPrimitive(declared))
                put("props", JsonObject(properties))
            },
            screenId = name,
        ).copy(
            openScreen = ObserveScreen(name, index, now, properties.toMap()),
            screensSeen = current.screensSeen + name,
        )
        save(current)
    }

    fun completed(properties: Map<String, JsonElement> = emptyMap()) {
        val now = clock()
        var current = session ?: return
        if (current.openScreen != null) current = emitScreenExit(current, now, ExitReason.FLOW_END)
        current = emit(
            current,
            now,
            payload("flow_completed") {
                put("flow", JsonPrimitive(flowId))
                put("screensSeen", JsonPrimitive(current.screensSeen.size))
                put("durationMs", JsonPrimitive((now - current.startedAtMs).coerceAtLeast(0)))
                put("props", JsonObject(properties))
            },
        )
        store.clear()
        session = null
        flushAction()
    }

    fun paywallShown(paywallId: String?, properties: Map<String, JsonElement>) = scopedEvent("paywall_shown") { current ->
        paywallId?.let { put("paywallId", JsonPrimitive(it)) }
        current.openScreen?.let { screen ->
            put("screen", JsonPrimitive(screen.name))
            screen.index?.let { put("index", JsonPrimitive(it)) }
        }
        put("props", JsonObject(properties))
    }

    fun purchaseIntent(product: String, properties: Map<String, JsonElement>) = scopedEvent("purchase_intent") { current ->
        put("productId", JsonPrimitive(product))
        current.openScreen?.let { put("screen", JsonPrimitive(it.name)) }
        put("props", JsonObject(properties))
    }

    fun purchaseResult(product: String, outcome: PurchaseOutcome, error: String?) = scopedEvent("purchase_result") {
        put("productId", JsonPrimitive(product))
        put("outcome", JsonPrimitive(outcome.wireValue))
        error?.let { put("error", JsonPrimitive(it)) }
    }

    fun track(name: String, properties: Map<String, JsonElement>) = scopedEvent("custom") {
        put("name", JsonPrimitive(name))
        put("props", JsonObject(properties))
    }

    fun setUserId(id: String?) {
        userId = id
        session?.let { save(it.copy(userId = id)) }
    }

    fun flush() = flushAction()
    fun shutdown() = shutdownAction()

    private fun startSession(now: Long): ObserveSession {
        var fresh = ObserveSession(
            sessionId = UUID.randomUUID().toString(), nextSeq = 0,
            startedAtMs = now, lastActivityAtMs = now, flowId = flowId, userId = userId,
            openScreen = null, screensSeen = emptySet(),
        )
        fresh = emit(fresh, now, payload("flow_started") {
            put("flow", JsonPrimitive(flowId))
            put("screenCount", JsonPrimitive(declaredScreens.size))
            put("declaredScreens", kotlinx.serialization.json.JsonArray(declaredScreens.map(::JsonPrimitive)))
        })
        save(fresh)
        return fresh
    }

    private fun scopedEvent(type: String, fields: JsonObjectBuilder.(ObserveSession) -> Unit) {
        val now = clock()
        val current = session ?: return
        val updated = emit(
            current, now, payload(type) { fields(current) },
            screenId = current.openScreen?.name,
            screenDwellMs = dwell(current, now),
        )
        save(updated)
    }

    private fun emitScreenExit(current: ObserveSession, now: Long, reason: ExitReason): ObserveSession {
        val screen = requireNotNull(current.openScreen)
        val closed = emit(
            current, now, payload("screen_exit") {
                put("screen", JsonPrimitive(screen.name))
                screen.index?.let { put("index", JsonPrimitive(it)) }
                put("reason", JsonPrimitive(reason.wireValue))
                put("props", JsonObject(screen.properties))
            },
            screenId = screen.name,
            screenDwellMs = dwell(current, now),
        ).copy(openScreen = null, lastActivityAtMs = now)
        save(closed)
        return closed
    }

    private fun emit(
        current: ObserveSession,
        now: Long,
        payload: JsonObject,
        screenId: String? = null,
        screenDwellMs: Double? = null,
    ): ObserveSession {
        sink.append(EventParams(
            flowId = current.flowId,
            screenId = screenId,
            sessionId = current.sessionId,
            userId = current.userId,
            ts = now.toDouble(),
            seq = current.nextSeq,
            payload = payload,
            screenDwellMs = screenDwellMs,
            eventId = UUID.randomUUID().toString(),
            launchId = launchId,
            elapsedMs = (now - current.startedAtMs).coerceAtLeast(0).toDouble(),
        ))
        return current.copy(nextSeq = current.nextSeq + 1, lastActivityAtMs = now).also(::save)
    }

    private fun deriveAbandonmentIfNeeded() {
        val prior = session ?: return
        val now = clock()
        if (prior.openScreen == null || now - prior.lastActivityAtMs <= sessionTimeoutMs) return
        emitScreenExit(prior, now, ExitReason.ABANDON)
        store.clear()
        session = null
    }

    private fun dwell(current: ObserveSession, now: Long): Double? =
        current.openScreen?.let { (now - it.enteredAtMs).coerceAtLeast(0).toDouble() }

    private fun save(value: ObserveSession) {
        session = value
        store.save(value)
    }
}

internal enum class ExitReason(val wireValue: String) {
    ADVANCE("advance"), BACK("back"), BACKGROUND("background"), FLOW_END("flow_end"), ABANDON("abandon")
}

private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder

private fun payload(type: String, content: JsonObjectBuilder.() -> Unit): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("type", JsonPrimitive(type))
        content()
    }
