package com.jarvis.assistant.wallpaper

import androidx.core.graphics.withTranslation
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.directives.PremiumOrbView
import com.jarvis.assistant.util.VisualMotionSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.min

/** Optional system wallpaper reusing the native orb. It never starts a voice session. */
class JarvisWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = PlasmaEngine()
    private inner class PlasmaEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var observation: Job? = null
        private val orb = PremiumOrbView(this@JarvisWallpaperService).apply { plasmaMode=true; renderingActive=false }
        private var visible = false
        private var ready = false
        private var touchedAt = 0L
        private var touchX = .5f
        private var touchY = .5f
        private var size = 0
        private var scheduled=false
        private var lastDrawAt=0L
        private var previousVisual: com.jarvis.assistant.ui.orb.PlasmaVisualState?=null
        private val frame = Runnable { scheduled=false;drawFrame() }
        init { setTouchEventsEnabled(true) }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible=isVisible
            observation?.cancel();observation=null
            main.removeCallbacks(frame);scheduled=false
            if(isVisible) {
                observation=scope.launch {
                    JarvisConversationService.voicePresentation.collectLatest { facts ->
                        orb.submit(facts)
                        val visual=com.jarvis.assistant.ui.orb.OrbPresentationMapper.map(facts,SystemClock.elapsedRealtime()).state
                        if(previousVisual != visual || !VisualMotionSettings.isReduced(this@JarvisWallpaperService)) requestFrame()
                        previousVisual=visual
                    }
                }
                requestFrame()
            }
        }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            ready=true;size=min(width,height).coerceAtMost(900).coerceAtLeast(1)
            orb.layout(0,0,size,size)
            requestFrame()
        }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            ready=false;main.removeCallbacks(frame);scheduled=false;super.onSurfaceDestroyed(holder)
        }
        override fun onTouchEvent(event: MotionEvent) {
            if(event.actionMasked == MotionEvent.ACTION_DOWN && visible && ready) {
                val bounds=surfaceHolder.surfaceFrame
                touchX=(event.x / bounds.width().coerceAtLeast(1)).coerceIn(.2f,.8f)
                touchY=(event.y / bounds.height().coerceAtLeast(1)).coerceIn(.2f,.8f)
                touchedAt=SystemClock.elapsedRealtime();requestFrame()
            }
        }
        private fun requestFrame() {
            if(!visible || !ready || scheduled) return
            val interval=if(getSystemService(PowerManager::class.java).isPowerSaveMode) 100L else 33L
            scheduled=true
            main.postDelayed(frame,(lastDrawAt+interval-SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
        private fun drawFrame() {
            if(!visible || !ready || !surfaceHolder.surface.isValid) return
            lastDrawAt=SystemClock.elapsedRealtime()
            val reduced=VisualMotionSettings.isReduced(this@JarvisWallpaperService)
            var canvas: android.graphics.Canvas?=null
            try {
                canvas=surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(getColor(com.jarvis.assistant.R.color.plasma_background))
                val drift=if(reduced) 0f else (1f-(SystemClock.elapsedRealtime()-touchedAt)/1600f).coerceIn(0f,1f)
                val x=canvas.width * (.5f+(touchX-.5f)*drift*.2f)
                val y=canvas.height * (.46f+(touchY-.5f)*drift*.12f)
                canvas.withTranslation(x-size/2f,y-size/2f) { orb.draw(this) }
            } catch (_: RuntimeException) { /* The system may replace the surface between callbacks. */ }
            finally { if(canvas != null) runCatching { surfaceHolder.unlockCanvasAndPost(canvas) } }
            if(visible && ready && !reduced) {
                requestFrame()
            }
        }
        override fun onDestroy() {
            visible=false;ready=false;observation?.cancel();scope.cancel();main.removeCallbacks(frame);scheduled=false
            super.onDestroy()
        }
    }
}
