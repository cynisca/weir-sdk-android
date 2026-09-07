package studio.aldric.weir.demo

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import studio.aldric.weir.Weir
import studio.aldric.weir.WeirFlowResult
import studio.aldric.weir.WeirSystemBarStyle
import studio.aldric.weir.bridge.EventSink
import studio.aldric.weir.bridge.LoggingEventSink
import studio.aldric.weir.persistence.EventQueue
import studio.aldric.weir.persistence.WeirIngestConfig
import java.io.File
import java.net.URL

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("Ready")
    private var flowUserId: String? = null
    private lateinit var flowEventSink: EventSink
    private var flowIngest: WeirIngestConfig? = null
    private lateinit var galleryRoot: File
    private var debugBuild = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // The gallery is anonymous by default. A QA run may provide a
        // synthetic debug-only ID when it explicitly needs deterministic
        // experiment bucketing; production hosts must opt in the same way.
        flowUserId = if (debugBuild) intent.getStringExtra("WEIR_DEMO_USER_ID") else null
        flowIngest = (if (debugBuild) intent.getStringExtra("WEIR_DEMO_INGEST_URL") else null)?.let { url ->
            WeirIngestConfig(
                endpointURL = URL(url),
                appId = intent.getStringExtra("WEIR_DEMO_APP_ID") ?: "weir-gallery",
                writeToken = intent.getStringExtra("WEIR_DEMO_INGEST_TOKEN") ?: "",
            )
        }
        flowEventSink = if (flowIngest != null) EventQueue(File(filesDir, "weir-events")) else LoggingEventSink()
        galleryRoot = installGallery()
        setContent { Gallery() }
    }

    @Composable
    private fun Gallery() {
        val ink = Color(0xFF10182B)
        val paper = Color(0xFFF2F5F7)
        val coral = Color(0xFFFF6D5A)
        val mist = Color(0xFFAEBBD0)
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(ink).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(38.dp))
                BasicText("WEIR / NATIVE", style = TextStyle(color = coral, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                Spacer(Modifier.height(10.dp))
                BasicText("Flow specimen cabinet", style = TextStyle(color = paper, fontSize = 32.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                BasicText("Thirteen stock screens, isolated and wired to the real renderer.", style = TextStyle(color = mist, fontSize = 16.sp))
                Spacer(Modifier.height(18.dp))
                StatusPill(status, paper, coral)
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(fixtures) { index, fixture ->
                SpecimenRow(index, fixture, paper, ink, coral)
            }
            item {
                Spacer(Modifier.height(6.dp))
                SpecimenRow(13, Fixture("everything", "Everything flow", "A complete 13-screen journey"), coral, ink, paper)
                if (debugBuild) {
                    Spacer(Modifier.height(10.dp))
                    SpecimenRow(
                        14,
                        Fixture(
                            "debug-missing-flow",
                            "Debug failure probe",
                            "Missing flow ID — verifies host failure containment",
                        ),
                        coral,
                        ink,
                        paper,
                    )
                }
                Spacer(Modifier.height(34.dp))
            }
        }
    }

    @Composable
    private fun StatusPill(value: String, paper: Color, coral: Color) {
        Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFF202C43)).padding(horizontal = 12.dp, vertical = 7.dp)) {
            BasicText("STATUS  $value", Modifier.semantics { contentDescription = "status: $value" }, TextStyle(color = if (value == "Ready") paper else coral, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        }
    }

    @Composable
    private fun SpecimenRow(index: Int, fixture: Fixture, card: Color, ink: Color, accent: Color) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(card).clickable { present(fixture.id) }.padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(7.dp).height(82.dp).background(accent))
            BasicText((index + 1).toString().padStart(2, '0'), Modifier.padding(horizontal = 14.dp), TextStyle(color = ink.copy(alpha = .55f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
            Column(Modifier.weight(1f).padding(vertical = 15.dp)) {
                BasicText(fixture.title, style = TextStyle(color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(4.dp))
                BasicText(fixture.note, style = TextStyle(color = ink.copy(alpha = .68f), fontSize = 13.sp))
            }
            BasicText("OPEN", style = TextStyle(color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        }
    }

    private fun present(flowId: String) {
        status = "Presenting $flowId"
        Weir.present(
            context = this,
            flowId = flowId,
            configRoot = galleryRoot,
            userId = flowUserId,
            eventSink = flowEventSink,
            ingest = flowIngest,
            systemBarStyle = WeirSystemBarStyle.forDarkBackground,
        ) { result ->
            runOnUiThread {
                status = when (result) {
                    is WeirFlowResult.Completed -> "Completed $flowId"
                    is WeirFlowResult.Dismissed -> "Dismissed: ${result.reason}"
                    is WeirFlowResult.Failed -> "Failed: ${result.error.message ?: result.error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun installGallery(): File = File(filesDir, "gallery").also { copyAsset("fixtures", it) }

    private fun copyAsset(assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            children.forEach { copyAsset("$assetPath/$it", File(dest, it)) }
        }
    }

    private data class Fixture(val id: String, val title: String, val note: String)
    private val fixtures = listOf(
        Fixture("welcome", "Welcome", "Hero, proof, demo and primary action"),
        Fixture("single-select", "Single select", "Selection affordance and auto-advance"),
        Fixture("multi-select", "Multi select", "Limits, grid layout and confirmation"),
        Fixture("slider", "Slider", "Range, step, default and unit"),
        Fixture("number-input", "Number input", "Stepper, bounds and unit conversion"),
        Fixture("text-input", "Text input", "Suggestions, validation and benefits"),
        Fixture("loader", "Loader", "Timed progress messages and computes"),
        Fixture("social-proof", "Social proof", "Ratings, testimonials and CTA"),
        Fixture("permission-prime", "Permission prime", "Benefits, skip and result variable"),
        Fixture("paywall", "Paywall", "Products, features, legal and restore"),
        Fixture("moment", "Moment", "Editorial reveal, art and citation"),
        Fixture("hold-to-commit", "Hold to commit", "Suggestions, custom entry and seal"),
        Fixture("demo", "Demo", "Conversation playback with sources"),
    )
}
