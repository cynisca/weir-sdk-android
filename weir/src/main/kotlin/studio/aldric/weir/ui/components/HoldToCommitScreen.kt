package studio.aldric.weir.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.HoldToCommitScreenConfig
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.RadiusToken
import studio.aldric.weir.ui.ScreenScaffold
import studio.aldric.weir.ui.WeirTextStyle
import studio.aldric.weir.ui.weirReduceMotion

internal fun holdCommitValue(text: String, suggestions: List<String>?): String =
    if (text.isBlank()) suggestions?.firstOrNull() ?: "" else text

@Composable
fun HoldToCommitScreen(config: HoldToCommitScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    val haptics = LocalHapticFeedback.current
    val reduceMotion = weirReduceMotion()
    var text by remember(config.base.id) { mutableStateOf("") }
    var isSealed by remember(config.base.id) { mutableStateOf(false) }
    val progress = remember(config.base.id) { Animatable(0f) }
    LaunchedEffect(isSealed) { if (isSealed) { delay(600); engine.submit(config.base.id) } }
    ScreenScaffold(null, config.title, config.subtitle, modifier, content = {
        if (config.allowCustom) {
            BasicTextField(
                text, { text = it },
                Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, theme.borderColor, RoundedCornerShape(theme.radius(RadiusToken.Md))).padding(12.dp),
                textStyle = theme.font(WeirTextStyle.Body).copy(color = theme.textColor),
                decorationBox = { inner -> Box { if (text.isEmpty()) BasicText(config.title, style = theme.font(WeirTextStyle.Body).copy(color = theme.dimTextColor)); inner() } },
            )
        }
        config.suggestions?.let { SuggestionChips(it) { suggestion -> text = suggestion } }
        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
            val ringColor = theme.borderColor
            val fillColor = theme.fill(RoleName.ctaPrimary)
            Box(
                Modifier.size(160.dp).pointerInput(config.holdMs, isSealed, reduceMotion) {
                    detectTapGestures(onPress = {
                        if (!isSealed) {
                            var completed = false
                            coroutineScope {
                                val animation = launch {
                                    if (!reduceMotion) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        launch { delay((config.holdMs * 0.6).toLong()); haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                                    }
                                    progress.animateTo(1f, tween(config.holdMs, easing = LinearEasing))
                                    completed = true
                                    isSealed = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val value = holdCommitValue(text, config.suggestions)
                                    engine.setVariable(config.variable, JsonPrimitive(value))
                                    engine.recordInteraction("commit", config.base.id, mapOf("variable" to JsonPrimitive(config.variable), "value" to JsonPrimitive(value)))
                                }
                                tryAwaitRelease()
                                if (!completed) { animation.cancel(); progress.animateTo(0f, tween(200)) }
                            }
                        }
                    })
                },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(160.dp)) {
                    val inset = 3.dp.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    drawCircle(ringColor, style = Stroke(6.dp.toPx()))
                    drawArc(fillColor, -90f, 360f * progress.value, false, Offset(inset, inset), arcSize, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                }
                if (isSealed) Image(rememberVectorPainter(Icons.Filled.Check), "Committed", colorFilter = ColorFilter.tint(fillColor), modifier = Modifier.size(42.dp))
                else BasicText(config.cta.label, Modifier.padding(20.dp), theme.font(WeirTextStyle.CtaLabel).copy(color = theme.textColor, textAlign = TextAlign.Center))
            }
        }
    }, cta = {})
}
