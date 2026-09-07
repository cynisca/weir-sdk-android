package studio.aldric.weir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import studio.aldric.weir.WeirSdk
import studio.aldric.weir.Weir
import studio.aldric.weir.WeirFlowConfigResolution
import studio.aldric.weir.WeirFlowResult
import studio.aldric.weir.billing.NoopPurchaseProvider
import studio.aldric.weir.billing.PurchaseProviding
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.HealthEvent
import studio.aldric.weir.bridge.PermissionRequester
import studio.aldric.weir.bridge.UnavailablePermissionRequester
import studio.aldric.weir.engine.CustomScreenConfig
import studio.aldric.weir.engine.DemoScreenConfig
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.HoldToCommitScreenConfig
import studio.aldric.weir.engine.LoaderScreenConfig
import studio.aldric.weir.engine.MomentScreenConfig
import studio.aldric.weir.engine.MultiSelectScreenConfig
import studio.aldric.weir.engine.NumberInputScreenConfig
import studio.aldric.weir.engine.PaywallScreenConfig
import studio.aldric.weir.engine.PermissionPrimeScreenConfig
import studio.aldric.weir.engine.SingleSelectScreenConfig
import studio.aldric.weir.engine.SliderScreenConfig
import studio.aldric.weir.engine.SocialProofScreenConfig
import studio.aldric.weir.engine.TextInputScreenConfig
import studio.aldric.weir.engine.WelcomeScreenConfig
import studio.aldric.weir.persistence.EventQueue
import studio.aldric.weir.persistence.ProcessLifecycleForegroundTrigger
import studio.aldric.weir.persistence.WeirIngestConfig
import studio.aldric.weir.persistence.WeirDeviceContext
import studio.aldric.weir.registry.ComponentRegistry
import studio.aldric.weir.ui.components.DemoScreen
import studio.aldric.weir.ui.components.HoldToCommitScreen
import studio.aldric.weir.ui.components.LoaderScreen
import studio.aldric.weir.ui.components.MomentScreen
import studio.aldric.weir.ui.components.MultiSelectScreen
import studio.aldric.weir.ui.components.NumberInputScreen
import studio.aldric.weir.ui.components.PaywallScreen
import studio.aldric.weir.ui.components.PermissionPrimeScreen
import studio.aldric.weir.ui.components.SingleSelectScreen
import studio.aldric.weir.ui.components.SliderScreen
import studio.aldric.weir.ui.components.SocialProofScreen
import studio.aldric.weir.ui.components.TextInputScreen
import studio.aldric.weir.ui.components.WelcomeScreen
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UnknownWeirComponentException(val component: String) :
    IllegalStateException("No registered Weir component named '$component'")

private class NoServableWeirScreenException :
    IllegalStateException("No screen in this config passed gating for this build/user.")

/** Gives resolution-time fallbacks (before an engine exists) the same safe,
 * durable HealthEvent builder as engine-owned renderer fallbacks. */
private class FlowFallbackReporter(private val flowId: String, private val eventSink: EventSink) {
    private val sessionId = UUID.randomUUID().toString()
    private val seq = AtomicInteger(0)

    fun report(reason: String) {
        eventSink.append(HealthEvent.flowFallback(flowId, sessionId, seq.getAndIncrement(), reason))
    }
}

