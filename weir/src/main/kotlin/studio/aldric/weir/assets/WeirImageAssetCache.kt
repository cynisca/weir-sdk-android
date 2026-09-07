package studio.aldric.weir.assets

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.aldric.weir.engine.AssetToken
import studio.aldric.weir.engine.CustomAsset
import studio.aldric.weir.engine.ImageRef
import studio.aldric.weir.persistence.BundleManager
import java.io.File

/**
 * Resolves built-in SDK photos and bundle-delivered custom photos. Every
 * failure is null, never an exception: an absent or corrupt image must not
 * prevent the themed fallback background (and therefore a flow) from drawing.
 */
object WeirImageAssetCache {
    private val builtIn = mutableMapOf<AssetToken, ImageBitmap>()

    /**
     * Keyed by resolved absolute path, not `CustomAsset.id`: two flow versions
     * may both call an image "hero" while pointing at different bytes.
     */
    private val custom = mutableMapOf<String, ImageBitmap>()

    /** Synchronous in-memory lookup for repaint without a fallback flash. */
    fun cached(ref: ImageRef?, customAssets: List<CustomAsset>, assetsRoot: File?): ImageBitmap? = when (ref) {
        null -> null
        is ImageRef.Asset -> builtIn[ref.token]
        is ImageRef.Custom -> customCacheKey(ref.id, customAssets, assetsRoot)?.let(custom::get)
    }

    /** Decodes off Main so callers never need to remember their own IO switch. */
    suspend fun resolve(
        ref: ImageRef?,
        customAssets: List<CustomAsset>,
        assetsRoot: File?,
        context: Context,
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(ref, customAssets, assetsRoot) ?: when (ref) {
            null -> null
            is ImageRef.Asset -> resolveBuiltIn(ref.token, context)
            is ImageRef.Custom -> resolveCustom(ref.id, customAssets, assetsRoot)
        }
    }

    private fun resolveBuiltIn(token: AssetToken, context: Context): ImageBitmap? = try {
        context.assets.open("weir/${assetFilename(token)}.jpg").use { input ->
            BitmapFactory.decodeStream(input)?.asImageBitmap()?.also { builtIn[token] = it }
        }
    } catch (_: Exception) {
        null
    }

    private fun resolveCustom(id: String, customAssets: List<CustomAsset>, assetsRoot: File?): ImageBitmap? = try {
        val key = customCacheKey(id, customAssets, assetsRoot) ?: return null
        BitmapFactory.decodeFile(key)?.asImageBitmap()?.also { custom[key] = it }
    } catch (_: Exception) {
        null
    }

    /** Reuses BundleManager's audited handling of signed-but-untrusted paths. */
    private fun customCacheKey(id: String, customAssets: List<CustomAsset>, assetsRoot: File?): String? {
        val asset = customAssets.firstOrNull { it.id == id } ?: return null
        val root = assetsRoot ?: return null
        return BundleManager.resolveSafeChild(root, asset.path)?.absolutePath
    }

    private fun assetFilename(token: AssetToken): String = when (token) {
        AssetToken.VERSE_HERO_OLIVE -> "verse-hero-olive"
        AssetToken.VERSE_HERO_ARCH -> "verse-hero-arch"
        AssetToken.PATH_REVEAL_ARCHES -> "path-reveal-arches"
        AssetToken.PATH_ANCHOR -> "path-anchor"
        AssetToken.WELCOME_HERO -> "welcome-hero"
    }

    /** Test-only cache reset for repeatable missing/corrupt asset coverage. */
    fun resetForTesting() {
        builtIn.clear()
        custom.clear()
    }
}
