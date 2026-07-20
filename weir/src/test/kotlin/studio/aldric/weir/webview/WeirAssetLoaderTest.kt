package studio.aldric.weir.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM tests for the bundle-root path resolution and MIME inference —
 * the Android analogue of `LocalAssetSchemeHandlerTests.swift`. Guards the
 * traversal check so the loader can never serve arbitrary filesystem content.
 */
class WeirAssetLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun resolvesFileWithinRoot() {
        val root = tmp.newFolder("bundle")
        val index = java.io.File(root, "index.html").apply { writeText("<html></html>") }
        val resolved = WeirAssetLoader.resolveBundleFile(root, "/index.html")
        assertEquals(index.canonicalFile, resolved)
    }

    @Test
    fun resolvesNestedFile() {
        val root = tmp.newFolder("bundle")
        val assets = java.io.File(root, "assets").apply { mkdirs() }
        val js = java.io.File(assets, "app.js").apply { writeText("//") }
        assertEquals(js.canonicalFile, WeirAssetLoader.resolveBundleFile(root, "/assets/app.js"))
    }

    @Test
    fun rejectsTraversalOutsideRoot() {
        val root = tmp.newFolder("bundle")
        java.io.File(tmp.root, "secret.txt").writeText("secret")
        assertNull(WeirAssetLoader.resolveBundleFile(root, "/../secret.txt"))
    }

    @Test
    fun missingFileResolvesNull() {
        val root = tmp.newFolder("bundle")
        assertNull(WeirAssetLoader.resolveBundleFile(root, "/nope.html"))
    }

    @Test
    fun mimeTypesForBundleAssets() {
        assertEquals("text/html", WeirAssetLoader.mimeType("html"))
        assertEquals("application/javascript", WeirAssetLoader.mimeType("js"))
        assertEquals("text/css", WeirAssetLoader.mimeType("css"))
        assertEquals("application/json", WeirAssetLoader.mimeType("json"))
        assertEquals("image/svg+xml", WeirAssetLoader.mimeType("svg"))
        assertEquals("font/woff2", WeirAssetLoader.mimeType("woff2"))
        assertEquals("application/octet-stream", WeirAssetLoader.mimeType("bin"))
    }
}
