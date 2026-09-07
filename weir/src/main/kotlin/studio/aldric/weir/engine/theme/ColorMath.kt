package studio.aldric.weir.engine.theme

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** sRGB parsing and WCAG colour math, ported from the renderer's color.ts. */
object ColorMath {
    data class Rgb(val r: Double, val g: Double, val b: Double)
    data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double) {
        val rgb: Rgb get() = Rgb(r, g, b)
    }

    fun parseCssColor(input: String): Rgba? {
        val value = input.trim().lowercase()
        when (value) {
            "transparent" -> return Rgba(0.0, 0.0, 0.0, 0.0)
            "white" -> return Rgba(255.0, 255.0, 255.0, 1.0)
            "black" -> return Rgba(0.0, 0.0, 0.0, 1.0)
        }

        functionInner(value)?.let { inner ->
            val parts = inner.replace('/', ' ').split(Regex("[,\\s]+")).filter(String::isNotEmpty)
            if (parts.size < 3) return null
            val numbers = parts.take(3).map { it.toDoubleOrNull() ?: return null }
            val alpha = if (parts.size > 3) parts[3].toDoubleOrNull() ?: 1.0 else 1.0
            return Rgba(numbers[0], numbers[1], numbers[2], alpha)
        }

        val hex = hexDigits(value) ?: return null
        fun expand(segment: String): Double {
            val expanded = if (segment.length == 1) segment.repeat(2) else segment
            return expanded.toInt(16).toDouble()
        }
        return when (hex.length) {
            3, 4 -> Rgba(
                expand(hex.substring(0, 1)),
                expand(hex.substring(1, 2)),
                expand(hex.substring(2, 3)),
                if (hex.length == 4) expand(hex.substring(3, 4)) / 255 else 1.0,
            )
            6, 8 -> Rgba(
                expand(hex.substring(0, 2)),
                expand(hex.substring(2, 4)),
                expand(hex.substring(4, 6)),
                if (hex.length == 8) expand(hex.substring(6, 8)) / 255 else 1.0,
            )
            else -> null
        }
    }

    private fun functionInner(value: String): String? {
        for (prefix in listOf("rgba(", "rgb(")) {
            if (value.startsWith(prefix) && value.endsWith(')')) {
                val inner = value.substring(prefix.length, value.length - 1)
                if (inner.isNotEmpty() && ')' !in inner) return inner
            }
        }
        return null
    }

    private fun hexDigits(value: String): String? {
        if (!value.startsWith('#')) return null
        val digits = value.drop(1)
        if (digits.length !in setOf(3, 4, 6, 8) || digits.any { !it.isDigit() && it !in 'a'..'f' }) return null
        return digits
    }

    fun composite(fg: Rgb, alpha: Double, over: Rgb): Rgb {
        val a = alpha.coerceIn(0.0, 1.0)
        return Rgb(fg.r * a + over.r * (1 - a), fg.g * a + over.g * (1 - a), fg.b * a + over.b * (1 - a))
    }

    fun relativeLuminance(c: Rgb): Double {
        fun channel(value: Double): Double {
            val s = value.coerceIn(0.0, 255.0) / 255
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
    }

    fun contrastRatio(a: Rgb, b: Rgb): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun wcagThreshold(fontSizePx: Double, fontWeight: Double): Double =
        if (fontSizePx >= 24 || (fontWeight >= 700 && fontSizePx >= 18.66)) 3.0 else 4.5

    fun rgbToHex(c: Rgb): String {
        fun hex(value: Double) = String.format(Locale.ROOT, "%02x", value.coerceIn(0.0, 255.0).roundToInt())
        return "#${hex(c.r)}${hex(c.g)}${hex(c.b)}"
    }

    val momentScrimRgb = Rgb(14.0, 22.0, 16.0)
    const val momentTextFallback = "#f7f5ef"

    fun autoForeground(background: String): String {
        val bg = parseCssColor(background)?.rgb ?: return "#ffffff"
        val light = Rgb(255.0, 255.0, 255.0)
        val dark = Rgb(17.0, 17.0, 17.0)
        return if (contrastRatio(light, bg) >= contrastRatio(dark, bg)) "#ffffff" else "#111111"
    }

    fun mutedForeground(text: String, background: String, surface: String? = null, threshold: Double = 4.5): String {
        val fg = parseCssColor(text) ?: return text
        val bg = parseCssColor(background) ?: return text
        val surfaceRgb = surface?.let(::parseCssColor)?.rgb
        fun clears(color: Rgb) = contrastRatio(color, bg.rgb) >= threshold &&
            (surfaceRgb == null || contrastRatio(color, surfaceRgb) >= threshold)
        if (!clears(fg.rgb)) return text
        var best = fg.rgb
        for (step in 1..20) {
            val candidate = composite(bg.rgb, step.toDouble() / 20, fg.rgb)
            if (clears(candidate)) best = candidate else break
        }
        return rgbToHex(best)
    }

    fun legibleOverScrim(themeText: String, themeBackground: String, threshold: Double = 4.5): String {
        var bestValue: String? = null
        var bestRatio = Double.NEGATIVE_INFINITY
        for (candidate in listOf(themeText, themeBackground)) {
            val parsed = parseCssColor(candidate) ?: continue
            if (parsed.a < 1) continue
            val ratio = contrastRatio(parsed.rgb, momentScrimRgb)
            if (ratio > bestRatio) {
                bestValue = candidate
                bestRatio = ratio
            }
            if (candidate == themeText && ratio >= threshold) return candidate
        }
        return if (bestValue != null && bestRatio >= threshold) bestValue else momentTextFallback
    }

    fun readableOn(color: String, backdrop: String, threshold: Double = 4.5): String {
        val fg = parseCssColor(color) ?: return color
        val background = parseCssColor(backdrop) ?: return color
        if (contrastRatio(fg.rgb, background.rgb) >= threshold) return color
        val target = if (relativeLuminance(background.rgb) > 0.18) Rgb(0.0, 0.0, 0.0) else Rgb(255.0, 255.0, 255.0)
        var current = fg.rgb
        for (step in 1..20) {
            current = composite(target, step.toDouble() / 20, fg.rgb)
            if (contrastRatio(current, background.rgb) >= threshold) return rgbToHex(current)
        }
        return rgbToHex(current)
    }
}
