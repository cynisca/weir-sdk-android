package studio.aldric.weir.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.bridge.PermissionRequester
import studio.aldric.weir.bridge.PermissionStatus
import studio.aldric.weir.bridge.PermissionType
import studio.aldric.weir.engine.PermissionPrimeScreenConfig
import studio.aldric.weir.ui.LocalWeirPermissionRequester

@RunWith(RobolectricTestRunner::class)
class PermissionPrimeScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun grantedAlwaysAdvances() = assertOutcome(PermissionStatus.GRANTED, true)
    @Test fun deniedAlwaysAdvances() = assertOutcome(PermissionStatus.DENIED, false)
    private fun assertOutcome(status: PermissionStatus, granted: Boolean) {
        val (engine, sink) = engineFor("""{"id":"p","type":"permissionPrime","title":"Notifications","permission":"camera","primeCta":{"label":"Allow"},"resultVariable":"granted"}""")
        val requester = object : PermissionRequester { override suspend fun requestPermission(type: PermissionType) = status }
        sink.events.clear(); compose.setContent {
            ThemedScreen(engine) { CompositionLocalProvider(LocalWeirPermissionRequester provides requester) { PermissionPrimeScreen(engine.currentScreen as PermissionPrimeScreenConfig, engine) } }
        }
        compose.onNodeWithText("Allow").performClick(); compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(JsonPrimitive(granted), engine.variable("granted"))
            assertEquals(listOf("permission_prompt_shown", "permission_result", "branch_decision", "flow_completed"), sink.types())
            assertTrue(engine.isComplete)
        }
    }
}
