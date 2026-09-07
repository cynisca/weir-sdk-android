package studio.aldric.weir.engine

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.aldric.weir.bridge.PermissionType
import studio.aldric.weir.bridge.WeirJson
import studio.aldric.weir.engine.theme.RoleColorOverride
import studio.aldric.weir.engine.theme.RoleName

@Serializable(with = ScalarValueSerializer::class)
sealed interface ScalarValue {
    data class StringValue(val value: String) : ScalarValue
    data class NumberValue(val value: Double) : ScalarValue
    data class BooleanValue(val value: Boolean) : ScalarValue
}

object ScalarValueSerializer : KSerializer<ScalarValue> {
    override val descriptor = PrimitiveSerialDescriptor("ScalarValue", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): ScalarValue {
        val primitive = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("ScalarValue must be a JSON primitive")
        return when {
            primitive.isString -> ScalarValue.StringValue(primitive.content)
            primitive.booleanOrNull != null -> ScalarValue.BooleanValue(primitive.booleanOrNull!!)
            primitive.doubleOrNull != null -> ScalarValue.NumberValue(primitive.double)
            else -> throw SerializationException("Unsupported ScalarValue")
        }
    }
    override fun serialize(encoder: Encoder, value: ScalarValue) {
        val element = when (value) {
            is ScalarValue.StringValue -> JsonPrimitive(value.value)
            is ScalarValue.NumberValue -> JsonPrimitive(value.value)
            is ScalarValue.BooleanValue -> JsonPrimitive(value.value)
        }
        (encoder as JsonEncoder).encodeJsonElement(element)
    }
}

@Serializable(with = VariableDefaultSerializer::class)
sealed interface VariableDefault {
    data class Scalar(val value: ScalarValue) : VariableDefault
    data class StringArray(val value: List<String>) : VariableDefault
}

object VariableDefaultSerializer : KSerializer<VariableDefault> {
    override val descriptor = buildClassSerialDescriptor("VariableDefault")
    override fun deserialize(decoder: Decoder): VariableDefault {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return if (element is JsonArray) {
            VariableDefault.StringArray(element.map { it.jsonPrimitive.content })
        } else {
            VariableDefault.Scalar(WeirJson.decodeFromJsonElement(ScalarValueSerializer, element))
        }
    }
    override fun serialize(encoder: Encoder, value: VariableDefault) {
        val element = when (value) {
            is VariableDefault.Scalar -> WeirJson.encodeToJsonElement(ScalarValueSerializer, value.value)
            is VariableDefault.StringArray -> JsonArray(value.value.map(::JsonPrimitive))
        }
        (encoder as JsonEncoder).encodeJsonElement(element)
    }
}

@Serializable enum class SpecPlatform { @SerialName("ios") IOS, @SerialName("android") ANDROID, @SerialName("reactNative") REACT_NATIVE }
@Serializable enum class IconToken { book, moon, star, sun, sparkle, heart, leaf, cloud, question, compass, bell, lock, shield, prayer, quran, dhikr, sadaqah, clock, calendar, mail, check }
@Serializable enum class AssetToken {
    @SerialName("verse-hero-olive") VERSE_HERO_OLIVE,
    @SerialName("verse-hero-arch") VERSE_HERO_ARCH,
    @SerialName("path-reveal-arches") PATH_REVEAL_ARCHES,
    @SerialName("path-anchor") PATH_ANCHOR,
    @SerialName("welcome-hero") WELCOME_HERO,
}

@Serializable(with = ImageRefSerializer::class)
sealed interface ImageRef {
    data class Asset(val token: AssetToken) : ImageRef
    data class Custom(val id: String) : ImageRef
}

