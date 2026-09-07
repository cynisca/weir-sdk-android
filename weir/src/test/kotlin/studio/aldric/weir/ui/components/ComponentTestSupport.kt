package studio.aldric.weir.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.WeirJson
import studio.aldric.weir.engine.FlowConfig
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.WeirTheme

internal class CapturingSink : EventSink {
    val events = mutableListOf<EventParams>()
    override fun append(event: EventParams) { events += event }
    fun types(): List<String> = events.mapNotNull { it.payload.jsonObject["type"]?.jsonPrimitive?.content }
}

@Composable
internal fun ThemedScreen(engine: FlowEngine, content: @Composable () -> Unit) {
    // `engine.currentScreen` is now Compose-observable (FlowEngine's mutableStateOf
    // migration) — a recomposition legitimately fires once the flow completes and
    // currentScreen goes back to null (the engine has nothing left to render).
    // Render nothing at that point rather than crashing; this mirrors how the real
    // WeirFlow composable already treats a null currentScreen (Weir/ui/WeirFlow.kt).
    val screen = engine.currentScreen ?: return
    CompositionLocalProvider(LocalWeirTheme provides WeirTheme.make(engine.config, screen), content = content)
}

internal fun engineFor(screenJson: String, sink: CapturingSink = CapturingSink()): Pair<FlowEngine, CapturingSink> {
    val config = WeirJson.decodeFromString(
        FlowConfig.serializer(),
        """{"specVersion":1,"id":"test","name":"Test","screens":[$screenJson]}""",
    )
    return FlowEngine(config, null, sink, "test-session") to sink
}
