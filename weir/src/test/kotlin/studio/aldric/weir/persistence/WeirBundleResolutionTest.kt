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
        assertEquals(2, resolved.version) // the fixture manifest's own `"version": 2`
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
        assertEquals(null, resolved.version) // bundled tier is never versioned
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
        assertEquals(null, resolved.version) // bundled tier is never versioned
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
        assertEquals(null, resolved.version) // bundled tier is never versioned
    }

    /**
     * The mid-flow publish invariant (sibling of iOS's
     * `testMidFlowPublishDoesNotSwapActiveBundleUntilNextResolve`): a revision
     * published while a flow is live must NOT change the bundle that live flow
     * renders. `checkForUpdate` only stages; `promoteStagedUpdateIfAny` runs
     * solely inside `resolve` (the next flow-present boundary), so a v2 fetched
     * after v1 is already live stays staged until a second `resolve` promotes
     * it. `WeirBundleResolution.resolve`'s own doc and `WeirUpdateController`'s
     * "promotion deliberately does NOT happen here" both state this; this test
     * exercises it end to end against real signed bytes.
     *
     * Manifests are signed in-test with the committed throwaway key under
     * `resources/signing/` (`test-signing-key.pkcs8.b64`, the same one
     * `generate.ts` regenerates the static fixtures with) via BouncyCastle, so
     * two monotonic versions can be staged without hand-committing a second
     * fixture directory.
     */
    @Test
    fun midFlowPublishDoesNotSwapActiveBundleUntilNextResolve() = runBlocking {
        val configJson = """{"id":"cob_intake","specVersion":4}""".toByteArray()
        val manifestUrl = "manifest://midflow"
        val v1 = signedManifest(version = 1, configId = "midflow-v1", configJson = configJson)
        val v2 = signedManifest(version = 2, configId = "midflow-v2", configJson = configJson)

        val client = VersionedHttpClient(
            manifestUrl = manifestUrl,
            currentManifestBytes = manifestBytes(v1),
            files = mutableMapOf(
                v1.files.single().url to configJson,
                v2.files.single().url to configJson,
            ),
        )
        val controller = WeirUpdateController(
            config = WeirUpdateConfig(manifestUrl, Base64.getEncoder().encodeToString(fixtureKeyRaw)),
            rootDirectory = tempDir(),
            httpClient = client,
        )
        val host = tempDir()
        val fallback = BundleManager(rootDirectory = tempDir())

        // Flow start: the background check stages v1; resolve promotes it.
        controller.bundleManager.checkForUpdate(manifestUrl)
        val r1 = WeirBundleResolution.resolve("cob_intake", host, controller, fallback)
        assertEquals(WeirBundleSource.REMOTE, r1.source)
        assertEquals(1, r1.version)
        val activeDuringFlow = controller.bundleManager.activeBundleURL

        // A new revision publishes mid-flow: bump the server to v2 and let the
        // background check stage it. A live flow never re-resolves, so the
        // active bundle it renders must NOT change — staging never swaps active.
        client.currentManifestBytes = manifestBytes(v2)
        controller.bundleManager.checkForUpdate(manifestUrl)
        assertEquals(
            "a mid-flow stage must not swap the active bundle a live flow is rendering",
            activeDuringFlow,
            controller.bundleManager.activeBundleURL,
        )
        assertEquals("active is still v1 — v2 is staged, not promoted", 1, controller.bundleManager.activeBundleVersion)

        // Next flow start: resolve promotes the staged v2. Only now does the
        // published revision reach a user.
        val r2 = WeirBundleResolution.resolve("cob_intake", host, controller, fallback)
        assertEquals(WeirBundleSource.REMOTE, r2.source)
        assertEquals(2, r2.version)
        assertEquals(2, controller.bundleManager.activeBundleVersion)
    }

    // -- In-test Ed25519 signing (committed throwaway key under resources/signing/)

    private val privateKeyParams: org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters by lazy {
        val pkcs8 = Base64.getDecoder().decode(signingResource("test-signing-key.pkcs8.b64").readText().trim())
        org.bouncycastle.crypto.util.PrivateKeyFactory.createKey(pkcs8) as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
    }

    private fun signedManifest(version: Int, configId: String, configJson: ByteArray): BundleUpdateManifest {
        val fileUrl = "https://cdn.example.com/midflow/$configId/config.json"
        val unsigned = BundleUpdateManifest(
            specVersion = 4,
            configId = configId,
            version = version,
            registryManifestVersion = 1,
            files = listOf(BundleManifestFile(path = "config.json", url = fileUrl, sha256 = BundleManager.sha256Hex(configJson))),
            signature = "",
        )
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer().apply {
            init(true, privateKeyParams)
            update(unsigned.signingPayload, 0, unsigned.signingPayload.size)
        }
        return unsigned.copy(signature = Base64.getEncoder().encodeToString(signer.generateSignature()))
    }

    private fun manifestBytes(manifest: BundleUpdateManifest): ByteArray =
        studio.aldric.weir.bridge.WeirJson.encodeToString(BundleUpdateManifest.serializer(), manifest).toByteArray()

    private class VersionedHttpClient(
        private val manifestUrl: String,
        var currentManifestBytes: ByteArray,
        private val files: MutableMap<String, ByteArray>,
    ) : BundleHttpClient {
        override suspend fun get(url: String, maxBytes: Long): ByteArray =
            if (url == manifestUrl) currentManifestBytes else files.getValue(url)
    }
}
