package studio.aldric.weir.persistence

import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ports the intent of `sdk-ios/Tests/WeirTests/WeirUpdateConfigTests.swift`:
 * the standard-base64 → raw-32-byte key decode, and the fail-closed `null`
 * result for anything that isn't a valid 32-byte key.
 */
class WeirUpdateConfigTest {

    private fun signingResource(rel: String): File =
        File(javaClass.getResource("/signing/$rel")!!.toURI())

    @Test
    fun decodesTheCommittedFixtureKeyToRaw32Bytes() {
        val b64 = signingResource("public-key.raw.b64").readText().trim()
        val config = WeirUpdateConfig(manifestURL = "https://x/manifest", publicKeyRawBase64 = b64)
        val raw = config.publicKeyRaw
        assertEquals(32, raw?.size)
        assertArrayEquals(Base64.getDecoder().decode(b64), raw)
    }

    @Test
    fun standardBase64WithPlusAndSlashDecodes() {
        // The committed fixture key contains '+' and '/', which a url-safe
        // decoder would mangle — this asserts standard base64 is used.
        val b64 = "ZCmlLMjUGy+GNgv+kI1ASfUOhUF8b0wPeGFdRkPiLT0="
        val config = WeirUpdateConfig("https://x/manifest", b64)
        assertEquals(32, config.publicKeyRaw?.size)
    }

    @Test
    fun garbageBase64FailsClosedToNull() {
        val config = WeirUpdateConfig("https://x/manifest", "!!! not base64 !!!")
        assertNull(config.publicKeyRaw)
    }

    @Test
    fun validBase64OfWrongLengthFailsClosedToNull() {
        // 16 bytes base64-encoded — valid base64, wrong key length ⇒ null.
        val sixteen = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val config = WeirUpdateConfig("https://x/manifest", sixteen)
        assertNull(config.publicKeyRaw)
    }
}
