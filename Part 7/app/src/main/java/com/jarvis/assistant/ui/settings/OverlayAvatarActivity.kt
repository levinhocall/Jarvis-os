package com.jarvis.assistant.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import android.widget.RadioGroup
import android.widget.Spinner
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

class OverlayAvatarActivity : AppCompatActivity() {

    companion object {
        private val ALLOWED_AVATARS = setOf(
            "alien", "blob", "cat", "circle", "cloud", "droid", "drop", "flower",
            "ghost", "hexagon", "mech", "pebble", "pill", "puddle", "square", "star", "triangle"
        )
    }
    private lateinit var avatarGallery: WebView
    private lateinit var overlaySwitch: SwitchCompat
    private val preferences get() = JarvisApp.instance.preferences
    private lateinit var assetLoader: WebViewAssetLoader
    private var pendingOverlayEnable = false
    private var aiGirlSwitchRef: SwitchCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_avatar)
        ThemeManager.applyDynamicBackground(findViewById(R.id.overlayAvatarRoot))

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
        overlaySwitch = findViewById(R.id.switchOverlayEnabled)
        val overlayPermissionGranted = Settings.canDrawOverlays(this)
        val overlayEnabled = preferences.isTaskOverlayEnabled && overlayPermissionGranted
        if (preferences.isTaskOverlayEnabled && !overlayPermissionGranted) {
            preferences.isTaskOverlayEnabled = false
        }
        overlaySwitch.isChecked = overlayEnabled
        val modeGroup = findViewById<RadioGroup>(R.id.radioOverlayMode)
        modeGroup.check(if (preferences.taskOverlayMode == "working") R.id.rbOverlayWorking else R.id.rbOverlayAlways)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            preferences.taskOverlayMode = if (checkedId == R.id.rbOverlayWorking) "working" else "always"
        }
        val edgeSpinner = findViewById<Spinner>(R.id.spinnerOverlayEdge)
        val edgeValues = listOf("right", "left", "top", "bottom")
        var edgeSelectionReady = false
        edgeSpinner.setSelection(edgeValues.indexOf(preferences.taskOverlayEdge).coerceAtLeast(0))
        edgeSpinner.post { edgeSelectionReady = true }
        modeGroup.isEnabled = preferences.isTaskOverlayEnabled
        edgeSpinner.isEnabled = preferences.isTaskOverlayEnabled
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = preferences.isTaskOverlayEnabled
        edgeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (edgeSelectionReady) edgeValues.getOrNull(position)?.let {
                    preferences.taskOverlayFreePosition = false
                    preferences.taskOverlayEdge = it
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        overlaySwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled && !Settings.canDrawOverlays(this)) {
                // Android requires the user to grant special overlay access in Settings.
                // Never persist an enabled state that the OS will refuse to display.
                pendingOverlayEnable = true
                overlaySwitch.isChecked = false
                try {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {
                    pendingOverlayEnable = false
                    android.widget.Toast.makeText(
                        this,
                        "Open Android Settings and allow display over other apps.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return@setOnCheckedChangeListener
            }
            preferences.isTaskOverlayEnabled = enabled
            modeGroup.isEnabled = enabled
            edgeSpinner.isEnabled = enabled
            for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = enabled
        }
        val aiGirlSwitch = findViewById<SwitchCompat>(R.id.switchOverlayAiGirl)
        aiGirlSwitch.isChecked = preferences.taskOverlayAvatar == "ai_girl"
        aiGirlSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                preferences.taskOverlayAvatar = "ai_girl"
            } else if (preferences.taskOverlayAvatar == "ai_girl") {
                preferences.taskOverlayAvatar = "ghost"
                selectInGallery("ghost")
            }
        }
        aiGirlSwitchRef = aiGirlSwitch
        val peekSwitch = findViewById<SwitchCompat>(R.id.switchOverlayPeek)
        peekSwitch.isChecked = preferences.taskOverlayPeek
        peekSwitch.setOnCheckedChangeListener { _, checked -> preferences.taskOverlayPeek = checked }
        avatarGallery = findViewById(R.id.avatarGallery)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        setupGallery()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupGallery() {
        avatarGallery.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        avatarGallery.settings.javaScriptEnabled = true
        avatarGallery.settings.domStorageEnabled = true
        avatarGallery.settings.allowFileAccess = false
        avatarGallery.addJavascriptInterface(AvatarPickerBridge(), "JarvisAvatarPicker")
        avatarGallery.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                // The JavaScript bridge is privileged. Never let this WebView navigate to
                // arbitrary content that could call it from an untrusted origin.
                return uri.scheme != "https" ||
                    uri.host != "appassets.androidplatform.net" ||
                    uri.path?.startsWith("/assets/") != true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                selectInGallery(preferences.taskOverlayAvatar)
                avatarGallery.evaluateJavascript(
                    "window.setGalleryTheme && window.setGalleryTheme('#${themeColor(R.attr.themeTextPrimary)}', '#${themeColor(R.attr.themeAccent)}')",
                    null
                )
            }
        }
        avatarGallery.loadUrl("https://appassets.androidplatform.net/assets/overlay-toys/gallery.html")
    }

    private fun selectInGallery(name: String) {
        val safeName = org.json.JSONObject.quote(name)
        avatarGallery.evaluateJavascript("window.setSelectedAvatar && window.setSelectedAvatar($safeName)", null)
    }

    private fun themeColor(attribute: Int): String {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return String.format(java.util.Locale.US, "%06X", 0xFFFFFF and value.data)
    }

    private inner class AvatarPickerBridge {
        @JavascriptInterface
        fun pageReady() {
            runOnUiThread { selectInGallery(preferences.taskOverlayAvatar) }
        }

        @JavascriptInterface
        fun selectAvatar(name: String) {
            if (name !in ALLOWED_AVATARS) return
            runOnUiThread {
                preferences.taskOverlayAvatar = name
                aiGirlSwitchRef?.isChecked = false
                selectInGallery(preferences.taskOverlayAvatar)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingOverlayEnable) {
            pendingOverlayEnable = false
            if (Settings.canDrawOverlays(this)) {
                preferences.isTaskOverlayEnabled = true
                overlaySwitch.isChecked = true
            } else {
                android.widget.Toast.makeText(
                    this,
                    "Overlay access was not granted. The floating avatar remains disabled.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } else if (preferences.isTaskOverlayEnabled && !Settings.canDrawOverlays(this)) {
            // Permission may be revoked while this screen is away.
            preferences.isTaskOverlayEnabled = false
            overlaySwitch.isChecked = false
        }
    }

    override fun onDestroy() {
        if (::avatarGallery.isInitialized) {
            avatarGallery.removeJavascriptInterface("JarvisAvatarPicker")
            avatarGallery.stopLoading()
            avatarGallery.destroy()
        }
        super.onDestroy()
    }
}
