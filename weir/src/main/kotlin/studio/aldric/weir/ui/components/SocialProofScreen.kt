package studio.aldric.weir.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.SocialProofScreenConfig
import studio.aldric.weir.engine.Testimonial
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.RadiusToken
import studio.aldric.weir.ui.ScreenScaffold
import studio.aldric.weir.ui.WeirTextStyle
import studio.aldric.weir.ui.weirReduceMotion

@Composable
fun SocialProofScreen(config: SocialProofScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    val reduceMotion = weirReduceMotion()
    var visibleCount by remember(config.base.id) { mutableIntStateOf(0) }
    LaunchedEffect(config.testimonials, reduceMotion) {
        if (reduceMotion) visibleCount = config.testimonials.size
        else while (visibleCount < config.testimonials.size) { visibleCount++; delay(150) }
    }
    ScreenScaffold(null, config.title, config.subtitle, modifier, content = {
        config.rating?.let { rating -> Stars(rating, config.ratingCount) }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            config.testimonials.forEachIndexed { index, testimonial ->
                val target = if (index < visibleCount) 1f else 0f
                val reveal by animateFloatAsState(
                    target,
                    tween(if (reduceMotion) 0 else theme.animationDurationMs),
                    label = "testimonial-$index",
                )
                TestimonialCard(testimonial, Modifier.alpha(reveal).offset(y = (12f * (1f - reveal)).dp))
            }
        }
    }, cta = { CtaButton(config.cta, true) { engine.submit(config.base.id) } })
}

@Composable
private fun Stars(rating: Double, count: Int? = null) {
    val theme = LocalWeirTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            // material-icons-core has no reliable outlined Star across supported versions.
            Image(
                painter = rememberVectorPainter(Icons.Filled.Star),
                contentDescription = null,
                colorFilter = ColorFilter.tint(theme.text(RoleName.emphasis)),
                modifier = Modifier.alpha(if (index < rating) 1f else 0.3f),
            )
        }
        count?.let { BasicText("($it)", style = theme.font(WeirTextStyle.Caption).copy(color = theme.mutedTextColor)) }
    }
}

@Composable
private fun TestimonialCard(testimonial: Testimonial, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    Column(
        modifier.fillMaxWidth().background(theme.surfaceColor, RoundedCornerShape(theme.radius(RadiusToken.Md))).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        testimonial.rating?.let { Stars(it) }
        BasicText(testimonial.quote, style = theme.font(WeirTextStyle.Body).copy(color = theme.textColor))
        BasicText("— ${testimonial.author}", style = theme.font(WeirTextStyle.Caption).copy(color = theme.mutedTextColor))
    }
}
