package studio.aldric.weir

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.aldric.weir.persistence.RegistryGating
import studio.aldric.weir.registry.ComponentRegistry
import studio.aldric.weir.registry.GeneratedRegistryEntry
import studio.aldric.weir.registry.WeirGeneratedComponentRegistry

/**
 * RC-P0 (gating protocol v2, release-gate re-review): [Weir.gatingQuery] —
 * extracted from [Weir.configure]'s `gatingProvider` closure, which is not
 * independently testable through a real `checkForUpdate` network fetch —
 * must always send the base trio, and the registry pair only when
 * [ComponentRegistry] actually has a generated registry installed. Also
 * pins that `sdkVersion` is really [WeirSdk.WEIR_SDK_VERSION] (the constant
 * [WeirSdkTest] separately pins to the server's stock-type floor), so a
 * drift between "what this test asserts" and "what actually goes over the
 * wire" can't hide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeirGatingQueryTest {

    @Before
    fun setUp() = ComponentRegistry.resetForTesting()

    @After
    fun tearDown() = ComponentRegistry.resetForTesting()

    @Test
    fun sendsBaseTrioAloneWhenNoGeneratedRegistryIsInstalled() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val query = Weir.gatingQuery(context)

        assertEquals(WeirSdk.WEIR_SDK_VERSION, query.sdkVersion)
        assertEquals("android", query.platform)
        assertEquals("no registry installed -> Unregistered", RegistryGating.Unregistered, query.registry)
        // The trio must still be a real, complete query string — the whole
        // point of this fix is that an app with zero custom components
        // still gets its sdkVersion floor enforced.
        val queryString = query.toQueryString()
        assert(queryString.contains("sdkVersion=${WeirSdk.WEIR_SDK_VERSION}")) {
            "expected sdkVersion in $queryString"
        }
        assert(!queryString.contains("registryManifestVersion")) {
            "must not send a partial/absent registry pair: $queryString"
        }
    }

    @Test
    fun includesTheRegistryPairOnlyWhenAGeneratedRegistryIsInstalled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ComponentRegistry.setGeneratedRegistry(
            object : WeirGeneratedComponentRegistry {
                override val registryManifestVersion = 9
                override val registryHash = "the-registry-hash"
                override val entries = listOf(GeneratedRegistryEntry("app.widget", "schema-hash"))
            },
        )

        val query = Weir.gatingQuery(context)

        assertEquals(WeirSdk.WEIR_SDK_VERSION, query.sdkVersion)
        assertEquals("android", query.platform)
        assertEquals(RegistryGating.Registered(9, "the-registry-hash"), query.registry)
        val queryString = query.toQueryString()
        assert(queryString.contains("registryManifestVersion=9")) { "expected registry pair in $queryString" }
        assert(queryString.contains("registryHash=the-registry-hash")) { "expected registry pair in $queryString" }
    }
}
