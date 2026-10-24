package com.jarvis.assistant.ui.fx

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import kotlin.random.Random

/**
 * Once-per-process 3D animated splash intro:
 * - Streaking star particles
 * - Condensing orb core
 * - Assistant name rendered in Instrument Serif font
 * - Total duration <= 2.2 s with smooth dissolve into Home
 * - Skippable on click
 * - Guarded by process-level `played` flag to never replay on rotation or resume
 */
class LiaSplash3D @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        @Volatile
        var played: Boolean = false

        fun resetForTesting() {
            played = false
        }
    }

    private class Star(
        var x: Float,
        var y: Float,
        var z: Float,
        val speed: Float,
        val size: Float
    )

    private val stars = ArrayList<Star>(120)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val orbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val assistantNameView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 32f
        setTextColor(ContextCompat.getColor(context, R.color.lia_text_primary))
        try {
            val serifFont = ResourcesCompat.getFont(context, R.font.instrument_serif)
            typeface = serifFont
        } catch (_: Exception) {}
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            topMargin = 220
        }
    }

    private var progress: Float = 0f
    private var introAnimator: ValueAnimator? = null
    var onFinishListener: (() -> Unit)? = null

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.lia_background))
        isClickable = true
        isFocusable = true

        val name = try {
            JarvisApp.instance.preferences.assistantName
        } catch (_: Exception) {
            "Jarvis"
        }
        assistantNameView.text = name
        addView(assistantNameView)

        setOnClickListener {
            skip()
        }

        initStars()
    }

    private fun initStars() {
        stars.clear()
        for (i in 0 until 100) {
            stars.add(
                Star(
                    x = Random.nextFloat() * 2f - 1f,
                    y = Random.nextFloat() * 2f - 1f,
                    z = Random.nextFloat() * 1.5f + 0.1f,
                    speed = Random.nextFloat() * 1.5f + 0.5f,
                    size = Random.nextFloat() * 3f + 1.5f
                )
            )
        }
    }

    fun startIntro(onFinish: (() -> Unit)? = null) {
        onFinishListener = onFinish

        val isReduceMotion = try {
            JarvisApp.instance.preferences.isReduceMotionEnabled
        } catch (_: Exception) {
            false
        }

        if (played || isReduceMotion) {
            played = true
            visibility = View.GONE
            onFinishListener?.invoke()
            return
        }

        visibility = View.VISIBLE
        alpha = 1.0f
        progress = 0f

        introAnimator?.cancel()
        // Total duration 2000 ms <= 2200 ms requirement
        introAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                updateIntroStage(progress)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    played = true
                    visibility = View.GONE
                    onFinishListener?.invoke()
                }
            })
            start()
        }
    }

    private fun updateIntroStage(p: Float) {
        // Assistant name fades in between 0.4 and 0.8, then dissolves
        if (p < 0.4f) {
            assistantNameView.alpha = 0f
            assistantNameView.scaleX = 0.85f
            assistantNameView.scaleY = 0.85f
        } else if (p <= 0.8f) {
            val norm = (p - 0.4f) / 0.4f
            assistantNameView.alpha = norm
            assistantNameView.scaleX = 0.85f + 0.15f * norm
            assistantNameView.scaleY = 0.85f + 0.15f * norm
        } else {
            // Dissolve out into Home between 0.8 and 1.0
            val dissolve = 1.0f - ((p - 0.8f) / 0.2f)
            alpha = dissolve
            assistantNameView.alpha = dissolve
        }
    }

    fun skip() {
        if (!played) {
            played = true
            introAnimator?.cancel()
            visibility = View.GONE
            onFinishListener?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (cx <= 0f || cy <= 0f) return

        // 1. Draw streaking stars
        val starSpeedMult = 1f + progress * 4f
        for (star in stars) {
            star.z -= 0.015f * star.speed * starSpeedMult
            if (star.z <= 0.05f) {
                star.z = 1.5f
                star.x = Random.nextFloat() * 2f - 1f
                star.y = Random.nextFloat() * 2f - 1f
            }
            val k = 1f / star.z
            val sx = cx + star.x * cx * k
            val sy = cy + star.y * cy * k
            if (sx in 0f..width.toFloat() && sy in 0f..height.toFloat()) {
                val alpha = (1f - star.z / 1.5f).coerceIn(0f, 1f)
                starPaint.alpha = (alpha * 255).toInt()
                canvas.drawCircle(sx, sy, star.size * k * 0.5f, starPaint)
            }
        }

        // 2. Condensing central orb particles
        val orbScale = (progress * 1.2f).coerceIn(0.1f, 1.0f)
        val orbRadius = cx * 0.35f * orbScale
        val orbAlpha = (progress * 1.5f).coerceIn(0f, 1f)

        orbGlowPaint.color = ContextCompat.getColor(context, R.color.lia_lotus)
        orbGlowPaint.alpha = (orbAlpha * 180).toInt()
        canvas.drawCircle(cx, cy, orbRadius, orbGlowPaint)

        orbGlowPaint.color = ContextCompat.getColor(context, R.color.lia_accent)
        orbGlowPaint.alpha = (orbAlpha * 220).toInt()
        canvas.drawCircle(cx, cy, orbRadius * 0.6f, orbGlowPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        introAnimator?.cancel()
    }
}
