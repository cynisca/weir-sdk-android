package studio.aldric.weir.persistence

import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import studio.aldric.weir.bridge.WeirJson
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports the intent of `sdk-ios/Tests/WeirTests/BundleManagerTests.swift` plus
 * THE R1 cross-language parity gate (docs/android-support-plan.md §9 R1):
 * loads the committed `src/test/resources/signing/` fixture — produced by the
 * real `services/api` TS signer — and proves the Kotlin/BouncyCastle verify
 * path accepts EXACTLY what that signer produces, byte-for-byte.
 *
 * Pure-JVM (no Robolectric): [BundleManager] takes an injectable root dir and
 * an injectable [BundleHttpClient], so these run against real temp dirs.
 */
class BundleManagerTest {

    // ---- Fixture helpers ----

    /** Resolves a file under `src/test/resources/signing/` from the classpath. */
    private fun signingResource(rel: String): File =
        File(javaClass.getResource("/signing/$rel")!!.toURI())

    /** The exploded fixture directory (e.g. `good`, `tampered-signature`). */
    private fun fixtureDir(name: String): File =
        signingResource("$name/manifest.json").parentFile!!

    private val fixturePublicKeyRaw: ByteArray by lazy {
        Base64.getDecoder().decode(signingResource("public-key.raw.b64").readText().trim())
    }

    private fun fixturePublicKey(): Ed25519PublicKeyParameters =
        Ed25519PublicKeyParameters(fixturePublicKeyRaw, 0)

    private fun loadManifest(name: String): BundleUpdateManifest =
        WeirJson.decodeFromString(
            BundleUpdateManifest.serializer(),
            File(fixtureDir(name), "manifest.json").readText(),
        )

    /**
     * A [BundleHttpClient] that serves a fixture directory: the manifest at a
     * sentinel URL, and each manifest file's declared `url` mapped to that
     * file's bytes on disk. This drives [BundleManager.checkForUpdate] through
     * its FULL real fetch → verify → per-file-hash → stage path — the strongest
     * parity proof (both the Ed25519 gate and the SHA-256 gate, exactly as a
     * live device would run them).
     */
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

    private fun tempRoot(): File = Files.createTempDirectory("BundleManagerTest-").toFile()

    private fun stagedDir(root: File, bundleId: String): File =
        File(File(root, "staged"), bundleId)

    /** Loads the committed throwaway PKCS8 test-signing key for in-test signing. */
    private fun fixturePrivateKey(): Ed25519PrivateKeyParameters {
        val pkcs8 = Base64.getDecoder().decode(signingResource("test-signing-key.pkcs8.b64").readText().trim())
        return PrivateKeyFactory.createKey(pkcs8) as Ed25519PrivateKeyParameters
    }

    /** Signs an arbitrary [BundleUpdateManifest] (minus `signature`) with the fixture key. */
    private fun sign(manifest: BundleUpdateManifest): BundleUpdateManifest {
        val payload = manifest.signingPayload
        val signer = Ed25519Signer().apply { init(true, fixturePrivateKey()) }
        signer.update(payload, 0, payload.size)
        val sig = Base64.getEncoder().encodeToString(signer.generateSignature())
        return manifest.copy(signature = sig)
    }

    // ---- THE R1 cross-language parity gate ----

