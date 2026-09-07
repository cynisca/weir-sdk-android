package studio.aldric.weir.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.MultiSelectScreenConfig

@RunWith(RobolectricTestRunner::class)
class MultiSelectScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun minMaxGateAndSortedPayload() {
        val (engine, sink) = engineFor("""{"id":"m","type":"multiSelect","title":"Pick","variable":"v","minSelections":2,"maxSelections":2,"options":[{"id":"b","label":"B"},{"id":"a","label":"A"},{"id":"c","label":"C"}]}""")
        val config = engine.currentScreen as MultiSelectScreenConfig
        sink.events.clear()
        compose.setContent { ThemedScreen(engine) { MultiSelectScreen(config, engine) } }
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("B").performClick(); compose.onNodeWithText("A").performClick(); compose.onNodeWithText("C").performClick()
        compose.runOnIdle {
            assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), engine.variable("v"))
            assertEquals(2, sink.types().count { it == "quiz_answer" })
            assertFalse("branch_decision" in sink.types())
        }
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertEquals(listOf("quiz_answer", "quiz_answer", "branch_decision", "flow_completed"), sink.types()) }
    }
}
