package studio.aldric.weir

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CompletableDeferred
import studio.aldric.weir.bridge.ActivityPermissionRequester

/** Full-screen Activity host for the Compose-native renderer. */
class WeirFlowActivity : ComponentActivity() {
    private var request: WeirPresentationRequest? = null
    private var token: String? = null
    private var pendingPermissionResult: CompletableDeferred<Map<String, Boolean>>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            pendingPermissionResult?.complete(granted)
            pendingPermissionResult = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent?.getStringExtra(EXTRA_TOKEN)
        val request = WeirPresentationRegistry.get(token) ?: run {
            finish()
            return
        }
        this.request = request
        applyEdgeToEdge(request.systemBarStyle)
        val permissionRequester = ActivityPermissionRequester(this, ::launchSystemPermission)
        setContent {
            request.content(permissionRequester) { result ->
                request.deliver(result)
                if (!isFinishing) finish()
            }
        }
    }

    private suspend fun launchSystemPermission(permissions: List<String>): Map<String, Boolean> {
        val deferred = CompletableDeferred<Map<String, Boolean>>()
        pendingPermissionResult = deferred
        runOnUiThread { permissionLauncher.launch(permissions.toTypedArray()) }
        return deferred.await()
    }

    private fun applyEdgeToEdge(style: WeirSystemBarStyle) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = style.lightStatusBarIcons
            isAppearanceLightNavigationBars = style.lightNavBarIcons
        }
    }

    override fun onDestroy() {
        pendingPermissionResult?.cancel()
        pendingPermissionResult = null
        if (isFinishing) {
            request?.let { if (!it.hasDelivered) it.deliver(WeirFlowResult.Dismissed("activity_finished")) }
            WeirPresentationRegistry.remove(token)
        }
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_TOKEN = "studio.aldric.weir.PRESENTATION_TOKEN"
    }
}
