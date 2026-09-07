package studio.aldric.weir.engine.theme

import kotlinx.serialization.Serializable

@Serializable
enum class RoleName { ctaPrimary, ctaAccent, badge, chatUser, kicker, emphasis, tint, progress }

enum class RoleChannelKind { fill, text }

@Serializable
data class RoleColorOverride(val fill: String? = null, val text: String? = null) {
    internal fun value(channel: RoleChannelKind): String? = if (channel == RoleChannelKind.fill) fill else text
}

data class RoleChannel(val value: String, val source: String, val derived: Boolean)
data class ResolvedRole(val fill: RoleChannel? = null, val text: RoleChannel? = null) {
    internal fun get(channel: RoleChannelKind): RoleChannel? = if (channel == RoleChannelKind.fill) fill else text
    internal fun with(channel: RoleChannelKind, value: RoleChannel?): ResolvedRole =
        if (channel == RoleChannelKind.fill) copy(fill = value) else copy(text = value)
}

data class ScreenRoleOverrides(val id: String, val type: String, val roles: Map<RoleName, RoleColorOverride>?)
data class RoleProvenance(
    val base: Map<RoleName, ResolvedRole>,
    val byScreenType: Map<String, Map<RoleName, ResolvedRole>>,
    val byScreenId: Map<String, Map<RoleName, ResolvedRole>>,
    val tokens: Map<String, String>,
)

/** Three-tier role resolver ported from packages/renderer/src/roles.ts. */
object RoleResolver {
    private data class RoleDefault(
        val channels: List<RoleChannelKind>,
        val selfFilled: Boolean,
        val derive: (Map<String, String>, String) -> Map<RoleChannelKind, RoleChannel>,
    )

    private fun plain(token: String, path: String) = RoleChannel(token, path, false)

    private fun legibleText(token: String, path: String, backdrop: String, threshold: Double): RoleChannel {
        val value = ColorMath.readableOn(token, backdrop, threshold)
        return RoleChannel(value, path, value.lowercase() != token.lowercase())
    }

    private fun mix(color: String, pct: Double, over: String): String {
        val fg = ColorMath.parseCssColor(color) ?: return over
        val bg = ColorMath.parseCssColor(over) ?: return over
        return ColorMath.rgbToHex(ColorMath.composite(fg.rgb, pct, bg.rgb))
    }

    /** Explicit insertion order mirrors the source table and never depends on Map implementation details. */
    private val roleDefaults: Map<RoleName, RoleDefault> = linkedMapOf(
        RoleName.ctaPrimary to RoleDefault(listOf(RoleChannelKind.fill, RoleChannelKind.text), true) { c, _ ->
            mapOf(
                RoleChannelKind.fill to plain(c["primary"] ?: "", "theme.colors.primary"),
                RoleChannelKind.text to plain(c["onPrimary"] ?: "", "theme.colors.onPrimary"),
            )
        },
        RoleName.ctaAccent to RoleDefault(listOf(RoleChannelKind.fill, RoleChannelKind.text), true) { c, _ ->
            mapOf(
                RoleChannelKind.fill to plain(c["accent"] ?: "", "theme.colors.accent"),
                RoleChannelKind.text to plain(c["onAccent"] ?: "", "theme.colors.onAccent"),
            )
        },
        RoleName.badge to RoleDefault(listOf(RoleChannelKind.fill, RoleChannelKind.text), true) { c, _ ->
            mapOf(
                RoleChannelKind.fill to plain(c["accent"] ?: "", "theme.colors.accent"),
                RoleChannelKind.text to plain(c["onAccent"] ?: "", "theme.colors.onAccent"),
            )
        },
        RoleName.chatUser to RoleDefault(listOf(RoleChannelKind.fill, RoleChannelKind.text), true) { c, _ ->
            mapOf(
                RoleChannelKind.fill to plain(c["primary"] ?: "", "theme.colors.primary"),
                RoleChannelKind.text to plain(c["onPrimary"] ?: "", "theme.colors.onPrimary"),
            )
        },
        RoleName.kicker to RoleDefault(listOf(RoleChannelKind.text), false) { c, backdrop ->
            mapOf(RoleChannelKind.text to legibleText(c["accent"] ?: "", "theme.colors.accent", backdrop, 4.5))
        },
        RoleName.emphasis to RoleDefault(listOf(RoleChannelKind.text), false) { c, backdrop ->
            mapOf(RoleChannelKind.text to legibleText(c["accent"] ?: "", "theme.colors.accent", backdrop, 4.5))
        },
        RoleName.tint to RoleDefault(listOf(RoleChannelKind.fill, RoleChannelKind.text), true) { c, backdrop ->
            val fill = mix(c["accent"] ?: "", 0.16, backdrop)
            mapOf(
                RoleChannelKind.fill to RoleChannel(fill, "theme.colors.accent", true),
                RoleChannelKind.text to legibleText(c["accent"] ?: "", "theme.colors.accent", fill, 4.5),
            )
        },
        RoleName.progress to RoleDefault(listOf(RoleChannelKind.fill), false) { c, _ ->
            mapOf(RoleChannelKind.fill to plain(c["accent"] ?: "", "theme.colors.accent"))
        },
    )

