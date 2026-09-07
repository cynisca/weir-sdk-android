package studio.aldric.weir.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import studio.aldric.weir.bridge.PermissionStatus
import studio.aldric.weir.bridge.PermissionType
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.PermissionPrimeScreenConfig
import studio.aldric.weir.ui.BenefitsList
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.LocalWeirPermissionRequester
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.ScreenScaffold
import studio.aldric.weir.ui.WeirTextStyle

internal fun PermissionType.wireValue(): String = name.lowercase()

@Composable
fun PermissionPrimeScreen(config: PermissionPrimeScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    val requester = LocalWeirPermissionRequester.current
    val scope = rememberCoroutineScope()
    var isRequesting by remember(config.base.id) { mutableStateOf(false) }
    ScreenScaffold(null, config.title, config.subtitle, modifier, content = {
        config.benefits?.let { BenefitsList(it) }
    }, cta = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CtaButton(config.primeCta, !isRequesting, Modifier.weight(1f)) {
                if (isRequesting) return@CtaButton
                isRequesting = true
                val permission = config.permission.wireValue()
                engine.recordInteraction("permission_prompt_shown", config.base.id, mapOf("permission" to JsonPrimitive(permission)))
                scope.launch {
                    val granted = requester.requestPermission(config.permission) == PermissionStatus.GRANTED
                    config.resultVariable?.let { engine.setVariable(it, JsonPrimitive(granted)) }
                    engine.recordInteraction("permission_result", config.base.id, mapOf(
                        "permission" to JsonPrimitive(permission), "granted" to JsonPrimitive(granted),
                    ))
                    isRequesting = false
                    engine.submit(config.base.id)
                }
            }
            config.skipCta?.let { skip ->
                BasicText(skip.label, Modifier.clickable(role = Role.Button) { engine.skip(config.base.id) }.padding(12.dp), theme.font(WeirTextStyle.Body).copy(color = theme.mutedTextColor))
            }
        }
    })
}
