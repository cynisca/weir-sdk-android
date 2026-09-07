package studio.aldric.weir.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * RFC-010 §8.1's cross-language signing-parity guarantee: [BundleManager.verify]
 * (the Kotlin/BouncyCastle Ed25519 verifier, driven by
 * [BundleUpdateManifest.signingPayload]) must agree with every other
 * language's verifier on every case of the shared fixture.
 *
 * The fixture (`packages/evals/fixtures/v4/signing.json`, 7 cases — 2 valid,
 * 5 tampered/corrupted) is generated from the TS reference implementation
 * (`packages/spec/src/config-signing.ts`); regenerate it there, not by hand,
 * if the payload format ever changes. Sibling to iOS's
 * `ConfigSigningParityTests.swift` (`sdk-ios/Tests/WeirCoreTests/`), which
 * consumes the exact same committed file — both SDKs prove parity against
 * ONE fixture, not two independently generated ones that could drift.
 */
class ConfigSigningParityTest {

    @Serializable
    private data class FixtureFile(
        val path: String,
        val url: String,
        val sha256: String,
    )

    @Serializable
    private data class FixtureCase(
        val name: String,
        val configId: String,
        val specVersion: Int,
        val version: Int,
        val registryManifestVersion: Int,
        val files: List<FixtureFile>,
        val signature: String,
        val expectedValid: Boolean,
    )

    @Serializable
    private data class Fixture(
        val publicKeyRawBase64: String,
        val cases: List<FixtureCase>,
    )

    private val fixtureJson = Json { ignoreUnknownKeys = true }

    /**
     * Gradle's JVM unit-test working directory for this module is
     * `sdk-android/weir/` (verified empirically: `System.getProperty("user.dir")`
     * during `:weir:testDebugUnitTest`) — walk up two levels to the repo root,
     * mirroring the Swift test's walk up from `#filePath`.
     */
    private fun fixtureFile(): File {
        val moduleDir = File(System.getProperty("user.dir")!!)
        val repoRoot = moduleDir.parentFile!!.parentFile!! // weir/ -> sdk-android/ -> repo root
        return File(repoRoot, "packages/evals/fixtures/v4/signing.json")
    }

    @Test
    fun kotlinVerifierAgreesWithReferenceForEveryFixtureCase() {
        val file = fixtureFile()
        org.junit.Assume.assumeTrue(
            "signing.json fixture missing at ${file.path} — regenerate via the packages/spec RFC-010 §8.1 fixture generator.",
            file.isFile,
        )

        val fixture = fixtureJson.decodeFromString(Fixture.serializer(), file.readText())
        assertEquals("fixture should have exactly the committed case count", 7, fixture.cases.size)

        val publicKeyRaw = Base64.getDecoder().decode(fixture.publicKeyRawBase64)
        val publicKey = Ed25519PublicKeyParameters(publicKeyRaw, 0)

        val mismatches = mutableListOf<String>()
        for (testCase in fixture.cases) {
            val manifest = BundleUpdateManifest(
                specVersion = testCase.specVersion,
                configId = testCase.configId,
                version = testCase.version,
                registryManifestVersion = testCase.registryManifestVersion,
                files = testCase.files.map { BundleManifestFile(path = it.path, url = it.url, sha256 = it.sha256) },
                signature = testCase.signature,
            )

            val result = BundleManager.verify(manifest, publicKey)
            val isValid = result == ManifestVerification.VALID
            if (isValid != testCase.expectedValid) {
                mismatches.add("${testCase.name}: expected valid=${testCase.expectedValid}, got $result (valid=$isValid)")
            }
        }

        assertTrue(
            "${mismatches.size} of ${fixture.cases.size} cases disagreed with the fixture's expectedValid:\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
    }
}
