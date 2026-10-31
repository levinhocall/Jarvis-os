package com.jarvis.assistant.ui.wallpaper

import android.service.wallpaper.WallpaperService
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.util.Log

/**
 * Battery-conscious user-video wallpaper. No playback while hidden; audio is always muted.
 * Android owns the wallpaper lifecycle and surface. The user confirms activation in system UI.
 */
class VideoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine() {
        private var player: MediaPlayer? = null
        private var visible = false
        private var surfaceReady = false

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            startIfReady(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (visible) startIfReady(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible && surfaceReady) startIfReady(surfaceHolder)
            else releasePlayer()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            releasePlayer()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            surfaceReady = false
            releasePlayer()
            super.onDestroy()
        }

        private fun startIfReady(holder: SurfaceHolder) {
            if (!visible || !surfaceReady || player != null) return
            val uriString = getSharedPreferences(VideoWallpaperActivity.PREFS, MODE_PRIVATE)
                .getString(VideoWallpaperActivity.KEY_VIDEO_URI, null)
            if (uriString.isNullOrBlank()) {
                Log.w(TAG, "No user-selected video URI configured")
                return
            }
            try {
                val media = MediaPlayer()
                player = media
                media.setSurface(holder.surface)
                media.setDataSource(this@VideoWallpaperService, Uri.parse(uriString))
                media.isLooping = true
                media.setVolume(0f, 0f)
                media.setOnPreparedListener { prepared ->
                    if (visible && player === prepared) prepared.start() else releasePlayer()
                }
                media.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Wallpaper video playback failed: $what/$extra")
                    releasePlayer()
                    true
                }
                media.prepareAsync()
            } catch (error: Exception) {
                Log.e(TAG, "Unable to start wallpaper video", error)
                releasePlayer()
            }
        }

        private fun releasePlayer() {
            val current = player ?: return
            player = null
            try {
                current.setOnPreparedListener(null)
                current.setOnErrorListener(null)
                if (current.isPlaying) current.stop()
            } catch (_: Exception) {
                // Player may still be preparing or the surface may have been detached.
            }
            try { current.reset() } catch (_: Exception) {}
            try { current.release() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "JarvisVideoWallpaper"
    }
}
