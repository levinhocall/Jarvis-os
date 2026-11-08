package com.jarvis.assistant.ui.orb

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Shared hardening for the WebViews that render bundled orb/HUD pages (audit finding F2).
 *
 * Local pages are served through [WebViewAssetLoader] on a fixed https origin instead of
 * `file://`, so file access (including file-to-file and universal access) stays disabled.
 * Navigation and sub-resource requests outside `/assets/` on that origin are blocked, which keeps
 * the optional JavaScript bridge reachable only from bundled content.
 */
object OrbWebViewSecurity {
    const val ASSET_HOST = "appassets.androidplatform.net"
    const val ORB_URL = "https://$ASSET_HOST/assets/orb.html?embedded=1"
    const val HUD_URL = "https://$ASSET_HOST/assets/hud/index.html"

    fun newAssetLoader(context: Context): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

    /** True only for bundled assets on the asset-loader origin. */
    fun isLocalAssetUrl(scheme: String?, host: String?, path: String?): Boolean =
        scheme == "https" && host == ASSET_HOST && path?.startsWith("/assets/") == true

    /** Serves bundled assets; anything else is answered locally with an empty 403 (never the network). */
    fun intercept(loader: WebViewAssetLoader, request: WebResourceRequest): WebResourceResponse {
        val uri = request.url
        if (!isLocalAssetUrl(uri.scheme, uri.host, uri.path)) return blocked(403)
        return loader.shouldInterceptRequest(uri) ?: blocked(404)
    }

    private fun blocked(status: Int) = WebResourceResponse(
        "text/plain", "utf-8", status, if (status == 404) "Not Found" else "Blocked",
        emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun applyLocalContentSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true // required by the orb renderer
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setGeolocationEnabled(false)
    }
}
