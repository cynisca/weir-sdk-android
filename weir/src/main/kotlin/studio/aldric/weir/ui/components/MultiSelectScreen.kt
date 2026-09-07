package studio.aldric.weir.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import studio.aldric.weir.engine.Cta
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.MultiSelectScreenConfig
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.OptionRow
import studio.aldric.weir.ui.ScreenScaffold

@Composable
fun MultiSelectScreen(config: MultiSelectScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    var selectedIds by remember(config.base.id) { mutableStateOf(emptySet<String>()) }
    val isValid = selectedIds.size >= config.minSelections &&
        (config.maxSelections == null || selectedIds.size <= config.maxSelections)
    ScreenScaffold(config.kicker, config.title, config.subtitle, modifier, content = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            config.options.forEach { option ->
                OptionRow(option, option.id in selectedIds, config.selectionStyle, true, {
                    val next = if (option.id in selectedIds) {
                        selectedIds - option.id
                    } else {
                        if (config.maxSelections != null && selectedIds.size >= config.maxSelections) return@OptionRow
                        selectedIds + option.id
                    }
                    selectedIds = next
                    val sorted = next.sorted()
                    val array = JsonArray(sorted.map(::JsonPrimitive))
                    engine.setVariable(config.variable, array)
                    engine.recordInteraction("quiz_answer", config.base.id, mapOf(
                        "variable" to JsonPrimitive(config.variable), "value" to array, "optionIds" to array,
                    ))
                })
            }
        }
    }, cta = {
        CtaButton(config.cta ?: Cta("Continue"), isValid) { engine.submit(config.base.id) }
    })
}
