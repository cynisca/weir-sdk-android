package studio.aldric.weir.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.NumberInputScreenConfig

@RunWith(RobolectricTestRunner::class)
class NumberInputScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun rangeValidationAndSubmit() {
        assertFalse(numberInputValid("4", 5.0, 10.0)); assertTrue(numberInputValid("7.5", 5.0, 10.0))
        val (engine, sink) = engineFor("""{"id":"n","type":"numberInput","title":"Age","variable":"age","min":5,"max":10,"placeholder":"Number"}""")
        val config = engine.currentScreen as NumberInputScreenConfig
        sink.events.clear(); compose.setContent { ThemedScreen(engine) { NumberInputScreen(config, engine) } }
        compose.onNode(hasSetTextAction()).performTextInput("7")
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle {
            assertEquals(JsonPrimitive(7.0), engine.variable("age"))
            assertEquals(listOf("input_submitted", "branch_decision", "flow_completed"), sink.types())
        }
    }
    @Test fun stepperRendersDistinctButtons() {
        val (engine, _) = engineFor("""{"id":"n","type":"numberInput","title":"Age","variable":"age","style":"stepper"}""")
        compose.setContent { ThemedScreen(engine) { NumberInputScreen(engine.currentScreen as NumberInputScreenConfig, engine) } }
        compose.onNodeWithText("0").assertExists()
    }
}
