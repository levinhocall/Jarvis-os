package com.jarvis.assistant.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

/**
 * Premium pill-shaped call-to-action button with warm marigold gradient,
 * 48dp minimum touch target, light haptic tap, and 0.96 press scale.
 *
 * Honors the system and preference reduce_motion setting.
 */
class PrimaryPillButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var buttonText: String = ""
    private var textColorInt: Int = ContextCompat.getColor(context, R.color.accent_pill_text)
    private var startColorInt: Int = ContextCompat.getColor(context, R.color.accent_pill_start)
    private var endColorInt: Int = ContextCompat.getColor(context, R.color.accent_pill_end)
    private var iconResId: Int = 0
    private var enableHaptics: Boolean = true

    private val containerLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
    }

    private val iconView = ImageView(context).apply {
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.spacing_lg),
            resources.getDimensionPixelSize(R.dimen.spacing_lg)
        ).apply {
            marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_sm)
        }
    }

    private val labelView = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(textColorInt)
        // Try applying Manrope or bold styling
        try {
            val manropeFont = ResourcesCompat.getFont(context, R.font.manrope)
            typeface = manropeFont
        } catch (_: Exception) {
            paint.isFakeBoldText = true
        }
    }

    private var scaleAnimator: ValueAnimator? = null

    init {
        val minTarget = resources.getDimensionPixelSize(R.dimen.min_touch_target)
        minimumHeight = minTarget
        minimumWidth = minTarget

        val padHorizontal = resources.getDimensionPixelSize(R.dimen.spacing_lg)
        val padVertical = resources.getDimensionPixelSize(R.dimen.spacing_md)
        setPadding(padHorizontal, padVertical, padHorizontal, padVertical)

        isClickable = true
        isFocusable = true

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.PrimaryPillButton, defStyleAttr, 0)
            try {
                buttonText = a.getString(R.styleable.PrimaryPillButton_pillText) ?: ""
                textColorInt = a.getColor(R.styleable.PrimaryPillButton_pillTextColor, textColorInt)
                startColorInt = a.getColor(R.styleable.PrimaryPillButton_pillStartColor, startColorInt)
                endColorInt = a.getColor(R.styleable.PrimaryPillButton_pillEndColor, endColorInt)
                iconResId = a.getResourceId(R.styleable.PrimaryPillButton_pillIcon, 0)
                enableHaptics = a.getBoolean(R.styleable.PrimaryPillButton_pillHaptic, true)
            } finally {
                a.recycle()
            }
        }

        containerLayout.addView(iconView)
        containerLayout.addView(labelView)
        addView(containerLayout)

        updateAppearance()
    }

    private fun updateAppearance() {
        labelView.text = buttonText
        labelView.setTextColor(textColorInt)

        if (iconResId != 0) {
            iconView.setImageResource(iconResId)
            iconView.imageTintList = ColorStateList.valueOf(textColorInt)
            iconView.visibility = View.VISIBLE
        } else {
            iconView.visibility = View.GONE
        }

        // Pill shape background with marigold gradient and ripple
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(startColorInt, endColorInt)
        ).apply {
            cornerRadius = 999f
        }

        val mask = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.WHITE)
        }

        val rippleColor = ColorStateList.valueOf(Color.argb(50, 255, 255, 255))
        background = RippleDrawable(rippleColor, gradient, mask)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)

        val isReduceMotion = try {
            JarvisApp.instance.preferences.isReduceMotionEnabled
        } catch (_: Exception) {
            false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (enableHaptics) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                if (!isReduceMotion) {
                    animateScale(0.96f, 100)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isReduceMotion) {
                    animateScale(1.0f, 150)
                }
            }
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

    var text: String
        get() = buttonText
        set(value) {
            buttonText = value
            labelView.text = value
            contentDescription = value
        }

    fun setIconResource(resId: Int) {
        iconResId = resId
        updateAppearance()
    }

    fun setColors(startColor: Int, endColor: Int, textColor: Int = textColorInt) {
        startColorInt = startColor
        endColorInt = endColor
        textColorInt = textColor
        updateAppearance()
    }
}