object ImageRefSerializer : KSerializer<ImageRef> {
    override val descriptor = PrimitiveSerialDescriptor("ImageRef", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): ImageRef {
        val raw = decoder.decodeString()
        val asset = runCatching { WeirJson.decodeFromString(AssetToken.serializer(), "\"$raw\"") }.getOrNull()
        return when {
            asset != null -> ImageRef.Asset(asset)
            raw.startsWith("custom:") -> ImageRef.Custom(raw.removePrefix("custom:"))
            else -> throw SerializationException("Unrecognized ImageRef \"$raw\"")
        }
    }
    override fun serialize(encoder: Encoder, value: ImageRef) {
        encoder.encodeString(when (value) {
            is ImageRef.Asset -> WeirJson.encodeToString(AssetToken.serializer(), value.token).trim('"')
            is ImageRef.Custom -> "custom:${value.id}"
        })
    }
}

@Serializable data class CustomAsset(val id: String, val path: String)
@Serializable data class Feature(val icon: IconToken? = null, val title: String, val subtitle: String? = null)
@Serializable data class DemoMessage(val from: From, val text: String, val sources: List<String>? = null) {
    @Serializable enum class From { user, app }
}
@Serializable data class Cta(val label: String, val variant: Variant = Variant.primary) {
    @Serializable enum class Variant { primary, accent }
}

@Serializable
data class ThemeColors(
    val background: String? = null, val surface: String? = null, val text: String? = null,
    val textMuted: String? = null, val primary: String? = null, val onPrimary: String? = null,
    val accent: String? = null, val onAccent: String? = null, val border: String? = null,
    val success: String? = null, val danger: String? = null, val textDim: String? = null,
) {
    fun asDictionary(): Map<String, String> = linkedMapOf<String, String>().apply {
        background?.let { put("background", it) }; surface?.let { put("surface", it) }
        text?.let { put("text", it) }; textMuted?.let { put("textMuted", it) }
        primary?.let { put("primary", it) }; onPrimary?.let { put("onPrimary", it) }
        accent?.let { put("accent", it) }; onAccent?.let { put("onAccent", it) }
        border?.let { put("border", it) }; success?.let { put("success", it) }
        danger?.let { put("danger", it) }; textDim?.let { put("textDim", it) }
    }
}
@Serializable data class ThemeRadius(val sm: Double = 0.0, val md: Double = 0.0, val lg: Double = 0.0)
@Serializable data class ThemeAnimation(val durationMs: Int? = null, val easing: String? = null)
@Serializable enum class HeadlineMotion { none, typewriter, @SerialName("fade-up") fadeUp }
@Serializable enum class AmbientMotion { none, particles }
@Serializable enum class ParticleSprite { leaf, petal, dust, star }
@Serializable enum class TransitionMotion { fade, slide, none }
@Serializable data class ParticleMotion(val sprite: ParticleSprite = ParticleSprite.leaf, val density: Double = 0.4)
@Serializable data class ThemeMotion(
    val headline: HeadlineMotion = HeadlineMotion.none,
    val ambient: AmbientMotion = AmbientMotion.none,
    val particle: ParticleMotion? = null,
    val transition: TransitionMotion = TransitionMotion.fade,
)
@Serializable data class ThemeConfig(
    val colors: ThemeColors = ThemeColors(),
    val roles: Map<RoleName, RoleColorOverride>? = null,
    val fontFamily: String = "system-ui",
    val fontFamilyDisplay: String? = null,
    val fontFamilyLabel: String? = null,
    val radius: ThemeRadius = ThemeRadius(),
    val spacing: Double = 16.0,
    val animation: ThemeAnimation = ThemeAnimation(),
    val motion: ThemeMotion = ThemeMotion(),
)

@Serializable enum class VariableType { string, number, boolean, stringArray, @SerialName("enum") enumType }
@Serializable data class FlowVariable(
    val id: String,
    val type: VariableType,
    val enumValues: List<String>? = null,
    @SerialName("default") val defaultValue: VariableDefault? = null,
    val description: String? = null,
)

@Serializable(with = ConditionSerializer::class)
sealed interface Condition {
    data class Comparison(val variable: String, val op: ComparisonOp, val value: ScalarValue) : Condition
    data class Membership(val variable: String, val op: MembershipOp, val value: List<ScalarValue>) : Condition
    data class Includes(val variable: String, val value: ScalarValue) : Condition
    data class All(val conditions: List<Condition>) : Condition
    data class Any(val conditions: List<Condition>) : Condition
    data class Not(val condition: Condition) : Condition
    @Serializable enum class ComparisonOp { eq, neq, gt, gte, lt, lte }
    @Serializable enum class MembershipOp { @SerialName("in") inOp, nin }
}

