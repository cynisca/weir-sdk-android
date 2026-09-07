package studio.aldric.weir.engine.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleResolverTest {
    private val lightColors = linkedMapOf(
        "background" to "#ffffff", "surface" to "#ffffff", "text" to "#1a1a1a",
        "primary" to "#6c5ce7", "onPrimary" to "#ffffff", "accent" to "#feca57",
        "onAccent" to "#1a1a1a", "border" to "#e0e0e0",
    )

    @Test fun tierPrecedenceForKickerMatchesRolesTs() {
        val derivedText = RoleResolver.resolveRoles(lightColors, null).base[RoleName.kicker]?.text
        assertNotNull(derivedText?.value)
        assertEquals("theme.colors.accent", derivedText?.source)

        val themeText = RoleResolver.resolveRoles(lightColors, mapOf(RoleName.kicker to RoleColorOverride(text = "#ff00ff"))).base[RoleName.kicker]?.text
        assertEquals("#ff00ff", themeText?.value)
        assertNotEquals(derivedText?.value, themeText?.value)
        assertEquals("theme.roles.kicker.text", themeText?.source)

        val resolved = RoleResolver.resolveRoles(
            lightColors,
            mapOf(RoleName.kicker to RoleColorOverride(text = "#ff00ff")),
            listOf(ScreenRoleOverrides("w", "welcome", mapOf(RoleName.kicker to RoleColorOverride(text = "#00aa00")))),
        )
        val screenText = resolved.byScreenId["w"]?.get(RoleName.kicker)?.text
        assertEquals("#00aa00", screenText?.value)
        assertEquals("screens[w].roles.kicker.text", screenText?.source)
        assertNotEquals(themeText?.value, screenText?.value)
        assertNotEquals(derivedText?.value, screenText?.value)
    }

    @Test fun derivedTierActuallyAdjustsContrast() {
        val kicker = RoleResolver.resolveRoles(lightColors, null).base[RoleName.kicker]!!.text!!
        assertNotEquals(lightColors.getValue("accent").lowercase(), kicker.value.lowercase())
        val ratio = ColorMath.contrastRatio(ColorMath.parseCssColor(kicker.value)!!.rgb, ColorMath.parseCssColor(lightColors.getValue("surface"))!!.rgb)
        assertTrue(ratio >= 3.0)
    }

    @Test fun momentScreenUsesDarkScrimScope() {
        val resolved = RoleResolver.resolveRoles(
            lightColors, null,
            listOf(ScreenRoleOverrides("w", "welcome", null), ScreenRoleOverrides("m", "moment", null)),
        )
        val light = resolved.base[RoleName.kicker]!!.text!!
        val moment = resolved.byScreenType.getValue("moment").getValue(RoleName.kicker).text!!
        assertNotEquals(light.value, moment.value)
        assertTrue(ColorMath.contrastRatio(ColorMath.parseCssColor(moment.value)!!.rgb, ColorMath.momentScrimRgb) >= 3.0)
    }

    @Test fun derivedProvenanceMatchesRolesTs() {
        val derived = RoleResolver.resolveRoles(lightColors, null)
        assertEquals(true, derived.base[RoleName.kicker]?.text?.derived)
        val theme = RoleResolver.resolveRoles(lightColors, mapOf(RoleName.kicker to RoleColorOverride(text = "#aabbcc")))
        assertEquals(false, theme.base[RoleName.kicker]?.text?.derived)
        val screen = RoleResolver.resolveRoles(lightColors, null, listOf(ScreenRoleOverrides("w", "welcome", mapOf(RoleName.kicker to RoleColorOverride(text = "#ccbbaa")))))
        assertEquals(false, screen.byScreenId["w"]?.get(RoleName.kicker)?.text?.derived)
        assertEquals(false, derived.base[RoleName.ctaPrimary]?.fill?.derived)
        assertEquals(true, derived.base[RoleName.tint]?.fill?.derived)
    }

    @Test fun selfFilledTableMatchesRolesTs() {
        listOf(RoleName.ctaPrimary, RoleName.ctaAccent, RoleName.badge, RoleName.chatUser, RoleName.tint).forEach {
            assertTrue(RoleResolver.roleIsSelfFilled(it))
        }
        listOf(RoleName.kicker, RoleName.emphasis, RoleName.progress).forEach {
            assertFalse(RoleResolver.roleIsSelfFilled(it))
        }
    }
}
