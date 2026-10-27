package com.jarvis.assistant.ui.fx

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ConversationState

/**
 * Full-screen slim ~26dp edge glow overlay:
 * - Violet during resting / listening
 * - Warm pink/marigold during speech playback (reacting to amplitude)
 * - Red during error states
 * - Hosted as TYPE_APPLICATION_OVERLAY window started/stopped by JarvisConversationService
 * - Silently does nothing without SYSTEM_ALERT_WINDOW permission or when disabled in preferences
 */
class ListeningEdgeGlow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var glowView: GlowBorderView? = null
    private var isAttached = false

    private class GlowBorderView(context: Context) : View(context) {
        var baseColor: Int = Color.parseColor("#8B5CF6") // Violet resting
        var amplitude: Float = 0f
        var pulse: Float = 0f

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderThickness = 26f * resources.displayMetrics.density

        private var pulseAnimator: ValueAnimator? = null

        init {
            pulseAnimator = ValueAnimator.ofFloat(0.5f, 1.0f).apply {
                duration = 1400
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    pulse = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val effectiveAlpha = ((0.35f + pulse * 0.25f + amplitude * 0.4f).coerceIn(0f, 1f) * 255).toInt()
            val glowColor = Color.argb(
                effectiveAlpha,
                Color.red(baseColor),
                Color.green(baseColor),
                Color.blue(baseColor)
            )
            val transparentColor = Color.TRANSPARENT

            // 1. Top glow gradient
            paint.shader = LinearGradient(0f, 0f, 0f, borderThickness, glowColor, transparentColor, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, borderThickness, paint)

            // 2. Bottom glow gradient
            paint.shader = LinearGradient(0f, h, 0f, h - borderThickness, glowColor, transparentColor, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, h - borderThickness, w, h, paint)

            // 3. Left glow gradient
            paint.shader = LinearGradient(0f, 0f, borderThickness, 0f, glowColor, transparentColor, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, borderThickness, h, paint)

            // 4. Right glow gradient
            paint.shader = LinearGradient(w, 0f, w - borderThickness, 0f, glowColor, transparentColor, Shader.TileMode.CLAMP)
            canvas.drawRect(w - borderThickness, 0f, w, h, paint)
        }

        fun dispose() {
            pulseAnimator?.cancel()
        }
    }

    fun canDraw(): Boolean {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        if (prefs?.isListeningEdgeGlowEnabled != true) {
            return false
        }
        return try {
            Settings.canDrawOverlays(context)
        } catch (_: Exception) {
            false
        }
    }

    fun start() {
        mainHandler.post {
            if (!canDraw() || isAttached || windowManager == null) {
                return@post
            }

            try {
                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )

                val view = GlowBorderView(context)
                windowManager.addView(view, layoutParams)
                glowView = view
                isAttached = true
            } catch (_: Exception) {
                // Silently do nothing if system restricts window addition
                isAttached = false
                glowView = null
            }
        }
    }

    fun stop() {
        mainHandler.post {
            if (!isAttached || glowView == null || windowManager == null) {
                return@post
            }
            try {
                glowView?.dispose()
                windowManager.removeView(glowView)
            } catch (_: Exception) {
            } finally {
                glowView = null
                isAttached = false
            }
        }
    }

    fun updateState(state: ConversationState, amplitude: Float = 0f) {
        mainHandler.post {
            val view = glowView ?: return@post
            val targetColor = when (state) {
                ConversationState.SPEAKING -> Color.parseColor("#FF6FAE") // Warm pink
                ConversationState.FAILED -> Color.parseColor("#EF4444") // Red error
                else -> Color.parseColor("#8B5CF6") // Violet resting
            }
            view.baseColor = targetColor
            view.amplitude = amplitude.coerceIn(0f, 1f)
            view.invalidate()
        }
    }

    fun updateAmplitude(amplitude: Float) {
        mainHandler.post {
            val view = glowView ?: return@post
            view.amplitude = amplitude.coerceIn(0f, 1f)
            view.invalidate()
        }
    }
}
