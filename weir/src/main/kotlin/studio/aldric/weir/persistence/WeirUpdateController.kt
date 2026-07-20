package studio.aldric.weir.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the background "check for a remote bundle update" trigger (remote
 * delivery M3) on top of [BundleManager]'s fetch/verify/stage machinery — adds
 * no new network or crypto logic, just the call sites and scheduling. Reuses
 * the [ForegroundFlushTrigger] app-foreground pattern already used by
 * [EventQueue] rather than inventing a new one. Faithful port of iOS
 * `WeirUpdateController`.
 *
 * Promotion ([BundleManager.promoteStagedUpdateIfAny]) deliberately does NOT
 * happen here — it must only run at a flow-present boundary (never mid-flow,
 * never on a foreground tick that could race a live WebView), so that call
 * site lives in [WeirBundleResolution.resolve] instead.
 *
 * One instance per [studio.aldric.weir.Weir.configure] call, held for the
 * process's lifetime.
 */
class WeirUpdateController(
    private val config: WeirUpdateConfig,
    rootDirectory: File? = null,
    embeddedBundleRoot: File? = null,
    httpClient: BundleHttpClient = HttpUrlConnectionBundleHttpClient(),
    /** Injectable so tests never touch a real Android lifecycle. Production
     *  wiring supplies [ProcessLifecycleForegroundTrigger]. */
    private val foregroundTrigger: ForegroundFlushTrigger? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val logger: (String) -> Unit = {},
) {
    val bundleManager: BundleManager = BundleManager(
        rootDirectory = rootDirectory,
        publicKeyRaw = config.publicKeyRaw,
        embeddedBundleRoot = embeddedBundleRoot,
        httpClient = httpClient,
        logger = logger,
    )

    /**
     * Kicks off the launch-time check (backgrounded — never blocks the caller)
     * and starts observing app-foreground to repeat it. Called once by
     * [studio.aldric.weir.Weir.configure].
     */
    fun start() {
        checkForUpdate()
        foregroundTrigger?.register { checkForUpdate() }
    }

    /**
     * One check-for-update attempt against `config.manifestURL`.
     * Fire-and-forget: [BundleManager.checkForUpdate] already swallows every
     * failure (network, decode, signature, unsupported spec) internally and
     * just logs, so there's nothing for this call site to react to.
     */
    fun checkForUpdate() {
        scope.launch {
            bundleManager.checkForUpdate(config.manifestURL)
        }
    }

    /** Stop observing app-foreground (idempotent). */
    fun stop() {
        foregroundTrigger?.unregister()
    }
}
