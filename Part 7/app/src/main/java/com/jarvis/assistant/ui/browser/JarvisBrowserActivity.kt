package com.jarvis.assistant.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.audio.VoiceSynthesisRouter
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.ActivityJarvisBrowserBinding
import org.json.JSONTokener

/** Explicit user browsing. Page text is display/speech data and never a privileged tool instruction. */
class JarvisBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityJarvisBrowserBinding
    private var readGeneration: Long? = null
    private var navigation = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        VoiceSynthesisRouter.init(applicationContext)
        binding = ActivityJarvisBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.browser.settings.apply {
            javaScriptEnabled = true // Modern pages; there is deliberately no JavascriptInterface.
            domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.browser, false)
        binding.browser.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
        binding.browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "https") return false
                if (request.isForMainFrame && request.url.scheme in setOf("http", "mailto", "tel")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                navigation++; stopReading(); binding.pageStatus.text = getString(com.jarvis.assistant.R.string.browser_loading)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                binding.address.setText(url.orEmpty())
                binding.pageStatus.text = getString(com.jarvis.assistant.R.string.browser_ready)
            }
        }
        binding.go.setOnClickListener { openAddress() }
        binding.address.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { openAddress(); true } else false
        }
        binding.closeBrowser.setOnClickListener { finish() }
        binding.browserBack.setOnClickListener { if(binding.browser.canGoBack()) binding.browser.goBack() else finish() }
        binding.stopReading.setOnClickListener { stopReading() }
        binding.readPage.setOnClickListener {
            if (JarvisConversationService.isSessionOn.value) {
                binding.pageStatus.text = getString(com.jarvis.assistant.R.string.browser_end_voice_first)
                return@setOnClickListener
            }
            val epoch = navigation
            // Fixed script, explicit button only. Form fields, hidden content and scripts are excluded.
            binding.browser.evaluateJavascript("""(function(){const b=document.body.cloneNode(true);
                b.querySelectorAll('script,style,input,textarea,select,[aria-hidden="true"],[hidden]').forEach(n=>n.remove());
                return (b.innerText||b.textContent||'').slice(0,3000);})()""") { encoded ->
                if (epoch != navigation || isFinishing || JarvisConversationService.isSessionOn.value) return@evaluateJavascript
                val text = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull().orEmpty().trim()
                if (text.isBlank()) return@evaluateJavascript
                if (!VoiceSynthesisRouter.isLocalReady) {
                    binding.pageStatus.text = getString(com.jarvis.assistant.R.string.browser_no_offline_voice)
                    return@evaluateJavascript
                }
                if (VoiceSynthesisRouter.speakPrivate(text)) readGeneration = VoiceSynthesisRouter.playback.value.generation
                else binding.pageStatus.text = getString(com.jarvis.assistant.R.string.browser_no_offline_voice)
            }
        }
        binding.address.setText(com.jarvis.assistant.R.string.browser_start_url)
        if(savedInstanceState == null) openAddress() else binding.browser.restoreState(savedInstanceState)
    }
    private fun openAddress() {
        val text=binding.address.text.toString().trim()
        val uri=(if("://" in text) text else "https://$text").toUri()
        if(uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
            binding.pageStatus.text=getString(com.jarvis.assistant.R.string.browser_https_only); return
        }
        binding.browser.loadUrl(uri.toString())
    }
    private fun stopReading() {
        if(readGeneration == VoiceSynthesisRouter.playback.value.generation) VoiceSynthesisRouter.stop()
        readGeneration=null
    }
    override fun onSaveInstanceState(outState: Bundle) { binding.browser.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onPause() { navigation++;stopReading();binding.browser.onPause();super.onPause() }
    override fun onResume() { super.onResume();if(::binding.isInitialized) binding.browser.onResume() }
    override fun onDestroy() {
        stopReading();binding.browser.stopLoading()
        (binding.browser.parent as? android.view.ViewGroup)?.removeView(binding.browser)
        binding.browser.destroy();super.onDestroy()
    }
}
