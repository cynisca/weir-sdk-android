package studio.aldric.weir.persistence

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ports the intent of `sdk-ios/Tests/WeirTests/WeirBundleResolutionTests.swift`:
 * the three-tier order promoted-remote-if-contains-flow → host-supplied →
 * embedded, exercised against real on-disk fixtures.
 */
class WeirBundleResolutionTest {

    private fun tempDir(): File = Files.createTempDirectory("ResolutionTest-").toFile()

    private fun signingResource(rel: String): File =
        File(javaClass.getResource("/signing/$rel")!!.toURI())

    private fun goodFixtureDir(): File = signingResource("good/manifest.json").parentFile!!

    private val fixtureKeyRaw: ByteArray by lazy {
        Base64.getDecoder().decode(signingResource("public-key.raw.b64").readText().trim())
    }

    private class FixtureHttpClient(dir: File, val manifestUrl: String) : BundleHttpClient {
        private val map: Map<String, ByteArray>
        init {
            val manifestBytes = File(dir, "manifest.json").readBytes()
            val manifest = studio.aldric.weir.bridge.WeirJson.decodeFromString(
                BundleUpdateManifest.serializer(), manifestBytes.toString(Charsets.UTF_8),
            )
            val m = mutableMapOf(manifestUrl to manifestBytes)
            for (f in manifest.files) m[f.url] = File(dir, f.path).readBytes()
            map = m
        }
        override suspend fun get(url: String, maxBytes: Long): ByteArray = map.getValue(url)
    }

    /** Builds a controller with a staged-and-promotable good bundle. */
    private fun controllerWithPromotableGood(root: File): WeirUpdateController {
        val controller = WeirUpdateController(
            config = WeirUpdateConfig("manifest://good", Base64.getEncoder().encodeToString(fixtureKeyRaw)),
            rootDirectory = root,
            httpClient = FixtureHttpClient(goodFixtureDir(), "manifest://good"),
        )
        runBlocking { controller.bundleManager.checkForUpdate("manifest://good") }
        return controller
    }

    @Test
    fun promotedRemoteWins_whenItContainsTheFlow() {
        val root = tempDir()
        val controller = controllerWithPromotableGood(root)
        val host = tempDir()
        val fallback = BundleManager(rootDirectory = tempDir())

        val resolved = WeirBundleResolution.resolve(
            flowId = "cob_intake",
            hostBundleRoot = host,
            updateController = controller,
            fallbackBundleManager = fallback,
        )
        assertEquals(WeirBundleSource.REMOTE, resolved.source)
        // Promotion happened at the resolve boundary: active is the promoted dir.
        assertEquals(controller.bundleManager.activeBundleURL, resolved.root)
    }

    @Test
    fun hostSuppliedWins_whenPromotedBundleLacksTheFlow() {
        val root = tempDir()
        val controller = controllerWithPromotableGood(root)
        val host = tempDir()
        val fallback = BundleManager(rootDirectory = tempDir())

        // The promoted good bundle ships cob_intake, NOT this flow, so it must
        // fall through to the host-supplied root.
        val resolved = WeirBundleResolution.resolve(
            flowId = "some_other_flow",
            hostBundleRoot = host,
            updateController = controller,
            fallbackBundleManager = fallback,
        )
        assertEquals(WeirBundleSource.BUNDLED, resolved.source)
        assertEquals(host, resolved.root)
    }

    @Test
    fun hostSuppliedWins_whenNoUpdateControllerConfigured() {
        val host = tempDir()
        val fallback = BundleManager(rootDirectory = tempDir())
        val resolved = WeirBundleResolution.resolve(
            flowId = "cob_intake",
            hostBundleRoot = host,
            updateController = null,
            fallbackBundleManager = fallback,
        )
        assertEquals(WeirBundleSource.BUNDLED, resolved.source)
        assertEquals(host, resolved.root)
    }

    @Test
    fun embeddedFallbackWins_whenNoHostAndNoRemote() {
        val fallback = BundleManager(rootDirectory = tempDir())
        val resolved = WeirBundleResolution.resolve(
            flowId = "cob_intake",
            hostBundleRoot = null,
            updateController = null,
            fallbackBundleManager = fallback,
        )
        assertEquals(WeirBundleSource.BUNDLED, resolved.source)
        assertEquals(fallback.activeBundleURL, resolved.root)
    }
}