/** Compose-native flow renderer for direct embedding in a host hierarchy. */
@Composable
fun WeirFlow(
    flowId: String,
    configRoot: File? = null,
    userId: String? = null,
    purchaseProvider: PurchaseProviding = NoopPurchaseProvider(),
    permissionRequester: PermissionRequester = UnavailablePermissionRequester,
    eventSink: EventSink,
    ingest: WeirIngestConfig? = null,
    onResult: (WeirFlowResult) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    var engine by remember(flowId, configRoot) { mutableStateOf<FlowEngine?>(null) }
    var failure by remember(flowId, configRoot) { mutableStateOf<Throwable?>(null) }
    val fallbackReporter = remember(flowId, configRoot, eventSink) { FlowFallbackReporter(flowId, eventSink) }
    val currentOnResult by rememberUpdatedState(onResult)
    val delivered = remember(flowId, configRoot) { AtomicBoolean(false) }
    val deliver: (WeirFlowResult) -> Unit = { result ->
        if (delivered.compareAndSet(false, true)) {
            currentOnResult(result)
            (eventSink as? EventQueue)?.triggerFlush()
        }
    }

    LaunchedEffect(flowId, configRoot) {
        if (ingest != null) {
            (eventSink as? EventQueue)?.configureIngest(
                if (ingest.deviceContext != null) ingest else ingest.copy(
                    deviceContext = WeirDeviceContext.current(appContext, WeirSdk.WEIR_SDK_VERSION),
                ),
                foregroundTrigger = ProcessLifecycleForegroundTrigger(),
            )
        }
        WeirFlowConfigResolution.resolve(
            flowId,
            configRoot,
            Weir.updateController,
            Weir.fallbackBundleManager(),
        ).fold(
            onSuccess = { (config, root) ->
                ComponentRegistry.checkDriftOnce(eventSink)
                val activeManager = Weir.updateController?.bundleManager
                val isRemote = activeManager != null && activeManager.activeBundleVersion > 0 && activeManager.activeBundleURL == root
                val newEngine = FlowEngine(
                    config, userId, eventSink,
                    assetsRoot = root,
                    configId = if (isRemote) root.name else null,
                    configVersion = if (isRemote) activeManager?.activeBundleVersion else null,
                    customScreenAvailability = ComponentRegistry::availability,
                )
                newEngine.onComplete { deliver(WeirFlowResult.Completed(it)) }
                newEngine.onDismiss { deliver(WeirFlowResult.Dismissed(it)) }
                if (newEngine.currentScreen == null) {
                    newEngine.recordHealthFallback("no_servable_screen")
                    val error = NoServableWeirScreenException()
                    failure = error
                    deliver(WeirFlowResult.Failed(error))
                } else {
                    engine = newEngine
                }
            },
            onFailure = { error ->
                fallbackReporter.report("config_resolution_failed")
                failure = error
                deliver(WeirFlowResult.Failed(error))
            },
        )
    }

    CompositionLocalProvider(
        LocalWeirPurchaseProvider provides purchaseProvider,
        LocalWeirPermissionRequester provides permissionRequester,
    ) {
        val liveEngine = engine
        val screen = liveEngine?.currentScreen
        val theme = remember(liveEngine, liveEngine?.currentScreenId) {
            if (liveEngine != null && screen != null) WeirTheme.make(liveEngine.config, screen) else WeirTheme.default
        }
        CompositionLocalProvider(LocalWeirTheme provides theme) {
            Box(Modifier.fillMaxSize().background(theme.backgroundColor)) {
                if (liveEngine != null && !liveEngine.isComplete) {
                    when (screen) {
                        is WelcomeScreenConfig -> WelcomeScreen(screen, liveEngine)
                        is SingleSelectScreenConfig -> SingleSelectScreen(screen, liveEngine)
                        is MultiSelectScreenConfig -> MultiSelectScreen(screen, liveEngine)
                        is SliderScreenConfig -> SliderScreen(screen, liveEngine)
                        is NumberInputScreenConfig -> NumberInputScreen(screen, liveEngine)
                        is TextInputScreenConfig -> TextInputScreen(screen, liveEngine)
                        is LoaderScreenConfig -> LoaderScreen(screen, liveEngine)
                        is SocialProofScreenConfig -> SocialProofScreen(screen, liveEngine)
                        is PermissionPrimeScreenConfig -> PermissionPrimeScreen(screen, liveEngine)
                        is PaywallScreenConfig -> PaywallScreen(screen, liveEngine)
                        is MomentScreenConfig -> MomentScreen(screen, liveEngine)
                        is HoldToCommitScreenConfig -> HoldToCommitScreen(screen, liveEngine)
                        is DemoScreenConfig -> DemoScreen(screen, liveEngine)
                        is CustomScreenConfig -> {
                            val view = ComponentRegistry.resolveView(screen.component, screen.props)
                            if (view != null) view() else UnknownComponentFailure(screen.component, liveEngine, deliver)
                        }
                        null -> Unit
                    }
                }
            }
        }
    }
    failure?.let { Unit } // Retain failure as presentation state; onResult is the host signal.
}

@Composable
private fun UnknownComponentFailure(component: String, engine: FlowEngine, deliver: (WeirFlowResult) -> Unit) {
    LaunchedEffect(component) {
        engine.recordHealthFallback("unrenderable_custom_component")
        deliver(WeirFlowResult.Failed(UnknownWeirComponentException(component)))
    }
}
