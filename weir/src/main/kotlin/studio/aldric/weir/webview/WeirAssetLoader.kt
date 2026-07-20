package studio.aldric.weir.webview

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * Serves flow-bundle assets into the WebView over an
 * `https://appassets.androidplatform.net/...` origin via
 * [WebViewAssetLoader], instead of a bare `file://` URL.
 *
 * Android analogue of `sdk-ios/Sources/Weir/WebView/LocalAssetSchemeHandler.swift`.
 * Why the https-origin loader rather than `file://`: bundle content runs in a
 * secure context, which the runtime needs (`crypto.randomUUID`, etc. — plan
 * R3). A dedicated [FilePathHandler] resolves requests against the resolved
 * bundle root (embedded assets copied to internal storage, or the promoted
 * `staged/` dir in Phase 2), with the same path-traversal guard the iOS
 * scheme handler applies.
 */
class WeirAssetLoader private constructor(
    val assetLoader: WebViewAssetLoader,
    /** The entry URL to load into the WebView (secure https origin). */
    val entryUrl: String,
) {
    companion object {
        /** The reserved origin `WebViewAssetLoader` serves under — a real
         *  domain Google guarantees is never routable, so it can't collide
         *  with remote content the flow might also load. */
        const val DOMAIN = "appassets.androidplatform.net"

        /**
         * Builds a loader that serves [bundleRoot]'s files at the origin root,
         * entry point `https://appassets.androidplatform.net/index.html`.
         */
        fun create(bundleRoot: File): WeirAssetLoader {
            val loader = WebViewAssetLoader.Builder()
                .setDomain(DOMAIN)
                .addPathHandler("/", FilePathHandler(bundleRoot))
                .build()
            return WeirAssetLoader(loader, "https://$DOMAIN/index.html")
        }

        /**
         * Resolves a request path against [root], rejecting any path that
         * escapes [root] (e.g. via `..`). Pure java.io — extracted so it's
         * unit-testable without a WebView. Returns the resolved regular file,
         * or `null` if it's missing or escapes the root.
         */
        fun resolveBundleFile(root: File, path: String): File? {
            val relative = path.trimStart('/')
            val rootCanonical = root.canonicalFile
            val candidate = File(rootCanonical, relative).canonicalFile
            val within = candidate == rootCanonical ||
                candidate.path.startsWith(rootCanonical.path + File.separator)
            if (!within) return null
            if (!candidate.isFile) return null
            return candidate
        }

        /** Mirrors LocalAssetSchemeHandler.mimeType — the small set the flow
         *  bundle actually uses, plus a binary default. */
        fun mimeType(ext: String): String = when (ext.lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
    }
}

/**
 * [WebViewAssetLoader.PathHandler] serving a single bundle-root directory.
 * Returns `null` for a missing/traversal-rejected path so the loader produces
 * a 404 rather than serving arbitrary filesystem content.
 */
internal class FilePathHandler(private val root: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val file = WeirAssetLoader.resolveBundleFile(root, path) ?: return null
        val mime = WeirAssetLoader.mimeType(file.extension)
        return WebResourceResponse(mime, null, file.inputStream())
    }
}
