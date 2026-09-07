package studio.aldric.weir.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import studio.aldric.weir.bridge.EventParams
import studio.aldric.weir.bridge.HealthEvent
import studio.aldric.weir.bridge.InMemoryEventSink
import studio.aldric.weir.bridge.WeirJson
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 structured diagnostics — proves [BundleManager]'s real
 * fetch/verify/stage/promote call sites emit the SDK-owned
 * `health_manifest_*`/`health_remote_*` events (docs/claude-two-app-completion-plan.md
 * Phase 3) with the right shape, at the right moment, and — the hard security
 * requirement — that `health_manifest_rejected` and every other emitted event
 * never carries the manifest's signature, the public key, or raw file bytes.
 *
 * Reuses the R1 fixture set at `src/test/resources/signing/` (same as
 * [BundleManagerTest]) so these tests exercise the exact real Ed25519/SHA-256
 * gates, not a stub.
 */
class BundleManagerHealthDiagnosticsTest {

    private fun signingResource(rel: String): File =
        File(javaClass.getResource("/signing/$rel")!!.toURI())

    private fun fixtureDir(name: String): File =
        signingResource("$name/manifest.json").parentFile!!

    private val fixturePublicKeyRaw: ByteArray by lazy {
        Base64.getDecoder().decode(signingResource("public-key.raw.b64").readText().trim())
    }

    private fun fixturePublicKey(): Ed25519PublicKeyParameters =
        Ed25519PublicKeyParameters(fixturePublicKeyRaw, 0)

    private fun fixturePrivateKey(): Ed25519PrivateKeyParameters {
        val pkcs8 = Base64.getDecoder().decode(signingResource("test-signing-key.pkcs8.b64").readText().trim())
        return PrivateKeyFactory.createKey(pkcs8) as Ed25519PrivateKeyParameters
    }

    private fun sign(manifest: BundleUpdateManifest): BundleUpdateManifest {
        val payload = manifest.signingPayload
        val signer = Ed25519Signer().apply { init(true, fixturePrivateKey()) }
        signer.update(payload, 0, payload.size)
        val sig = Base64.getEncoder().encodeToString(signer.generateSignature())
        return manifest.copy(signature = sig)
    }

    private fun loadManifest(name: String): BundleUpdateManifest =
        WeirJson.decodeFromString(
            BundleUpdateManifest.serializer(),
            File(fixtureDir(name), "manifest.json").readText(),
        )

    private fun tempRoot(): File = Files.createTempDirectory("BundleManagerHealthDiagnosticsTest-").toFile()

    private class FixtureHttpClient(dir: File, val manifestUrl: String) : BundleHttpClient {
        private val map: Map<String, ByteArray>
        init {
            val manifestBytes = File(dir, "manifest.json").readBytes()
            val manifest = WeirJson.decodeFromString(
                BundleUpdateManifest.serializer(), manifestBytes.toString(Charsets.UTF_8),
            )
            val m = mutableMapOf(manifestUrl to manifestBytes)
            for (f in manifest.files) m[f.url] = File(dir, f.path).readBytes()
            map = m
        }
        override suspend fun get(url: String, maxBytes: Long): ByteArray =
            map[url] ?: throw java.io.IOException("no fixture bytes for $url")
    }

    private fun type(event: EventParams): String = event.payload.jsonObject.getValue("type").jsonPrimitive.content

    // ---- health_manifest_fetch_started ----

    @Test
    fun checkForUpdate_emitsManifestFetchStarted_withOriginAndPathOnly_noQueryOrCredentials() {
        val sink = InMemoryEventSink()
        val manager = BundleManager(
            rootDirectory = tempRoot(),
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "https://user:secret@cdn.example.com/manifest.json?token=abc123"),
            eventSink = sink,
        )
        runBlocking {
            manager.checkForUpdate("https://user:secret@cdn.example.com/manifest.json?token=abc123")
        }

