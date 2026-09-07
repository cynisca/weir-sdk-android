package studio.aldric.weir.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import studio.aldric.weir.bridge.PurchaseStatus
import studio.aldric.weir.engine.Feature
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.PaywallContentAlign
import studio.aldric.weir.engine.PaywallFeatureStyle
import studio.aldric.weir.engine.PaywallProduct
import studio.aldric.weir.engine.PaywallProductsLayout
import studio.aldric.weir.engine.PaywallScreenConfig
import studio.aldric.weir.engine.theme.RoleName
import studio.aldric.weir.ui.CtaButton
import studio.aldric.weir.ui.LocalWeirPurchaseProvider
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.RadiusToken
import studio.aldric.weir.ui.WeirTextStyle
import studio.aldric.weir.ui.materialIcon
import studio.aldric.weir.ui.weirReduceMotion

enum class PaywallCardEmphasis { Selected, Highlighted, Plain;
    companion object {
        fun resolve(isSelected: Boolean, highlighted: Boolean): PaywallCardEmphasis = when {
            isSelected -> Selected
            highlighted -> Highlighted
            else -> Plain
        }
    }
}

internal fun badgePulseEnabled(reduceMotion: Boolean): Boolean = !reduceMotion

@Composable
fun PaywallScreen(config: PaywallScreenConfig, engine: FlowEngine, modifier: Modifier = Modifier) {
    val theme = LocalWeirTheme.current
    val provider = LocalWeirPurchaseProvider.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedProductId by remember(config.base.id) { mutableStateOf<String?>(null) }
    var isPurchasing by remember(config.base.id) { mutableStateOf(false) }
    val center = config.contentAlign == PaywallContentAlign.center

    LaunchedEffect(config.base.id) {
        if (selectedProductId == null) selectedProductId = config.products.firstOrNull()?.id
        engine.recordInteraction(
            "paywall_shown",
            config.base.id,
            mapOf("products" to JsonArray(config.products.map { JsonPrimitive(it.id) })),
        )
    }

    Box(modifier.fillMaxSize().background(theme.backgroundColor)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(theme.spacing),
            ) {
                config.title?.let {
                    BasicText(it, style = theme.font(WeirTextStyle.Kicker).copy(color = theme.text(RoleName.kicker)))
                }
                BasicText(
                    config.headline,
                    style = theme.font(WeirTextStyle.Headline).copy(color = theme.textColor, textAlign = if (center) TextAlign.Center else TextAlign.Start),
                )
                config.subtitle?.let {
                    BasicText(it, style = theme.font(WeirTextStyle.Subtitle).copy(color = theme.mutedTextColor))
                }
                config.features?.let { Features(it, config.featureStyle) }
                if (config.productsLayout == PaywallProductsLayout.row) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        config.products.forEach { product ->
                            ProductCard(product, selectedProductId == product.id, Modifier.weight(1f)) { selectedProductId = product.id }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        config.products.forEach { product ->
                            ProductCard(product, selectedProductId == product.id) { selectedProductId = product.id }
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(theme.backgroundColor).imePadding().navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CtaButton(config.cta, selectedProductId != null && !isPurchasing) {
                    val productId = selectedProductId ?: return@CtaButton
                    if (isPurchasing) return@CtaButton
                    isPurchasing = true
                    engine.recordInteraction("purchase_intent", config.base.id, mapOf("product" to JsonPrimitive(productId)))
                    scope.launch {
                        val result = provider.purchase(productId, null)
                        engine.recordInteraction(
                            "purchase_result",
                            config.base.id,
                            mapOf("product" to JsonPrimitive(productId), "status" to JsonPrimitive(result.status.name.lowercase())),
                        )
                        isPurchasing = false
                        if (result.status == PurchaseStatus.PURCHASED || result.status == PurchaseStatus.RESTORED) {
                            engine.complete("purchased")
                        }
                    }
                }
                Footnote(config.footnote)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FooterLink(config.restoreLabel) {
                        scope.launch {
                            val result = provider.restore()
                            engine.recordInteraction(
                                "purchase_result",
                                config.base.id,
                                mapOf(
                                    "product" to JsonPrimitive(selectedProductId ?: ""),
                                    "status" to JsonPrimitive(result.status),
                                ),
                            )
                            if (result.status == "restored") engine.complete("purchased")
                        }
                    }
                    validWebUri(config.termsUrl)?.let { uri -> FooterLink("Terms") { launchUri(context, uri) } }
                    validWebUri(config.privacyUrl)?.let { uri -> FooterLink("Privacy") { launchUri(context, uri) } }
                    // P1 ruling (RC re-review): rejecting a paywall EXITS the
                    // flow — dismiss(reason:), not skip(). skip() is for a
                    // skippable NON-terminal affordance that still advances
                    // via `next`; a paywall close is terminal, the same
                    // event-stream outcome RN's PaywallScreen.tsx and iOS's
                    // PaywallScreenView.swift already produce for both their
                    // close icon and dismissLabel link — unified here too,
                    // not just the icon.
                    config.dismissLabel?.let { label -> FooterLink(label) { engine.dismiss(reason = "dismissed") } }
                }
            }
        }
        if (config.dismissable) {
            Image(
                rememberVectorPainter(Icons.Filled.Close),
                contentDescription = "Dismiss",
                colorFilter = ColorFilter.tint(theme.mutedTextColor),
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(28.dp)
                    .clickable(role = Role.Button) { engine.dismiss(reason = "dismissed") },
            )
        }
    }
}

