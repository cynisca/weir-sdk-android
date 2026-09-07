package studio.aldric.weir.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.bridge.WeirJson
import studio.aldric.weir.engine.FlowConfig
import studio.aldric.weir.engine.WelcomeScreenConfig
import studio.aldric.weir.engine.theme.RoleName

@RunWith(RobolectricTestRunner::class)
class WeirThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wiresColorsRolesAndFontFallbacks() {
        val config = WeirJson.decodeFromString(
            FlowConfig.serializer(),
            """{"specVersion":1,"id":"f","name":"F","theme":{"colors":{"primary":"#123456"}},"screens":[{"id":"w","type":"welcome","title":"Hi","cta":{"label":"Go"}}]}""",
        )
        val screen = config.screens.single() as WelcomeScreenConfig
        val theme = WeirTheme.make(config, screen)
        assertEquals(Color(0xFF123456), theme.fill(RoleName.ctaPrimary))

        val registered = FontFamily.Serif
        WeirFontRegistry.register("Registered", registered)
        var systemFamily: FontFamily? = null
        var registeredFamily: FontFamily? = null
        var missingFamily: FontFamily? = null
        compose.setContent {
            systemFamily = theme.font(WeirTextStyle.Body).fontFamily
            val registeredConfig = config.copy(theme = config.theme.copy(fontFamily = "Registered"))
            registeredFamily = WeirTheme.make(registeredConfig, screen).font(WeirTextStyle.Body).fontFamily
            val missingConfig = config.copy(theme = config.theme.copy(fontFamily = "Definitely Missing"))
            missingFamily = WeirTheme.make(missingConfig, screen).font(WeirTextStyle.Body).fontFamily
        }
        compose.runOnIdle {
            assertEquals(FontFamily.Default, systemFamily)
            assertEquals(registered, registeredFamily)
            assertEquals(FontFamily.Default, missingFamily)
        }
    }
}
