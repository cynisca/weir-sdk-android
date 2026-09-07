package studio.aldric.weir.engine.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {
    @Test fun parsesRendererSupportedCssForms() {
        assertEquals(ColorMath.Rgba(255.0, 255.0, 255.0, 1.0), ColorMath.parseCssColor("#fff"))
        assertEquals(ColorMath.Rgba(254.0, 202.0, 87.0, 1.0), ColorMath.parseCssColor("#feca57"))
        assertEquals(ColorMath.Rgba(14.0, 22.0, 16.0, 0.94), ColorMath.parseCssColor("rgba(14,22,16,0.94)"))
        assertEquals(ColorMath.Rgba(0.0, 0.0, 0.0, 0.0), ColorMath.parseCssColor("transparent"))
        assertEquals(null, ColorMath.parseCssColor("hsl(0 0% 0%)"))
    }

    @Test fun derivedLightAccentClearsRendererBackdropContrast() {
        val rawAccent = "#feca57"
        val derived = ColorMath.readableOn(rawAccent, "#ffffff")
        assertNotEquals(rawAccent.lowercase(), derived.lowercase())
        val ratio = ColorMath.contrastRatio(ColorMath.parseCssColor(derived)!!.rgb, ColorMath.parseCssColor("#ffffff")!!.rgb)
        assertTrue("derived=$derived ratio=$ratio", ratio >= 3.0)
    }

    @Test fun momentScrimConstantAndTextRemainLegible() {
        assertEquals("#0e1610", ColorMath.rgbToHex(ColorMath.momentScrimRgb))
        val text = ColorMath.legibleOverScrim("#feca57", "#ffffff")
        assertTrue(ColorMath.contrastRatio(ColorMath.parseCssColor(text)!!.rgb, ColorMath.momentScrimRgb) >= 3.0)
    }

    @Test fun wcagLargeTextThresholdMatchesRenderer() {
        assertEquals(3.0, ColorMath.wcagThreshold(24.0, 400.0), 0.0)
        assertEquals(3.0, ColorMath.wcagThreshold(18.66, 700.0), 0.0)
        assertEquals(4.5, ColorMath.wcagThreshold(18.65, 700.0), 0.0)
    }
}
