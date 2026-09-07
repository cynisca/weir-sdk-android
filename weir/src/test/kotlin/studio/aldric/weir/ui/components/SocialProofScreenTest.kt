package studio.aldric.weir.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.SocialProofScreenConfig

@RunWith(RobolectricTestRunner::class)
class SocialProofScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun ctaOnlyNavigates() {
        val (engine, sink) = engineFor("""{"id":"p","type":"socialProof","title":"Loved","testimonials":[{"quote":"Great","author":"A"}],"cta":{"label":"Next"}}""")
        sink.events.clear(); compose.setContent { ThemedScreen(engine) { SocialProofScreen(engine.currentScreen as SocialProofScreenConfig, engine) } }
        compose.onNodeWithText("Next").performClick()
        compose.runOnIdle { assertEquals(listOf("branch_decision", "flow_completed"), sink.types()) }
    }
}
