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
import studio.aldric.weir.engine.HeadlineMotion
import studio.aldric.weir.engine.MomentScreenConfig
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.WeirTheme

@RunWith(RobolectricTestRunner::class)
class MomentScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ctaSubmitsWithoutInteractionEvent() {
        val (engine, sink) = engineFor("""{"id":"moment","type":"moment","headline":"A *moment*","cta":{"label":"Continue"}}""")
        val config = engine.currentScreen as MomentScreenConfig
        sink.events.clear()
        compose.setContent {
            CompositionLocalProvider(LocalWeirTheme provides WeirTheme.make(engine.config, config)) { MomentScreen(config, engine) }
        }
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle {
            assertTrue("branch_decision" in sink.types())
            assertFalse(sink.types().any { it == "quiz_answer" || it.startsWith("purchase_") })
        }
    }

    @Test fun noneAndReducedMotionSkipRevealGate() {
        assertTrue(shouldRevealImmediately(false, HeadlineMotion.none))
        assertTrue(shouldRevealImmediately(true, HeadlineMotion.typewriter))
        assertFalse(shouldRevealImmediately(false, HeadlineMotion.fadeUp))
    }
}
