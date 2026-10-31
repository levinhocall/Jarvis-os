package com.jarvis.assistant.ui.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.util.ThemeManager

/**
 * User-owned video wallpaper flow. Videos remain in the user's document provider;
 * the app persists only the granted content URI and never downloads third-party media.
 */
class VideoWallpaperActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var videoView: VideoView? = null
    private var status: TextView? = null

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString(KEY_VIDEO_URI, uri.toString()).apply()
                bindPreview(uri)
                status?.text = "Video selected. Preview it, then open Android's wallpaper confirmation."
            } catch (error: SecurityException) {
                status?.text = "Could not retain access to this video. Please select it again."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(ThemeManager.getThemeBgColor(
                com.jarvis.assistant.JarvisApp.instance.preferences.appTheme,
                com.jarvis.assistant.JarvisApp.instance.preferences.customThemeColor
            ))
        }
        root.addView(TextView(this).apply {
            text = "JARVIS • VIDEO WALLPAPER"
            textSize = 21f
            setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Choose a video you own or have permission to use. Playback is muted and starts only while the wallpaper is visible."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(14))
            setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextMuted))
        })
        videoView = VideoView(this).also { player ->
            root.addView(player, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(14) })
        }
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextMuted))
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(status)
        root.addView(actionButton("Choose video") { videoPicker.launch(arrayOf("video/*")) })
        root.addView(actionButton("Preview selected video") {
            val uri = selectedUri()
            if (uri == null) status?.text = "Choose a video first." else bindPreview(uri)
        })
        root.addView(actionButton("Apply live wallpaper") { openSystemWallpaperPreview() })
        root.addView(actionButton("Stop preview") { videoView?.stopPlayback() })
        setContentView(root)

        selectedUri()?.let(::bindPreview) ?: run {
            status?.text = "No video selected. Import a video to begin."
        }
    }

    private fun selectedUri(): Uri? = prefs.getString(KEY_VIDEO_URI, null)?.let(Uri::parse)

    private fun bindPreview(uri: Uri) {
        videoView?.apply {
            setVideoURI(uri)
            setOnPreparedListener { player ->
                player.isLooping = true
                player.setVolume(0f, 0f)
                player.start()
            }
            setOnErrorListener { _, what, extra ->
                status?.text = "Preview unavailable (media error $what/$extra). Choose another supported video."
                true
            }
        }
    }

    private fun openSystemWallpaperPreview() {
        if (selectedUri() == null) {
            status?.text = "Choose a video before applying the wallpaper."
            return
        }
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@VideoWallpaperActivity, VideoWallpaperService::class.java)
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
            status?.text = "Android wallpaper preview is unavailable on this device."
        }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun resolveColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        videoView?.pause()
    }

    override fun onDestroy() {
        videoView?.stopPlayback()
        videoView = null
        super.onDestroy()
    }

    companion object {
        const val PREFS = "jarvis_video_wallpaper"
        const val KEY_VIDEO_URI = "selected_video_uri"
    }
}