object ConditionSerializer : KSerializer<Condition> {
    override val descriptor = buildClassSerialDescriptor("Condition")
    override fun deserialize(decoder: Decoder): Condition = decode((decoder as JsonDecoder).decodeJsonElement().jsonObject)
    private fun decode(objectValue: JsonObject): Condition {
        objectValue["all"]?.let { return Condition.All(it.jsonArray.map { item -> decode(item.jsonObject) }) }
        objectValue["any"]?.let { return Condition.Any(it.jsonArray.map { item -> decode(item.jsonObject) }) }
        objectValue["not"]?.let { return Condition.Not(decode(it.jsonObject)) }
        val variable = objectValue.requiredString("var")
        val op = objectValue.requiredString("op")
        return when (op) {
            "includes" -> Condition.Includes(variable, scalar(objectValue.getValue("value")))
            "in", "nin" -> Condition.Membership(
                variable,
                if (op == "in") Condition.MembershipOp.inOp else Condition.MembershipOp.nin,
                objectValue.getValue("value").jsonArray.map(::scalar),
            )
            else -> Condition.Comparison(variable, Condition.ComparisonOp.valueOf(op), scalar(objectValue.getValue("value")))
        }
    }
    private fun scalar(element: JsonElement) = WeirJson.decodeFromJsonElement(ScalarValueSerializer, element)
    override fun serialize(encoder: Encoder, value: Condition) = (encoder as JsonEncoder).encodeJsonElement(encode(value))
    private fun encode(value: Condition): JsonObject = when (value) {
        is Condition.All -> JsonObject(mapOf("all" to JsonArray(value.conditions.map(::encode))))
        is Condition.Any -> JsonObject(mapOf("any" to JsonArray(value.conditions.map(::encode))))
        is Condition.Not -> JsonObject(mapOf("not" to encode(value.condition)))
        is Condition.Comparison -> JsonObject(mapOf("var" to JsonPrimitive(value.variable), "op" to JsonPrimitive(value.op.name), "value" to WeirJson.encodeToJsonElement(ScalarValueSerializer, value.value)))
        is Condition.Membership -> JsonObject(mapOf("var" to JsonPrimitive(value.variable), "op" to JsonPrimitive(if (value.op == Condition.MembershipOp.inOp) "in" else "nin"), "value" to JsonArray(value.value.map { WeirJson.encodeToJsonElement(ScalarValueSerializer, it) })))
        is Condition.Includes -> JsonObject(mapOf("var" to JsonPrimitive(value.variable), "op" to JsonPrimitive("includes"), "value" to WeirJson.encodeToJsonElement(ScalarValueSerializer, value.value)))
    }
}

@Serializable data class Branch(val `when`: Condition, val goto: String)
@Serializable(with = NextTransitionSerializer::class)
sealed interface NextTransition {
    data class Screen(val id: String) : NextTransition
    data class Conditional(val branches: List<Branch>, val defaultScreen: String) : NextTransition
}
object NextTransitionSerializer : KSerializer<NextTransition> {
    override val descriptor = buildClassSerialDescriptor("NextTransition")
    override fun deserialize(decoder: Decoder): NextTransition {
        return when (val element = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonPrimitive -> NextTransition.Screen(element.content)
            is JsonObject -> NextTransition.Conditional(
                WeirJson.decodeFromJsonElement(element.getValue("branches")),
                element.requiredString("default"),
            )
            else -> throw SerializationException("Invalid next transition")
        }
    }
    override fun serialize(encoder: Encoder, value: NextTransition) {
        val element = when (value) {
            is NextTransition.Screen -> JsonPrimitive(value.id)
            is NextTransition.Conditional -> JsonObject(mapOf(
                "branches" to WeirJson.encodeToJsonElement(value.branches),
                "default" to JsonPrimitive(value.defaultScreen),
            ))
        }
        (encoder as JsonEncoder).encodeJsonElement(element)
    }
}

