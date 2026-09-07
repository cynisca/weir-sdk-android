package studio.aldric.weir.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.SingleSelectScreenConfig
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.WeirTheme

@RunWith(RobolectricTestRunner::class)
class SingleSelectScreenTest {
    @get:Rule val compose = createComposeRule()

    private val base = """"id":"choice","type":"singleSelect","title":"Choose","variable":"answer","options":[{"id":"a","label":"A"},{"id":"b","label":"B","value":"custom"}]"""

    @Test fun autoAdvanceSetsRecordsAndSubmitsWithoutCta() {
        val (engine, sink) = engineFor("""{$base,"autoAdvance":true}""")
        val config = engine.currentScreen as SingleSelectScreenConfig
        sink.events.clear()
        compose.setContent { Themed(config, engine) }
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.onNodeWithText("A").performClick()
        compose.runOnIdle {
            assertEquals(JsonPrimitive("a"), engine.variable("answer"))
            assertTrue("quiz_answer" in sink.types())
            assertTrue("branch_decision" in sink.types())
        }
    }

    @Test fun manualModeRequiresSelectionThenSecondTap() {
        val (engine, sink) = engineFor("""{$base,"autoAdvance":false,"required":true}""")
        val config = engine.currentScreen as SingleSelectScreenConfig
        sink.events.clear()
        compose.setContent { Themed(config, engine) }
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle { assertFalse("branch_decision" in sink.types()) }
        compose.onNodeWithText("B").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle {
            assertEquals(JsonPrimitive("custom"), engine.variable("answer"))
            assertTrue("branch_decision" in sink.types())
        }
    }

    @Test fun priorVariableRestoresMatchingOption() {
        val (engine, _) = engineFor("""{$base,"autoAdvance":false}""")
        engine.setVariable("answer", JsonPrimitive("custom"))
        val config = engine.currentScreen as SingleSelectScreenConfig
        compose.setContent { Themed(config, engine) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Selected").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun Themed(config: SingleSelectScreenConfig, engine: studio.aldric.weir.engine.FlowEngine) {
        CompositionLocalProvider(LocalWeirTheme provides WeirTheme.make(engine.config, config)) {
            SingleSelectScreen(config, engine)
        }
    }
}
