package com.jarvis.assistant.ui.aura

import android.os.Bundle
import android.content.Intent
import android.widget.SeekBar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.directives.DirectiveActivity

class AvatarStudioActivity : DirectiveActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val prefs = JarvisApp.instance.preferences
        heading("Floating Avatar Studio")
        label("19 shapes × 6 accessories = 114 combinations. Drag to move. Double-tap stops the active task; a tap still interrupts speech.")
        options(AuraOptions.shapes, prefs.taskOverlayAvatar) { prefs.taskOverlayAvatar = it }
        options(AuraOptions.styles, AuraOptions.avatarStyle) { AuraOptions.avatarStyle = it }
        val size = label("Size: ${AuraOptions.avatarSize} dp")
        page.addView(SeekBar(this).apply {
            max = 96; progress = AuraOptions.avatarSize - 48
            contentDescription = "Floating avatar size"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { if (user) { AuraOptions.avatarSize = value + 48; size.text = "Size: ${value + 48} dp" } }
            })
        })
        options(listOf("always", "working"), prefs.taskOverlayMode) { prefs.taskOverlayMode = it }
        options(listOf("left", "right", "top", "bottom"), prefs.taskOverlayEdge) { prefs.taskOverlayEdge = it; prefs.taskOverlayFreePosition = false }
        action("Overlay permission & enable switch") { startActivity(Intent(this, com.jarvis.assistant.ui.settings.OverlayAvatarActivity::class.java)) }
        label("Visibility follows the running voice service. Android requires your display-over-other-apps permission. Preview the selected character below.")
        val preview = android.webkit.WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
            val loader = androidx.webkit.WebViewAssetLoader.Builder().addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this@AvatarStudioActivity)).build()
            webViewClient = object : androidx.webkit.WebViewClientCompat() {
                override fun shouldInterceptRequest(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) = loader.shouldInterceptRequest(request.url)
            }
        }
        page.addView(preview, android.widget.LinearLayout.LayoutParams(-1, dp(200)))
        action("Refresh preview") { preview.loadUrl("https://appassets.androidplatform.net/assets/avatar-studio.html?shape=${prefs.taskOverlayAvatar}&style=${AuraOptions.avatarStyle}") }
        preview.loadUrl("https://appassets.androidplatform.net/assets/avatar-studio.html?shape=${prefs.taskOverlayAvatar}&style=${AuraOptions.avatarStyle}")
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) { preview.onPause() }
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) { preview.onResume() }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { preview.destroy() }
        })
    }
}
