package com.jarvis.assistant.ui.forge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.jarvis.assistant.forge.ForgeStage
import com.jarvis.assistant.forge.ForgeUiState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 2D Canvas fallback scene for Website Forge when WebGL is unavailable or on low-RAM devices.
 * Uses identical ForgeUiState telemetry to render dynamic energy rings and stage progression.
 */
class ForgeFallbackScene @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentState = ForgeUiState()
    private var rotationAngle = 0f
    private var pulseScale = 1.0f

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#070709")
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B5CF6")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#06B6D4")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val arcBounds = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 8000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            pulseScale = 0.95f + 0.1f * sin(Math.toRadians(rotationAngle.toDouble() * 2)).toFloat()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    fun updateState(state: ForgeUiState) {
        currentState = state
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(width, height) * 0.28f * pulseScale

        // Concentric rings
        ringPaint.alpha = 60
        canvas.drawCircle(cx, cy, baseRadius * 1.35f, ringPaint)
        ringPaint.alpha = 120
        canvas.drawCircle(cx, cy, baseRadius * 0.95f, ringPaint)
        ringPaint.alpha = 200
        canvas.drawCircle(cx, cy, baseRadius * 0.55f, ringPaint)

        // Sweeping progress arc
        val arcRadius = baseRadius * 1.15f
        arcBounds.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
        val sweep = (currentState.progress * 360f).coerceIn(4f, 360f)
        canvas.drawArc(arcBounds, rotationAngle - 90f, sweep, false, arcPaint)

        // Stage nodes (6 dots in a circle)
        val nodeRadius = baseRadius * 1.45f
        for (i in 1..6) {
            val angleRad = Math.toRadians(((i - 1) * 60 - 90).toDouble())
            val nx = cx + nodeRadius * cos(angleRad).toFloat()
            val ny = cy + nodeRadius * sin(angleRad).toFloat()

            if (i <= currentState.stage.index) {
                nodePaint.color = Color.parseColor("#06B6D4")
                canvas.drawCircle(nx, ny, 10f, nodePaint)
            } else {
                nodePaint.color = Color.parseColor("#334155")
                canvas.drawCircle(nx, ny, 6f, nodePaint)
            }
        }

        // Center stage text
        textPaint.color = Color.parseColor("#F8FAFC")
        textPaint.textSize = 38f
        canvas.drawText(currentState.stage.label, cx, cy - 10f, textPaint)

        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 24f
        val percent = (currentState.progress * 100).toInt()
        canvas.drawText("$percent%", cx, cy + 32f, textPaint)
    }
}
