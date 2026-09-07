package studio.aldric.weir.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.WelcomeScreenConfig
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.DemoTranscript
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.WeirTextStyle
import studio.aldric.weir.ui.weirReduceMotion

@Composable
fun WelcomeScreen(config: WelcomeScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    val reduceMotion = weirReduceMotion()
    var revealed by remember(config.base.id) { mutableStateOf(false) }
    var imageResolved by remember(config.base.id) { mutableStateOf(false) }
    val immediate = shouldRevealImmediately(reduceMotion, theme.motion.headline)
    LaunchedEffect(immediate) { revealed = true }
    val headlineAlpha by animateFloatAsState(
        targetValue = if (immediate || revealed) 1f else 0f,
        animationSpec = tween(if (immediate) 0 else theme.animationDurationMs),
        label = "welcome-headline",
    )

    Box(modifier.fillMaxSize()) {
        BackdropSlot(config.image, engine, {
            Box(
                Modifier.fillMaxSize()
                    .background(theme.backgroundColor)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                theme.fill(RoleName.ctaPrimary).copy(alpha = 0.35f),
                                theme.fill(RoleName.progress).copy(alpha = 0.15f),
                            ),
                        ),
                    ),
            )
        }) { resolved -> imageResolved = resolved }
        if (imageResolved) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))),
                ),
            )
        }
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(theme.spacing),
            ) {
                Spacer(Modifier.padding(top = 20.dp))
                BasicText(
                    config.title,
                    Modifier.alpha(headlineAlpha),
                    theme.font(WeirTextStyle.Headline).copy(color = if (imageResolved) Color.White else theme.textColor),
                )
                config.subtitle?.let {
                    BasicText(it, style = theme.font(WeirTextStyle.Subtitle).copy(color = if (imageResolved) Color.White.copy(alpha = 0.85f) else theme.mutedTextColor))
                }
                config.socialProof?.let { proof ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        proof.rating?.let { rating ->
                            repeat(5) { index ->
                                Image(
                                    rememberVectorPainter(Icons.Filled.Star),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(
                                        theme.text(RoleName.emphasis).copy(alpha = if (index < rating) 1f else 0.35f),
                                    ),
                                )
                            }
                        }
                        proof.ratingCount?.let {
                            BasicText("$it+", style = theme.font(WeirTextStyle.Caption).copy(color = theme.mutedTextColor))
                        }
                        proof.tagline?.let {
                            BasicText(it, style = theme.font(WeirTextStyle.Caption).copy(color = theme.mutedTextColor))
                        }
                    }
                }
                config.demo?.let { demo -> DemoTranscript(demo.messages, autoPlay = true) }
            }
            Column(
                Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                CtaButton(config.cta, true) { engine.submit(config.base.id) }
            }
        }
    }
}
