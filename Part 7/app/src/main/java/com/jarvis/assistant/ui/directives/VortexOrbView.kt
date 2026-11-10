package com.jarvis.assistant.ui.directives

import android.content.Context
import android.graphics.Color
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

/** Same procedural renderer and service events as the home deck; no second microphone. */
class VortexOrbView(context:Context):FrameLayout(context),DefaultLifecycleObserver {
    private val web=WebView(context)
    private var ready=false
    private var visualState="idle"
    private var lastPush=0L
    var level=0f
        set(value) {field=if(value.isFinite()) value.coerceIn(0f,1f) else 0f;pushAudio()}
    init {
        contentDescription="JARVIS voice orb";web.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.apply {javaScriptEnabled=true;allowFileAccess=false;allowContentAccess=false;domStorageEnabled=false}
        val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context)).build()
        web.webViewClient=object:WebViewClientCompat() {
            override fun shouldInterceptRequest(view:WebView,request:android.webkit.WebResourceRequest):android.webkit.WebResourceResponse? =
                loader.shouldInterceptRequest(request.url) ?: android.webkit.WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(ByteArray(0)))
            override fun shouldOverrideUrlLoading(view:WebView,request:android.webkit.WebResourceRequest)=true
            override fun onPageFinished(view:WebView,url:String) {
                ready=true
                view.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setTheme('dark');",null)
                applyMotionPolicy();updateState(visualState);pushAudio(force=true)
            }
        }
        addView(web,LayoutParams(-1,-1))
        web.loadUrl("https://appassets.androidplatform.net/assets/orb.html")
    }
    fun updateState(value:String) {
        visualState=when(value.lowercase(java.util.Locale.ROOT)) {
            "listening","connected","interrupted"->"listening";"thinking","connecting","executing","recovering"->"thinking";"speaking"->"speaking";"error","failed"->"error";else->"idle"
        }
        if(ready) web.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setState('$visualState');",null)
    }
    private fun pushAudio(force:Boolean=false) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(!ready || (!force && now-lastPush<33))return
        lastPush=now
        web.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setExternalAudio($level);",null)
    }
    private fun applyMotionPolicy() {
        if(ready) web.evaluateJavascript("window.JarvisOrb && window.JarvisOrb.setReducedMotion(${com.jarvis.assistant.util.VisualMotionSettings.isReduced(context)});",null)
    }
    override fun onResume(owner:LifecycleOwner) {web.onResume();web.resumeTimers();applyMotionPolicy()}
    override fun onPause(owner:LifecycleOwner) {web.onPause();web.pauseTimers()}
    override fun onDestroy(owner:LifecycleOwner) {ready=false;web.stopLoading();removeView(web);web.destroy()}
}
