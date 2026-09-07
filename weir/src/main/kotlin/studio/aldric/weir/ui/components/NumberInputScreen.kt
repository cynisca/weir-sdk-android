package studio.aldric.weir.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import studio.aldric.weir.engine.Cta
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.NumberInputScreenConfig
import studio.aldric.weir.engine.NumberInputStyle
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.RadiusToken
import studio.aldric.weir.ui.ScreenScaffold
import studio.aldric.weir.ui.WeirTextStyle

internal fun numberInputValid(text: String, min: Double?, max: Double?): Boolean {
    val value = text.toDoubleOrNull() ?: return false
    return (min == null || value >= min) && (max == null || value <= max)
}

@Composable
fun NumberInputScreen(config: NumberInputScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    var text by remember(config.base.id) { mutableStateOf("") }
    LaunchedEffect(config.variable) {
        (engine.variable(config.variable) as? JsonPrimitive)?.doubleOrNull?.let { text = formatSteppedValue(it, config.step) }
    }
    val valid = numberInputValid(text, config.min, config.max)
    ScreenScaffold(config.kicker, config.title, config.subtitle, modifier, content = {
        if (config.style == NumberInputStyle.stepper) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
                StepperButton("−", "Decrease") {
                    text = adjustedNumber(text, -config.step, config)
                }
                Column(Modifier.defaultMinSize(minWidth = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(text.ifEmpty { "0" }, style = theme.font(WeirTextStyle.Headline).copy(color = theme.textColor))
                    config.unit?.let { BasicText(it, style = theme.font(WeirTextStyle.Caption).copy(color = theme.mutedTextColor)) }
                }
                StepperButton("+", "Increase") { text = adjustedNumber(text, config.step, config) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().border(1.dp, theme.borderColor, RoundedCornerShape(theme.radius(RadiusToken.Md))).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                    textStyle = theme.font(WeirTextStyle.Body).copy(color = theme.textColor),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    decorationBox = { inner -> if (text.isEmpty()) BasicText(config.placeholder ?: "", style = theme.font(WeirTextStyle.Body).copy(color = theme.dimTextColor)); inner() },
                )
                config.unit?.let { BasicText(it, style = theme.font(WeirTextStyle.Body).copy(color = theme.mutedTextColor)) }
            }
        }
    }, cta = {
        CtaButton(config.cta ?: Cta("Continue"), valid) {
            val value = text.toDoubleOrNull() ?: return@CtaButton
            val json = JsonPrimitive(value)
            engine.setVariable(config.variable, json)
            engine.recordInteraction("input_submitted", config.base.id, mapOf("variable" to JsonPrimitive(config.variable), "value" to json))
            engine.submit(config.base.id)
        }
    })
}

private fun adjustedNumber(text: String, delta: Double, config: NumberInputScreenConfig): String {
    var value = (text.toDoubleOrNull() ?: config.min ?: 0.0) + delta
    config.min?.let { value = value.coerceAtLeast(it) }
    config.max?.let { value = value.coerceAtMost(it) }
    return formatSteppedValue(value, config.step)
}

@Composable
private fun StepperButton(glyph: String, description: String, onClick: () -> Unit) {
    val theme = LocalWeirTheme.current
    Box(
        Modifier
            .size(44.dp)
            .background(theme.fill(RoleName.ctaPrimary), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = theme.font(WeirTextStyle.Title).copy(color = theme.text(RoleName.ctaPrimary)))
    }
}
