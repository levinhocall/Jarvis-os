package com.jarvis.assistant.ui.forge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Sandboxed WebView displaying the generated website.
 * No JavascriptInterface, external links routed to system browser, mixed content blocked.
 */
class GeneratedSiteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    interface OnSiteRenderedListener {
        fun onSitePainted()
    }

    var renderListener: OnSiteRenderedListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLoadedLength = 0
    private var lastLoadTime = 0L
    private var isPainting = false

    init {
        setupSandboxedSettings()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupSandboxedSettings() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val host = url.host ?: ""
                if (host.equals("localhost", ignoreCase = true) || host.contains("appassets.androidplatform.net")) {
                    return false
                }
                // Open external links in external system browser
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, url).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(browserIntent)
                } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isPainting) {
                    mainHandler.postDelayed({
                        renderListener?.onSitePainted()
                        isPainting = false
                    }, 400)
                }
            }
        }
    }

    /**
     * Updates preview during build. Throttled to at most every 1.5s and >= 4KB growth.
     */
    fun updateStreamingPreview(partialHtml: String, isLowRam: Boolean = false) {
        if (isLowRam) return // Skip intermediate renders on low-RAM devices

        val now = System.currentTimeMillis()
        val growth = partialHtml.length - lastLoadedLength
        if (now - lastLoadTime < 1500 || growth < 4096) {
            return
        }

        lastLoadTime = now
        lastLoadedLength = partialHtml.length
        loadDataWithBaseURL("https://localhost/", partialHtml, "text/html", "UTF-8", null)
    }

    /**
     * Loads the final HTML and waits for paint before triggering reveal callback.
     */
    fun loadFinalSite(html: String) {
        isPainting = true
        loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null)

        // Safety timeout in case onPageFinished takes too long
        mainHandler.postDelayed({
            if (isPainting) {
                isPainting = false
                renderListener?.onSitePainted()
            }
        }, 2500)
    }
}
