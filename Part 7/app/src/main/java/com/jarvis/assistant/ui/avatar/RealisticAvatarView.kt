package com.jarvis.assistant.ui.avatar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.LinearInterpolator
import com.jarvis.assistant.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * JARVIS "AI Girl" portrait stage.
 *
 * IMPORTANT, read before extending: this is the INTERIM renderer. It animates one high-resolution
 * portrait (res/drawable-nodpi/avatar_girl_hero.webp) with procedural life: breathing, slow sway,
 * state-dependent lean / tilt / bounce / nod, colour grading, scan line, ripples and an
 * audio-reactive glow. It deliberately does NOT fake mouth movement or blinking on a photograph:
 * real lip-sync, blinking and expressions need a rigged model (Live2D .moc3 or Rive .riv with a
 * mouth-open parameter) or per-state video loops. See docs/AVATAR_GAP_ANALYSIS.md for the exact
 * assets required. The public API below is what a future rig renderer must keep supporting.
 */
class RealisticAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class AvatarState { IDLE, LISTENING, THINKING, SPEAKING, HAPPY, ACKNOWLEDGING, ERROR }

    /** PORTRAIT = rounded-rectangle hero stage (Home). CIRCLE = compact face (floating overlay). */
    enum class Shape { PORTRAIT, CIRCLE }

    var shape: Shape = Shape.PORTRAIT
        set(value) {
            if (field == value) return
            field = value
            rebuildGeometry(width, height)
            invalidate()
        }

    /** Set from AppPreferences.hapticFeedbackEnabled by the host. */
    var hapticsEnabled: Boolean = false

    /** Optional pull-style amplitude (0..1), polled every frame. Used by the floating overlay. */
    var amplitudeSource: (() -> Float)? = null

    private val hero: Bitmap? by lazy {
        runCatching { BitmapFactory.decodeResource(resources, R.drawable.avatar_girl_hero) }.getOrNull()
    }

    private var baseState = AvatarState.IDLE
    private var overlayState: AvatarState? = null
    private var overlayStart = 0L
    private var overlayUntil = 0L
    private var shownState = AvatarState.IDLE

    @Volatile private var pushedAmplitude = 0f
    @Volatile private var pushedAt = 0L
    private var voice = 0f

    // Smoothed pose parameters (lerped toward per-state targets every frame).
    private var leanScale = 0f
    private var tilt = 0f
    private var dimAlpha = 0f
    private var warmAlpha = 0f
    private var redAlpha = 0f

    private var clock = 0f
    private var lastTick = 0L
    private var ticker: ValueAnimator? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()
    private val tintPaint = Paint()
    private val scanPaint = Paint()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val clipPath = Path()
    private val clipRect = RectF()
    private val rippleRect = RectF()
    private val arcRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val sweepMatrix = Matrix()
    private var ringShader: SweepGradient? = null
    private var cornerRadius = 0f
    private var haloRadius = 0f

    init {
        isClickable = true
        isFocusable = true
    }

    // ---- Public API -------------------------------------------------------------------

    /** Accepts the same keys used by the Orb: idle / listening / thinking / speaking / error. */
    fun setStateKey(key: String) {
        baseState = when (key.lowercase()) {
            "listening" -> AvatarState.LISTENING
            "thinking" -> AvatarState.THINKING
            "speaking" -> AvatarState.SPEAKING
            "error" -> AvatarState.ERROR
            else -> AvatarState.IDLE
        }
    }

    /** Raw 0..1 playback amplitude. Safe from any thread. */
    fun setAmplitude(amp: Float) {
        pushedAmplitude = amp.coerceIn(0f, 1f)
        pushedAt = SystemClock.uptimeMillis()
    }

    /** Short "task done" nod. */
    fun playAcknowledge(durationMs: Long = 1500L) = playTransient(AvatarState.ACKNOWLEDGING, durationMs)

    /** Short friendly bounce and warm glow. */
    fun playHappy(durationMs: Long = 1800L) = playTransient(AvatarState.HAPPY, durationMs)

    private fun playTransient(state: AvatarState, durationMs: Long) {
        val now = SystemClock.uptimeMillis()
        overlayState = state
        overlayStart = now
        overlayUntil = now + durationMs
    }

    // ---- Lifecycle --------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTicker()
    }

    override fun onDetachedFromWindow() {
        stopTicker()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startTicker() else stopTicker()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && this.visibility == VISIBLE && isAttachedToWindow) startTicker() else stopTicker()
    }

    private fun startTicker() {
        if (ticker != null) return
        lastTick = 0L
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tick() }
            start()
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        lastTick = 0L
    }

    // ---- Simulation -------------------------------------------------------------------

    private fun effectiveState(now: Long): AvatarState {
        val o = overlayState
        return if (o != null && now < overlayUntil) o else baseState
    }

    private fun approach(current: Float, target: Float, dt: Long, tauMs: Float): Float =
        current + (target - current) * min(1f, dt / tauMs)

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastTick == 0L) 16L else (now - lastTick).coerceIn(1L, 64L)
        lastTick = now
        clock += dt / 1000f

        val state = effectiveState(now)
        if (state != shownState) {
            if (hapticsEnabled && (state == AvatarState.LISTENING || state == AvatarState.SPEAKING)) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            shownState = state
        }

        // Voice level: pull source wins, then a fresh pushed value; synthetic only as a visual fallback.
        val pulled = amplitudeSource?.invoke()
        val raw = when {
            pulled != null -> pulled
            now - pushedAt < 250L -> pushedAmplitude
            else -> 0f
        }
        val voiceTarget = if (state == AvatarState.SPEAKING) {
            if (raw > 0.01f) (raw * 2.2f).coerceIn(0f, 1f)
            else (0.45f + 0.35f * sin(clock * 9f) * sin(clock * 4.1f)).coerceIn(0f, 1f) * 0.6f
        } else 0f
        voice = approach(voice, voiceTarget, dt, 55f)

        val leanTarget = when (state) {
            AvatarState.LISTENING -> 0.035f
            AvatarState.HAPPY -> 0.045f
            AvatarState.SPEAKING -> 0.012f
            else -> 0f
        }
        val tiltTarget = when (state) {
            AvatarState.THINKING -> 3.2f
            AvatarState.HAPPY -> -1.5f
            else -> 0f
        }
        val dimTarget = when (state) {
            AvatarState.THINKING -> 0.20f
            AvatarState.ERROR -> 0.12f
            else -> 0f
        }
        val warmTarget = if (state == AvatarState.HAPPY) 0.16f else 0f
        val redTarget = if (state == AvatarState.ERROR) 0.20f else 0f

        leanScale = approach(leanScale, leanTarget, dt, 260f)
        tilt = approach(tilt, tiltTarget, dt, 320f)
        dimAlpha = approach(dimAlpha, dimTarget, dt, 300f)
        warmAlpha = approach(warmAlpha, warmTarget, dt, 260f)
        redAlpha = approach(redAlpha, redTarget, dt, 200f)

        invalidate()
    }

    // ---- Geometry ---------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry(w, h)
    }

    private fun rebuildGeometry(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val pad = 8f * density
        val cx = w / 2f
        val cy = h / 2f

        if (shape == Shape.CIRCLE) {
            val r = min(w, h) / 2f - pad * 0.5f
            clipRect.set(cx - r, cy - r, cx + r, cy + r)
            cornerRadius = r
        } else {
            clipRect.set(pad, pad, w - pad, h - pad)
            cornerRadius = 28f * density
        }
        clipPath.reset()
        clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)
        haloRadius = max(w, h) * 0.62f

        auraPaint.shader = RadialGradient(
            cx, cy, haloRadius,
            intArrayOf(Color.argb(140, 255, 79, 163), Color.argb(60, 255, 199, 107), Color.TRANSPARENT),
            floatArrayOf(0.45f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        ringShader = SweepGradient(cx, cy, intArrayOf(PINK, GOLD, CYAN, PINK), null)

        // Bottom fade so the portrait melts into the dark UI under it (portrait stage only).
        fadePaint.shader = LinearGradient(
            0f, clipRect.top + clipRect.height() * 0.62f,
            0f, clipRect.bottom,
            Color.TRANSPARENT, Color.argb(235, 7, 5, 16),
            Shader.TileMode.CLAMP,
        )
        scanPaint.shader = LinearGradient(
            0f, 0f, 0f, 14f * density,
            intArrayOf(Color.TRANSPARENT, Color.argb(150, 255, 199, 107), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    // ---- Drawing ----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || clipRect.isEmpty) return

        val now = SystemClock.uptimeMillis()
        val density = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f
        val state = shownState
        val pulse = 0.5f + 0.5f * sin(clock * 2f * PI.toFloat() / 3.4f)

        // Aura behind the portrait; swells with the voice.
        auraPaint.alpha = (255 * (0.40f + 0.22f * pulse + 0.38f * voice)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, haloRadius, auraPaint)

        // Listening ripples (outside the frame).
        if (state == AvatarState.LISTENING) {
            for (i in 0..1) {
                val p = (clock * 0.55f + i * 0.5f) % 1f
                val grow = 22f * density * p
                rippleRect.set(clipRect.left - grow, clipRect.top - grow, clipRect.right + grow, clipRect.bottom + grow)
                ripplePaint.color = CYAN
                ripplePaint.alpha = ((1f - p) * 140).toInt().coerceIn(0, 255)
                ripplePaint.strokeWidth = 2.2f * density
                canvas.drawRoundRect(rippleRect, cornerRadius + grow, cornerRadius + grow, ripplePaint)
            }
        }

        // Procedural pose: breathing + slow sway + state lean / nod / bounce.
        val breathe = sin(clock * 2f * PI.toFloat() / 4.4f)
        var scale = BASE_ZOOM * (1f + 0.012f * breathe + leanScale + 0.010f * voice)
        var dy = clipRect.height() * 0.004f * breathe
        val dx = clipRect.width() * 0.006f * sin(clock * 2f * PI.toFloat() / 9.5f)
        val rot = tilt + 0.9f * sin(clock * 2f * PI.toFloat() / 7.2f)
        if (state == AvatarState.ACKNOWLEDGING) {
            val t = ((now - overlayStart) / max(1f, (overlayUntil - overlayStart).toFloat())).coerceIn(0f, 1f)
            dy += clipRect.height() * 0.030f * sin(t * PI.toFloat() * 2f).let { if (it < 0f) it * 0.35f else it }
        }
        if (state == AvatarState.HAPPY) {
            dy -= clipRect.height() * 0.012f * abs(sin(clock * 5.2f))
        }
        scale = scale.coerceAtLeast(1.04f)

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.save()
        canvas.translate(dx, dy)
        canvas.rotate(rot, cx, cy)
        canvas.scale(scale, scale, cx, cy)
        drawHero(canvas)
        canvas.restore()

        // Colour grading per state, drawn inside the frame.
        if (dimAlpha > 0.01f) {
            tintPaint.color = Color.argb((255 * dimAlpha).toInt(), 6, 2, 18)
            canvas.drawRect(clipRect, tintPaint)
        }
        if (warmAlpha > 0.01f) {
            tintPaint.color = Color.argb((255 * warmAlpha).toInt(), 255, 190, 120)
            canvas.drawRect(clipRect, tintPaint)
        }
        if (redAlpha > 0.01f) {
            tintPaint.color = Color.argb((255 * redAlpha * (0.7f + 0.3f * pulse)).toInt(), 255, 40, 60)
            canvas.drawRect(clipRect, tintPaint)
        }

        // Thinking: gold scan line sweeping down the portrait.
        if (state == AvatarState.THINKING) {
            val travel = clipRect.height() + 28f * density
            val y = clipRect.top - 14f * density + ((clock * 0.45f) % 1f) * travel
            canvas.save()
            canvas.translate(0f, y)
            canvas.drawRect(clipRect.left, 0f, clipRect.right, 14f * density, scanPaint)
            canvas.restore()
        }

        if (shape == Shape.PORTRAIT) canvas.drawRect(clipRect, fadePaint)
        canvas.restore()

        // Thinking arcs orbit the compact (circle) avatar.
        if (state == AvatarState.THINKING && shape == Shape.CIRCLE) {
            arcPaint.color = GOLD
            arcPaint.alpha = 230
            arcPaint.strokeWidth = 3f * density
            val g = 4f * density
            arcRect.set(clipRect.left - g, clipRect.top - g, clipRect.right + g, clipRect.bottom + g)
            canvas.drawArc(arcRect, (clock * 220f) % 360f, 70f, false, arcPaint)
            canvas.drawArc(arcRect, (clock * 220f + 180f) % 360f, 40f, false, arcPaint)
        }

        // Frame ring: rotating pink / gold / cyan, red on error.
        ringPaint.strokeWidth = 2.6f * density
        if (state == AvatarState.ERROR) {
            ringPaint.shader = null
            ringPaint.color = RED
            ringPaint.alpha = (150 + 100 * pulse).toInt().coerceIn(0, 255)
        } else {
            ringShader?.let {
                sweepMatrix.setRotate(clock * 16f, cx, cy)
                it.setLocalMatrix(sweepMatrix)
            }
            ringPaint.shader = ringShader
            ringPaint.alpha = 255
        }
        canvas.drawRoundRect(clipRect, cornerRadius, cornerRadius, ringPaint)
    }

    /** Center-crops the hero bitmap to the frame, biased toward the face. */
    private fun drawHero(canvas: Canvas) {
        val bmp = hero ?: return
        val dw = clipRect.width()
        val dh = clipRect.height()
        val dstAspect = dw / dh
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val srcW: Float
        val srcH: Float
        if (bw / bh > dstAspect) {
            srcH = bh
            srcW = bh * dstAspect
        } else {
            srcW = bw
            srcH = bw / dstAspect
        }
        val left = (FOCUS_X * bw - srcW / 2f).coerceIn(0f, bw - srcW)
        val top = (FOCUS_Y * bh - srcH / 2f).coerceIn(0f, bh - srcH)
        srcRect.set(left.toInt(), top.toInt(), (left + srcW).toInt(), (top + srcH).toInt())
        dstRect.set(clipRect)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
    }

    private companion object {
        const val BASE_ZOOM = 1.07f
        const val FOCUS_X = 0.54f
        const val FOCUS_Y = 0.46f
        val PINK = Color.parseColor("#FF4FA3")
        val GOLD = Color.parseColor("#FFC76B")
        val CYAN = Color.parseColor("#39E1FF")
        val RED = Color.parseColor("#FF3B3B")
    }
}
