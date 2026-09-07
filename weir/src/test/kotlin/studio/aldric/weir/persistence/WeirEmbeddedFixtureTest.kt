package studio.aldric.weir.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.Weir
import studio.aldric.weir.WeirFlowConfigResolution
import studio.aldric.weir.bridge.WeirJson
import studio.aldric.weir.engine.FlowConfig
import studio.aldric.weir.engine.WelcomeScreenConfig
import java.io.File
import java.nio.file.Files

/**
 * Closes the iOS/Android parity gap for the embedded baseline fallback
 * (`docs/research/android-parity-punchlist.md` item #4): a host that supplies
 * no remote config and no host bundle must still render a real flow. iOS ships
 * its fallback via SPM `Bundle.module`
 * (`sdk-ios/Sources/WeirCore/Resources/flows.bundle`); Android ships it as a
 * raw asset under `assets/weir-embedded/`, materialized to disk by
 * [WeirEmbeddedFixture] and resolved by [Weir]'s fallback manager.
 *
 * Robolectric serves the real `src/main/assets` tree, so these tests read the
 * exact bytes a device build ships — same pattern as `WeirImageAssetCacheTest`.
 */
@RunWith(RobolectricTestRunner::class)
class WeirEmbeddedFixtureTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun materialize_copiesExactFixtureContentToDisk() {
        val target = Files.createTempDirectory("WeirEmbeddedFixtureTest-").toFile()

        assertTrue(WeirEmbeddedFixture.materialize(context, target))

        val flowFile = File(target, "flows/${WeirEmbeddedFixture.FLOW_ID}.json")
        assertTrue("flows/embedded-fixture.json must be materialized", flowFile.isFile)

        // Byte-exact: the on-disk fixture matches the iOS mirror verbatim.
        val expected = """
            {
              "specVersion": 4,
              "id": "embedded-fixture",
              "name": "Embedded Fixture",
              "screens": [
                {
                  "id": "w",
                  "type": "welcome",
                  "title": "Embedded baseline",
                  "subtitle": "Rendered from the SDK-embedded fallback, not a remote or host-supplied config.",
                  "cta": { "label": "Continue" }
                }
              ]
            }
        """.trimIndent()
        val parsed = WeirJson.parseToJsonElement(flowFile.readText())
        assertEquals(WeirJson.parseToJsonElement(expected), parsed)
    }

    @Test fun materialize_isIdempotentAcrossCalls() {
        val target = Files.createTempDirectory("WeirEmbeddedFixtureIdempotent-").toFile()
        assertTrue(WeirEmbeddedFixture.materialize(context, target))
        val flowFile = File(target, "flows/${WeirEmbeddedFixture.FLOW_ID}.json")
        val firstBytes = flowFile.readBytes()
        // Re-materialize must not corrupt or drop the file.
        assertTrue(WeirEmbeddedFixture.materialize(context, target))
        assertEquals(firstBytes.toList(), flowFile.readBytes().toList())
    }

    @Test fun noHostNoRemote_resolvesRealEmbeddedFixtureConfig() {
        // Seed the SDK app context (as Weir.present/Weir.configure do in
        // production) so the fallback manager can reach AssetManager.
        Weir.configureEmbeddedFixtureForTest(context)
        val manager = Weir.fallbackBundleManager()

        // No host bundle, no remote controller: the last resolution tier must
        // surface the real embedded fixture, not the empty placeholder.
        val result = WeirFlowConfigResolution.resolve(
            flowId = WeirEmbeddedFixture.FLOW_ID,
            hostConfigRoot = null,
            updateController = null,
            fallbackBundleManager = manager,
        )

        assertTrue("resolution must succeed for the embedded fixture", result.isSuccess)
        val (config, root) = result.getOrThrow()
        assertEmbeddedFixtureContent(config)
        assertTrue(
            "resolution root must carry the materialized fixture file",
            File(root, "flows/${WeirEmbeddedFixture.FLOW_ID}.json").isFile,
        )
        assertTrue(manager.activeBundleContainsFlow(WeirEmbeddedFixture.FLOW_ID))
    }

    @Test fun noHostNoRemote_bundledResolutionReportsEmbeddedSource() {
        Weir.configureEmbeddedFixtureForTest(context)
        val manager = Weir.fallbackBundleManager()

        val resolved = WeirBundleResolution.resolve(
            flowId = WeirEmbeddedFixture.FLOW_ID,
            hostBundleRoot = null,
            updateController = null,
            fallbackBundleManager = manager,
        )

        // The embedded/placeholder tier always reports BUNDLED (never REMOTE);
        // what changed is that the root is now a real flow directory.
        assertEquals(WeirBundleSource.BUNDLED, resolved.source)
        assertTrue(File(resolved.root, "flows/${WeirEmbeddedFixture.FLOW_ID}.json").isFile)
    }

    private fun assertEmbeddedFixtureContent(config: FlowConfig) {
        assertEquals(WeirEmbeddedFixture.FLOW_ID, config.id)
        assertEquals("Embedded Fixture", config.name)
        assertEquals(1, config.screens.size)
        val screen = config.screens.first()
        assertEquals("w", screen.base.id)
        val welcome = screen as WelcomeScreenConfig
        assertEquals("welcome", screen.typeName)
        assertEquals("Embedded baseline", welcome.title)
        assertEquals(
            "Rendered from the SDK-embedded fallback, not a remote or host-supplied config.",
            welcome.subtitle,
        )
        assertEquals("Continue", welcome.cta.label)
    }
}
