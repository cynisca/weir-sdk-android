package studio.aldric.weir.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.LoaderScreenConfig
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.WeirTextStyle
import kotlin.math.max

@Composable
fun LoaderScreen(config: LoaderScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    var targetProgress by remember(config.base.id) { mutableFloatStateOf(0f) }
    var messageIndex by remember(config.base.id) { mutableIntStateOf(0) }
    var didFinish by remember(config.base.id) { mutableStateOf(false) }
    val tickMs = config.durationMs / 30L
    val progress by animateFloatAsState(targetProgress, tween(tickMs.toInt()), label = "loader-progress")
    LaunchedEffect(config.base.id) {
        val totalTicks = 30
        val messageEvery = max(1, totalTicks / max(config.messages.size, 1))
        for (tick in 1..totalTicks) {
            delay(tickMs)
            targetProgress = tick.toFloat() / totalTicks
            if (tick % messageEvery == 0) messageIndex++
        }
        if (!didFinish) {
            didFinish = true
            config.computes.forEach { engine.setVariable(it, JsonPrimitive(true)) }
            engine.recordInteraction("loader_completed", config.base.id, mapOf("computed" to JsonArray(config.computes.map(::JsonPrimitive))))
            engine.submit(config.base.id)
        }
    }
    Column(
        modifier.fillMaxSize().background(theme.backgroundColor).padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.weight(1f))
        config.title?.let { BasicText(it, style = theme.font(WeirTextStyle.Title).copy(color = theme.textColor)) }
        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp).height(6.dp).background(theme.borderColor, CircleShape)) {
            Box(Modifier.fillMaxWidth(progress).height(6.dp).background(theme.fill(RoleName.progress), CircleShape))
        }
        if (config.messages.isNotEmpty()) {
            // Instant swaps are deliberate; progress motion remains the primary timed feedback.
            BasicText(config.messages[messageIndex % config.messages.size], style = theme.font(WeirTextStyle.Body).copy(color = theme.mutedTextColor))
        }
        Spacer(Modifier.weight(1f))
    }
}