    fun roleIsSelfFilled(role: RoleName): Boolean = roleDefaults[role]?.selfFilled ?: false

    val roleChannels: Map<RoleName, List<RoleChannelKind>> = linkedMapOf<RoleName, List<RoleChannelKind>>().apply {
        for (role in RoleName.entries) put(role, roleDefaults.getValue(role).channels)
    }

    private val themeColorKeyOrder = listOf(
        "background", "surface", "text", "textMuted", "primary", "onPrimary",
        "accent", "onAccent", "border", "success", "danger", "textDim",
    )

    private fun orderedTokenEntries(colors: Map<String, String>): List<Pair<String, String>> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Pair<String, String>>()
        for (key in themeColorKeyOrder) colors[key]?.let { result += key to it; seen += key }
        for (key in colors.keys.sorted()) if (key !in seen) result += key to colors.getValue(key)
        return result
    }

    private val momentBackdrop = ColorMath.rgbToHex(ColorMath.momentScrimRgb)

    fun resolveRoles(
        colors: Map<String, String>,
        themeRoles: Map<RoleName, RoleColorOverride>?,
        screens: List<ScreenRoleOverrides> = emptyList(),
    ): RoleProvenance {
        val surface = colors["surface"] ?: colors["background"] ?: "#ffffff"
        val base = resolveScope(colors, surface, themeRoles, { role, channel -> "theme.roles.${role.name}.${channel.name}" })
        val momentScope = resolveScope(colors, momentBackdrop, themeRoles, { role, channel -> "theme.roles.${role.name}.${channel.name}" })
        val byScreenId = linkedMapOf<String, Map<RoleName, ResolvedRole>>()
        for (screen in screens) {
            val overrides = screen.roles ?: continue
            val inherited = if (screen.type == "moment") momentScope else base
            val backdrop = if (screen.type == "moment") momentBackdrop else surface
            byScreenId[screen.id] = resolveScope(
                colors,
                backdrop,
                overrides,
                { role, channel -> "screens[${screen.id}].roles.${role.name}.${channel.name}" },
                inherited,
            )
        }
        val tokens = linkedMapOf<String, String>()
        for ((name, value) in orderedTokenEntries(colors)) tokens.putIfAbsent(value.lowercase(), "theme.colors.$name")
        return RoleProvenance(base, mapOf("moment" to momentScope), byScreenId, tokens)
    }

    private fun resolveScope(
        colors: Map<String, String>,
        backdrop: String,
        overrides: Map<RoleName, RoleColorOverride>?,
        overridePath: (RoleName, RoleChannelKind) -> String,
        inherited: Map<RoleName, ResolvedRole>? = null,
    ): Map<RoleName, ResolvedRole> {
        val out = linkedMapOf<RoleName, ResolvedRole>()
        for (role in RoleName.entries) {
            val definition = roleDefaults.getValue(role)
            val derived = definition.derive(colors, backdrop)
            var resolved = ResolvedRole()
            for (channel in definition.channels) {
                val value = overrides?.get(role)?.value(channel)?.let { RoleChannel(it, overridePath(role, channel), false) }
                    ?: inherited?.get(role)?.get(channel)
                    ?: derived[channel]
                resolved = resolved.with(channel, value)
            }
            out[role] = resolved
        }
        return out
    }
}
