package com.jarvis.assistant.ui.forge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.jarvis.assistant.forge.ForgeStage
import com.jarvis.assistant.forge.ForgeUiState
import org.json.JSONObject

/**
 * Sandboxed Three.js r160 WebGL scene host for Website Forge.
 * Uses WebViewAssetLoader, disables external navigation, and pushes state diffs every 60ms.
 */
class ForgeWebGlScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    interface Callback {
        fun onReady()
        fun onRevealFinished()
        fun onWebGLFailed()
    }

    var callback: Callback? = null

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isBridgeReady = false
    private var lastPushedStage = 0
    private var lastPushedProgress = -1f
    private var lastSectionCount = 0
    private var lastLogCount = 0
    private var lastPushTime = 0L

    init {
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        setBackgroundColor(Color.parseColor("#070709"))

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
            displayZoomControls = false
        }

        addJavascriptInterface(object {
            @JavascriptInterface
            fun onReady() {
                mainHandler.post {
                    isBridgeReady = true
                    callback?.onReady()
                }
            }

            @JavascriptInterface
            fun onRevealFinished() {
                mainHandler.post {
                    callback?.onRevealFinished()
                }
            }

            @JavascriptInterface
            fun onWebGLFailed() {
                mainHandler.post {
                    callback?.onWebGLFailed()
                }
            }
        }, "ForgeBridge")

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // Strictly block navigation inside the 3D scene
                return true
            }
        }

        loadUrl("https://appassets.androidplatform.net/assets/forge/forge.html")
    }

    /**
     * Initializes the Three.js scene with design tokens from XML.
     */
    fun initTheme(
        bgPrimary: String = "#070709",
        bgSecondary: String = "#121218",
        accentPurple: String = "#8B5CF6",
        accentGold: String = "#F59E0B",
        accentCyan: String = "#06B6D4",
        textPrimary: String = "#F8FAFC",
        textSecondary: String = "#94A3B8",
        reduceMotion: Boolean = false,
        isLowRam: Boolean = false
    ) {
        val themeJson = JSONObject().apply {
            put("bgPrimary", bgPrimary)
            put("bgSecondary", bgSecondary)
            put("accentPurple", accentPurple)
            put("accentGold", accentGold)
            put("accentCyan", accentCyan)
            put("textPrimary", textPrimary)
            put("textSecondary", textSecondary)
            put("reduceMotion", reduceMotion)
            put("isLowRam", isLowRam)
        }.toString()

        evaluateJavascript("if (window.Forge && window.Forge.init) window.Forge.init($themeJson);", null)
    }

    /**
     * Pushes state diffs to the JS 3D scene throttled to at most every 60ms.
     */
    fun pushState(state: ForgeUiState) {
        if (!isBridgeReady) return

        val now = System.currentTimeMillis()
        if (now - lastPushTime < 60 && state.stage.index == lastPushedStage && (state.progress - lastPushedProgress) < 0.05f) {
            return
        }
        lastPushTime = now

        // Stage diff
        if (state.stage.index != lastPushedStage) {
            lastPushedStage = state.stage.index
            evaluateJavascript("if (window.Forge) window.Forge.setStage(${state.stage.index});", null)
        }

        // Progress diff
        if (Math.abs(state.progress - lastPushedProgress) >= 0.01f) {
            lastPushedProgress = state.progress
            evaluateJavascript("if (window.Forge) window.Forge.setProgress(${state.progress});", null)
        }

        // Sections diff
        if (state.sections.size > lastSectionCount) {
            for (i in lastSectionCount until state.sections.size) {
                val escaped = JSONObject.quote(state.sections[i])
                evaluateJavascript("if (window.Forge) window.Forge.addSection($escaped);", null)
            }
            lastSectionCount = state.sections.size
        }

        // Logs diff
        if (state.log.size > lastLogCount) {
            for (i in lastLogCount until state.log.size) {
                val escaped = JSONObject.quote(state.log[i])
                evaluateJavascript("if (window.Forge) window.Forge.log($escaped);", null)
            }
            lastLogCount = state.log.size
        }
    }

    fun triggerReveal() {
        evaluateJavascript("if (window.Forge) window.Forge.reveal();", null)
    }

    fun triggerFail() {
        evaluateJavascript("if (window.Forge) window.Forge.fail();", null)
    }

    fun disposeScene() {
        evaluateJavascript("if (window.Forge) window.Forge.dispose();", null)
        stopLoading()
        destroy()
    }
}
