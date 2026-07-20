package studio.aldric.weir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure tests for [WeirFlowActivity]'s first-paint helpers — the hex color
 * parser (mirrors packages/spec's Color token + iOS `UIColor(weirHex:)`) and
 * the best-effort manifest `theme.backgroundColor` read. These drive the
 * no-white-flash window background; the Activity lifecycle around them needs an
 * instrumented run.
 */
class WeirFlowActivityHelpersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesSixDigitHex() {
        // #0f1a14 → opaque ARGB.
        assertEquals(0xFF0F1A14.toInt(), WeirFlowActivity.parseWeirHexColor("#0f1a14"))
    }

    @Test
    fun parsesShorthandThreeDigitHex() {
        // #fff → white, expanded to #ffffff.
        assertEquals(0xFFFFFFFF.toInt(), WeirFlowActivity.parseWeirHexColor("#fff"))
    }

    @Test
    fun parsesEightDigitHexWithAlpha() {
        // #rrggbbaa: red at 50% alpha.
        val parsed = WeirFlowActivity.parseWeirHexColor("#ff000080")
        assertEquals(0x80FF0000.toInt(), parsed)
    }

    @Test
    fun toleratesMissingLeadingHash() {
        assertEquals(0xFF0F1A14.toInt(), WeirFlowActivity.parseWeirHexColor("0f1a14"))
    }

    @Test
    fun rejectsMalformedHex() {
        assertNull(WeirFlowActivity.parseWeirHexColor("#12"))
        assertNull(WeirFlowActivity.parseWeirHexColor("#zzzzzz"))
        assertNull(WeirFlowActivity.parseWeirHexColor(""))
    }

    @Test
    fun readsBackgroundFromManifest() {
        val root = tmp.newFolder("bundle")
        File(root, "manifest.json").writeText(
            """{"bundleId":"b","theme":{"backgroundColor":"#0f1a14"}}""",
        )
        assertEquals(0xFF0F1A14.toInt(), WeirFlowActivity.readManifestBackgroundColor(root))
    }

    @Test
    fun missingManifestReturnsNull() {
        val root = tmp.newFolder("empty")
        assertNull(WeirFlowActivity.readManifestBackgroundColor(root))
    }

    @Test
    fun manifestWithoutThemeReturnsNull() {
        val root = tmp.newFolder("no-theme")
        File(root, "manifest.json").writeText("""{"bundleId":"b"}""")
        assertNull(WeirFlowActivity.readManifestBackgroundColor(root))
    }

    @Test
    fun malformedManifestReturnsNullNotCrash() {
        val root = tmp.newFolder("bad")
        File(root, "manifest.json").writeText("this is not json {")
        assertNull(WeirFlowActivity.readManifestBackgroundColor(root))
    }
}
