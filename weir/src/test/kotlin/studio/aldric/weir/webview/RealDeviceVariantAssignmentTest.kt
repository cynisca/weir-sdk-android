package studio.aldric.weir.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the zero-`variant_assigned` bug — the Android port of
 * `sdk-ios/Tests/WeirTests/RealDeviceVariantAssignmentTests.swift`.
 *
 * Root cause on iOS: `packages/renderer/src/dom.ts`'s bucketer reads
 * `window.__weirUserId`, but nothing on the real present path ever injected it
 * before the flow booted, so every experiment silently rendered as holdout and
 * an on-device run completed with ZERO `variant_assigned` events.
 *
 * On Android the injection is [WeirWebView.userIdDocumentStartScript] applied
 * via `WebViewCompat.addDocumentStartJavaScript` at document start. Rather than
 * stand up a full WebView, this pins the load-bearing invariant the iOS bug
 * came down to: a real userId produces a document-start script that sets
 * `window.__weirUserId` (properly JSON-escaped), and a null userId injects
 * nothing — so the injection can never silently regress to "not present."
 */
class RealDeviceVariantAssignmentTest {

    @Test
    fun userIdProducesDocumentStartInjection() {
        val script = WeirWebView.userIdDocumentStartScript("real-device-test-user-1")
        assertTrue("must inject window.__weirUserId", script!!.contains("window.__weirUserId"))
        assertTrue("must carry the userId value", script.contains("real-device-test-user-1"))
    }

    @Test
    fun nullUserIdInjectsNothing() {
        // The iOS `if let userId` guard: no userId ⇒ no script injected. The
        // flow still renders, just excluded from any declared experiment.
        assertNull(WeirWebView.userIdDocumentStartScript(null))
    }

    @Test
    fun injectedUserIdIsJsonEscapedAndRoundTrips() {
        // A userId containing quotes/backslashes must not break out of the JS
        // string literal — JSON-escaping is what makes the injection safe.
        val hostile = """weird"user\id"""
        val script = WeirWebView.userIdDocumentStartScript(hostile)!!
        // Extract the literal after the assignment and confirm it parses back
        // to exactly the original userId.
        val literal = script.removePrefix("window.__weirUserId = ").removeSuffix(";")
        val parsed = (Json.parseToJsonElement(literal) as JsonPrimitive).content
        assertEquals(hostile, parsed)
    }

    @Test
    fun injectionMatchesJsonPrimitiveEscaping() {
        // Parity with the escaping the runtime expects (same shape iOS's
        // JSONEncoder-escaped literal produced).
        val userId = "user-42"
        val expected = "window.__weirUserId = ${JsonPrimitive(userId)};"
        assertEquals(expected, WeirWebView.userIdDocumentStartScript(userId))
    }
}
