package studio.aldric.weir.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.LoaderScreenConfig

@RunWith(RobolectricTestRunner::class)
class LoaderScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun completionSetsComputesRecordsThenAdvances() {
        val (engine, sink) = engineFor("""{"id":"l","type":"loader","durationMs":300,"messages":["One","Two"],"computes":["ready"]}""")
        val config = engine.currentScreen as LoaderScreenConfig
        sink.events.clear(); compose.mainClock.autoAdvance = false
        compose.setContent { ThemedScreen(engine) { LoaderScreen(config, engine) } }
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(JsonPrimitive(true), engine.variable("ready"))
            assertEquals(listOf("loader_completed", "branch_decision", "flow_completed"), sink.types())
        }
    }
}
