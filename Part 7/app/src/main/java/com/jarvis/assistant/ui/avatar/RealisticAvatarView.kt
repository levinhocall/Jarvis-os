package com.jarvis.assistant.ui.avatar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.annotation.DrawableRes
import com.jarvis.assistant.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightweight, state-driven realistic avatar for JARVIS OS Home.
 *
 * Pure Canvas (no Compose / Rive / Live2D dependency) so it fits the existing Views/XML
 * architecture. Expression frames are bundled bitmaps in res/drawable-nodpi
 * (avatar_girl_*.webp). Replace those files with higher-resolution renders at any time;
 * no code change is required.
 *
 * Motion layers: breathing + subtle head sway, random blink (closed-eye frame),
 * crossfaded expression changes, amplitude-driven lip-sync (open-mouth frame blended over
 * the closed-mouth frame), and state rings (listening ripples, thinking sweep, error ring).
 */
class RealisticAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class AvatarState { IDLE, LISTENING, THINKING, SPEAKING, HAPPY, ACKNOWLEDGING, ERROR }

    /** Set from AppPreferences.hapticFeedbackEnabled by the host. */
    var hapticsEnabled: Boolean = false

    private val frameIdle: Bitmap? by lazy { loadFrame(R.drawable.avatar_girl_idle) }
    private val frameListening: Bitmap? by lazy { loadFrame(R.drawable.avatar_girl_listening) }
    private val frameThinking: Bitmap? by lazy { loadFrame(R.drawable.avatar_girl_thinking) } // eyes closed, also the blink frame
    private val frameSpeaking: Bitmap? by lazy { loadFrame(R.drawable.avatar_girl_speaking) }
    private val frameHappy: Bitmap? by lazy { loadFrame(R.drawable.avatar_girl_happy) }
    private val frameAck: Bitmap? by lazy { loadFrame(R.drawable.avatar_girl_ack) }

    private var baseState = AvatarState.IDLE
    private var overlayState: AvatarState? = null
    private var overlayUntil = 0L
    private var shownState = AvatarState.IDLE

    private var currentFrame: Bitmap? = null
    private var previousFrame: Bitmap? = null
    private var fade = 1f

    @Volatile private var amplitude = 0f
    @Volatile private var lastAmpAt = 0L
    private var mouth = 0f

    private var nextBlinkAt = 0L
    private var blinkUntil = 0L

    private var clock = 0f
    private var lastTick = 0L
    private var ticker: ValueAnimator? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val clipPath = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val arcRect = RectF()
    private val sweepMatrix = Matrix()
    private var ringShader: SweepGradient? = null

    init {
        currentFrame = frameIdle
        isClickable = true
        isFocusable = true
    }

    // ---- Public API -------------------------------------------------------------------

    /** Accepts the same keys used by the Orb: idle / listening / thinking / speaking (+ error). */
    fun setStateKey(key: String) {
        baseState = when (key.lowercase()) {
            "listening" -> AvatarState.LISTENING
            "thinking" -> AvatarState.THINKING
            "speaking" -> AvatarState.SPEAKING
            "error" -> AvatarState.ERROR
            else -> AvatarState.IDLE
        }
    }

    /** Raw 0..1 playback amplitude, used for lip-sync while SPEAKING. Safe from any thread. */
    fun setAmplitude(amp: Float) {
        amplitude = amp.coerceIn(0f, 1f)
        lastAmpAt = SystemClock.uptimeMillis()
    }

    /** Short "task done" nod. */
    fun playAcknowledge(durationMs: Long = 1600L) = playTransient(AvatarState.ACKNOWLEDGING, durationMs)

    /** Short friendly smile. */
    fun playHappy(durationMs: Long = 1800L) = playTransient(AvatarState.HAPPY, durationMs)

    private fun playTransient(state: AvatarState, durationMs: Long) {
        overlayState = state
        overlayUntil = SystemClock.uptimeMillis() + durationMs
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
        val overlay = overlayState
        return if (overlay != null && now < overlayUntil) overlay else baseState
    }

    private fun frameFor(state: AvatarState): Bitmap? = when (state) {
        AvatarState.LISTENING -> frameListening
        AvatarState.THINKING -> frameThinking
        AvatarState.HAPPY -> frameHappy
        AvatarState.ACKNOWLEDGING -> frameAck
        AvatarState.IDLE, AvatarState.SPEAKING, AvatarState.ERROR -> frameIdle
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastTick == 0L) 16L else (now - lastTick).coerceIn(1L, 64L)
        lastTick = now
        clock += dt / 1000f

        val eff = effectiveState(now)
        if (eff != shownState) onStateChanged(eff)

        if (fade < 1f) fade = min(1f, fade + dt / 240f)

        // Lip-sync: real amplitude when fresh, otherwise a believable synthetic talk pattern.
        var target = 0f
        if (eff == AvatarState.SPEAKING) {
            target = if (now - lastAmpAt < 250L) {
                (amplitude * 2.4f).coerceIn(0f, 1f)
            } else {
                (0.5f + 0.5f * sin(clock * 11f) * sin(clock * 5.3f)).coerceIn(0f, 1f) * 0.8f
            }
        }
        mouth += (target - mouth) * min(1f, dt / 45f)

        // Blink only while calm.
        if (eff == AvatarState.IDLE || eff == AvatarState.LISTENING) {
            if (nextBlinkAt == 0L) nextBlinkAt = now + 2500L
            if (now >= nextBlinkAt) {
                blinkUntil = now + 140L
                nextBlinkAt = now + Random.nextLong(2800L, 6200L)
            }
        }

        invalidate()
    }

    private fun onStateChanged(newState: AvatarState) {
        previousFrame = currentFrame
        currentFrame = frameFor(newState)
        fade = 0f
        if (hapticsEnabled && (newState == AvatarState.LISTENING || newState == AvatarState.SPEAKING)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        shownState = newState
    }

    // ---- Drawing ----------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        if (r <= 0f) return
        auraPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.argb(150, 255, 79, 163), Color.argb(70, 255, 199, 107), Color.TRANSPARENT),
            floatArrayOf(0.62f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        ringShader = SweepGradient(
            cx, cy,
            intArrayOf(PINK, GOLD, CYAN, PINK),
            null,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        val cx = w / 2f
        val cy = h / 2f
        val outer = min(w, h) / 2f
        val faceR = outer * 0.72f
        val state = shownState

        // Aura (pulses; stronger while speaking)
        val pulse = 0.5f + 0.5f * sin(clock * 2f * PI.toFloat() / 3.2f)
        auraPaint.alpha = (255 * (0.45f + 0.25f * pulse + 0.30f * mouth)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, outer, auraPaint)

        // State rings
        when (state) {
            AvatarState.LISTENING -> {
                for (i in 0..1) {
                    val p = (clock * 0.6f + i * 0.5f) % 1f
                    ringPaint.shader = null
                    ringPaint.color = CYAN
                    ringPaint.alpha = ((1f - p) * 150).toInt().coerceIn(0, 255)
                    ringPaint.strokeWidth = faceR * 0.035f
                    canvas.drawCircle(cx, cy, faceR * (1.05f + 0.28f * p), ringPaint)
                }
            }
            AvatarState.THINKING -> {
                accentPaint.shader = null
                accentPaint.color = GOLD
                accentPaint.alpha = 230
                accentPaint.strokeWidth = faceR * 0.05f
                val rr = faceR * 1.12f
                arcRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                canvas.drawArc(arcRect, (clock * 220f) % 360f, 70f, false, accentPaint)
                canvas.drawArc(arcRect, (clock * 220f + 180f) % 360f, 40f, false, accentPaint)
            }
            else -> Unit
        }

        // Portrait (breathing + head sway), clipped to a circle
        val breathe = sin(clock * 2f * PI.toFloat() / 4.2f)
        val scale = 1f + 0.018f * breathe
        val shiftY = h * 0.006f * breathe
        val sway = if (state == AvatarState.SPEAKING) 1.2f else 0.7f
        val rotation = sway * sin(clock * 2f * PI.toFloat() / 7.5f)

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.translate(0f, shiftY)
        canvas.rotate(rotation, cx, cy + faceR * 0.5f)

        clipPath.reset()
        clipPath.addCircle(cx, cy, faceR, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        dstRect.set(cx - faceR, cy - faceR, cx + faceR, cy + faceR)

        val prev = previousFrame
        if (fade < 1f && prev != null) drawFrame(canvas, prev, 255)
        currentFrame?.let { drawFrame(canvas, it, if (fade < 1f && prev != null) (fade * 255).toInt() else 255) }

        // Lip-sync overlay: open-mouth frame blended over the closed-mouth one.
        if (mouth > 0.02f && (state == AvatarState.SPEAKING || mouth > 0.05f)) {
            frameSpeaking?.let { drawFrame(canvas, it, (mouth * 255).toInt().coerceIn(0, 255)) }
        }
        // Blink overlay (closed-eye frame).
        if (now < blinkUntil && (state == AvatarState.IDLE || state == AvatarState.LISTENING)) {
            frameThinking?.let { drawFrame(canvas, it, 255) }
        }
        canvas.restore()

        // Face ring
        ringPaint.strokeWidth = faceR * 0.045f
        if (state == AvatarState.ERROR) {
            ringPaint.shader = null
            ringPaint.color = RED
            ringPaint.alpha = (150 + 100 * pulse).toInt().coerceIn(0, 255)
        } else {
            ringShader?.let {
                sweepMatrix.setRotate(clock * 18f, cx, cy)
                it.setLocalMatrix(sweepMatrix)
            }
            ringPaint.shader = ringShader
            ringPaint.alpha = 255
        }
        canvas.drawCircle(cx, cy, faceR, ringPaint)
        canvas.restore()
    }

    private fun drawFrame(canvas: Canvas, bmp: Bitmap, alpha: Int) {
        val side = min(bmp.width, bmp.height)
        val left = (bmp.width - side) / 2
        val top = (bmp.height - side) / 2
        srcRect.set(left, top, left + side, top + side)
        bitmapPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
    }

    private fun loadFrame(@DrawableRes id: Int): Bitmap? =
        runCatching { BitmapFactory.decodeResource(resources, id) }.getOrNull()

    private companion object {
        val PINK = Color.parseColor("#FF4FA3")
        val GOLD = Color.parseColor("#FFC76B")
        val CYAN = Color.parseColor("#39E1FF")
        val RED = Color.parseColor("#FF3B3B")
    }
}
