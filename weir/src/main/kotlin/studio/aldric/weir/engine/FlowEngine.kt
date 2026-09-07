package studio.aldric.weir.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.HealthEvent
import studio.aldric.weir.bridge.WeirVariable
import java.io.File
import java.util.UUID

/** Closed runtime outcome for a custom screen. The engine owns the fallback
 * transition, so a component with incompatible props never becomes a
 * platform-specific dead-end view. */
enum class CustomScreenAvailability { renderable, unregistered, invalidProps }

/** One flow's native state machine and canonical event stream. */
class FlowEngine(
    val config: FlowConfig,
    val userId: String? = null,
    private val eventSink: EventSink,
    private val sessionId: String = UUID.randomUUID().toString(),
    val assetsRoot: File? = null,
    private val configId: String? = null,
    private val configVersion: Int? = null,
    private val isComponentRegistered: (String) -> Boolean = { true },
    private val customScreenAvailability: (String, Map<String, JsonElement>) -> CustomScreenAvailability = { name, _ ->
        if (isComponentRegistered(name)) CustomScreenAvailability.renderable else CustomScreenAvailability.unregistered
    },
) {
    var currentScreenId: String? by mutableStateOf(null)
        private set
    var variables: Map<String, JsonElement> by mutableStateOf<Map<String, JsonElement>>(emptyMap())
        private set
    var isComplete: Boolean by mutableStateOf(false)
        private set

    val assignments: Map<String, Bucketer.Assignment>
    val currentScreen: ScreenConfig? get() = currentScreenId?.let(screensById::get)

    private val flowId = config.id
    private var seq = 0
    private var screenEnteredAtNanos = System.nanoTime()
    private val screensById = linkedMapOf<String, ScreenConfig>()
    private val screenOrder = mutableListOf<String>()
    private var completionHandler: ((List<WeirVariable>) -> Unit)? = null
    private var dismissHandler: ((String) -> Unit)? = null
    private data class ComponentFallback(val screen: ScreenConfig, val availability: CustomScreenAvailability)
    private val pendingFallbacks = mutableListOf<ComponentFallback>()
    private var defersFallbackEvents = true

    init {
        for (screen in config.screens) {
            screensById[screen.base.id] = screen
            screenOrder += screen.base.id
        }

        val resolvedAssignments = linkedMapOf<String, Bucketer.Assignment>()
        for (experiment in config.experiments) {
            resolvedAssignments[experiment.id] = if (userId != null) {
                Bucketer.assignVariant(experiment.bucketerExperiment(), userId)
            } else {
                Bucketer.Assignment(experiment.id, Bucketer.HOLDOUT)
            }
        }
        assignments = resolvedAssignments

        val initialVariables = linkedMapOf<String, JsonElement>()
        for (variable in config.variables) defaultJson(variable.defaultValue)?.let { initialVariables[variable.id] = it }
        variables = initialVariables

        currentScreenId = resolveGated(config.entry ?: config.screens.firstOrNull()?.base?.id, variables)
        emitFlowStarted(currentScreenId ?: "")
        val hadComponentFallback = pendingFallbacks.isNotEmpty()
        pendingFallbacks.forEach { emitScreenFallback(it.screen, it.availability) }
        pendingFallbacks.clear()
        defersFallbackEvents = false
        currentScreenId?.let(::emitScreenImpression)
        if (currentScreenId == null && hadComponentFallback) dismiss()
    }

    fun onComplete(handler: (List<WeirVariable>) -> Unit) { completionHandler = handler }
    fun onDismiss(handler: (String) -> Unit) { dismissHandler = handler }

    fun setVariable(id: String, value: JsonElement) {
        variables = LinkedHashMap(variables).apply { put(id, value) }
    }

    fun variable(id: String): JsonElement? = variables[id]

    fun submit(fromScreenId: String, explicitTarget: String? = null) {
        if (fromScreenId != currentScreenId) return
        val (chosen, matchedBranch) = if (explicitTarget != null) explicitTarget to null else resolveNext(fromScreenId)
        emitBranchDecision(fromScreenId, chosen ?: "", matchedBranch)
        advance(chosen)
    }

    fun skip(fromScreenId: String) {
        if (fromScreenId != currentScreenId) return
        val screen = screensById[fromScreenId] ?: return
        emitScreenSkipped(screen)
        advance(resolveNext(fromScreenId).first)
    }

    fun complete(reason: String = "reached_end") {
        if (isComplete) return
        isComplete = true
        val result = config.variables.mapNotNull { variable ->
            variables[variable.id]?.let { WeirVariable(variable.id, variable.type.wireValue, it) }
        }
        emitFlowCompleted(reason)
        completionHandler?.invoke(result)
    }

    fun dismiss(reason: String = "dismissed") {
        if (isComplete) return
        isComplete = true
        emitFlowCompleted(reason)
        dismissHandler?.invoke(reason)
    }

    fun recordInteraction(type: String, screenId: String, fields: Map<String, JsonElement> = emptyMap()) {
        val payload = LinkedHashMap(fields)
        // Text remains available in `variables` for local flow branching, but
        // must never enter the durable event queue by default. Keep numeric
        // input measurable; free-form input retains only an explicit marker
        // and its length for UX analysis.
        if (type == "input_submitted" && payload["value"] is JsonPrimitive) {
            val value = payload.getValue("value").jsonPrimitive
            if (value.isString) {
                payload["value"] = JsonPrimitive("[redacted]")
                payload["valueRedacted"] = JsonPrimitive(true)
                payload["valueLength"] = JsonPrimitive(value.content.length)
            }
        }
        payload["type"] = JsonPrimitive(type)
        payload["seq"] = JsonPrimitive(nextSeq())
        emit(screenId, payload = JsonObject(payload))
    }

    /** Emits a fail-closed renderer fallback on this flow's actual session
     * and monotonic event sequence; health events never carry host identity. */
    fun recordHealthFallback(reason: String) {
        eventSink.append(HealthEvent.flowFallback(flowId, sessionId, nextSeq(), reason))
    }

    private fun advance(nextId: String?) {
        val gated = resolveGated(nextId, variables)
        currentScreenId = gated
        screenEnteredAtNanos = System.nanoTime()
        if (gated != null) emitScreenImpression(gated) else complete()
    }

    private fun resolveNext(screenId: String): Pair<String?, Int?> {
        val screen = screensById[screenId] ?: return null to null
        return when (val next = screen.base.next) {
            null -> defaultNextId(screenId) to null
            is NextTransition.Screen -> next.id to null
            is NextTransition.Conditional -> {
                next.branches.forEachIndexed { index, branch ->
                    if (evaluate(branch.`when`, variables)) return branch.goto to index
                }
                next.defaultScreen to null
            }
        }
    }

    private fun defaultNextId(screenId: String): String? {
        val index = screenOrder.indexOf(screenId)
        return if (index >= 0 && index + 1 < screenOrder.size) screenOrder[index + 1] else null
    }

    private fun resolveGated(candidateId: String?, liveVariables: Map<String, JsonElement>): String? {
        var current = candidateId
        var guardCount = 0
        while (current != null && guardCount < screenOrder.size + 1) {
            guardCount++
            val screen = screensById[current] ?: return null
            val gate = screen.base.variant
            if (gate != null) {
                val assigned = assignments[gate.experiment]?.variant
                if (assigned == null || assigned !in gate.showFor) {
                    current = staticNext(screen, liveVariables)
                    continue
                }
            }
            if (screen is CustomScreenConfig) {
                val availability = customScreenAvailability(screen.component, screen.props)
                if (availability == CustomScreenAvailability.renderable) return current
                if (defersFallbackEvents) pendingFallbacks += ComponentFallback(screen, availability) else emitScreenFallback(screen, availability)
                val gating = screen.base.gating
                current = if (gating?.fallback == ScreenGating.Fallback.substitute) gating.substituteScreen ?: staticNext(screen, liveVariables) else staticNext(screen, liveVariables)
                continue
            }
            return current
        }
        return null
    }

    private fun emitScreenFallback(screen: ScreenConfig, availability: CustomScreenAvailability) {
        val custom = screen as? CustomScreenConfig ?: return
        val gating = screen.base.gating
        val declared = gating?.fallback
        val substitute = if (declared == ScreenGating.Fallback.substitute) gating?.substituteScreen else null
        val values = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive("screen_fallback"), "seq" to JsonPrimitive(nextSeq()),
            "screen" to JsonPrimitive(screen.base.id), "screenType" to JsonPrimitive("custom"),
            "component" to JsonPrimitive(custom.component),
            "reason" to JsonPrimitive(if (availability == CustomScreenAvailability.invalidProps) "invalid_props" else "unregistered"),
            "declaredFallback" to JsonPrimitive(declared?.name ?: "undeclared"),
            "appliedFallback" to JsonPrimitive(if (substitute == null) "skip" else "substitute"),
        )
        if (substitute != null) values["substituteScreen"] = JsonPrimitive(substitute)
        emit(null, payload = JsonObject(values))
    }

    private fun staticNext(screen: ScreenConfig, liveVariables: Map<String, JsonElement>): String? = when (val next = screen.base.next) {
        null -> defaultNextId(screen.base.id)
        is NextTransition.Screen -> next.id
        is NextTransition.Conditional -> next.branches.firstOrNull { evaluate(it.`when`, liveVariables) }?.goto ?: next.defaultScreen
    }

    private fun evaluate(condition: Condition, liveVariables: Map<String, JsonElement>): Boolean {
        return when (condition) {
            is Condition.Comparison -> compare(liveVariables[condition.variable], condition.op, condition.value)
            is Condition.Membership -> {
                val actual = liveVariables[condition.variable]
                    ?: return condition.op == Condition.MembershipOp.nin
                val matches = condition.value.any { scalarEquals(actual, it) }
                if (condition.op == Condition.MembershipOp.inOp) matches else !matches
            }
            is Condition.Includes -> liveVariables[condition.variable]?.let { actual ->
                (actual as? JsonArray)?.any { scalarEquals(it, condition.value) }
            } ?: false
            is Condition.All -> condition.conditions.all { evaluate(it, liveVariables) }
            is Condition.Any -> condition.conditions.any { evaluate(it, liveVariables) }
            is Condition.Not -> !evaluate(condition.condition, liveVariables)
        }
    }

    private fun compare(actual: JsonElement?, op: Condition.ComparisonOp, expected: ScalarValue): Boolean {
        return when (op) {
            Condition.ComparisonOp.eq -> actual?.let { scalarEquals(it, expected) } ?: false
            Condition.ComparisonOp.neq -> !(actual?.let { scalarEquals(it, expected) } ?: false)
            Condition.ComparisonOp.gt, Condition.ComparisonOp.gte, Condition.ComparisonOp.lt, Condition.ComparisonOp.lte -> {
                val a = (actual as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return false
                val b = (expected as? ScalarValue.NumberValue)?.value ?: return false
                when (op) {
                    Condition.ComparisonOp.gt -> a > b
                    Condition.ComparisonOp.gte -> a >= b
                    Condition.ComparisonOp.lt -> a < b
                    Condition.ComparisonOp.lte -> a <= b
                    else -> false
                }
            }
        }
    }

    private fun scalarEquals(actual: JsonElement, expected: ScalarValue): Boolean {
        val primitive = actual as? JsonPrimitive ?: return false
        return when (expected) {
            is ScalarValue.StringValue -> primitive.isString && primitive.content == expected.value
            is ScalarValue.NumberValue -> !primitive.isString && primitive.booleanOrNull == null && primitive.doubleOrNull == expected.value
            is ScalarValue.BooleanValue -> !primitive.isString && primitive.booleanOrNull == expected.value
        }
    }

    private fun defaultJson(value: VariableDefault?): JsonElement? = when (value) {
        null -> null
        is VariableDefault.StringArray -> JsonArray(value.value.map(::JsonPrimitive))
        is VariableDefault.Scalar -> when (val scalar = value.value) {
            is ScalarValue.StringValue -> JsonPrimitive(scalar.value)
            is ScalarValue.NumberValue -> JsonPrimitive(scalar.value)
            is ScalarValue.BooleanValue -> JsonPrimitive(scalar.value)
        }
    }

    private fun emitFlowStarted(entry: String) {
        emit(null, payload = payload(
            "type" to JsonPrimitive("flow_started"),
            "seq" to JsonPrimitive(nextSeq()),
            "flowId" to JsonPrimitive(flowId),
            "entry" to JsonPrimitive(entry),
        ))
        // Declaration order is the contract; never derive this sequence from assignments' map iteration.
        for (experiment in config.experiments) {
            val assignment = assignments[experiment.id] ?: continue
            emit(null, assignment.variant, payload(
                "type" to JsonPrimitive("variant_assigned"),
                "seq" to JsonPrimitive(nextSeq()),
                "experiment" to JsonPrimitive(experiment.id),
                "variant" to JsonPrimitive(assignment.variant),
            ))
        }
    }

    private fun emitScreenImpression(screenId: String) {
        val screen = screensById[screenId] ?: return
        val index = screenOrder.indexOf(screenId)
        if (index < 0) return
        emit(screenId, payload = payload(
            "type" to JsonPrimitive("screen_impression"),
            "seq" to JsonPrimitive(nextSeq()),
            "screen" to JsonPrimitive(screenId),
            "screenType" to JsonPrimitive(screen.typeName),
            "index" to JsonPrimitive(index),
        ))
    }

    private fun emitBranchDecision(screen: String, chosen: String, matchedBranch: Int?) {
        emit(screen, payload = payload(
            "type" to JsonPrimitive("branch_decision"),
            "seq" to JsonPrimitive(nextSeq()),
            "screen" to JsonPrimitive(screen),
            "chosen" to JsonPrimitive(chosen),
            "matchedBranch" to (matchedBranch?.let(::JsonPrimitive) ?: JsonNull),
        ))
    }

    private fun emitScreenSkipped(screen: ScreenConfig) {
        emit(screen.base.id, payload = payload(
            "type" to JsonPrimitive("screen_skipped"),
            "seq" to JsonPrimitive(nextSeq()),
            "screen" to JsonPrimitive(screen.base.id),
            "screenType" to JsonPrimitive(screen.typeName),
        ))
    }

    private fun emitFlowCompleted(reason: String) {
        emit(null, payload = payload(
            "type" to JsonPrimitive("flow_completed"),
            "seq" to JsonPrimitive(nextSeq()),
            "flowId" to JsonPrimitive(flowId),
            "reason" to JsonPrimitive(reason),
        ))
    }

    private fun nextSeq(): Int = seq++

    private fun emit(screenId: String?, variantId: String? = null, payload: JsonElement) {
        val dwellMs = screenId?.let { (System.nanoTime() - screenEnteredAtNanos) / 1_000_000.0 }
        eventSink.append(EventParams(
            flowId = flowId,
            screenId = screenId,
            variantId = variantId,
            sessionId = sessionId,
            userId = userId,
            ts = System.currentTimeMillis().toDouble(),
            seq = seq,
            payload = payload,
            screenDwellMs = dwellMs,
            configId = configId,
            configVersion = configVersion,
        ))
    }

    private fun payload(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*fields))
}

private val VariableType.wireValue: String
    get() = if (this == VariableType.enumType) "enum" else name
