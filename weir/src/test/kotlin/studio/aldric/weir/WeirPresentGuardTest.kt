package studio.aldric.weir

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import studio.aldric.weir.bridge.LoggingEventSink
import java.io.File

/**
 * R2-R2: two rapid [Weir.presentActivity] calls must not stack two overlapping
 * flow Activities (two live bridges/eventSinks on screen). The second call, made
 * while the first is still in flight, is rejected with a dismissal and never
 * starts a second Activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeirPresentGuardTest {

    @After
    fun tearDown() {
        // The guard lives on the Weir singleton; don't leak in-flight state.
        Weir.resetPresentationGuardForTest()
    }

    private fun bundleRoot(): File {
        val dir = File.createTempFile("WeirPresentGuardTest-", "").apply {
            delete()
            mkdirs()
        }
        File(dir, "index.html").writeText("<html></html>")
        return dir
    }

    @Test
    fun secondPresentWhileFirstInFlight_isRejectedNotStacked() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val results = mutableListOf<WeirFlowResult>()
        val root = bundleRoot()

        Weir.presentActivity(context = activity, flowId = "cob_intake", bundleRoot = root, eventSink = LoggingEventSink()) { results.add(it) }
        Weir.presentActivity(context = activity, flowId = "cob_intake", bundleRoot = root, eventSink = LoggingEventSink()) { results.add(it) }

        // The second call is rejected with a dismissal; the first is still in flight.
        assertEquals(1, results.size)
        val second = results[0]
        assertTrue("second present must be dismissed, not stacked", second is WeirFlowResult.Dismissed)
        assertEquals("already_presenting", (second as WeirFlowResult.Dismissed).reason)

        // Exactly ONE WeirFlowActivity was started — the second never reached startActivity.
        val shadow = Shadows.shadowOf(activity)
        val started = generateSequence { shadow.nextStartedActivity }.toList()
        val weirStarts = started.count { it.component?.className == WeirFlowActivity::class.java.name }
        assertEquals("only one flow Activity should be started", 1, weirStarts)
    }
}