@Composable
private fun Features(features: List<Feature>, style: PaywallFeatureStyle) {
    val theme = LocalWeirTheme.current
    if (style == PaywallFeatureStyle.cards) {
        Row(
            Modifier.fillMaxWidth().testTag("paywall-features-cards"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            features.forEach { feature ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    feature.icon?.let {
                        Box(Modifier.size(40.dp).background(theme.fill(RoleName.tint), CircleShape), contentAlignment = Alignment.Center) {
                            Image(rememberVectorPainter(it.materialIcon()), null, colorFilter = ColorFilter.tint(theme.text(RoleName.tint)), modifier = Modifier.size(22.dp))
                        }
                    }
                    BasicText(feature.title, style = theme.font(WeirTextStyle.Caption).copy(color = theme.textColor, textAlign = TextAlign.Center))
                }
            }
        }
    } else {
        Column(Modifier.testTag("paywall-features-plain"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            features.forEach { feature ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    feature.icon?.let {
                        Image(rememberVectorPainter(it.materialIcon()), null, colorFilter = ColorFilter.tint(theme.text(RoleName.tint)), modifier = Modifier.size(20.dp))
                    }
                    Column {
                        BasicText(feature.title, style = theme.font(WeirTextStyle.Body).copy(color = theme.textColor))
                        feature.subtitle?.let { BasicText(it, style = theme.font(WeirTextStyle.Caption).copy(color = theme.mutedTextColor)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(product: PaywallProduct, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val theme = LocalWeirTheme.current
    val reduceMotion = weirReduceMotion()
    val emphasis = PaywallCardEmphasis.resolve(isSelected, product.highlighted)
    val borderColor = when (emphasis) {
        PaywallCardEmphasis.Selected -> theme.fill(RoleName.ctaPrimary)
        PaywallCardEmphasis.Highlighted -> theme.fill(RoleName.ctaAccent)
        PaywallCardEmphasis.Plain -> theme.borderColor
    }
    val pulse = if (!badgePulseEnabled(reduceMotion)) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "badge-pulse")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "badge-scale",
        )
        animated
    }
    val shape = RoundedCornerShape(theme.radius(RadiusToken.Md))
    Column(
        modifier.fillMaxWidth().background(if (isSelected) theme.fill(RoleName.tint) else Color.Transparent, shape)
            .border(if (emphasis == PaywallCardEmphasis.Selected) 2.dp else 1.dp, borderColor, shape)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        product.badge?.let {
            BasicText(
                it,
                Modifier.scale(pulse).background(theme.fill(RoleName.badge), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
                theme.font(WeirTextStyle.Caption).copy(color = theme.text(RoleName.badge), fontWeight = FontWeight.Bold),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(product.label, Modifier.weight(1f), theme.font(WeirTextStyle.Body).copy(color = theme.textColor, fontWeight = FontWeight.Bold))
            product.priceHint?.let { BasicText(it, style = theme.font(WeirTextStyle.Body).copy(color = theme.textColor, fontWeight = FontWeight.Bold)) }
        }
        product.caption?.let { BasicText(it, style = theme.font(WeirTextStyle.Caption).copy(color = theme.text(RoleName.emphasis))) }
    }
}

@Composable
private fun Footnote(footnote: studio.aldric.weir.engine.Footnote?) {
    val theme = LocalWeirTheme.current
    val lines = when (footnote) {
        null -> emptyList()
        is studio.aldric.weir.engine.Footnote.Single -> listOf(footnote.value)
        is studio.aldric.weir.engine.Footnote.Lines -> footnote.value
    }
    lines.forEach { BasicText(it, style = theme.font(WeirTextStyle.Caption).copy(color = theme.dimTextColor, textAlign = TextAlign.Center)) }
}

@Composable
private fun FooterLink(label: String, onClick: () -> Unit) {
    val theme = LocalWeirTheme.current
    BasicText(
        label,
        Modifier.clickable(role = Role.Button, onClick = onClick).padding(2.dp),
        theme.font(WeirTextStyle.Caption).copy(color = theme.dimTextColor),
    )
}

private fun validWebUri(raw: String?): Uri? = raw?.let(Uri::parse)?.takeIf {
    (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrBlank()
}

private fun launchUri(context: android.content.Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // A valid URL can still have no installed handler; keep the flow usable.
    }
}
