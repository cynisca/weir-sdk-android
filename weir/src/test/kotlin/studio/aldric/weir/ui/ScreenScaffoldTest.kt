package studio.aldric.weir.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.Cta

@RunWith(RobolectricTestRunner::class)
class ScreenScaffoldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longContentDoesNotPushPinnedCtaOffscreen() {
        compose.setContent {
            ScreenScaffold(null, "Title", null, content = {
                repeat(60) { BasicText("Option $it") }
            }, cta = { CtaButton(Cta("Continue"), true) {} })
        }
        compose.onNodeWithText("Continue").assertIsDisplayed().assertHasClickAction()
        val titleBottom = compose.onNodeWithText("Title").fetchSemanticsNode().boundsInRoot.bottom
        val ctaTop = compose.onNodeWithText("Continue").fetchSemanticsNode().boundsInRoot.top
        assertTrue(ctaTop > titleBottom)
    }

    @Test fun disabledAndEnabledCtaExposeExpectedClickState() {
        assertTrue(ctaAlpha(false) == 0.5f)
        assertTrue(ctaAlpha(true) == 1f)
        compose.setContent {
            Column {
                CtaButton(Cta("Disabled"), false) {}
                CtaButton(Cta("Enabled"), true) {}
            }
        }
        compose.onNodeWithText("Disabled").assertIsNotEnabled()
        compose.onNodeWithText("Enabled").assertIsEnabled().assertHasClickAction()
    }
}