    @Test
    fun r1_good_isAcceptedByBothGates() {
        // Signature/spec gate: pure `verify` accepts the TS-signed manifest.
        val manifest = loadManifest("good")
        assertEquals(ManifestVerification.VALID, BundleManager.verify(manifest, fixturePublicKey()))

        // Per-file SHA-256 gate: every declared hash matches the exact bytes.
        for (f in manifest.files) {
            val bytes = File(fixtureDir("good"), f.path).readBytes()
            assertEquals("hash for ${f.path}", f.sha256, BundleManager.sha256Hex(bytes))
        }

        // Full real path: checkForUpdate stages the good bundle end-to-end.
        val root = tempRoot()
        val manager = BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        )
        runBlocking { manager.checkForUpdate("manifest://good") }
        val staged = stagedDir(root, manifest.bundleId)
        assertTrue("good/ must ACCEPT: staged dir present", staged.isDirectory)
        assertTrue(File(staged, "index.html").isFile)
        assertTrue(File(staged, "flows/cob_intake.json").isFile)
        assertTrue(File(staged, BundleManager.STAGED_MANIFEST_FILENAME).isFile)
    }

    @Test
    fun r1_tamperedSignature_isRejectedOnSignatureGate() {
        val manifest = loadManifest("tampered-signature")
        // Ed25519 gate REJECTS (one signature byte flipped, AU… vs AE…).
        assertEquals(ManifestVerification.INVALID_SIGNATURE, BundleManager.verify(manifest, fixturePublicKey()))

        val root = tempRoot()
        val manager = BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("tampered-signature"), "manifest://tsig"),
        )
        runBlocking { manager.checkForUpdate("manifest://tsig") }
        assertFalse(
            "tampered-signature/ must REJECT: nothing staged",
            stagedDir(root, manifest.bundleId).exists(),
        )
    }

    @Test
    fun r1_tamperedFile_isRejectedOnSha256Gate() {
        val manifest = loadManifest("tampered-file")
        // Signature is still VALID (manifest byte-identical to good/)...
        assertEquals(ManifestVerification.VALID, BundleManager.verify(manifest, fixturePublicKey()))
        // ...but index.html was altered on disk, so its SHA-256 no longer matches.
        val declared = manifest.files.first { it.path == "index.html" }.sha256
        val onDisk = BundleManager.sha256Hex(File(fixtureDir("tampered-file"), "index.html").readBytes())
        assertNotEquals(declared, onDisk)

        val root = tempRoot()
        val manager = BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("tampered-file"), "manifest://tfile"),
        )
        runBlocking { manager.checkForUpdate("manifest://tfile") }
        assertFalse(
            "tampered-file/ must REJECT on SHA-256: partial stage discarded",
            stagedDir(root, manifest.bundleId).exists(),
        )
    }

    private fun assertNotEquals(a: Any?, b: Any?) = assertFalse("expected $a != $b", a == b)

    // ---- R2-S1 anti-downgrade ----

    /**
     * After promoting version 2 (the good fixture), a validly-signed but OLDER
     * (version 1) manifest — a replay by a transport-controlling attacker,
     * arriving in a LATER launch — must NOT stage or promote. The last-promoted
     * version is read from `active.json` at init, so this proves the gate
     * survives a process relaunch (the real cross-session replay attack).
     */
    @Test
    fun checkForUpdate_rejectsOlderVersionReplayAcrossRelaunch_antiDowngrade() {
        val root = tempRoot()

        // Launch 1: promote the good fixture (version 2).
        BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        ).apply {
            runBlocking { checkForUpdate("manifest://good") }
            promoteStagedUpdateIfAny()
            assertEquals("2026-07-17-r1fixture", activeBundleURL.name)
        }

        // A validly-signed OLDER manifest (version 1, distinct bundleId) served
        // over the good fixture's own file bytes.
        val v1 = sign(loadManifest("good").copy(version = 1, bundleId = "downgrade-v1"))
        val v1Bytes = WeirJson.encodeToString(BundleUpdateManifest.serializer(), v1).toByteArray()
        val client = object : BundleHttpClient {
            override suspend fun get(url: String, maxBytes: Long): ByteArray =
                if (url == "manifest://downgrade") v1Bytes
                else File(fixtureDir("good"), v1.files.first { it.url == url }.path).readBytes()
        }

        // Launch 2: a fresh BundleManager rehydrates activeVersion=2 from
        // active.json, so the replayed version-1 manifest is rejected.
        val relaunched = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw, httpClient = client)
        runBlocking { relaunched.checkForUpdate("manifest://downgrade") }
        relaunched.promoteStagedUpdateIfAny()

        assertFalse("older manifest must not stage", stagedDir(root, "downgrade-v1").exists())
        assertEquals("active bundle must stay on version 2", "2026-07-17-r1fixture", relaunched.activeBundleURL.name)
    }

    /**
     * R2-S5: staging a newer bundle prunes the earlier (superseded) staged dir,
     * so `staged/` holds at most one directory rather than accumulating one per
     * version between relaunches.
     */
    @Test
    fun checkForUpdate_prunesSupersededStagedDirs() {
        val root = tempRoot()
        val good = loadManifest("good") // version 2, bundleId 2026-07-17-r1fixture
        val v3 = sign(good.copy(version = 3, bundleId = "prune-v3"))
        val v3Bytes = WeirJson.encodeToString(BundleUpdateManifest.serializer(), v3).toByteArray()
        val goodBytes = File(fixtureDir("good"), "manifest.json").readBytes()
        val client = object : BundleHttpClient {
            override suspend fun get(url: String, maxBytes: Long): ByteArray = when (url) {
                "manifest://v2" -> goodBytes
                "manifest://v3" -> v3Bytes
                else -> File(fixtureDir("good"), good.files.first { it.url == url }.path).readBytes()
            }
        }
        val manager = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw, httpClient = client)

        runBlocking { manager.checkForUpdate("manifest://v2") }
        assertTrue(stagedDir(root, good.bundleId).isDirectory)

        runBlocking { manager.checkForUpdate("manifest://v3") }
        assertFalse("superseded stage must be pruned", stagedDir(root, good.bundleId).exists())
        assertTrue("newest stage must remain", stagedDir(root, "prune-v3").isDirectory)
        val stagedChildren = File(root, "staged").listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        assertEquals(listOf("prune-v3"), stagedChildren)
    }

    // ---- R2-S2 download bounds ----

    /**
     * A manifest listing more than [BundleManager.MAX_MANIFEST_FILES] files is
     * rejected BEFORE any file is fetched — the file-count bound trips first, so
     * a huge (even validly-signed) file list can't drive unbounded downloads.
     */
    @Test
    fun checkForUpdate_rejectsManifestWithTooManyFiles_failsClosed() {
        val root = tempRoot()
        val manyFiles = (0..BundleManager.MAX_MANIFEST_FILES).map { i ->
            BundleManifestFile(path = "f$i.txt", url = "manifest://f$i", sha256 = "0".repeat(64))
        }
        val manifest = sign(
            BundleUpdateManifest(
                specVersion = 3, bundleId = "2026-07-18-manyfiles", version = 1,
                files = manyFiles, signature = "",
            ),
        )
        assertEquals(ManifestVerification.VALID, BundleManager.verify(manifest, fixturePublicKey()))
        val manifestBytes = WeirJson.encodeToString(BundleUpdateManifest.serializer(), manifest).toByteArray()

        var fileFetches = 0
        val client = object : BundleHttpClient {
            override suspend fun get(url: String, maxBytes: Long): ByteArray {
                if (url == "manifest://many") return manifestBytes
                fileFetches++
                return ByteArray(0)
            }
        }
        val manager = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw, httpClient = client)
        runBlocking { manager.checkForUpdate("manifest://many") }

        assertFalse("over-count manifest must not stage", stagedDir(root, manifest.bundleId).exists())
        assertEquals("no file should be fetched once the count bound trips", 0, fileFetches)
    }

    // ---- Ported iOS BundleManagerTests intent ----

    @Test
    fun verifyRejectsUnsupportedSpecVersion() {
        // The fixture is specVersion 3; forge a spec version outside {0,3}.
        val good = loadManifest("good")
        val bumped = good.copy(specVersion = 999)
        assertEquals(ManifestVerification.UNSUPPORTED_SPEC_VERSION, BundleManager.verify(bumped, fixturePublicKey()))
    }

    @Test
    fun verifyRejectsWhenNoPublicKey_failClosed() {
        val good = loadManifest("good")
        assertEquals(ManifestVerification.INVALID_SIGNATURE, BundleManager.verify(good, null))
    }

    @Test
    fun verifyRejectsMalformedSignatureEncoding() {
        val good = loadManifest("good")
        val broken = good.copy(signature = "not valid base64 !!!")
        assertEquals(ManifestVerification.MALFORMED_SIGNATURE_ENCODING, BundleManager.verify(broken, fixturePublicKey()))
    }

    @Test
    fun activeBundleURLImmediatelyAvailableAtInit() {
        val manager = BundleManager(rootDirectory = tempRoot(), publicKeyRaw = fixturePublicKeyRaw)
        assertTrue(manager.activeBundleURL.exists())
    }

    @Test
    fun activeBundleResolvesEmbeddedWhenNoPromotedMarker() {
        val embedded = tempRoot()
        File(embedded, "flows").mkdirs()
        File(File(embedded, "flows"), "embedded-fixture.json").writeText("{}")
        val manager = BundleManager(
            rootDirectory = tempRoot(),
            publicKeyRaw = fixturePublicKeyRaw,
            embeddedBundleRoot = embedded,
        )
        assertEquals(embedded, manager.activeBundleURL)
        assertTrue(manager.activeBundleContainsFlow("embedded-fixture"))
        assertFalse(manager.activeBundleContainsFlow("no-such-flow"))
    }

    @Test
    fun activeBundleContainsFlowRecognizesPublishedManifestShape() {
        // The flat weir_deploy shape: manifest.json with a top-level flowId.
        val embedded = tempRoot()
        File(embedded, "manifest.json").writeText("""{"flowId":"cob_intake","specVersion":3}""")
        File(embedded, "index.html").writeText("<html></html>")
        val manager = BundleManager(
            rootDirectory = tempRoot(),
            publicKeyRaw = fixturePublicKeyRaw,
            embeddedBundleRoot = embedded,
        )
        assertTrue(manager.activeBundleContainsFlow("cob_intake"))
        assertFalse(manager.activeBundleContainsFlow("some_other_flow"))
    }

    @Test
    fun promoteWithNoStagedUpdateIsNoOp() {
        val root = tempRoot()
        val manager = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw)
        val before = manager.activeBundleURL
        manager.promoteStagedUpdateIfAny()
        assertEquals(before, manager.activeBundleURL)
    }

    @Test
    fun checkForUpdateStagesButDoesNotPromote() {
        val root = tempRoot()
        val manager = BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        )
        val activeBefore = manager.activeBundleURL
        runBlocking { manager.checkForUpdate("manifest://good") }
        // Staged, but active hasn't changed until promotion.
        assertEquals(activeBefore, manager.activeBundleURL)

        manager.promoteStagedUpdateIfAny()
        val bundleId = loadManifest("good").bundleId
        assertEquals(File(File(root, "promoted"), bundleId), manager.activeBundleURL)
        assertTrue(manager.activeBundleContainsFlow("cob_intake"))
        // active.json persisted.
        assertTrue(File(root, "active.json").isFile)
    }

    @Test
    fun checkForUpdateWithNoKeyNeverStages_failClosed() {
        val root = tempRoot()
        val manager = BundleManager(
            rootDirectory = root,
            publicKeyRaw = null, // fail closed
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        )
        runBlocking { manager.checkForUpdate("manifest://good") }
        assertFalse(stagedDir(root, loadManifest("good").bundleId).exists())
        manager.promoteStagedUpdateIfAny()
        // Still on embedded/placeholder — no remote bundle ever activated.
        assertFalse(manager.activeBundleContainsFlow("cob_intake"))
    }

    // ---- Rehydration / relaunch (THE OTA regression) ----

    @Test
    fun stagedBundleSurvivesRelaunchAndPromotesAtNextBoundary() {
        val root = tempRoot()
        // Launch N: stage the good bundle, do NOT promote.
        BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        ).also { runBlocking { it.checkForUpdate("manifest://good") } }

        val bundleId = loadManifest("good").bundleId
        assertTrue(stagedDir(root, bundleId).isDirectory)

        // Launch N+1: a fresh BundleManager on the SAME root (no in-memory
        // carryover) must rehydrate + re-verify the staged dir, then promote.
        val relaunched = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw)
        relaunched.promoteStagedUpdateIfAny()
        assertEquals(File(File(root, "promoted"), bundleId), relaunched.activeBundleURL)
        assertTrue(relaunched.activeBundleContainsFlow("cob_intake"))
    }

    @Test
    fun rehydrationReVerifiesAndDiscardsTamperedStage() {
        val root = tempRoot()
        BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        ).also { runBlocking { it.checkForUpdate("manifest://good") } }

        val bundleId = loadManifest("good").bundleId
        // Tamper a staged file on disk AFTER it was validly staged.
        File(stagedDir(root, bundleId), "index.html").writeText("tampered-on-disk")

        // Relaunch must RE-VERIFY (not trust the sidecar) and discard the stage.
        val relaunched = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw)
        relaunched.promoteStagedUpdateIfAny()
        assertFalse("tampered stage must be discarded on rehydrate", stagedDir(root, bundleId).exists())
        assertFalse(relaunched.activeBundleContainsFlow("cob_intake"))
    }

    @Test
    fun promotedBundleSurvivesRelaunchViaActiveJson() {
        val root = tempRoot()
        BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        ).also {
            runBlocking { it.checkForUpdate("manifest://good") }
            it.promoteStagedUpdateIfAny()
        }
        val bundleId = loadManifest("good").bundleId

        // Relaunch: active.json must restore the promoted bundle as active.
        val relaunched = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw)
        assertEquals(File(File(root, "promoted"), bundleId), relaunched.activeBundleURL)
        assertTrue(relaunched.activeBundleContainsFlow("cob_intake"))
    }

    @Test
    fun rehydrationCannotJudgeLeavesStageForKeyedInstance() {
        val root = tempRoot()
        // Stage validly with a keyed instance.
        BundleManager(
            rootDirectory = root,
            publicKeyRaw = fixturePublicKeyRaw,
            httpClient = FixtureHttpClient(fixtureDir("good"), "manifest://good"),
        ).also { runBlocking { it.checkForUpdate("manifest://good") } }
        val bundleId = loadManifest("good").bundleId

        // A KEYLESS instance (like Weir.fallbackBundleManager) constructed on
        // the same shared root must NOT destroy the stage it cannot judge.
        BundleManager(rootDirectory = root, publicKeyRaw = null)
        assertTrue("keyless instance must leave a stage it cannot judge", stagedDir(root, bundleId).isDirectory)

        // A correctly-keyed relaunch still promotes it.
        val keyed = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw)
        keyed.promoteStagedUpdateIfAny()
        assertEquals(File(File(root, "promoted"), bundleId), keyed.activeBundleURL)
    }

    // ---- Path traversal (android review P1) ----

    /**
     * A VALIDLY-SIGNED manifest whose file path is `../escape.txt` must be
     * rejected wholesale — nothing written outside `staged/`, and no partial
     * stage left behind — even though the signature itself checks out. Signing
     * proves authenticity of content, not that the declared path is safe.
     */
    @Test
    fun checkForUpdateRejectsPathTraversingFileEntry_failsClosed() {
        val root = tempRoot()
        val maliciousBytes = "pwned".toByteArray()
        val maliciousFile = BundleManifestFile(
            path = "../escape.txt",
            url = "manifest://evil-file",
            sha256 = BundleManager.sha256Hex(maliciousBytes),
        )
        val unsigned = BundleUpdateManifest(
            specVersion = 3,
            bundleId = "2026-07-18-traversal",
            version = 1,
            files = listOf(maliciousFile),
            signature = "",
        )
        val manifest = sign(unsigned)
        // Sanity: the signature really is valid — proves this test isn't
        // accidentally exercising the (separate) signature-rejection path.
        assertEquals(ManifestVerification.VALID, BundleManager.verify(manifest, fixturePublicKey()))

        val manifestBytes = WeirJson.encodeToString(BundleUpdateManifest.serializer(), manifest).toByteArray()
        val httpClient = object : BundleHttpClient {
            override suspend fun get(url: String, maxBytes: Long): ByteArray = when (url) {
                "manifest://traversal" -> manifestBytes
                "manifest://evil-file" -> maliciousBytes
                else -> throw java.io.IOException("no fixture bytes for $url")
            }
        }
        val manager = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw, httpClient = httpClient)
        runBlocking { manager.checkForUpdate("manifest://traversal") }

        // Nothing staged for this bundleId...
        assertFalse("malicious stage must be discarded, not left behind", stagedDir(root, manifest.bundleId).exists())
        // ...and nothing written outside staged/ at all (the actual escape target).
        assertFalse("path traversal must not escape staged/", File(root, "escape.txt").exists())
        assertFalse("path traversal must not escape the root", File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun checkForUpdateRejectsPathTraversingBundleId_failsClosed() {
        val root = tempRoot()
        val unsigned = BundleUpdateManifest(
            specVersion = 3,
            bundleId = "../escape-bundle",
            version = 1,
            files = emptyList(),
            signature = "",
        )
        val manifest = sign(unsigned)
        assertEquals(ManifestVerification.VALID, BundleManager.verify(manifest, fixturePublicKey()))

        val manifestBytes = WeirJson.encodeToString(BundleUpdateManifest.serializer(), manifest).toByteArray()
        val httpClient = object : BundleHttpClient {
            override suspend fun get(url: String, maxBytes: Long): ByteArray = when (url) {
                "manifest://traversal-id" -> manifestBytes
                else -> throw java.io.IOException("no fixture bytes for $url")
            }
        }
        val manager = BundleManager(rootDirectory = root, publicKeyRaw = fixturePublicKeyRaw, httpClient = httpClient)
        runBlocking { manager.checkForUpdate("manifest://traversal-id") }

        assertFalse(File(File(root, "staged"), "../escape-bundle").exists())
        assertFalse(File(root.parentFile, "escape-bundle").exists())
    }

    @Test
    fun resolveSafeChildRejectsTraversalAbsoluteAndEmptySegments() {
        val parent = tempRoot()
        assertNull(BundleManager.resolveSafeChild(parent, "../escape.txt"))
        assertNull(BundleManager.resolveSafeChild(parent, "a/../../escape.txt"))
        assertNull(BundleManager.resolveSafeChild(parent, "/etc/passwd"))
        assertNull(BundleManager.resolveSafeChild(parent, ""))
        assertNotNull(BundleManager.resolveSafeChild(parent, "flows/a.json"))
        assertNotNull(BundleManager.resolveSafeChild(parent, "index.html"))
    }

    @Test
    fun isSafePathSegmentRejectsSeparatorsAndDotSegments() {
        assertFalse(BundleManager.isSafePathSegment(".."))
        assertFalse(BundleManager.isSafePathSegment("."))
        assertFalse(BundleManager.isSafePathSegment(""))
        assertFalse(BundleManager.isSafePathSegment("../x"))
        assertFalse(BundleManager.isSafePathSegment("a/b"))
        assertTrue(BundleManager.isSafePathSegment("2026-07-17-r1fixture"))
    }
}
