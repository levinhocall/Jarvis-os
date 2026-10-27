package com.jarvis.assistant.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.R

/**
 * Premium 3D glass input bar with translucent glass background, subtle border,
 * text input field, and action button.
 */
class GlassInputBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val inputEditText = EditText(context).apply {
        background = null
        textSize = 15f
        setHintTextColor(ContextCompat.getColor(context, R.color.glass_text_muted))
        setTextColor(ContextCompat.getColor(context, R.color.glass_text_primary))
        hint = context.getString(android.R.string.search_go)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = resources.getDimensionPixelSize(R.dimen.spacing_base)
            marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_sm)
        }
        try {
            val manropeFont = ResourcesCompat.getFont(context, R.font.manrope)
            typeface = manropeFont
        } catch (_: Exception) {}
    }

    private val actionButton = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.min_touch_target),
            resources.getDimensionPixelSize(R.dimen.min_touch_target)
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_xs)
        }
        setPadding(
            resources.getDimensionPixelSize(R.dimen.spacing_md),
            resources.getDimensionPixelSize(R.dimen.spacing_md),
            resources.getDimensionPixelSize(R.dimen.spacing_md),
            resources.getDimensionPixelSize(R.dimen.spacing_md)
        )
        setImageResource(android.R.drawable.ic_menu_send)
        imageTintList = ContextCompat.getColorStateList(context, R.color.lia_accent)
        contentDescription = "Send"
        isClickable = true
        isFocusable = true
    }

    private val contentContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private var onSendAction: ((String) -> Unit)? = null

    init {
        minimumHeight = resources.getDimensionPixelSize(R.dimen.min_touch_target)

        val glassRadius = resources.getDimension(R.dimen.card_radius_lg)
        val glassBg = GradientDrawable().apply {
            cornerRadius = glassRadius
            setColor(ContextCompat.getColor(context, R.color.glass_surface))
            setStroke(
                resources.getDimensionPixelSize(R.dimen.glass_border_width),
                ContextCompat.getColor(context, R.color.glass_border)
            )
        }
        background = glassBg
        elevation = resources.getDimension(R.dimen.glass_elevation)

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.GlassInputBar, defStyleAttr, 0)
            try {
                val hint = a.getString(R.styleable.GlassInputBar_inputHint)
                if (!hint.isNullOrEmpty()) {
                    inputEditText.hint = hint
                }
            } finally {
                a.recycle()
            }
        }

        contentContainer.addView(inputEditText)
        contentContainer.addView(actionButton)
        addView(contentContainer)

        actionButton.setOnClickListener {
            val query = inputEditText.text.toString().trim()
            if (query.isNotEmpty()) {
                onSendAction?.invoke(query)
            }
        }
    }

    var text: String
        get() = inputEditText.text.toString()
        set(value) {
            inputEditText.setText(value)
            inputEditText.setSelection(value.length)
        }

    fun setHint(hintText: String) {
        inputEditText.hint = hintText
    }

    fun clearText() {
        inputEditText.text.clear()
    }

    fun setOnSendClickListener(listener: (String) -> Unit) {
        onSendAction = listener
    }

    val editText: EditText
        get() = inputEditText

    val sendButton: View
        get() = actionButton
}
