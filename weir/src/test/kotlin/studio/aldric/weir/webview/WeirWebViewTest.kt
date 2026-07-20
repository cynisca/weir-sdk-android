package studio.aldric.weir.webview

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.aldric.weir.bridge.InMemoryEventSink
import java.io.File

/**
 * Robolectric-backed test for the `WebViewFeature.DOCUMENT_START_SCRIPT`
 * observability gap (android review P2): when the current WebView provider
 * doesn't support document-start injection, `window.__weirUserId` never gets
 * set before the bundle's first script runs — the same shape of regression
 * `userIdDocumentStartScript` (below) exists to close, silently reopened for
 * whatever devices lack the feature. That gap must be measurable via a health
 * event, not silent.
 *
 * `isDocumentStartScriptSupported` is an injection seam purely so this branch
 * is testable without depending on what a given Robolectric/emulator WebView
 * shadow happens to report for the real androidx WebKit feature check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeirWebViewTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun bundleRoot(): File {
        val dir = File.createTempFile("WeirWebViewTest-", "").apply {
            delete()
            mkdirs()
        }
        File(dir, "index.html").writeText("<html></html>")
        return dir
    }

    @Test
    fun emitsHealthEventWhenDocumentStartScriptUnsupported() {
        val eventSink = InMemoryEventSink()
        WeirWebView(
            context = context,
            flowId = "cob_intake",
            bundleRoot = bundleRoot(),
            userId = "user-123",
            eventSink = eventSink,
            isDocumentStartScriptSupported = { false },
        )

        val healthEvent = eventSink.events.firstOrNull {
            (it.payload as? JsonObject)?.get("type")?.jsonPrimitive?.content == "health_bridge_error"
        }
        assertTrue("expected a health_bridge_error event", healthEvent != null)
        val payload = healthEvent!!.payload as JsonObject
        assertEquals("userid_injection_unavailable", payload["code"]?.jsonPrimitive?.content)
        assertEquals("userIdDocumentStartScript", payload["method"]?.jsonPrimitive?.content)
    }

    // Note: the "supported" branch calls the real
    // `WebViewCompat.addDocumentStartJavaScript`, which Robolectric's WebView
    // shadow doesn't implement (throws `UnsupportedOperationException`) — not
    // exercisable in this pure-JVM unit test environment. The seam
    // (`isDocumentStartScriptSupported`) exists specifically so the
    // *unsupported* branch below is testable without that dependency.

    @Test
    fun noHealthEventWhenNoUserIdToInject() {
        // No userId ⇒ no script to inject in the first place, so the
        // unsupported-feature branch is never reached regardless of support.
        val eventSink = InMemoryEventSink()
        WeirWebView(
            context = context,
            flowId = "cob_intake",
            bundleRoot = bundleRoot(),
            userId = null,
            eventSink = eventSink,
            isDocumentStartScriptSupported = { false },
        )

        val hasInjectionGapEvent = eventSink.events.any {
            (it.payload as? JsonObject)?.get("code")?.jsonPrimitive?.content == "userid_injection_unavailable"
        }
        assertTrue("no userId means nothing to inject; no gap event expected", !hasInjectionGapEvent)
    }

    /**
     * R2-R1: `destroy()` must cancel the per-present coroutine scope so a bridge
     * coroutine parked on an in-flight permission request (the user back-pressed
     * while the OS dialog was up, so the launcher callback never fired) is
     * cancelled — not left `await()`-ing forever, strong-holding the destroyed
     * Activity + WebView for the process lifetime.
     */
    @Test
    fun destroy_cancelsPerPresentScopeAndUnblocksParkedPermissionCoroutine() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val neverCompletes = CompletableDeferred<Map<String, Boolean>>()
        var cancelled = false
        // Simulates the bridge's parked permission coroutine.
        scope.launch {
            try {
                neverCompletes.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }

        val webView = WeirWebView(
            context = context,
            flowId = "cob_intake",
            bundleRoot = bundleRoot(),
            eventSink = InMemoryEventSink(),
            scope = scope,
        )

        assertFalse("scope must be active before destroy()", scope.coroutineContext[Job]!!.isCancelled)
        webView.destroy()

        assertTrue("destroy() must cancel the per-present scope", scope.coroutineContext[Job]!!.isCancelled)
        assertTrue("the parked permission coroutine must be cancelled, not leaked", cancelled)
    }
}