        val started = sink.events.first { type(it) == "health_manifest_fetch_started" }
        assertEquals(HealthEvent.UPDATE_SESSION_FLOW_ID, started.flowId)
        val url = started.payload.jsonObject.getValue("url").jsonPrimitive.content
        assertEquals("https://cdn.example.com/manifest.json", url)
        assertFalse("must never carry the query string", url.contains("token"))
        assertFalse("must never carry credentials", url.contains("secret"))
    }

    // ---- health_manifest_fetch_failed ----

    @Test
    fun checkForUpdate_emitsManifestFetchFailed_withRealHttpStatus() {
        val sink = InMemoryEventSink()
        val client = object : BundleHttpClient {
            override suspend fun get(url: String, maxBytes: Long): ByteArray =
                throw BundleHttpStatusException(503, "HTTP 503 fetching $url?token=abc123")
        }
        val manager = BundleManager(rootDirectory = tempRoot(), publicKeyRaw = fixturePublicKeyRaw, httpClient = client, eventSink = sink)
        runBlocking { manager.checkForUpdate("https://cdn.example.com/manifest.json") }

        val failed = sink.events.first { type(it) == "health_manifest_fetch_failed" }
        assertEquals(HealthEvent.UPDATE_SESSION_FLOW_ID, failed.flowId)
        assertEquals(503, failed.payload.jsonObject.getValue("httpStatus").jsonPrimitive.content.toInt())
        val reason = failed.payload.jsonObject.getValue("reason").jsonPrimitive.content
        assertFalse("reason must be a short safe code, not the raw exception message", reason.contains("token"))
        assertEquals("http_error", reason)
    }

    @Test
    fun checkForUpdate_emitsManifestFetchFailed_withZeroStatus_whenNoResponseAtAll() {
        val sink = InMemoryEventSink()
        val client = object : BundleHttpClient {
            override suspend fun get(url: String, maxBytes: Long): ByteArray =
                throw java.net.SocketTimeoutException("connect timed out")
        }
        val manager = BundleManager(rootDirectory = tempRoot(), publicKeyRaw = fixturePublicKeyRaw, httpClient = client, eventSink = sink)
        runBlocking { manager.checkForUpdate("https://cdn.example.com/manifest.json") }

        val failed = sink.events.first { type(it) == "health_manifest_fetch_failed" }
        assertEquals(0, failed.payload.jsonObject.getValue("httpStatus").jsonPrimitive.content.toInt())
        assertEquals("timeout", failed.payload.jsonObject.getValue("reason").jsonPrimitive.content)
    }

    // ---- health_manifest_rejected ----

    @Test
    fun checkForUpdate_emitsManifestRejected_signatureInvalid_andNeverLeaksSignatureOrKey() {
        val sink = InMemoryEventSink()
        val manifest = loadManifest("tampered-signature")
        val manager = BundleManager(
            rootDirectory = tempRoot(),
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("tampered-signature"), "manifest://tsig"),
            eventSink = sink,
        )
        runBlocking { manager.checkForUpdate("manifest://tsig") }

        val rejected = sink.events.first { type(it) == "health_manifest_rejected" }
        assertEquals(HealthEvent.UPDATE_SESSION_FLOW_ID, rejected.flowId)
        assertEquals("signature_invalid", rejected.payload.jsonObject.getValue("reason").jsonPrimitive.content)

        assertNoSecretMaterialLeaked(sink, manifest.signature, fixturePublicKeyRaw)
    }

    @Test
    fun checkForUpdate_emitsManifestRejected_hashMismatch() {
        val sink = InMemoryEventSink()
        val manager = BundleManager(
            rootDirectory = tempRoot(),
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("tampered-file"), "manifest://tfile"),
            eventSink = sink,
        )
        runBlocking { manager.checkForUpdate("manifest://tfile") }

        val rejected = sink.events.first { type(it) == "health_manifest_rejected" }
        assertEquals("hash_mismatch", rejected.payload.jsonObject.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun checkForUpdate_emitsManifestRejected_antiDowngrade() {
        val root = tempRoot()
        BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        ).apply {
            runBlocking { checkForUpdate("manifest://good") }
            promoteStagedUpdateIfAny()
        }

        val sink = InMemoryEventSink()
        val v1 = sign(loadManifest("good").copy(version = 1, configId = "downgrade-v1"))
        val v1Bytes = WeirJson.encodeToString(BundleUpdateManifest.serializer(), v1).toByteArray()
        val client = object : BundleHttpClient {
            override suspend fun get(url: String, maxBytes: Long): ByteArray =
                if (url == "manifest://downgrade") v1Bytes
                else File(fixtureDir("good"), v1.files.first { it.url == url }.path).readBytes()
        }
        val relaunched = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw, httpClient = client, eventSink = sink)
        runBlocking { relaunched.checkForUpdate("manifest://downgrade") }

        val rejected = sink.events.first { type(it) == "health_manifest_rejected" }
        assertEquals("anti_downgrade", rejected.payload.jsonObject.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun checkForUpdate_emitsManifestRejected_schemaInvalid_forUnsupportedSpecVersion() {
        val sink = InMemoryEventSink()
        val bumped = loadManifest("good").copy(specVersion = 999)
        val bumpedBytes = WeirJson.encodeToString(BundleUpdateManifest.serializer(), bumped).toByteArray()
        val client = object : BundleHttpClient {
            override suspend fun get(url: String, maxBytes: Long): ByteArray = bumpedBytes
        }
        val manager = BundleManager(rootDirectory = tempRoot(), publicKeyRaw = fixturePublicKeyRaw, httpClient = client, eventSink = sink)
        runBlocking { manager.checkForUpdate("manifest://bumped") }

        val rejected = sink.events.first { type(it) == "health_manifest_rejected" }
        assertEquals("schema_invalid", rejected.payload.jsonObject.getValue("reason").jsonPrimitive.content)
    }

    // ---- health_remote_staged / health_remote_promoted ----

    @Test
    fun checkForUpdate_emitsRemoteStaged_withVersionAndBundleId() {
        val sink = InMemoryEventSink()
        val manifest = loadManifest("good")
        val manager = BundleManager(
            rootDirectory = tempRoot(),
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
            eventSink = sink,
        )
        runBlocking { manager.checkForUpdate("manifest://good") }

        val staged = sink.events.first { type(it) == "health_remote_staged" }
        assertEquals(HealthEvent.UPDATE_SESSION_FLOW_ID, staged.flowId)
        assertEquals(manifest.version, staged.payload.jsonObject.getValue("version").jsonPrimitive.content.toInt())
        // HealthEvent's JSON key stays "bundleId" (device-local telemetry, not
        // the signed wire contract — see BundleManager.kt's checkForUpdate).
        assertEquals(manifest.configId, staged.payload.jsonObject.getValue("bundleId").jsonPrimitive.content)
    }

    @Test
    fun promoteStagedUpdateIfAny_emitsRemotePromoted_withVersionAndBundleId() {
        val sink = InMemoryEventSink()
        val manifest = loadManifest("good")
        val manager = BundleManager(
            rootDirectory = tempRoot(),
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
            eventSink = sink,
        )
        runBlocking { manager.checkForUpdate("manifest://good") }
        manager.promoteStagedUpdateIfAny()

        val promoted = sink.events.first { type(it) == "health_remote_promoted" }
        assertEquals(HealthEvent.UPDATE_SESSION_FLOW_ID, promoted.flowId)
        assertEquals(manifest.version, promoted.payload.jsonObject.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(manifest.configId, promoted.payload.jsonObject.getValue("bundleId").jsonPrimitive.content)
    }

    @Test
    fun promoteStagedUpdateIfAny_withNothingStaged_emitsNoRemotePromoted() {
        val sink = InMemoryEventSink()
        val manager = BundleManager(rootDirectory = tempRoot(), publicKeyRaw = fixturePublicKeyRaw, eventSink = sink)
        manager.promoteStagedUpdateIfAny()
        assertTrue(sink.events.none { type(it) == "health_remote_promoted" })
    }

    // ---- Backward compatibility: null eventSink is silent, not a crash ----

    @Test
    fun checkForUpdate_withNoEventSink_doesNotThrow() {
        val manager = BundleManager(
            rootDirectory = tempRoot(),
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        )
        runBlocking { manager.checkForUpdate("manifest://good") }
        manager.promoteStagedUpdateIfAny()
        // No assertion beyond "didn't throw" — eventSink defaults to null.
    }

    /**
     * Serializes every event this run emitted and asserts none of them, as a
     * whole, contain the manifest's own signature or the raw public key bytes
     * — the hard constraint behind `health_manifest_rejected`'s [reason]
     * always being a closed-set code rather than anything derived from the
     * manifest content itself.
     */
    private fun assertNoSecretMaterialLeaked(sink: InMemoryEventSink, signature: String, publicKeyRaw: ByteArray) {
        val publicKeyB64 = Base64.getEncoder().encodeToString(publicKeyRaw)
        for (event in sink.events) {
            val json = event.payload.toString()
            assertFalse("event payload must never contain the manifest signature: $json", json.contains(signature))
            assertFalse("event payload must never contain the raw public key: $json", json.contains(publicKeyB64))
        }
    }
}
