package studio.aldric.weir.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.SliderScreenConfig

@RunWith(RobolectricTestRunner::class)
class SliderScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultOnlySubmitsOnCtaAndMathClampsSnaps() {
        assertEquals(4.0, snapSliderValue(3.7, 0.0, 10.0, 2.0), 0.0)
        assertEquals(10.0, snapSliderValue(99.0, 0.0, 10.0, 2.0), 0.0)
        val (engine, sink) = engineFor("""{"id":"s","type":"slider","title":"Value","variable":"v","min":0,"max":10,"step":2,"default":4}""")
        val config = engine.currentScreen as SliderScreenConfig
        sink.events.clear(); compose.setContent { ThemedScreen(engine) { SliderScreen(config, engine) } }
        compose.runOnIdle { assertNull(engine.variable("v")) }
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle {
            assertEquals(JsonPrimitive(4.0), engine.variable("v"))
            assertEquals(listOf("input_submitted", "branch_decision", "flow_completed"), sink.types())
        }
    }
}
