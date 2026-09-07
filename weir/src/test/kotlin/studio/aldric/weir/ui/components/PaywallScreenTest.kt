package studio.aldric.weir.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.billing.PurchaseProviding
import studio.aldric.weir.bridge.ProductInfo
import studio.aldric.weir.bridge.PurchaseRestoreResult
import studio.aldric.weir.bridge.PurchaseStartResult
import studio.aldric.weir.bridge.PurchaseStatus
import studio.aldric.weir.engine.PaywallScreenConfig
import studio.aldric.weir.ui.LocalWeirPurchaseProvider
import studio.aldric.weir.ui.LocalWeirTheme
import studio.aldric.weir.ui.WeirTheme

@RunWith(RobolectricTestRunner::class)
class PaywallScreenTest {
    @get:Rule val compose = createComposeRule()

    private val products = """"products":[{"id":"monthly","label":"Monthly"},{"id":"yearly","label":"Yearly","highlighted":true}],"cta":{"label":"Subscribe"}"""

    @Test fun defaultSelectionPurchasesFirstProductAndCompletesForPurchased() = purchaseCase(PurchaseStatus.PURCHASED, true)
    @Test fun restoredPurchaseCompletes() = purchaseCase(PurchaseStatus.RESTORED, true)
    @Test fun failedPurchaseDoesNotComplete() = purchaseCase(PurchaseStatus.FAILED, false)
    @Test fun cancelledPurchaseDoesNotComplete() = purchaseCase(PurchaseStatus.CANCELLED, false)

    @Test fun featureStyleUsesDistinctPaths() {
        val cards = engineFor("""{"id":"pay","type":"paywall","headline":"Unlock",$products,"featureStyle":"cards","features":[{"title":"One","icon":"star"}]}""").first
        val config = cards.currentScreen as PaywallScreenConfig
        compose.setContent { Themed(config, cards, FakeProvider()) }
        compose.onNodeWithTag("paywall-features-cards").assertExists()
    }

    @Test fun plainFeatureStyleUsesPlainPath() {
        val plain = engineFor("""{"id":"pay","type":"paywall","headline":"Unlock",$products,"featureStyle":"plain","features":[{"title":"One","icon":"star"}]}""").first
        val config = plain.currentScreen as PaywallScreenConfig
        compose.setContent { Themed(config, plain, FakeProvider()) }
        compose.onNodeWithTag("paywall-features-plain").assertExists()
    }

    @Test fun successfulRestoreCompletesFlow() {
        val (engine, sink) = engineFor("""{"id":"pay","type":"paywall","headline":"Unlock",$products}""")
        val config = engine.currentScreen as PaywallScreenConfig
        val provider = FakeProvider(restoreStatus = "restored")
        sink.events.clear()
        compose.setContent { Themed(config, engine, provider) }
        compose.onNodeWithText("Restore").performClick()
        compose.waitUntil { engine.isComplete }
        assertEquals(1, provider.restoreCalls)
        assertTrue("purchase_result" in sink.types())
        assertTrue(engine.isComplete)
    }

    // P1 ruling (RC re-review): a paywall close/X is terminal — it calls
    // engine.dismiss(reason:"dismissed"), not engine.skip(). skip() is for a
    // skippable NON-terminal affordance that still advances via `next`; a
    // rejected paywall exits the flow instead, the same outcome RN's
    // PaywallScreen.tsx and iOS's PaywallScreenView.swift already produce.
    @Test fun closeIconDismissesFlowRatherThanSkippingToNext() {
        val json = """{"id":"pay","type":"paywall","headline":"Unlock","next":"done",$products},{"id":"done","type":"welcome","title":"Done","cta":{"label":"Finish"}}"""
        val (engine, sink) = engineFor(json)
        val config = engine.currentScreen as PaywallScreenConfig
        sink.events.clear()
        compose.setContent { Themed(config, engine, FakeProvider()) }
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.runOnIdle {
            assertEquals("pay", engine.currentScreenId)
            assertTrue(engine.isComplete)
            assertFalse("screen_skipped" in sink.types())
            assertFalse("branch_decision" in sink.types())
            val completed = sink.events.single { it.payload.jsonObject["type"]?.jsonPrimitive?.content == "flow_completed" }
            assertEquals("dismissed", completed.payload.jsonObject["reason"]?.jsonPrimitive?.content)
        }
    }

    @Test fun dismissLabelLinkDismissesFlowTheSameWayAsTheCloseIcon() {
        val json = """{"id":"pay","type":"paywall","headline":"Unlock","dismissLabel":"Maybe later","next":"done",$products},{"id":"done","type":"welcome","title":"Done","cta":{"label":"Finish"}}"""
        val (engine, sink) = engineFor(json)
        val config = engine.currentScreen as PaywallScreenConfig
        sink.events.clear()
        compose.setContent { Themed(config, engine, FakeProvider()) }
        compose.onNodeWithText("Maybe later").performClick()
        compose.runOnIdle {
            assertEquals("pay", engine.currentScreenId)
            assertTrue(engine.isComplete)
            assertFalse("screen_skipped" in sink.types())
        }
    }

    @Test fun emphasisResolverPrioritizesSelection() {
        assertEquals(PaywallCardEmphasis.Selected, PaywallCardEmphasis.resolve(true, true))
        assertEquals(PaywallCardEmphasis.Highlighted, PaywallCardEmphasis.resolve(false, true))
        assertEquals(PaywallCardEmphasis.Plain, PaywallCardEmphasis.resolve(false, false))
        assertFalse(badgePulseEnabled(true))
        assertTrue(badgePulseEnabled(false))
    }

    private fun purchaseCase(status: PurchaseStatus, shouldComplete: Boolean) {
        val (engine, sink) = engineFor("""{"id":"pay","type":"paywall","headline":"Unlock",$products}""")
        val config = engine.currentScreen as PaywallScreenConfig
        val provider = FakeProvider(status)
        sink.events.clear()
        compose.setContent { Themed(config, engine, provider) }
        compose.onNodeWithText("Subscribe").performClick()
        compose.waitUntil { provider.purchaseCalls > 0 && "purchase_result" in sink.types() }
        compose.runOnIdle {
            assertEquals(listOf("monthly"), provider.purchasedProducts)
            assertEquals(1, sink.types().count { it == "paywall_shown" })
            val interactionTypes = sink.types().filter { it == "purchase_intent" || it == "purchase_result" }
            assertEquals(listOf("purchase_intent", "purchase_result"), interactionTypes)
            assertEquals(shouldComplete, engine.isComplete)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Themed(config: PaywallScreenConfig, engine: studio.aldric.weir.engine.FlowEngine, provider: PurchaseProviding) {
        CompositionLocalProvider(
            LocalWeirTheme provides WeirTheme.make(engine.config, config),
            LocalWeirPurchaseProvider provides provider,
        ) { PaywallScreen(config, engine) }
    }

    private class FakeProvider(
        private val purchaseStatus: PurchaseStatus = PurchaseStatus.FAILED,
        private val restoreStatus: String = "failed",
    ) : PurchaseProviding {
        var purchaseCalls = 0
        var restoreCalls = 0
        val purchasedProducts = mutableListOf<String>()

        override suspend fun purchase(productId: String, offerId: String?): PurchaseStartResult {
            purchaseCalls++
            purchasedProducts += productId
            return PurchaseStartResult(purchaseStatus)
        }

        override suspend fun restore(): PurchaseRestoreResult {
            restoreCalls++
            return PurchaseRestoreResult(restoreStatus)
        }

        override suspend fun products(ids: List<String>): List<ProductInfo> = emptyList()
    }
}