@Serializable data class VariantGate(val experiment: String, val showFor: List<String>)
@Serializable data class ScreenGating(
    val minAppVersion: String? = null,
    val platforms: List<SpecPlatform>? = null,
    val fallback: Fallback = Fallback.serveOlderConfig,
    val substituteScreen: String? = null,
) { @Serializable enum class Fallback { skip, substitute, serveOlderConfig } }
@Serializable data class Option(
    val id: String, val label: String, val value: ScalarValue? = null,
    val icon: IconToken? = null, val description: String? = null,
)
data class ScreenBase(
    val id: String,
    val next: NextTransition? = null,
    val variant: VariantGate? = null,
    val roles: Map<RoleName, RoleColorOverride>? = null,
    val gating: ScreenGating? = null,
)

@Serializable enum class OptionsLayout { grid2, grid3, stack }
@Serializable enum class SelectionStyle { outline, affordance }

@Serializable(with = ScreenConfigSerializer::class)
sealed interface ScreenConfig {
    val base: ScreenBase
    val typeName: String
}

data class WelcomeScreenConfig(
    override val base: ScreenBase, val title: String, val subtitle: String? = null,
    val image: ImageRef? = null, val socialProof: SocialProof? = null, val demo: DemoBlock? = null, val cta: Cta,
) : ScreenConfig {
    override val typeName = "welcome"
    @Serializable data class SocialProof(val rating: Double? = null, val ratingCount: Int? = null, val tagline: String? = null)
    @Serializable data class DemoBlock(val messages: List<DemoMessage>)
}
data class SingleSelectScreenConfig(
    override val base: ScreenBase, val kicker: String? = null, val title: String, val subtitle: String? = null,
    val variable: String, val options: List<Option>, val required: Boolean = true, val autoAdvance: Boolean = true,
    val optionsLayout: OptionsLayout = OptionsLayout.stack, val selectionStyle: SelectionStyle = SelectionStyle.affordance,
) : ScreenConfig { override val typeName = "singleSelect" }
data class MultiSelectScreenConfig(
    override val base: ScreenBase, val kicker: String? = null, val title: String, val subtitle: String? = null,
    val variable: String, val options: List<Option>, val minSelections: Int = 0, val maxSelections: Int? = null,
    val cta: Cta? = null, val optionsLayout: OptionsLayout = OptionsLayout.stack,
    val selectionStyle: SelectionStyle = SelectionStyle.affordance,
) : ScreenConfig { override val typeName = "multiSelect" }
data class SliderScreenConfig(
    override val base: ScreenBase, val title: String, val subtitle: String? = null, val variable: String,
    val min: Double, val max: Double, val step: Double = 1.0, val defaultValue: Double? = null,
    val unit: String? = null, val cta: Cta? = null,
) : ScreenConfig { override val typeName = "slider" }
data class UnitToggleOption(val label: String, val unit: String, val factor: Double, val offset: Double = 0.0)
data class UnitToggle(val options: List<UnitToggleOption>, val defaultIndex: Int = 0)
@Serializable enum class NumberInputStyle { @SerialName("default") defaultStyle, stepper }
@Serializable enum class NumberInputDisplay { number, feetInches }
data class NumberInputScreenConfig(
    override val base: ScreenBase, val kicker: String? = null, val title: String, val subtitle: String? = null,
    val variable: String, val min: Double? = null, val max: Double? = null, val placeholder: String? = null,
    val unit: String? = null, val cta: Cta? = null, val style: NumberInputStyle = NumberInputStyle.defaultStyle,
    val step: Double = 1.0, val unitToggle: UnitToggle? = null, val display: NumberInputDisplay = NumberInputDisplay.number,
) : ScreenConfig { override val typeName = "numberInput" }
@Serializable enum class TextValidation { none, email }
data class TextInputScreenConfig(
    override val base: ScreenBase, val title: String, val subtitle: String? = null, val variable: String,
    val placeholder: String? = null, val multiline: Boolean = false, val maxLength: Int? = null,
    val suggestions: List<String>? = null, val validation: TextValidation = TextValidation.none,
    val skipCta: Cta? = null, val benefits: List<Feature>? = null, val cta: Cta? = null,
) : ScreenConfig { override val typeName = "textInput" }
data class LoaderScreenConfig(
    override val base: ScreenBase, val title: String? = null, val durationMs: Int = 2500,
    val messages: List<String>, val computes: List<String> = emptyList(),
) : ScreenConfig { override val typeName = "loader" }
@Serializable data class Testimonial(val quote: String, val author: String, val rating: Double? = null)
data class SocialProofScreenConfig(
    override val base: ScreenBase, val title: String? = null, val subtitle: String? = null,
    val rating: Double? = null, val ratingCount: Int? = null, val testimonials: List<Testimonial>, val cta: Cta,
) : ScreenConfig { override val typeName = "socialProof" }
data class PermissionPrimeScreenConfig(
    override val base: ScreenBase, val title: String, val subtitle: String? = null, val permission: PermissionType,
    val primeCta: Cta, val skipCta: Cta? = null, val benefits: List<Feature>? = null, val resultVariable: String? = null,
) : ScreenConfig { override val typeName = "permissionPrime" }
@Serializable data class PaywallProduct(
    val id: String, val label: String, val priceHint: String? = null, val badge: String? = null,
    val highlighted: Boolean = false, val hasIntroOffer: Boolean = false, val caption: String? = null,
)
@Serializable(with = FootnoteSerializer::class)
sealed interface Footnote { data class Single(val value: String) : Footnote; data class Lines(val value: List<String>) : Footnote }
object FootnoteSerializer : KSerializer<Footnote> {
    override val descriptor = buildClassSerialDescriptor("Footnote")
    override fun deserialize(decoder: Decoder): Footnote = when (val element = (decoder as JsonDecoder).decodeJsonElement()) {
        is JsonPrimitive -> Footnote.Single(element.content)
        is JsonArray -> Footnote.Lines(element.map { it.jsonPrimitive.content })
        else -> throw SerializationException("Invalid footnote")
    }
    override fun serialize(encoder: Encoder, value: Footnote) = (encoder as JsonEncoder).encodeJsonElement(
        when (value) { is Footnote.Single -> JsonPrimitive(value.value); is Footnote.Lines -> JsonArray(value.value.map(::JsonPrimitive)) },
    )
}
@Serializable enum class PaywallFeatureStyle { cards, plain }
@Serializable enum class PaywallProductsLayout { row, stack }
@Serializable enum class PaywallContentAlign { left, center }
data class PaywallScreenConfig(
    override val base: ScreenBase, val title: String? = null, val headline: String, val subtitle: String? = null,
    val features: List<Feature>? = null, val featureStyle: PaywallFeatureStyle = PaywallFeatureStyle.plain,
    val productsLayout: PaywallProductsLayout = PaywallProductsLayout.stack, val products: List<PaywallProduct>,
    val cta: Cta, val restoreLabel: String = "Restore", val termsUrl: String? = null, val privacyUrl: String? = null,
    val footnote: Footnote? = null, val contentAlign: PaywallContentAlign = PaywallContentAlign.left,
    val dismissable: Boolean = true, val dismissLabel: String? = null,
) : ScreenConfig { override val typeName = "paywall" }
@Serializable data class Citation(val text: String, val source: String)
data class MomentScreenConfig(
    override val base: ScreenBase, val kicker: String? = null, val headline: String, val body: String? = null,
    val image: ImageRef? = null, val citation: Citation? = null, val cta: Cta,
) : ScreenConfig { override val typeName = "moment" }
data class HoldToCommitScreenConfig(
    override val base: ScreenBase, val title: String, val subtitle: String? = null, val variable: String,
    val suggestions: List<String>? = null, val allowCustom: Boolean = true, val holdMs: Int = 1500,
    val sealedTitle: String? = null, val cta: Cta,
) : ScreenConfig { override val typeName = "holdToCommit" }
data class DemoScreenConfig(
    override val base: ScreenBase, val title: String? = null, val subtitle: String? = null,
    val messages: List<DemoMessage>, val cta: Cta,
) : ScreenConfig { override val typeName = "demo" }
data class CustomScreenConfig(
    override val base: ScreenBase, val component: String, val props: Map<String, JsonElement> = emptyMap(),
) : ScreenConfig { override val typeName = "custom" }

