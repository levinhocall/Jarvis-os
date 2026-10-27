package com.jarvis.assistant.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

/**
 * Premium 3D glass card view with translucent surface, subtle 1dp border, top rim light,
 * and ambient shadow via ViewOutlineProvider elevation.
 *
 * Honors the system and preference reduce_motion setting.
 */
class GlassCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var cornerRadius: Float = resources.getDimension(R.dimen.corner_radius_md)
    private var surfaceColor: Int = ContextCompat.getColor(context, R.color.glass_surface)
    private var borderColor: Int = ContextCompat.getColor(context, R.color.glass_border)
    private var rimLightColor: Int = ContextCompat.getColor(context, R.color.glass_rim_light)
    private var borderWidth: Float = resources.getDimension(R.dimen.glass_border_width)

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val rectF = RectF()
    private val rimRectF = RectF()
    private var scaleAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.GlassCardView, defStyleAttr, 0)
            try {
                cornerRadius = a.getDimension(R.styleable.GlassCardView_glassCornerRadius, cornerRadius)
                surfaceColor = a.getColor(R.styleable.GlassCardView_glassSurfaceColor, surfaceColor)
                borderColor = a.getColor(R.styleable.GlassCardView_glassBorderColor, borderColor)
                rimLightColor = a.getColor(R.styleable.GlassCardView_glassRimLightColor, rimLightColor)
                borderWidth = a.getDimension(R.styleable.GlassCardView_glassBorderWidth, borderWidth)
                val elev = a.getDimension(R.styleable.GlassCardView_glassElevation, resources.getDimension(R.dimen.glass_elevation))
                elevation = elev
            } finally {
                a.recycle()
            }
        } else {
            elevation = resources.getDimension(R.dimen.glass_elevation)
        }

        updatePaints()
        setupOutline()
    }

    private fun updatePaints() {
        surfacePaint.color = surfaceColor
        borderPaint.color = borderColor
        borderPaint.strokeWidth = borderWidth
        rimPaint.strokeWidth = borderWidth * 1.5f
    }

    private fun setupOutline() {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = borderWidth / 2f
        rectF.set(inset, inset, w.toFloat() - inset, h.toFloat() - inset)
        rimRectF.set(inset, inset, w.toFloat() - inset, h.toFloat() - inset)

        // Gradient for top rim light fading as it goes down
        rimPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat() * 0.4f,
            rimLightColor,
            0x00FFFFFF and rimLightColor,
            Shader.TileMode.CLAMP
        )
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        // 1. Draw translucent glass surface background
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, surfacePaint)

        // 2. Draw 1dp subtle border
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)

        // 3. Draw top rim light accent
        canvas.drawRoundRect(rimRectF, cornerRadius, cornerRadius, rimPaint)

        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isClickable && !isFocusable) {
            return super.onTouchEvent(event)
        }

        val isReduceMotion = try {
            JarvisApp.instance.preferences.isReduceMotionEnabled
        } catch (_: Exception) {
            false
        }

        if (isReduceMotion) {
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animateScale(0.98f, 100)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateScale(1.0f, 150)
        }
        return super.onTouchEvent(event)
    }

    private fun animateScale(targetScale: Float, durationMs: Long) {
        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(scaleX, targetScale).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                scaleX = value
                scaleY = value
            }
            start()
        }
    }

    fun setGlassCornerRadius(radiusPx: Float) {
        cornerRadius = radiusPx
        invalidateOutline()
        invalidate()
    }

    fun setGlassSurfaceColor(color: Int) {
        surfaceColor = color
        surfacePaint.color = color
        invalidate()
    }

    fun setGlassBorderColor(color: Int) {
        borderColor = color
        borderPaint.color = color
        invalidate()
    }

    fun setGlassRimLightColor(color: Int) {
        rimLightColor = color
        if (height > 0) {
            rimPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat() * 0.4f,
                rimLightColor,
                0x00FFFFFF and rimLightColor,
                Shader.TileMode.CLAMP
            )
        }
        invalidate()
    }
}
