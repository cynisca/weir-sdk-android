package studio.aldric.weir.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.WeirJson
import java.io.File

class EngineConformanceTest {
    private class CapturingEventSink : EventSink {
        val events = mutableListOf<EventParams>()
        override fun append(event: EventParams) { events += event }
    }

    private fun repoRoot(): File {
        val moduleDir = File(System.getProperty("user.dir")!!)
        return moduleDir.parentFile!!.parentFile!!
    }

    @Test fun everyEngineConformanceFixturePassesIncludingExperimentDeclarationOrder() {
        val directory = File(repoRoot(), "packages/evals/fixtures/engine-conformance")
        assertTrue("engine-conformance fixture directory missing at ${directory.path}", directory.isDirectory)
        val files = directory.listFiles { file -> file.extension == "json" }!!.sortedBy { it.name }
        assertTrue("expected at least 20 conformance fixtures, found ${files.size}", files.size >= 20)
        assertTrue("fixture 24 must be present and must not be skipped", files.any { it.name == "24-multiple-experiment-event-order.json" })
        files.forEach(::runFixture)
    }

    private fun runFixture(file: File) {
        val root = WeirJson.parseToJsonElement(file.readText()).jsonObject
        val flow = WeirJson.decodeFromJsonElement(FlowConfig.serializer(), root.getValue("flow"))
        val userId = root["userId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        val registeredComponents = root["registeredComponents"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
        val invalidPropsComponents = root["invalidPropsComponents"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
        val expected = root.getValue("expected").jsonObject
        val sink = CapturingEventSink()
        val engine = FlowEngine(flow, userId, sink, "conformance-${file.name}", customScreenAvailability = { name, _ ->
            when {
                name in invalidPropsComponents -> CustomScreenAvailability.invalidProps
                registeredComponents == null || name in registeredComponents -> CustomScreenAvailability.renderable
                else -> CustomScreenAvailability.unregistered
            }
        })

        for (stepElement in root.getValue("script").jsonArray) {
            val step = stepElement.jsonObject
            when (val op = step.getValue("op").jsonPrimitive.content) {
                "setVariable" -> engine.setVariable(step.getValue("id").jsonPrimitive.content, step.getValue("value"))
                "submit" -> engine.submit(
                    step.getValue("screenId").jsonPrimitive.content,
                    step["explicitTarget"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull,
                )
                "skip" -> engine.skip(step.getValue("screenId").jsonPrimitive.content)
                "dismiss" -> step["reason"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.let(engine::dismiss) ?: engine.dismiss()
                "complete" -> step["reason"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.let(engine::complete) ?: engine.complete()
                else -> throw AssertionError("${file.name}: unknown op $op")
            }
        }

        val visited = sink.events.mapNotNull { event ->
            val payload = event.payload as? JsonObject
            if (payload?.get("type")?.jsonPrimitive?.content == "screen_impression") payload["screen"]?.jsonPrimitive?.content else null
        }
        assertEquals(file.name, expected.getValue("visitedScreens").jsonArray.map { it.jsonPrimitive.content }, visited)

        val expectedEvents = expected.getValue("events").jsonArray.map { it.jsonObject }
        assertEquals("${file.name}: event count", expectedEvents.size, sink.events.size)
        sink.events.zip(expectedEvents).forEachIndexed { index, (actual, expectedEvent) ->
            val prefix = "${file.name} event $index"
            val payload = actual.payload.jsonObject
            val expectsDwell = expectedEvent["screenDwellMs"]?.jsonPrimitive?.booleanOrNull == true
            assertEquals("$prefix: screenDwellMs presence", expectsDwell, actual.screenDwellMs != null)
            assertEquals("$prefix: envelope seq", index + 1, actual.seq)
            assertEquals("$prefix: payload seq", index, payload.getValue("seq").jsonPrimitive.int)
            for ((key, expectedValue) in expectedEvent) {
                if (key == "screenDwellMs") continue
                val actualValue: JsonElement? = when (key) {
                    "screenId" -> actual.screenId?.let(::JsonPrimitive)
                    "variantId" -> actual.variantId?.let(::JsonPrimitive)
                    else -> payload[key]
                }
                assertEquals("$prefix: $key", expectedValue, actualValue)
            }
        }

        val expectedVariables = expected.getValue("finalVariables").jsonObject
        assertEquals("${file.name}: final variable keys", expectedVariables.keys, engine.variables.keys)
        for ((key, value) in expectedVariables) assertEquals("${file.name}: finalVariables.$key", value, engine.variables[key])
    }
}
