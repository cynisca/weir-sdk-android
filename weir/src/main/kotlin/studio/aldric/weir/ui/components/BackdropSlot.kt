package studio.aldric.weir.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import studio.aldric.weir.assets.WeirImageAssetCache
import studio.aldric.weir.engine.FlowEngine
import studio.aldric.weir.engine.ImageRef
import studio.aldric.weir.engine.HeadlineMotion

internal fun shouldRevealImmediately(reduceMotion: Boolean, motion: HeadlineMotion): Boolean =
    reduceMotion || motion == HeadlineMotion.none

/** Full-bleed resolved image, or the caller's themed fallback while unavailable. */
@Composable
internal fun BackdropSlot(
    ref: ImageRef?,
    engine: FlowEngine,
    fallback: @Composable () -> Unit,
    onResolutionChanged: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    var resolved by remember(ref) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(ref, engine.assetsRoot) {
        if (ref == null) {
            resolved = null
            onResolutionChanged?.invoke(false)
            return@LaunchedEffect
        }
        val warm = WeirImageAssetCache.cached(ref, engine.config.customAssets, engine.assetsRoot)
        if (warm != null) {
            resolved = warm
            onResolutionChanged?.invoke(true)
            return@LaunchedEffect
        }
        val image = WeirImageAssetCache.resolve(ref, engine.config.customAssets, engine.assetsRoot, context)
        resolved = image
        onResolutionChanged?.invoke(image != null)
    }
    if (resolved != null) {
        Image(
            bitmap = resolved!!,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        fallback()
    }
}
