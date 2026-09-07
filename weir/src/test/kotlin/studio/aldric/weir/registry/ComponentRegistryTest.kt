package studio.aldric.weir.registry

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import studio.aldric.weir.BuildConfig
import studio.aldric.weir.bridge.InMemoryEventSink

class ComponentRegistryTest {
    @Before fun setUp() = ComponentRegistry.resetForTesting()
    @After fun tearDown() = ComponentRegistry.resetForTesting()

    @Test fun registerAndResolve_roundTripsTypedProps() {
        ComponentRegistry.register(
            name = "example.greeting",
            propSchema = WeirPropSchema.objectOf(mapOf("title" to WeirPropSchema.StringType)),
            decode = { props -> props.jsonObject["title"]?.jsonPrimitive?.content },
            view = { },
        )

        assertTrue(ComponentRegistry.isRegistered("example.greeting"))
        assertNotNull(ComponentRegistry.resolveView("example.greeting", mapOf("title" to JsonPrimitive("Salaam"))))
    }

    @Test fun resolveView_unknownComponentReturnsNull() {
        assertNull(ComponentRegistry.resolveView("absent.component", emptyMap()))
    }

    @Test fun schemaHash_isDeterministicAndSortsObjectKeys() {
        val first = WeirPropSchema.objectOf(linkedMapOf(
            "zebra" to WeirPropSchema.NumberType,
            "apple" to WeirPropSchema.StringType,
        ))
        val second = WeirPropSchema.objectOf(linkedMapOf(
            "apple" to WeirPropSchema.StringType,
            "zebra" to WeirPropSchema.NumberType,
        ))

        assertEquals(first.schemaHash, first.schemaHash)
        assertEquals(first.schemaHash, second.schemaHash)
        assertEquals(
            "{\"apple\":{\"type\":\"string\"},\"zebra\":{\"type\":\"number\"}}",
            WeirPropSchema.canonicalize(first.jsonSchema.jsonObject.getValue("properties")),
        )
    }

    @Test fun driftCheck_emitsHealthEventForMismatch() {
        ComponentRegistry.register(
            name = "example.live",
            propSchema = WeirPropSchema.StringType,
            decode = { _: JsonElement -> "ok" },
            view = { },
        )
        ComponentRegistry.setGeneratedRegistry(registry("example.other", "different"))
        ComponentRegistry.debugAssertOnDrift = false
        val sink = InMemoryEventSink()

        ComponentRegistry.checkDriftOnce(sink)

        assertEquals(1, sink.events.size)
        val payload = sink.events.single().payload.jsonObject
        assertEquals("health_registry_drift", payload.getValue("type").jsonPrimitive.content)
        assertEquals(1, payload.getValue("liveCount").jsonPrimitive.content.toInt())
        assertEquals(1, payload.getValue("expectedCount").jsonPrimitive.content.toInt())
    }

    // RC-P0 (release-gate re-review): the health_registry_drift event must
    // fire BEFORE the debug-only assertion, so it's never masked by the
    // assertion throwing first. Unit tests run under the debug variant, so
    // debugAssertOnDrift defaults to BuildConfig.DEBUG == true here —
    // exactly the scenario that used to skip the event entirely.
    @Test fun driftCheck_emitsHealthEventBeforeThrowingWhenAssertionIsLeftAtItsDefault() {
        ComponentRegistry.register(
            name = "example.live",
            propSchema = WeirPropSchema.StringType,
            decode = { _: JsonElement -> "ok" },
            view = { },
        )
        ComponentRegistry.setGeneratedRegistry(registry("example.other", "different"))
        val sink = InMemoryEventSink()

        var threw = false
        try {
            ComponentRegistry.checkDriftOnce(sink)
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue("the debug-default assertion must still throw", threw)
        assertEquals(1, sink.events.size)
        assertEquals("health_registry_drift", sink.events.single().payload.jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun debugAssertOnDrift_defaultsToBuildConfigDebug() {
        assertEquals(BuildConfig.DEBUG, ComponentRegistry.debugAssertOnDrift)
    }

    @Test fun driftCheck_staysSilentForMatch() {
        val schema = WeirPropSchema.StringType
        ComponentRegistry.register("example.live", propSchema = schema, decode = { _: JsonElement -> "ok" }, view = { })
        ComponentRegistry.setGeneratedRegistry(registry("example.live", schema.schemaHash))
        val sink = InMemoryEventSink()

        ComponentRegistry.checkDriftOnce(sink)

        assertTrue(sink.events.isEmpty())
    }

    @Test fun registryGatingInfo_isAllOrNothing() {
        assertNull(ComponentRegistry.registryGatingInfo())
        ComponentRegistry.setGeneratedRegistry(registry("example.live", "schema-hash", version = 7, embeddedHash = "hash"))

        assertEquals(7 to "hash", ComponentRegistry.registryGatingInfo())
        assertEquals("hash", ComponentRegistry.registryFingerprint())
        assertFalse(ComponentRegistry.isRegistered("example.live"))
    }

    private fun registry(
        name: String,
        schemaHash: String,
        version: Int = 3,
        embeddedHash: String = "registry-hash",
    ) = object : WeirGeneratedComponentRegistry {
        override val registryManifestVersion = version
        override val registryHash = embeddedHash
        override val entries = listOf(GeneratedRegistryEntry(name, schemaHash))
    }
}
