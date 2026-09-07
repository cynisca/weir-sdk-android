package studio.aldric.weir.observe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.InMemoryEventSink

@RunWith(RobolectricTestRunner::class)
class WeirObserveTest {
    private lateinit var context: Context
    private var storeCounter = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun declaredOrderAndUndeclaredFallbackAreMapped() {
        val fixture = fixture(screens = listOf("welcome", "goals"))
        fixture.runtime.screen("goals", null, emptyMap())
        fixture.runtime.screen("surprise", null, emptyMap())

        val impressions = fixture.sink.events.filter { it.type == "screen_impression" }
        assertEquals(1, impressions[0].payload.jsonObject["index"]!!.jsonPrimitive.int)
        assertTrue(impressions[0].payload.jsonObject["declared"]!!.jsonPrimitive.boolean)
        assertNull(impressions[1].payload.jsonObject["index"])
        assertFalse(impressions[1].payload.jsonObject["declared"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun dwellUsesInjectedClockWithoutSleeping() {
        val fixture = fixture()
        fixture.runtime.screen("welcome", null, emptyMap())
        fixture.now.value += 275
        fixture.runtime.screen("goals", null, emptyMap())

        val exit = fixture.sink.events.single { it.type == "screen_exit" }
        assertEquals(275.0, exit.screenDwellMs!!, 0.0)
        assertEquals("advance", exit.payload.jsonObject["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun sessionAndSequencePersistAcrossRelaunch() {
        val prefs = newPrefs()
        val now = MutableClock(10_000)
        val firstSink = InMemoryEventSink()
        val first = runtime(prefs, now, firstSink, launchId = "launch-1")
        first.screen("welcome", null, emptyMap())
        val sessionId = firstSink.events.first().sessionId

        now.value += 100
        val secondSink = InMemoryEventSink()
        val second = runtime(prefs, now, secondSink, launchId = "launch-2")
        second.screen("goals", null, emptyMap())

        assertEquals(listOf(2, 3), secondSink.events.map(EventParams::seq))
        assertTrue(secondSink.events.all { it.sessionId == sessionId })
    }

    @Test
    fun timedOutRelaunchEmitsAbandonForPersistedLastScreen() {
        val prefs = newPrefs()
        val now = MutableClock(20_000)
        runtime(prefs, now, InMemoryEventSink(), launchId = "launch-1", timeoutMs = 500)
            .screen("goals", null, emptyMap())

        now.value += 501
        val relaunchedSink = InMemoryEventSink()
        runtime(prefs, now, relaunchedSink, launchId = "launch-2", timeoutMs = 500)

        val abandon = relaunchedSink.events.single()
        assertEquals("screen_exit", abandon.type)
        assertEquals("goals", abandon.payload.jsonObject["screen"]!!.jsonPrimitive.content)
        assertEquals("abandon", abandon.payload.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals("launch-2", abandon.launchId)
        assertNull(ObserveSessionStore(prefs).load())
    }

    @Test
    fun hostPropertiesCannotShadowPayloadRootFields() {
        val fixture = fixture()
        fixture.runtime.screen(
            "welcome", null,
            mapOf("type" to JsonPrimitive("host-type"), "index" to JsonPrimitive(99)),
        )

        val payload = fixture.sink.events.single { it.type == "screen_impression" }.payload.jsonObject
        assertEquals("screen_impression", payload["type"]!!.jsonPrimitive.content)
        assertEquals(0, payload["index"]!!.jsonPrimitive.int)
        val props = payload["props"]!!.jsonObject
        assertEquals("host-type", props["type"]!!.jsonPrimitive.content)
        assertEquals(99, props["index"]!!.jsonPrimitive.int)
    }

    @Test
    fun eventIdsAreUniqueAndLaunchIdIsStable() {
        val fixture = fixture(launchId = "one-process-launch")
        fixture.runtime.screen("welcome", null, emptyMap())
        fixture.runtime.track("cta_tapped", emptyMap())
        fixture.runtime.completed()

        val events = fixture.sink.events
        assertEquals(events.size, events.map(EventParams::eventId).toSet().size)
        assertTrue(events.all { it.launchId == "one-process-launch" })
        assertTrue(events.all { it.elapsedMs != null })
        assertTrue(events.all { it.configId == null && it.configVersion == null })
    }

    @Test
    fun purchaseAndPaywallWireValuesMatchContract() {
        val fixture = fixture()
        fixture.runtime.screen("welcome", null, emptyMap())
        fixture.runtime.paywallShown("annual", emptyMap())
        fixture.runtime.purchaseIntent("pro_yearly", emptyMap())
        fixture.runtime.purchaseResult("pro_yearly", PurchaseOutcome.CANCELLED, null)

        assertEquals(listOf("flow_started", "screen_impression", "paywall_shown", "purchase_intent", "purchase_result"), fixture.sink.events.map { it.type })
        assertEquals("cancelled", fixture.sink.events.last().payload.jsonObject["outcome"]!!.jsonPrimitive.content)
    }

    private fun fixture(
        screens: List<String> = listOf("welcome", "goals"),
        launchId: String = "launch",
    ): Fixture {
        val now = MutableClock(1_000)
        val sink = InMemoryEventSink()
        return Fixture(runtime(newPrefs(), now, sink, screens, launchId), sink, now)
    }

    private fun runtime(
        prefs: android.content.SharedPreferences,
        now: MutableClock,
        sink: InMemoryEventSink,
        screens: List<String> = listOf("welcome", "goals"),
        launchId: String,
        timeoutMs: Long = 1_800_000,
    ) = ObserveRuntime(
        flowId = "onboarding",
        declaredScreens = screens,
        initialUserId = "user-1",
        sessionTimeoutMs = timeoutMs,
        clock = now::read,
        launchId = launchId,
        sink = sink,
        store = ObserveSessionStore(prefs),
    )

    private fun newPrefs() = context.getSharedPreferences("observe-test-${storeCounter++}", Context.MODE_PRIVATE).also {
        it.edit().clear().commit()
    }

    private data class Fixture(val runtime: ObserveRuntime, val sink: InMemoryEventSink, val now: MutableClock)
    private class MutableClock(var value: Long) { fun read(): Long = value }
    private val EventParams.type: String get() = payload.jsonObject["type"]!!.jsonPrimitive.content
}