object ScreenConfigSerializer : KSerializer<ScreenConfig> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ScreenConfig")
    override fun deserialize(decoder: Decoder): ScreenConfig {
        val o = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val base = decodeBase(o)
        return when (o.requiredString("type")) {
            "welcome" -> WelcomeScreenConfig(base, o.requiredString("title"), o.string("subtitle"), o.decodeOrNull("image"), o.decodeOrNull("socialProof"), o.decodeOrNull("demo"), o.decode("cta"))
            "singleSelect" -> SingleSelectScreenConfig(base, o.string("kicker"), o.requiredString("title"), o.string("subtitle"), o.requiredString("variable"), o.decode("options"), o.bool("required") ?: true, o.bool("autoAdvance") ?: true, o.decodeOrNull("optionsLayout") ?: OptionsLayout.stack, o.decodeOrNull("selectionStyle") ?: SelectionStyle.affordance)
            "multiSelect" -> MultiSelectScreenConfig(base, o.string("kicker"), o.requiredString("title"), o.string("subtitle"), o.requiredString("variable"), o.decode("options"), o.int("minSelections") ?: 0, o.int("maxSelections"), o.decodeOrNull("cta"), o.decodeOrNull("optionsLayout") ?: OptionsLayout.stack, o.decodeOrNull("selectionStyle") ?: SelectionStyle.affordance)
            "slider" -> SliderScreenConfig(base, o.requiredString("title"), o.string("subtitle"), o.requiredString("variable"), o.double("min")!!, o.double("max")!!, o.double("step") ?: 1.0, o.double("default"), o.string("unit"), o.decodeOrNull("cta"))
            "numberInput" -> NumberInputScreenConfig(base, o.string("kicker"), o.requiredString("title"), o.string("subtitle"), o.requiredString("variable"), o.double("min"), o.double("max"), o.string("placeholder"), o.string("unit"), o.decodeOrNull("cta"), o.decodeOrNull("style") ?: NumberInputStyle.defaultStyle, o.double("step") ?: 1.0, decodeUnitToggle(o["unitToggle"]), o.decodeOrNull("display") ?: NumberInputDisplay.number)
            "textInput" -> TextInputScreenConfig(base, o.requiredString("title"), o.string("subtitle"), o.requiredString("variable"), o.string("placeholder"), o.bool("multiline") ?: false, o.int("maxLength"), o.decodeOrNull("suggestions"), o.decodeOrNull("validation") ?: TextValidation.none, o.decodeOrNull("skipCta"), o.decodeOrNull("benefits"), o.decodeOrNull("cta"))
            "loader" -> LoaderScreenConfig(base, o.string("title"), o.int("durationMs") ?: 2500, o.decode("messages"), o.decodeOrNull("computes") ?: emptyList())
            "socialProof" -> SocialProofScreenConfig(base, o.string("title"), o.string("subtitle"), o.double("rating"), o.int("ratingCount"), o.decode("testimonials"), o.decode("cta"))
            "permissionPrime" -> PermissionPrimeScreenConfig(base, o.requiredString("title"), o.string("subtitle"), o.decode("permission"), o.decode("primeCta"), o.decodeOrNull("skipCta"), o.decodeOrNull("benefits"), o.string("resultVariable"))
            "paywall" -> PaywallScreenConfig(base, o.string("title"), o.requiredString("headline"), o.string("subtitle"), o.decodeOrNull("features"), o.decodeOrNull("featureStyle") ?: PaywallFeatureStyle.plain, o.decodeOrNull("productsLayout") ?: PaywallProductsLayout.stack, o.decode("products"), o.decode("cta"), o.string("restoreLabel") ?: "Restore", o.string("termsUrl"), o.string("privacyUrl"), o.decodeOrNull("footnote"), o.decodeOrNull("contentAlign") ?: PaywallContentAlign.left, o.bool("dismissable") ?: true, o.string("dismissLabel"))
            "moment" -> MomentScreenConfig(base, o.string("kicker"), o.requiredString("headline"), o.string("body"), o.decodeOrNull("image"), o.decodeOrNull("citation"), o.decode("cta"))
            "holdToCommit" -> HoldToCommitScreenConfig(base, o.requiredString("title"), o.string("subtitle"), o.requiredString("variable"), o.decodeOrNull("suggestions"), o.bool("allowCustom") ?: true, o.int("holdMs") ?: 1500, o.string("sealedTitle"), o.decode("cta"))
            "demo" -> DemoScreenConfig(base, o.string("title"), o.string("subtitle"), o.decode("messages"), o.decode("cta"))
            "custom" -> CustomScreenConfig(base, o.requiredString("component"), o["props"]?.jsonObject ?: emptyMap())
            else -> throw SerializationException("Unrecognized screen type ${o["type"]}")
        }
    }

    private fun decodeBase(o: JsonObject) = ScreenBase(
        id = o.requiredString("id"),
        next = o.decodeOrNull("next"),
        variant = o.decodeOrNull("variant"),
        roles = o.decodeOrNull("roles"),
        gating = o.decodeOrNull("gating"),
    )

    private fun decodeUnitToggle(element: JsonElement?): UnitToggle? {
        val o = (element ?: return null).jsonObject
        val options = o.getValue("options").jsonArray.map { item ->
            val option = item.jsonObject
            UnitToggleOption(option.requiredString("label"), option.requiredString("unit"), option.double("factor")!!, option.double("offset") ?: 0.0)
        }
        return UnitToggle(options, o.int("default") ?: 0)
    }

    override fun serialize(encoder: Encoder, value: ScreenConfig) {
        throw SerializationException("FlowConfig screen encoding is not supported; configs are decode-only SDK inputs")
    }
}

