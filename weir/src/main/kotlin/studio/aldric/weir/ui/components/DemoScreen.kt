package studio.aldric.weir.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.aldric.weir.engine.DemoScreenConfig
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.DemoTranscript
import studio.aldric.weir.ui.ScreenScaffold

@Composable
fun DemoScreen(config: DemoScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    ScreenScaffold(null, config.title, config.subtitle, modifier, content = {
        Box(Modifier.heightIn(max = 320.dp)) { DemoTranscript(config.messages, autoPlay = true) }
    }, cta = { CtaButton(config.cta, true) { engine.submit(config.base.id) } })
}
