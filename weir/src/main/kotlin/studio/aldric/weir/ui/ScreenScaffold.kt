package studio.aldric.weir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import studio.aldric.weir.engine.theme.RoleName

@Composable
fun ScreenScaffold(
    kicker: String?,
    title: String?,
    subtitle: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
    cta: @Composable () -> Unit,
) {
    val theme = LocalWeirTheme.current
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxSize().background(theme.backgroundColor)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Clearing focus at drag start is stable across API 26+ and dismisses the IME
                // without tying this library to a particular nested-scroll implementation.
                .pointerInput(focusManager) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val start = down.position
                        var dismissed = false
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Final)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (!dismissed && change != null && (change.position - start).getDistance() > viewConfiguration.touchSlop) {
                                focusManager.clearFocus()
                                dismissed = true
                            }
                            pressed = event.changes.any { it.pressed }
                        }
                    }
                }
                .padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(theme.spacing),
        ) {
            kicker?.let {
                BasicText(it.uppercase(), style = theme.font(WeirTextStyle.Kicker).copy(color = theme.text(RoleName.kicker)))
            }
            title?.let { BasicText(it, style = theme.font(WeirTextStyle.Title).copy(color = theme.textColor)) }
            subtitle?.let { BasicText(it, style = theme.font(WeirTextStyle.Subtitle).copy(color = theme.mutedTextColor)) }
            content()
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(theme.backgroundColor)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            // A no-op `cta = {}` is safe: SwiftUI's EmptyView/safeAreaInset defect has no
            // Compose equivalent, so no artificial one-pixel footer is required here.
            cta()
        }
    }
}
