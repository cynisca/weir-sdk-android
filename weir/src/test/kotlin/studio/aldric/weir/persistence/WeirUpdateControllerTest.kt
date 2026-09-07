package studio.aldric.weir.persistence

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WeirUpdateControllerTest {
    @Test fun gatingQueryAppendsAllFiveFieldsAndPreservesExistingQuery() = runTest {
        val client = RecordingClient()
        val controller = controller(client) {
            ConfigFetchGatingQuery("2.3 beta", "0.1.1", "android", RegistryGating.Registered(7, "a+b/c="))
        }

        controller.checkForUpdate()
        advanceUntilIdle()

        assertEquals(
            "https://example.test/manifest?appId=demo&appVersion=2.3%20beta&sdkVersion=0.1.1&platform=android&registryManifestVersion=7&registryHash=a%2Bb%2Fc%3D",
            client.urls.single(),
        )
    }

    // RC-P0 (gating protocol v2, release-gate re-review): the base trio must
    // still produce a real, complete query string on its own — an app with
    // no generated component registry (registryManifestVersion/registryHash
    // both null) still gets its sdkVersion floor enforced, unlike the prior
    // all-5-or-none contract (see nullGatingLeavesManifestUrlUntouched below
    // for the genuinely-no-provider-at-all case, which is unaffected).
    @Test fun gatingQueryWithNoRegistryPairSendsOnlyTheBaseTrio() = runTest {
        val client = RecordingClient()
        val controller = controller(client) {
            ConfigFetchGatingQuery("2.3 beta", "1.0.0", "android")
        }

        controller.checkForUpdate()
        advanceUntilIdle()

        assertEquals(
            "https://example.test/manifest?appId=demo&appVersion=2.3%20beta&sdkVersion=1.0.0&platform=android",
            client.urls.single(),
        )
    }

    @Test fun nullGatingLeavesManifestUrlUntouched() = runTest {
        val client = RecordingClient()
        val controller = controller(client) { null }

        controller.checkForUpdate()
        advanceUntilIdle()

        assertEquals(listOf("https://example.test/manifest?appId=demo"), client.urls)
    }

    @Test fun gatingProviderIsInvokedFreshForEveryCheck() = runTest {
        val client = RecordingClient()
        var version = 0
        val controller = controller(client) {
            version += 1
            ConfigFetchGatingQuery("1", "0.1.1", "android", RegistryGating.Registered(version, "hash-$version"))
        }

        controller.checkForUpdate()
        advanceUntilIdle()
        controller.checkForUpdate()
        advanceUntilIdle()

        assertEquals(2, version)
        assertEquals(true, client.urls[0].contains("registryManifestVersion=1&registryHash=hash-1"))
        assertEquals(true, client.urls[1].contains("registryManifestVersion=2&registryHash=hash-2"))
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        client: BundleHttpClient,
        provider: () -> ConfigFetchGatingQuery?,
    ) = WeirUpdateController(
        config = WeirUpdateConfig("https://example.test/manifest?appId=demo", "invalid"),
        httpClient = client,
        scope = this,
        gatingProvider = provider,
    )

    private class RecordingClient : BundleHttpClient {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, maxBytes: Long): ByteArray {
            urls += url
            return "not-json".toByteArray()
        }
    }
}
