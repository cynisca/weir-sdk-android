package studio.aldric.weir.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import studio.aldric.weir.engine.Cta
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.Option
import studio.aldric.weir.engine.ScalarValue
import studio.aldric.weir.engine.SingleSelectScreenConfig
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.OptionRow
import studio.aldric.weir.ui.ScreenScaffold

@Composable
fun SingleSelectScreen(config: SingleSelectScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    var selectedId by remember(config.base.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(config.variable) {
        val existing = engine.variable(config.variable)?.jsonPrimitive?.contentOrNull
        selectedId = config.options.firstOrNull { option -> scalarJson(option.value ?: ScalarValue.StringValue(option.id)).jsonPrimitive.content == existing }?.id
    }
    ScreenScaffold(
        kicker = config.kicker,
        title = config.title,
        subtitle = config.subtitle,
        modifier = modifier,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                config.options.forEach { option ->
                    OptionRow(option, selectedId == option.id, config.selectionStyle, false, {
                        selectedId = option.id
                        select(config, engine, option)
                    })
                }
            }
        },
        cta = {
            if (!config.autoAdvance) {
                CtaButton(Cta("Continue"), selectedId != null || !config.required) {
                    engine.submit(config.base.id)
                }
            }
        },
    )
}

private fun select(config: SingleSelectScreenConfig, engine: FlowEngine, option: Option) {
    val value = scalarJson(option.value ?: ScalarValue.StringValue(option.id))
    engine.setVariable(config.variable, value)
    engine.recordInteraction(
        "quiz_answer",
        config.base.id,
        mapOf(
            "variable" to JsonPrimitive(config.variable),
            "value" to value,
            "optionIds" to JsonArray(listOf(JsonPrimitive(option.id))),
        ),
    )
    if (config.autoAdvance) engine.submit(config.base.id)
}

private fun scalarJson(value: ScalarValue): JsonElement = when (value) {
    is ScalarValue.StringValue -> JsonPrimitive(value.value)
    is ScalarValue.NumberValue -> JsonPrimitive(value.value)
    is ScalarValue.BooleanValue -> JsonPrimitive(value.value)
}
