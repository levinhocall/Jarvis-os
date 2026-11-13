package com.jarvis.assistant.ui.orb

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.jarvis.assistant.data.model.VoicePresentationSnapshot
import com.jarvis.assistant.ui.directives.PremiumOrbView
import com.jarvis.assistant.util.VisualMotionSettings
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** One local-only host per screen. No audio, service, navigation or accessibility bridge. */
@SuppressLint("SetJavaScriptEnabled")
class OrbWebHost(private val container: FrameLayout, private val web: WebView) {
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var destroyed = false
    private var failed = false
    private var visible = false
    private var scheduled = false
    private var observingPolicy = false
    private val policyObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { configure(); fallback?.invalidate() }
    }
    private val powerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) { configure(); fallback?.invalidate() }
    }
    private fun observePolicy(enabled: Boolean) {
        if (observingPolicy == enabled) return
        observingPolicy = enabled
        if (enabled) {
            androidx.core.content.ContextCompat.registerReceiver(web.context, powerReceiver,
                android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            web.context.contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), false, policyObserver)
        } else {
            runCatching { web.context.unregisterReceiver(powerReceiver) }
            web.context.contentResolver.unregisterContentObserver(policyObserver)
        }
    }
    private var loadedMode = ""
    private var theme = "dark"
    private var customBackground: String? = null
    private var snapshot = VoicePresentationSnapshot()
    private var fallback: PremiumOrbView? = null
    val isPlasma get() = loadedMode == "fluid_plasma"
    private val push = Runnable { scheduled = false; sendLatest() }
    private val timeout = Runnable { fail() }
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(web.context)).build()

    init {
        web.setBackgroundColor(Color.TRANSPARENT)
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun onFailure() { handler.post { fail() } }
        }, "OrbRendererBridge")
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                loader.shouldInterceptRequest(request.url) ?: WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: androidx.webkit.WebResourceErrorCompat) {
                if (request.isForMainFrame) fail()
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (destroyed || failed || !url.startsWith(ASSET_URL)) return
                view.evaluateJavascript("!!(window.JarvisOrb && !window.JarvisOrb.inspect().failed)") { result ->
                    if (destroyed || failed) return@evaluateJavascript
                    if (result != "true") { fail(); return@evaluateJavascript }
                    ready = true; handler.removeCallbacks(timeout)
                    configure(); setVisible(visible); sendLatest()
                }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail()
                destroyWeb()
                return true
            }
        }
        reloadIfChanged()
    }

    fun reloadIfChanged() {
        if (destroyed || failed) return
        val mode = OrbAppearance.rendererMode()
        if (loadedMode == mode) { configure(); return }
        loadedMode = mode; ready = false
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 8_000)
        web.loadUrl("$ASSET_URL?embedded=1&mode=$mode")
    }

    private fun configure() {
        fallback?.configure(OrbAppearance.configuration(container.context))
        if (!ready || failed || destroyed) return
        val config = OrbAppearance.configuration(web.context)
        web.evaluateJavascript("JarvisOrb.configure($config);JarvisOrb.setReducedMotion(${VisualMotionSettings.isReduced(web.context)});", null)
        setTheme(theme, customBackground)
    }

    fun setTheme(value: String, background: String? = null) {
        theme = value.takeIf { it in setOf("light", "dark", "red", "midnight") } ?: "dark"
        customBackground = background?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
        if (ready && !failed && !destroyed && !isPlasma) {
            val script = customBackground?.let { "JarvisOrb.setBackground(${JSONObject.quote(it)});" }
                ?: "JarvisOrb.setTheme(${JSONObject.quote(theme)});"
            web.evaluateJavascript(script, null)
        }
    }

    fun submit(facts: VoicePresentationSnapshot) {
        val invalidatesOutput = facts.sessionId != snapshot.sessionId || facts.interruptionGeneration != snapshot.interruptionGeneration
        snapshot = facts
        if (visible) fallback?.submit(facts)
        if (!visible || destroyed || failed) return
        if (invalidatesOutput) { handler.removeCallbacks(push); scheduled = false; sendLatest(); return }
        if (scheduled) return
        scheduled = true
        handler.postDelayed(push, 33)
    }

    private fun sendLatest() {
        if (!visible || !ready || destroyed || failed) return
        val p = OrbPresentationMapper.map(snapshot, SystemClock.elapsedRealtime())
        if (isPlasma) {
            val json = JSONObject().put("state", p.state.name).put("mic", p.mic).put("output", p.output)
                .put("sessionId", p.sessionId).put("generation", p.interruptionGeneration)
            web.evaluateJavascript("JarvisOrb.setPresentation($json);", null)
        } else {
            val state = when (p.state) {
                PlasmaVisualState.LISTENING, PlasmaVisualState.USER_SPEAKING, PlasmaVisualState.INTERRUPTED -> "listening"
                PlasmaVisualState.PROCESSING -> "thinking"
                PlasmaVisualState.AI_SPEAKING -> "speaking"
                PlasmaVisualState.ERROR -> "error"
                else -> "idle"
            }
            web.evaluateJavascript("JarvisOrb.setState('$state');JarvisOrb.setExternalAudio(${maxOf(p.mic, p.output)},0,0);", null)
        }
    }

    fun setVisible(value: Boolean) {
        if (destroyed) return
        visible = value
        observePolicy(value)
        fallback?.let {
            it.renderingActive = value; it.visibility = if (value) View.VISIBLE else View.INVISIBLE
            if (value) it.submit(snapshot)
        }
        if (!value) { handler.removeCallbacks(push); scheduled = false }
        if (destroyed || failed) return
        web.visibility = if (value) View.VISIBLE else View.INVISIBLE
        if (value) web.onResume()
        if (ready) web.evaluateJavascript("JarvisOrb.setVisible($value);", null)
        if (!value) web.onPause() else { reloadIfChanged(); sendLatest() }
    }

    private fun fail() {
        if (destroyed || failed) return
        failed = true; ready = false
        handler.removeCallbacks(timeout); handler.removeCallbacks(push); scheduled = false
        web.visibility = View.INVISIBLE
        // Initialization/context failure affects visuals only. Controls and voice owner survive.
        fallback = PremiumOrbView(container.context).also {
            it.plasmaMode = isPlasma; it.renderingActive = visible; it.configure(OrbAppearance.configuration(container.context)); it.submit(snapshot)
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            it.setOnClickListener { container.performClick() }
            it.setOnLongClickListener { container.performLongClick() }
            container.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        runCatching { web.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.dispose();", null); web.onPause() }
    }

    private var webDestroyed = false
    private fun destroyWeb() {
        if (webDestroyed) return
        webDestroyed = true
        container.removeView(web)
        runCatching { web.removeJavascriptInterface("OrbRendererBridge"); web.stopLoading() }
        web.destroy()
    }

    fun dispose() {
        if (destroyed) return
        observePolicy(false)
        destroyed = true; ready = false
        handler.removeCallbacksAndMessages(null)
        fallback?.let { it.renderingActive = false; container.removeView(it) }; fallback = null
        if (!webDestroyed) {
            runCatching { web.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.dispose();", null) }
            destroyWeb()
        }
    }
    companion object { private const val ASSET_URL = "https://appassets.androidplatform.net/assets/orb.html" }
}
