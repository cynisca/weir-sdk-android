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
import studio.aldric.weir.engine.TextInputScreenConfig
import studio.aldric.weir.engine.TextValidation

@RunWith(RobolectricTestRunner::class)
class TextInputScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun emailValidationSuggestionReplacementAndSubmit() {
        assertFalse(textInputValid("a@b", null, TextValidation.email)); assertTrue(textInputValid("a@b.c", null, TextValidation.email))
        val (engine, sink) = engineFor("""{"id":"t","type":"textInput","title":"Email","variable":"email","placeholder":"Address","validation":"email","suggestions":["me@example.com"]}""")
        val config = engine.currentScreen as TextInputScreenConfig
        sink.events.clear(); compose.setContent { ThemedScreen(engine) { TextInputScreen(config, engine) } }
        compose.onNode(hasSetTextAction()).performTextInput("bad")
        compose.onNodeWithText("Enter a valid email address").assertExists()
        compose.onNodeWithText("me@example.com").performClick(); compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle {
            assertEquals(JsonPrimitive("me@example.com"), engine.variable("email"))
            assertEquals(listOf("input_submitted", "branch_decision", "flow_completed"), sink.types())
        }
    }
    @Test fun skipIsNeverValidityGated() {
        val (engine, sink) = engineFor("""{"id":"t","type":"textInput","title":"Email","variable":"email","validation":"email","skipCta":{"label":"Skip"}}""")
        sink.events.clear(); compose.setContent { ThemedScreen(engine) { TextInputScreen(engine.currentScreen as TextInputScreenConfig, engine) } }
        compose.onNodeWithText("Skip").performClick(); compose.runOnIdle { assertTrue("screen_skipped" in sink.types()) }
    }
}
