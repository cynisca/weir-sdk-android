package studio.aldric.weir.persistence

import android.content.Context
import java.io.File

/**
 * The SDK's baked-in embedded fallback flow — the Android analogue of iOS's
 * SPM `Bundle.module` resource
 * (`sdk-ios/Sources/WeirCore/Resources/flows.bundle`). Shipped as a raw asset
 * under `assets/weir-embedded/` and materialized to disk on demand, because
 * [BundleManager] resolves flow configs off real files (not the Android
 * [android.content.res.AssetManager]) and a host that supplies no
 * [BundleManager.embeddedBundleRoot] must still get a renderable flow, not an
 * empty placeholder.
 *
 * On-disk layout (matches the third shape [BundleManager.activeBundleContainsFlow]
 * recognizes, and what [studio.aldric.weir.WeirFlowConfigResolution] reads):
 * ```
 * <targetDir>/
 *   flows/
 *     embedded-fixture.json
 * ```
 * Greenfield-clean: ships only the v4 flow JSON, NOT the webview-era
 * `index.html` that still sits beside it in iOS's `flows.bundle`
 * (MISSION.md working agreement 2).
 */
object WeirEmbeddedFixture {

    /** Asset root under `src/main/assets/`. */
    internal const val ASSET_ROOT = "weir-embedded"

    /** The flow id the embedded fixture declares (`"id"` in the JSON). */
    const val FLOW_ID = "embedded-fixture"

    /**
     * Copies the embedded-fixture asset tree into [targetDir], producing the
     * `flows/embedded-fixture.json` layout [BundleManager] resolves as an
     * [BundleManager.embeddedBundleRoot]. Idempotent: rewrites the bytes on
     * every call so a newer SDK build's shipped asset always wins over a stale
     * disk copy from a prior install. Any I/O failure is swallowed (`false`
     * returned); the caller keeps the empty-placeholder path rather than
     * crashing — asset access must never throw out of SDK initialization.
     */
    fun materialize(context: Context, targetDir: File): Boolean = try {
        copyAssetTree(context.applicationContext, ASSET_ROOT, targetDir)
        File(targetDir, "flows/$FLOW_ID.json").isFile
    } catch (_: Exception) {
        false
    }

    /**
     * Recursively copies [assetPath] from the [context]'s [android.content.res.AssetManager]
     * into [dest]. [android.content.res.AssetManager.list] returns only the
     * immediate child names of a directory, so a node is a file when it has no
     * children and a directory otherwise.
     */
    private fun copyAssetTree(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetTree(context, "$assetPath/$child", File(dest, child))
        }
    }
}
