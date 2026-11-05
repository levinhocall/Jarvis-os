package com.jarvis.assistant.ui.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Glowing gold/pink mic ring from the JARVIS OS Home mockup.
 * Purely visual: the host wires click / long-click to the existing mic handlers.
 */
class MicRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { OFF, LISTENING, SPEAKING, THINKING, MUTED }

    private var mode = Mode.OFF
    private var clock = 0f
    private var lastTick = 0L
    private var ticker: ValueAnimator? = null
    @Volatile private var level = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rippleOrArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A0A22") }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66FF4FA3")
    }
    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val sweepMatrix = Matrix()
    private val arcRect = RectF()
    private var ringShader: SweepGradient? = null

    private val micIcon by lazy {
        ContextCompat.getDrawable(context, R.drawable.ic_mic_jarvis)?.mutate()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        invalidate()
    }

    /** Optional 0..1 amplitude: makes the glow breathe with the voice. */
    fun setLevel(amp: Float) {
        level = amp.coerceIn(0f, 1f)
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
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        if (r <= 0f) return
        glowPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.argb(170, 255, 79, 163), Color.argb(70, 255, 199, 107), Color.TRANSPARENT),
            floatArrayOf(0.55f, 0.8f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Half gold / half pink ring, like the mockup.
        ringShader = SweepGradient(
            cx, cy,
            intArrayOf(PINK, PINK, GOLD, GOLD),
            floatArrayOf(0f, 0.5f, 0.5f, 1f),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val outer = min(w, h) / 2f
        val ringR = outer * 0.62f
        val strokeW = outer * 0.075f

        val active = mode == Mode.LISTENING || mode == Mode.SPEAKING || mode == Mode.THINKING
        val pulse = 0.5f + 0.5f * sin(clock * 2f * PI.toFloat() / 2.4f)
        val dim = when (mode) {
            Mode.OFF -> 0.45f
            Mode.MUTED -> 0.6f
            else -> 1f
        }

        // Glow
        glowPaint.alpha = (255 * dim * (if (active) 0.55f + 0.25f * pulse + 0.2f * level else 0.5f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, outer, glowPaint)

        // Ripples while listening / speaking
        if (mode == Mode.LISTENING || mode == Mode.SPEAKING) {
            for (i in 0..1) {
                val p = (clock * 0.7f + i * 0.5f) % 1f
                rippleOrArcPaint.shader = null
                rippleOrArcPaint.color = if (mode == Mode.SPEAKING) GOLD else PINK
                rippleOrArcPaint.alpha = ((1f - p) * 120).toInt().coerceIn(0, 255)
                rippleOrArcPaint.strokeWidth = strokeW * 0.5f
                canvas.drawCircle(cx, cy, ringR + (outer - ringR) * p, rippleOrArcPaint)
            }
        }

        // Main ring (slowly rotating when active)
        ringShader?.let {
            sweepMatrix.setRotate(if (active) clock * 40f else 90f, cx, cy)
            it.setLocalMatrix(sweepMatrix)
        }
        ringPaint.shader = ringShader
        ringPaint.strokeWidth = strokeW
        ringPaint.alpha = (255 * dim).toInt()
        canvas.drawCircle(cx, cy, ringR, ringPaint)

        // Inner disc + thin inner ring
        canvas.drawCircle(cx, cy, ringR - strokeW * 0.5f, discPaint)
        innerRingPaint.strokeWidth = strokeW * 0.25f
        canvas.drawCircle(cx, cy, ringR * 0.84f, innerRingPaint)

        // Thinking: gold arc orbiting the ring
        if (mode == Mode.THINKING) {
            rippleOrArcPaint.shader = null
            rippleOrArcPaint.color = GOLD
            rippleOrArcPaint.alpha = 230
            rippleOrArcPaint.strokeWidth = strokeW * 0.8f
            val rr = ringR + strokeW * 1.4f
            arcRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            canvas.drawArc(arcRect, (clock * 240f) % 360f, 60f, false, rippleOrArcPaint)
        }

        // Mic icon
        micIcon?.let {
            val half = (outer * 0.26f).toInt()
            it.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            it.setTint(if (mode == Mode.MUTED) Color.parseColor("#9A8FA6") else Color.WHITE)
            it.alpha = (255 * (if (mode == Mode.OFF) 0.7f else 1f)).toInt()
            it.draw(canvas)
        }

        // Mute slash
        if (mode == Mode.MUTED) {
            slashPaint.strokeWidth = strokeW * 0.55f
            val d = outer * 0.24f
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, slashPaint)
        }
    }

    private companion object {
        val PINK = Color.parseColor("#FF4FA3")
        val GOLD = Color.parseColor("#FFC76B")
    }
}
