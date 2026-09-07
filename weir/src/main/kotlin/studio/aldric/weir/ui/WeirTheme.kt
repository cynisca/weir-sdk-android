package studio.aldric.weir.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.aldric.weir.engine.FlowConfig
import studio.aldric.weir.engine.ScreenConfig
import studio.aldric.weir.engine.ThemeConfig
import studio.aldric.weir.engine.ThemeMotion
import studio.aldric.weir.engine.theme.ColorMath
import studio.aldric.weir.engine.theme.RoleChannelKind
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.engine.theme.RoleProvenance
import studio.aldric.weir.engine.theme.RoleResolver
import studio.aldric.weir.engine.theme.ScreenRoleOverrides

enum class WeirTextStyle { Title, Subtitle, Body, Caption, Headline, Kicker, CtaLabel }
enum class RadiusToken { Sm, Md, Lg }

/**
 * Registry seam for flow-declared Android font families. Unlike iOS, Android cannot enumerate
 * bundled font resources by a display-family name. Hosts therefore register the Compose
 * [FontFamily] they bundled. Unknown names deliberately fall straight back to the platform
 * default: `Typeface.create(name, ...)` silently substitutes unknown names and cannot reliably
 * tell Weir whether it found the requested family.
 */
object WeirFontRegistry {
    private val families = mutableMapOf<String, FontFamily>()

    @Synchronized
    fun register(familyName: String, fontFamily: FontFamily) {
        families[familyName] = fontFamily
    }

    @Synchronized
    internal fun resolve(familyName: String): FontFamily? = families[familyName]
}

class WeirTheme(
    private val provenance: RoleProvenance,
    private val themeConfig: ThemeConfig,
    private val screenType: String,
    private val screenId: String,
    private val resolvedColors: Map<String, String>,
) {
    fun colorToken(name: String): Color = parseColor(resolvedColors[name] ?: defaultColors[name])

    val backgroundColor get() = colorToken("background")
    val surfaceColor get() = colorToken("surface")
    val borderColor get() = colorToken("border")
    val textColor get() = colorToken("text")
    val mutedTextColor get() = colorToken("textMuted")
    val dimTextColor get() = if (resolvedColors["textDim"] != null) colorToken("textDim") else mutedTextColor

    fun fill(role: RoleName): Color = parseColor(resolvedChannel(role, RoleChannelKind.fill))
    fun text(role: RoleName): Color = parseColor(resolvedChannel(role, RoleChannelKind.text))

    @Composable
    fun font(style: WeirTextStyle): TextStyle {
        val baseFamily = when (style) {
            WeirTextStyle.Title, WeirTextStyle.Headline -> themeConfig.fontFamilyDisplay ?: themeConfig.fontFamily
            else -> themeConfig.fontFamily
        }
        val familyName = if (style == WeirTextStyle.Kicker) themeConfig.fontFamilyLabel ?: baseFamily else baseFamily
        val family = if (familyName.isEmpty() || familyName == "system-ui") {
            FontFamily.Default
        } else {
            WeirFontRegistry.resolve(familyName) ?: FontFamily.Default
        }
        val (size, weight) = when (style) {
            WeirTextStyle.Title -> 28.sp to FontWeight.Bold
            WeirTextStyle.Headline -> 32.sp to FontWeight.Bold
            WeirTextStyle.Subtitle -> 17.sp to FontWeight.Normal
            WeirTextStyle.Body -> 16.sp to FontWeight.Normal
            WeirTextStyle.Caption -> 13.sp to FontWeight.Normal
            WeirTextStyle.Kicker -> 13.sp to FontWeight.SemiBold
            WeirTextStyle.CtaLabel -> 17.sp to FontWeight.SemiBold
        }
        return TextStyle(fontFamily = family, fontSize = size, fontWeight = weight)
    }

    fun radius(token: RadiusToken): Dp = when (token) {
        RadiusToken.Sm -> themeConfig.radius.sm.dp
        RadiusToken.Md -> themeConfig.radius.md.dp
        RadiusToken.Lg -> themeConfig.radius.lg.dp
    }

    val spacing: Dp get() = themeConfig.spacing.dp
    val animationDurationMs: Int get() = themeConfig.animation.durationMs ?: 240
    val motion: ThemeMotion get() = themeConfig.motion

    private fun resolvedChannel(role: RoleName, channel: RoleChannelKind): String? {
        fun from(scope: Map<RoleName, studio.aldric.weir.engine.theme.ResolvedRole>?): String? {
            val resolved = scope?.get(role) ?: return null
            return if (channel == RoleChannelKind.fill) resolved.fill?.value else resolved.text?.value
        }
        return from(provenance.byScreenId[screenId])
            ?: from(provenance.byScreenType[screenType])
            ?: from(provenance.base)
    }

    private fun parseColor(raw: String?): Color {
        val parsed = raw?.let(ColorMath::parseCssColor) ?: return Color.Transparent
        return Color(
            red = (parsed.r / 255.0).toFloat(),
            green = (parsed.g / 255.0).toFloat(),
            blue = (parsed.b / 255.0).toFloat(),
            alpha = parsed.a.toFloat(),
        )
    }

    companion object {
        val defaultColors: Map<String, String> = linkedMapOf(
            "background" to "#0e0e14",
            "surface" to "#1a1a24",
            "text" to "#ffffff",
            "textMuted" to "#9aa0b4",
            "primary" to "#6c5ce7",
            "onPrimary" to "#ffffff",
            "accent" to "#00d2a8",
            "onAccent" to "#0e0e14",
            "border" to "#2a2a3a",
            "success" to "#28c76f",
            "danger" to "#ea5455",
        )

        fun make(config: FlowConfig, currentScreen: ScreenConfig): WeirTheme {
            val colors = defaultColors + config.theme.colors.asDictionary()
            val screens = config.screens.map { ScreenRoleOverrides(it.base.id, it.typeName, it.base.roles) }
            return WeirTheme(
                provenance = RoleResolver.resolveRoles(colors, config.theme.roles, screens),
                themeConfig = config.theme,
                screenType = currentScreen.typeName,
                screenId = currentScreen.base.id,
                resolvedColors = colors,
            )
        }

        val default: WeirTheme by lazy {
            WeirTheme(
                RoleResolver.resolveRoles(defaultColors, null),
                ThemeConfig(),
                "",
                "",
                defaultColors,
            )
        }
    }
}

val LocalWeirTheme = staticCompositionLocalOf { WeirTheme.default }

@Composable
fun weirReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
