package studio.aldric.weir.bridge

import kotlinx.serialization.json.jsonPrimitive
import studio.aldric.weir.billing.NoopPurchaseProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the router through its injected `respond` seam (no WebView needed),
 * exactly as `sdk-ios/Tests/WeirTests/BridgeRouterTests.swift` drives
 * `dispatch(...)`. Only the synchronous methods (capabilities/event/
 * complete/dismiss + decode failures) are exercised here — the async
 * permission/purchase/products handlers hop onto a coroutine scope and are out
 * of this pure-logic suite's scope.
 */
class BridgeRouterTest {

    private val responses = mutableListOf<String>()
    private val sink = InMemoryEventSink()

    private fun router(
        onComplete: ((List<WeirVariable>) -> Unit)? = null,
        onDismiss: ((String) -> Unit)? = null,
    ): BridgeRouter = BridgeRouter(
        purchaseProvider = NoopPurchaseProvider(),
        eventSink = sink,
        onComplete = onComplete,
        onDismiss = onDismiss,
        respond = { responses.add(it) },
    )

    private fun lastResponse(): BridgeResponseEnvelope =
        WeirJson.decodeFromString(BridgeResponseEnvelope.serializer(), responses.last())

    @Test
    fun capabilitiesReturnsVersion1AndFullMethodList() {
        router().postMessage("""{"id":"c1","method":"capabilities","params":{}}""")
        val response = lastResponse()
        assertTrue(response.ok)
        assertEquals("c1", response.id)
        val caps = WeirJson.decodeFromJsonElement(CapabilitiesResult.serializer(), response.result!!)
        assertEquals(1, caps.version)
        assertEquals(
            listOf(
                "permission.request", "purchase.start", "purchase.restore", "haptic",
                "event", "complete", "dismiss", "capabilities", "products.list",
            ),
            caps.methods,
        )
    }

    @Test
    fun malformedBodyReturnsOkFalseNotCrash() {
        router().postMessage("this is not json")
        val response = lastResponse()
        assertFalse(response.ok)
        assertNull(response.result)
        assertTrue(response.error!!.contains("malformed"))
    }

    @Test
    fun unknownMethodReturnsOkFalse() {
        router().postMessage("""{"id":"u1","method":"not.a.real.method","params":{}}""")
        val response = lastResponse()
        assertEquals("u1", response.id)
        assertFalse(response.ok)
        assertTrue(response.error!!.contains("unknown bridge method"))
    }

    @Test
    fun eventReachesEventSink() {
        router().postMessage(
            """{"id":"e1","method":"event","params":{"flowId":"flow1","sessionId":"sess1","ts":1000,"seq":0,"payload":{"kind":"screen_view"}}}""",
        )
        assertEquals(1, sink.events.size)
        assertEquals("flow1", sink.events.first().flowId)
        assertEquals("sess1", sink.events.first().sessionId)
        assertTrue(lastResponse().ok)
    }

    @Test
    fun completeInvokesCallbackWithDecodedVariables() {
        var received: List<WeirVariable>? = null
        router(onComplete = { received = it }).postMessage(
            """{"id":"cm1","method":"complete","params":{"variables":[{"id":"v1","type":"string","value":"hello"}]}}""",
        )
        assertEquals(1, received?.size)
        assertEquals("v1", received?.first()?.id)
        assertTrue(lastResponse().ok)
    }

    @Test
    fun dismissInvokesCallbackWithReason() {
        var reason: String? = null
        router(onDismiss = { reason = it }).postMessage(
            """{"id":"d1","method":"dismiss","params":{"reason":"user_closed"}}""",
        )
        assertEquals("user_closed", reason)
        assertTrue(lastResponse().ok)
    }

    @Test
    fun malformedParamsRejectedWithoutInvokingCallbackAndEmitsBridgeError() {
        var called = false
        router(onDismiss = { called = true }).postMessage(
            """{"id":"d2","method":"dismiss","params":{}}""",
        )
        assertFalse("handler callback must not fire on decode failure", called)
        val response = lastResponse()
        assertFalse(response.ok)
        // bridge_error health event emitted to the sink.
        assertEquals(1, sink.events.size)
        val payload = sink.events.first().payload as kotlinx.serialization.json.JsonObject
        assertEquals("health_bridge_error", payload["type"]!!.jsonPrimitive.content)
        assertEquals("dismiss", payload["method"]!!.jsonPrimitive.content)
        assertEquals("params_decode_failed", payload["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun hapticAcksImmediatelyWithoutEngine() {
        // No HapticEngine injected — must still ack ok:true, never crash.
        router().postMessage("""{"id":"h1","method":"haptic","params":{"style":"soft","intensity":0.5}}""")
        val response = lastResponse()
        assertTrue(response.ok)
        assertEquals("h1", response.id)
    }

    @Test
    fun successfulEventDoesNotEmitBridgeError() {
        router().postMessage(
            """{"id":"e2","method":"event","params":{"flowId":"f","sessionId":"s","ts":1000,"seq":0,"payload":{}}}""",
        )
        // Only the product event, no health_bridge_error.
        assertEquals(1, sink.events.size)
        assertEquals("f", sink.events.first().flowId)
    }
}
