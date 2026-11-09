package com.jarvis.assistant.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Audio-reactive bar waveform flanking the mic ring (JARVIS OS Home mockup).
 * Bars are tallest next to the mic and taper outward. Set [mirrored] for the right-hand side.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** false = tallest on the right edge (left-hand waveform); true = tallest on the left edge. */
    var mirrored: Boolean = false
        set(value) {
            field = value
            rebuildShader()
            invalidate()
        }

    private val barCount = 15
    private val phases = FloatArray(barCount) { i -> (i * 2.399963f) % 6.2831855f } // golden-angle spread
    private val speeds = FloatArray(barCount) { i -> 3.2f + (i % 5) * 0.9f }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    @Volatile private var targetLevel = 0f
    private var level = 0f
    private var active = false
    private var clock = 0f
    private var lastTick = 0L
    private var ticker: ValueAnimator? = null

    /** Whether the waveform should move (listening / speaking). Inactive = flat dotted line. */
    fun setActive(value: Boolean) {
        active = value
    }

    /** 0..1 amplitude. Safe from any thread. */
    fun setLevel(amp: Float) {
        targetLevel = amp.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }

    private fun start() {
        if (!isShown || !ValueAnimator.areAnimatorsEnabled()) return
        if (ticker != null) return
        lastTick = 0L
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = SystemClock.uptimeMillis()
                val dt = if (lastTick == 0L) 16L else (now - lastTick).coerceIn(1L, 64L)
                lastTick = now
                clock += dt / 1000f
                val goal = if (active) max(targetLevel, 0.18f) else 0f
                level += (goal - level) * (dt / 90f).coerceAtMost(1f)
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        ticker?.cancel()
        ticker = null
        lastTick = 0L
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader()
    }

    private fun rebuildShader() {
        if (width <= 0) return
        val w = width.toFloat()
        // Outer end pink, inner end (next to the mic ring) gold.
        paint.shader = if (mirrored) {
            LinearGradient(0f, 0f, w, 0f, GOLD, PINK, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, w, 0f, PINK, GOLD, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val slot = w / barCount
        val barW = slot * 0.46f
        val cy = h / 2f
        val minH = barW // resting dot
        val maxH = h * 0.92f

        for (i in 0 until barCount) {
            // Position 0..1 from outer edge to the mic side.
            val t = if (mirrored) 1f - i / (barCount - 1f) else i / (barCount - 1f)
            val envelope = 0.18f + 0.82f * t * t
            val wobble = 0.55f + 0.45f * abs(sin(clock * speeds[i] + phases[i]))
            val bh = max(minH, minH + (maxH * envelope * wobble - minH) * level)
            val left = i * slot + (slot - barW) / 2f
            rect.set(left, cy - bh / 2f, left + barW, cy + bh / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }

    private companion object {
        val PINK = Color.parseColor("#FF4FA3")
        val GOLD = Color.parseColor("#FFC76B")
    }
}
