package com.jarvis.assistant.ui.wallpaper

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Video lives behind the main deck only; hidden/paused views release playback. */
class InAppWallpaper(context: Context, content: View, private val backgrounds: List<View>) : FrameLayout(context), DefaultLifecycleObserver {
    private val video = VideoView(context)
    private val shade = View(context).apply { setBackgroundColor(0x77000000) }
    init { addView(video,LayoutParams(-1,-1)); addView(shade,LayoutParams(-1,-1)); addView(content,LayoutParams(-1,-1)); video.visibility=GONE; shade.visibility=GONE }
    override fun onResume(owner: LifecycleOwner) {
        val prefs=context.getSharedPreferences(VideoWallpaperActivity.PREFS,Context.MODE_PRIVATE)
        val uri=prefs.getString(VideoWallpaperActivity.KEY_VIDEO_URI,null)
        val enabled=prefs.getBoolean("show_in_app",false) && uri != null && !(context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
        video.visibility=if(enabled) VISIBLE else GONE; shade.visibility=video.visibility
        backgrounds.forEach { if(enabled) it.setBackgroundColor(Color.TRANSPARENT) else com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(it) }
        if(enabled) {
            video.setOnPreparedListener { it.isLooping=true; it.setVolume(0f,0f); video.start() }
            video.setOnErrorListener { _,_,_ -> video.stopPlayback(); video.visibility=GONE; true }
            video.setVideoURI(Uri.parse(uri))
        }
    }
    override fun onPause(owner: LifecycleOwner) { video.stopPlayback() }
    override fun onDestroy(owner: LifecycleOwner) { video.stopPlayback(); video.setOnPreparedListener(null) }
}
