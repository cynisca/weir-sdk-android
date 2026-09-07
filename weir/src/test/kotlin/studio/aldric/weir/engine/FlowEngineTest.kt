package studio.aldric.weir.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.aldric.weir.bridge.InMemoryEventSink

class FlowEngineTest {
    private fun config(variables: List<FlowVariable> = emptyList(), experiments: List<FlowExperiment> = emptyList()) = FlowConfig(
        specVersion = 4,
        id = "engine_test",
        name = "Engine Test",
        variables = variables,
        experiments = experiments,
        screens = listOf(WelcomeScreenConfig(ScreenBase("only"), "Only", cta = Cta("Done"))),
    )

    @Test fun completionAndDismissalUseSeparateCallbacksAndAreIdempotent() {
        val variable = FlowVariable("answer", VariableType.string, defaultValue = VariableDefault.Scalar(ScalarValue.StringValue("yes")))
        val completionSink = InMemoryEventSink()
        val completed = FlowEngine(config(listOf(variable)), eventSink = completionSink)
        var completionCalls = 0
        var dismissCalls = 0
        completed.onComplete { values -> completionCalls++; assertEquals("yes", values.single().value.jsonPrimitive.content) }
        completed.onDismiss { dismissCalls++ }
        completed.complete("purchased")
        completed.complete()
        completed.dismiss()
        assertEquals(1, completionCalls); assertEquals(0, dismissCalls)
        assertEquals(1, completionSink.events.count { it.payload.jsonObject["type"]?.jsonPrimitive?.content == "flow_completed" })

        val dismissSink = InMemoryEventSink()
        val dismissed = FlowEngine(config(listOf(variable)), eventSink = dismissSink)
        var dismissedCompletionCalls = 0
        var reason: String? = null
        dismissed.onComplete { dismissedCompletionCalls++ }
        dismissed.onDismiss { reason = it }
        dismissed.dismiss("host_closed")
        assertEquals(0, dismissedCompletionCalls); assertEquals("host_closed", reason)
        assertTrue(dismissed.isComplete)
    }

    @Test fun recordInteractionOverridesReservedFieldsAndMaintainsSequenceContract() {
        val sink = InMemoryEventSink()
        val engine = FlowEngine(config(), eventSink = sink)
        engine.recordInteraction("commit", "only", mapOf("type" to JsonPrimitive("wrong"), "seq" to JsonPrimitive(99), "choice" to JsonPrimitive("x")))
        val event = sink.events.last()
        val payload = event.payload as JsonObject
        assertEquals("commit", payload.getValue("type").jsonPrimitive.content)
        assertEquals(event.seq - 1, payload.getValue("seq").jsonPrimitive.content.toInt())
        assertEquals("x", payload.getValue("choice").jsonPrimitive.content)
        assertTrue(event.screenDwellMs != null)
    }

    @Test fun recordInteractionRedactsFreeFormInputBeforeItReachesTheEventSink() {
        val sink = InMemoryEventSink()
        val engine = FlowEngine(config(), eventSink = sink)

        engine.recordInteraction(
            "input_submitted",
            "only",
            mapOf("variable" to JsonPrimitive("name"), "value" to JsonPrimitive("Ada Lovelace")),
        )

        val payload = sink.events.last().payload.jsonObject
        assertEquals("[redacted]", payload.getValue("value").jsonPrimitive.content)
        assertEquals(true, payload.getValue("valueRedacted").jsonPrimitive.boolean)
        assertEquals(12, payload.getValue("valueLength").jsonPrimitive.int)
    }

    @Test fun healthFallbackUsesTheEngineSessionAndSequence() {
        val sink = InMemoryEventSink()
        val engine = FlowEngine(config(), userId = "host-user", eventSink = sink, sessionId = "flow-session")

        engine.recordHealthFallback("unrenderable_custom_component")

        val event = sink.events.last()
        val payload = event.payload.jsonObject
        assertEquals("health_flow_fallback", payload.getValue("type").jsonPrimitive.content)
        assertEquals("unrenderable_custom_component", payload.getValue("reason").jsonPrimitive.content)
        assertEquals("flow-session", event.sessionId)
        assertEquals(null, event.userId)
        assertEquals(2, event.seq)
        assertEquals(null, payload["seq"])
    }

    @Test fun nilUserUsesHoldoutAndFullyGatedEntryFailsClosedWithoutCompleting() {
        val experiment = FlowExperiment("exp", listOf(FlowExperiment.VariantWeight("treatment")))
        val gated = FlowConfig(
            4, "gated", "Gated", experiments = listOf(experiment),
            screens = listOf(WelcomeScreenConfig(ScreenBase("only", variant = VariantGate("exp", listOf("treatment"))), "Only", cta = Cta("Done"))),
        )
        val sink = InMemoryEventSink()
        val engine = FlowEngine(gated, userId = null, eventSink = sink)
        assertEquals(Bucketer.HOLDOUT, engine.assignments.getValue("exp").variant)
        assertNull(engine.currentScreenId)
        assertFalse(engine.isComplete)
        assertEquals(listOf("flow_started", "variant_assigned"), sink.events.map { it.payload.jsonObject.getValue("type").jsonPrimitive.content })
    }
}
