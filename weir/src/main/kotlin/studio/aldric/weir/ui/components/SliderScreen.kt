package studio.aldric.weir.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import studio.aldric.weir.engine.Cta
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.SliderScreenConfig
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.ScreenScaffold
import studio.aldric.weir.ui.WeirTextStyle
import kotlin.math.round

internal fun formatSteppedValue(value: Double, step: Double): String =
    if (step % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)

internal fun snapSliderValue(raw: Double, min: Double, max: Double, step: Double): Double =
    (min + round((raw - min) / step) * step).coerceIn(min, max)

@Composable
fun SliderScreen(config: SliderScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    var value by remember(config.base.id) { mutableDoubleStateOf(config.defaultValue ?: config.min) }
    var trackWidth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(config.variable) {
        value = (engine.variable(config.variable) as? JsonPrimitive)?.doubleOrNull ?: config.defaultValue ?: config.min
    }
    val range = (config.max - config.min).takeIf { it > 0.0 } ?: 1.0
    val fraction = ((value - config.min) / range).coerceIn(0.0, 1.0).toFloat()
    ScreenScaffold(null, config.title, config.subtitle, modifier, content = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(
                formatSteppedValue(value, config.step) + (config.unit?.let { " $it" } ?: ""),
                style = theme.font(WeirTextStyle.Headline).copy(color = theme.text(RoleName.emphasis)),
            )
            Box(
                Modifier.fillMaxWidth().height(36.dp).onSizeChanged { trackWidth = it.width.toFloat() }
                    .draggable(rememberDraggableState { delta ->
                        if (trackWidth > 0f) value = snapSliderValue(value + delta / trackWidth * range, config.min, config.max, config.step)
                    }, Orientation.Horizontal),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(theme.borderColor, CircleShape))
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(theme.fill(RoleName.progress), CircleShape))
                Box(
                    Modifier.offset { IntOffset(((trackWidth - 20.dp.toPx()) * fraction).toInt(), 0) }
                        .size(20.dp).background(theme.fill(RoleName.progress), CircleShape),
                )
            }
        }
    }, cta = {
        CtaButton(config.cta ?: Cta("Continue"), true) {
            val json = JsonPrimitive(value)
            engine.setVariable(config.variable, json)
            engine.recordInteraction("input_submitted", config.base.id, mapOf("variable" to JsonPrimitive(config.variable), "value" to json))
            engine.submit(config.base.id)
        }
    })
}
