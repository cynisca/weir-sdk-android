package studio.aldric.weir.demo

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import studio.aldric.weir.Weir
import studio.aldric.weir.WeirFlowResult
import studio.aldric.weir.WeirSystemBarStyle
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.LoggingEventSink
import studio.aldric.weir.persistence.EventQueue
import studio.aldric.weir.persistence.WeirIngestConfig
import java.io.File
import java.net.URL

/**
 * Minimal installable host proving the Weir Android SDK end-to-end (plan §8
 * Phase 3, item 5): it copies the embedded flow bundle out of `assets/` to
 * internal storage and calls [Weir.presentActivity] to run it, exactly as a
 * real integration (Niyat, CutOrBulk) would. Also the target the Maestro flows
 * in `sdk-android/maestro/` drive.
 *
 * Plain Android Views (no Compose) keep the demo's dependency surface tiny; the
 * status banners exist purely so Maestro can assert bridge outcomes (permission
 * grant/deny, completion, crash-containment fallback) that otherwise only reach
 * the host via callbacks — the Android analogue of iOS `WeirDemo`'s
 * `ContentView` banners.
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusBanner: TextView

    // IS-3 E2E seam: the conductor passes box config via intent extras so the
    // emulator run points at a hermetic box (userId + ingest). Absent extras,
    // this is the original local demo (a device-stable userId, log-only sink).
    private lateinit var flowUserId: String
    private lateinit var flowEventSink: EventSink
    private var flowIngest: WeirIngestConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // R2-S4: this Activity is exported (LAUNCHER), so any app could start it
        // with crafted extras. The conductor's box-pointing config
        // (userId/ingest/autostart) is a DEBUG-ONLY test seam — honor it only in a
        // debuggable build, so a release demo can't be driven to flush events to
        // an attacker-controlled endpoint via intent extras.
        val debugConfig = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        flowUserId = (if (debugConfig) intent.getStringExtra("WEIR_DEMO_USER_ID") else null)
            ?: Weir.stableUserId(this)
        flowIngest = (if (debugConfig) intent.getStringExtra("WEIR_DEMO_INGEST_URL") else null)?.let { url ->
            WeirIngestConfig(
                endpointURL = URL(url),
                appId = intent.getStringExtra("WEIR_DEMO_APP_ID") ?: "cob",
                writeToken = intent.getStringExtra("WEIR_DEMO_INGEST_TOKEN") ?: "",
            )
        }
        // A plain EventQueue lets presentActivity(ingest:) attach a live flush to
        // the box; absent an ingest URL, keep the log-only sink (local demo).
        flowEventSink = if (flowIngest != null) EventQueue(File(filesDir, "weir-events")) else LoggingEventSink()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Weir Android Demo"
            textSize = 22f
            gravity = Gravity.CENTER
        }

        statusBanner = TextView(this).apply {
            // Stable content-description Maestro asserts against.
            text = "status: idle"
            contentDescription = "status: idle"
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        }

        val startButton = Button(this).apply {
            text = "Start onboarding"
            setOnClickListener { presentFlow(installBundle()) }
        }

        // Crash-containment target: point at a bundle root that doesn't exist,
        // so the WebView's main-frame load fails and the SDK surfaces
        // WeirFlowResult.Failed (native fallback) instead of taking the app down.
        val brokenButton = Button(this).apply {
            text = "Start broken flow"
            setOnClickListener { presentFlow(File(filesDir, "does-not-exist")) }
        }

        root.addView(title)
        root.addView(statusBanner)
        root.addView(startButton, wrap())
        root.addView(brokenButton, wrap())
        setContentView(root)

        // Conductor-driven runs auto-present so no tap is needed over adb —
        // debug-only, same rationale as the config extras above (R2-S4).
        if (debugConfig && intent.getStringExtra("WEIR_DEMO_AUTOSTART") == "1") {
            presentFlow(installBundle())
        }
    }

    private fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun presentFlow(bundleRoot: File) {
        setStatus("status: presenting")
        Weir.presentActivity(
            context = this,
            flowId = "demo_onboarding",
            bundleRoot = bundleRoot,
            userId = flowUserId,
            eventSink = flowEventSink,
            ingest = flowIngest,
            // null background → WeirFlowActivity reads manifest.json theme.
            backgroundColor = null,
            systemBarStyle = WeirSystemBarStyle.forDarkBackground,
            onPermissionResult = { type, status ->
                runOnUiThread { setStatus("permissionResult: ${type.name.lowercase()}=${status.name.lowercase()}") }
            },
        ) { result ->
            runOnUiThread {
                when (result) {
                    is WeirFlowResult.Completed ->
                        setStatus("onComplete: " + result.variables.joinToString(", ") { "${it.id}=${it.value}" })
                    is WeirFlowResult.Dismissed -> setStatus("onDismiss: ${result.reason}")
                    is WeirFlowResult.Failed -> setStatus("onFailure: ${result.error.message}")
                }
            }
        }
    }

    private fun setStatus(text: String) {
        statusBanner.text = text
        statusBanner.contentDescription = text
    }

    /**
     * Copies the embedded `assets/demoflow` bundle to internal storage and
     * returns its root [File]. [studio.aldric.weir.webview.WeirAssetLoader]
     * serves from a filesystem directory (embedded assets or a promoted OTA
     * bundle), so an APK-`assets/`-only bundle is materialized once here — the
     * same thing a real host does at first launch.
     */
    private fun installBundle(): File {
        val dest = File(filesDir, "demoflow")
        copyAsset("demoflow", dest)
        return dest
    }

    private fun copyAsset(assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // Leaf file.
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAsset("$assetPath/$child", File(dest, child))
        }
    }
}
