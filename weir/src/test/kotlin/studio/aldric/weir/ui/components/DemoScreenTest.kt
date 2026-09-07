package studio.aldric.weir.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.DemoScreenConfig

@RunWith(RobolectricTestRunner::class)
class DemoScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun ctaOnlyNavigates() {
        val (engine, sink) = engineFor("""{"id":"d","type":"demo","messages":[{"from":"user","text":"Hi"}],"cta":{"label":"Done"}}""")
        sink.events.clear(); compose.setContent { ThemedScreen(engine) { DemoScreen(engine.currentScreen as DemoScreenConfig, engine) } }
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(listOf("branch_decision", "flow_completed"), sink.types()) }
    }
}