@Serializable data class ExperimentTargeting(
    val platforms: List<SpecPlatform>? = null, val locales: List<String>? = null,
    val minAppVersion: String? = null, val newVsReturning: NewVsReturning = NewVsReturning.both,
) { @Serializable enum class NewVsReturning { new, returning, both } }
@Serializable data class FlowExperiment(
    val id: String, val variants: List<VariantWeight>, val holdout: Double = 0.0,
    val targeting: ExperimentTargeting? = null,
) {
    @Serializable data class VariantWeight(val id: String, val weight: Double = 1.0)
    fun bucketerExperiment() = Bucketer.Experiment(id, variants.map { Bucketer.Variant(it.id, it.weight) }, holdout)
}
@Serializable data class FlowMeta(val app: String? = null, val locale: String? = null, val description: String? = null)
@Serializable data class FlowConfig(
    val specVersion: Int,
    val id: String,
    val name: String,
    val meta: FlowMeta = FlowMeta(),
    val theme: ThemeConfig = ThemeConfig(),
    val variables: List<FlowVariable> = emptyList(),
    val experiments: List<FlowExperiment> = emptyList(),
    val customAssets: List<CustomAsset> = emptyList(),
    val entry: String? = null,
    val minAppVersion: String? = null,
    val platforms: List<SpecPlatform>? = null,
    val screens: List<ScreenConfig>,
)

private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = get(key)?.jsonPrimitive?.booleanOrNull
private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
private fun JsonObject.double(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull
private inline fun <reified T> JsonObject.decode(key: String): T = WeirJson.decodeFromJsonElement(getValue(key))
private inline fun <reified T> JsonObject.decodeOrNull(key: String): T? = get(key)?.takeUnless { it is JsonNull }?.let { WeirJson.decodeFromJsonElement(it) }
