package studio.aldric.weir.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.WelcomeScreenConfig
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.WeirTheme

@RunWith(RobolectricTestRunner::class)
class WelcomeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ctaSubmitsWithoutInteractionEvent() {
        val (engine, sink) = engineFor("""{"id":"welcome","type":"welcome","title":"Welcome","cta":{"label":"Begin"}}""")
        val config = engine.currentScreen as WelcomeScreenConfig
        sink.events.clear()
        compose.setContent {
            CompositionLocalProvider(LocalWeirTheme provides WeirTheme.make(engine.config, config)) {
                WelcomeScreen(config, engine)
            }
        }
        compose.onNodeWithText("Begin").performClick()
        compose.runOnIdle {
            assertTrue("branch_decision" in sink.types())
            assertFalse(sink.types().any { it in setOf("quiz_answer", "purchase_intent", "purchase_result") })
        }
    }

    @Test fun headlineMotionNoneAndReducedMotionBothRevealImmediately() {
        assertTrue(shouldRevealImmediately(false, studio.aldric.weir.engine.HeadlineMotion.none))
        assertTrue(shouldRevealImmediately(true, studio.aldric.weir.engine.HeadlineMotion.fadeUp))
        assertFalse(shouldRevealImmediately(false, studio.aldric.weir.engine.HeadlineMotion.fadeUp))
    }
}
