package studio.aldric.weir.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import studio.aldric.weir.engine.Cta
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.TextInputScreenConfig
import studio.aldric.weir.engine.TextValidation
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.ui.BenefitsList
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.RadiusToken
import studio.aldric.weir.ui.ScreenScaffold
import studio.aldric.weir.ui.WeirTextStyle

internal fun textInputValid(text: String, maxLength: Int?, validation: TextValidation): Boolean =
    text.isNotEmpty() && (maxLength == null || text.length <= maxLength) &&
        (validation != TextValidation.email || ('@' in text && '.' in text))

@Composable
fun SuggestionChips(suggestions: List<String>, modifier: Modifier = Modifier, onSelected: (String) -> Unit) {
    val theme = LocalWeirTheme.current
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.forEach { suggestion ->
            BasicText(
                suggestion,
                Modifier.clickable(role = Role.Button) { onSelected(suggestion) }
                    .background(theme.fill(RoleName.tint), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                theme.font(WeirTextStyle.Caption).copy(color = theme.text(RoleName.tint)),
            )
        }
    }
}

@Composable
fun TextInputScreen(config: TextInputScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    var text by remember(config.base.id) { mutableStateOf("") }
    val valid = textInputValid(text, config.maxLength, config.validation)
    ScreenScaffold(null, config.title, config.subtitle, modifier, content = {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = if (config.multiline) 100.dp else 48.dp)
                .border(1.dp, theme.borderColor, RoundedCornerShape(theme.radius(RadiusToken.Md))).padding(12.dp),
            textStyle = theme.font(WeirTextStyle.Body).copy(color = theme.textColor),
            singleLine = !config.multiline,
            minLines = if (config.multiline) 4 else 1,
            decorationBox = { inner ->
                Box { if (text.isEmpty()) BasicText(config.placeholder ?: "", style = theme.font(WeirTextStyle.Body).copy(color = theme.dimTextColor)); inner() }
            },
        )
        config.suggestions?.let { SuggestionChips(it) { suggestion -> text = suggestion } }
        if (config.validation == TextValidation.email && text.isNotEmpty() && !valid) {
            BasicText("Enter a valid email address", style = theme.font(WeirTextStyle.Caption).copy(color = Color(0xFFFF3B30)))
        }
        config.benefits?.let { BenefitsList(it) }
    }, cta = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CtaButton(config.cta ?: Cta("Continue"), valid, Modifier.weight(1f)) {
                val value = JsonPrimitive(text)
                engine.setVariable(config.variable, value)
                engine.recordInteraction("input_submitted", config.base.id, mapOf("variable" to JsonPrimitive(config.variable), "value" to value))
                engine.submit(config.base.id)
            }
            config.skipCta?.let { skip ->
                BasicText(
                    skip.label,
                    Modifier.clickable(role = Role.Button) { engine.skip(config.base.id) }.padding(12.dp),
                    theme.font(WeirTextStyle.Body).copy(color = theme.mutedTextColor),
                )
            }
        }
    })
}
