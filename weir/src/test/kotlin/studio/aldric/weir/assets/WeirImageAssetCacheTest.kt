package studio.aldric.weir.assets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import studio.aldric.weir.engine.AssetToken
import studio.aldric.weir.engine.CustomAsset
import studio.aldric.weir.engine.ImageRef
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class WeirImageAssetCacheTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = WeirImageAssetCache.resetForTesting()
    @After fun tearDown() = WeirImageAssetCache.resetForTesting()

    @Test fun builtInAsset_resolvesAndDecodes() = runTest {
        val image = WeirImageAssetCache.resolve(ImageRef.Asset(AssetToken.WELCOME_HERO), emptyList(), null, context)

        assertNotNull(image)
        assertNotNull(WeirImageAssetCache.cached(ImageRef.Asset(AssetToken.WELCOME_HERO), emptyList(), null))
    }

    @Test fun unknownCustomAsset_resolvesToNull() = runTest {
        assertNull(WeirImageAssetCache.resolve(ImageRef.Custom("missing-id"), emptyList(), null, context))
    }

    @Test fun escapingCustomAssetPath_resolvesToNull() = runTest {
        val root = Files.createTempDirectory("WeirImageAssetCacheTest-").toFile()
        val image = WeirImageAssetCache.resolve(
            ImageRef.Custom("hero"),
            listOf(CustomAsset("hero", "../../etc/passwd")),
            root,
            context,
        )

        assertNull(image)
    }
}
