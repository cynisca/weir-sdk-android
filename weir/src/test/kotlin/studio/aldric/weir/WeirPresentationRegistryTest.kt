package studio.aldric.weir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for the in-process presentation handoff + exactly-once completion
 * delivery ([WeirPresentationRegistry] / [WeirPresentationRequest]) — the
 * plumbing behind [Weir.present] that guarantees the caller's completion fires
 * once and only once no matter which terminal path wins.
 */
class WeirPresentationRegistryTest {

    private fun request(onResult: (WeirFlowResult) -> Unit) = WeirPresentationRequest(
        systemBarStyle = WeirSystemBarStyle.Default,
        content = { _, _ -> error("content must not be invoked in this unit test") },
        completion = onResult,
    )

    @Test
    fun deliverFiresCompletionExactlyOnce() {
        val results = mutableListOf<WeirFlowResult>()
        val req = request { results.add(it) }

        req.deliver(WeirFlowResult.Completed(emptyList()))
        req.deliver(WeirFlowResult.Dismissed("late"))
        req.deliver(WeirFlowResult.Failed(RuntimeException("later still")))

        assertEquals("completion must fire exactly once", 1, results.size)
        assertEquals(WeirFlowResult.Completed(emptyList()), results.first())
    }

    @Test
    fun hasDeliveredFlipsAfterFirstDelivery() {
        val req = request { }
        assertEquals(false, req.hasDelivered)
        req.deliver(WeirFlowResult.Dismissed("x"))
        assertEquals(true, req.hasDelivered)
    }

    @Test
    fun registerThenGetReturnsSameRequest() {
        val req = request { }
        val token = WeirPresentationRegistry.register(req)
        assertNotNull(token)
        assertSame(req, WeirPresentationRegistry.get(token))
    }

    @Test
    fun removeClearsEntry() {
        val req = request { }
        val token = WeirPresentationRegistry.register(req)
        WeirPresentationRegistry.remove(token)
        assertNull("removed token must not resolve (process-death path finishes)", WeirPresentationRegistry.get(token))
    }

    @Test
    fun getWithNullOrUnknownTokenIsNull() {
        assertNull(WeirPresentationRegistry.get(null))
        assertNull(WeirPresentationRegistry.get("never-registered"))
    }

    @Test
    fun distinctRegistrationsGetDistinctTokens() {
        val a = WeirPresentationRegistry.register(request { })
        val b = WeirPresentationRegistry.register(request { })
        assertEquals(false, a == b)
    }
}
