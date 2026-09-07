package studio.aldric.weir.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import studio.aldric.weir.engine.Cta
import studio.aldric.weir.engine.DemoMessage
import studio.aldric.weir.engine.Feature
import studio.aldric.weir.engine.IconToken
import studio.aldric.weir.engine.Option
import studio.aldric.weir.engine.SelectionStyle
import studio.aldric.weir.engine.theme.RoleName

internal fun ctaAlpha(isEnabled: Boolean): Float = if (isEnabled) 1f else 0.5f

@Composable
fun CtaButton(cta: Cta, isEnabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val theme = LocalWeirTheme.current
    val role = if (cta.variant == Cta.Variant.accent) RoleName.ctaAccent else RoleName.ctaPrimary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(ctaAlpha(isEnabled))
            .background(theme.fill(role), RoundedCornerShape(theme.radius(RadiusToken.Md)))
            .clickable(enabled = isEnabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(cta.label, style = theme.font(WeirTextStyle.CtaLabel).copy(color = theme.text(role)))
    }
}

/** Core-set substitutions intentionally avoid the much larger material-icons-extended artifact. */
fun IconToken.materialIcon(): ImageVector = when (this) {
    IconToken.book -> Icons.Filled.Menu
    IconToken.moon -> Icons.Filled.Settings
    IconToken.star -> Icons.Filled.Star
    IconToken.sun -> Icons.Filled.Settings
    IconToken.sparkle -> Icons.Filled.Star
    IconToken.heart -> Icons.Filled.Favorite
    IconToken.leaf -> Icons.Filled.Place
    IconToken.cloud -> Icons.Filled.Home
    IconToken.question -> Icons.Filled.Info
    IconToken.compass -> Icons.Filled.Place
    IconToken.bell -> Icons.Filled.Notifications
    IconToken.lock -> Icons.Filled.Lock
    IconToken.shield -> Icons.Filled.Lock
    IconToken.prayer -> Icons.Filled.AccountCircle
    IconToken.quran -> Icons.Filled.List
    IconToken.dhikr -> Icons.Filled.MoreVert
    IconToken.sadaqah -> Icons.Filled.ShoppingCart
    IconToken.clock -> Icons.Filled.DateRange
    IconToken.calendar -> Icons.Filled.DateRange
    IconToken.mail -> Icons.Filled.Email
    IconToken.check -> Icons.Filled.Check
}

data class EmphasisSegment(val text: String, val emphasized: Boolean)

fun parseEmphasisSegments(text: String): List<EmphasisSegment> {
    val result = mutableListOf<EmphasisSegment>()
    var remainder = text
    while (true) {
        val start = remainder.indexOf('*')
        if (start < 0) break
        if (start > 0) result += EmphasisSegment(remainder.substring(0, start), false)
        val end = remainder.indexOf('*', start + 1)
        if (end < 0) {
            result += EmphasisSegment(remainder.substring(start), false)
            remainder = ""
            break
        }
        if (end > start + 1) result += EmphasisSegment(remainder.substring(start + 1, end), true)
        remainder = remainder.substring(end + 1)
    }
    if (remainder.isNotEmpty()) result += EmphasisSegment(remainder, false)
    return result.ifEmpty { listOf(EmphasisSegment(text, false)) }
}

@Composable
fun EmphasisText(text: String, baseColor: Color, font: TextStyle, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    val annotated: AnnotatedString = buildAnnotatedString {
        parseEmphasisSegments(text).forEach { segment ->
            withStyle(SpanStyle(color = if (segment.emphasized) theme.text(RoleName.emphasis) else baseColor)) {
                append(segment.text)
            }
        }
    }
    BasicText(annotated, modifier = modifier, style = font)
}

@Composable
fun OptionRow(
    option: Option,
    isSelected: Boolean,
    selectionStyle: SelectionStyle,
    isMultiple: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalWeirTheme.current
    val borderColor = if (isSelected) theme.fill(RoleName.ctaPrimary) else theme.borderColor
    val shape = RoundedCornerShape(theme.radius(RadiusToken.Md))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(if (isSelected) 2.dp else 1.dp, borderColor, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        option.icon?.let { icon ->
            Box(
                Modifier.size(28.dp).background(theme.fill(RoleName.tint), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = rememberVectorPainter(icon.materialIcon()),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(theme.text(RoleName.tint)),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(option.label, style = theme.font(WeirTextStyle.Body).copy(color = theme.textColor))
            option.description?.let {
                BasicText(it, style = theme.font(WeirTextStyle.Caption).copy(color = theme.mutedTextColor))
            }
        }
        if (selectionStyle == SelectionStyle.affordance) {
            val selectionColor = if (isSelected) theme.fill(RoleName.ctaPrimary) else theme.mutedTextColor
            val affordanceShape = if (isMultiple) RoundedCornerShape(3.dp) else CircleShape
            Box(
                Modifier
                    .size(22.dp)
                    .semantics { contentDescription = if (isSelected) "Selected" else "Not selected" }
                    .border(2.dp, selectionColor, affordanceShape)
                    .then(if (isSelected && isMultiple) Modifier.background(selectionColor, affordanceShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected && isMultiple) {
                    Image(
                        rememberVectorPainter(Icons.Filled.Check),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(theme.text(RoleName.ctaPrimary)),
                        modifier = Modifier.size(16.dp),
                    )
                } else if (isSelected) {
                    Box(Modifier.size(10.dp).background(selectionColor, CircleShape))
                }
            }
        }
    }
}

@Composable
fun DemoTranscript(messages: List<DemoMessage>, autoPlay: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    val reduceMotion = weirReduceMotion()
    var visibleCount by remember(messages) { mutableStateOf(0) }
    LaunchedEffect(autoPlay, reduceMotion, messages) {
        if (reduceMotion || !autoPlay) {
            visibleCount = messages.size
        } else {
            visibleCount = 0
            while (visibleCount < messages.size) {
                delay(600)
                visibleCount++
            }
        }
    }
    Column(
        modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        messages.take(visibleCount).forEach { message ->
            val user = message.from == DemoMessage.From.user
            Row(Modifier.fillMaxWidth()) {
                if (!user) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Column(Modifier.weight(4f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(
                        message.text,
                        Modifier.background(
                            if (user) theme.fill(RoleName.chatUser) else theme.surfaceColor,
                            RoundedCornerShape(14.dp),
                        ).padding(10.dp),
                        theme.font(WeirTextStyle.Body).copy(
                            color = if (user) theme.text(RoleName.chatUser) else theme.textColor,
                        ),
                    )
                    message.sources?.takeIf { it.isNotEmpty() }?.let { sources ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sources.forEach { source ->
                                BasicText(
                                    source,
                                    Modifier.background(theme.fill(RoleName.tint), CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    theme.font(WeirTextStyle.Caption).copy(color = theme.text(RoleName.tint)),
                                )
                            }
                        }
                    }
                }
                if (user) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun BenefitsList(benefits: List<Feature>, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        benefits.forEach { feature ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                feature.icon?.let {
                    Image(
                        rememberVectorPainter(it.materialIcon()),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(theme.text(RoleName.tint)),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column {
                    BasicText(feature.title, style = theme.font(WeirTextStyle.Body).copy(color = theme.textColor))
                    feature.subtitle?.let {
                        BasicText(it, style = theme.font(WeirTextStyle.Caption).copy(color = theme.mutedTextColor))
                    }
                }
            }
        }
    }
}
