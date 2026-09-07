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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.MomentScreenConfig
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.EmphasisText
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.WeirTextStyle
import studio.aldric.weir.ui.weirReduceMotion

@Composable
fun MomentScreen(config: MomentScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    val reduceMotion = weirReduceMotion()
    var revealed by remember(config.base.id) { mutableStateOf(false) }
    var imageResolved by remember(config.base.id) { mutableStateOf(false) }
    val immediate = shouldRevealImmediately(reduceMotion, theme.motion.headline)
    LaunchedEffect(immediate) { revealed = true }
    val alpha by animateFloatAsState(
        if (immediate || revealed) 1f else 0f,
        tween(if (immediate) 0 else theme.animationDurationMs),
        label = "moment-headline",
    )
    Box(modifier.fillMaxSize()) {
        BackdropSlot(config.image, engine, { Box(Modifier.fillMaxSize()) }) { resolved -> imageResolved = resolved }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0E1610).copy(alpha = if (imageResolved) 0.55f else 1f),
                        Color.Black.copy(alpha = if (imageResolved) 0.75f else 1f),
                    ),
                ),
            ),
        )
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(theme.spacing),
            ) {
                Spacer(Modifier.padding(top = 30.dp))
                config.kicker?.let {
                    BasicText(it.uppercase(), style = theme.font(WeirTextStyle.Kicker).copy(color = theme.text(RoleName.kicker)))
                }
                EmphasisText(config.headline, Color.White, theme.font(WeirTextStyle.Headline), Modifier.alpha(alpha))
                config.body?.let {
                    BasicText(it, style = theme.font(WeirTextStyle.Body).copy(color = Color.White.copy(alpha = 0.85f)))
                }
                config.citation?.let {
                    BasicText(it.text, style = theme.font(WeirTextStyle.Caption).copy(color = Color.White.copy(alpha = 0.7f), fontStyle = FontStyle.Italic))
                    BasicText("— ${it.source}", style = theme.font(WeirTextStyle.Caption).copy(color = Color.White.copy(alpha = 0.5f)))
                }
            }
            Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                CtaButton(config.cta, true) { engine.submit(config.base.id) }
            }
        }
    }
}
