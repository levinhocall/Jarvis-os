package com.jarvis.assistant.ui.directives

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.jarvis.assistant.R

/** One canonical nine-entry registry shared by the main deck and feature hub. */
enum class DirectiveId(val title: String, val description: String, val icon: Int) {
    VOICE("Voice Mode", "Holographic orb", R.drawable.ic_settings_voice),
    LENS("Neural Lens", "Live object scan", R.drawable.ic_screen_share),
    MAP("AMOLED Map", "Vector guide", R.drawable.ic_settings_apps),
    AURA("Aura Control", "Appearance & power", R.drawable.ic_settings_palette),
    NOTIFICATIONS("Notifications", "Alerts & history", R.drawable.ic_settings_book),
    THEME("Home Theme", "System / Light / Dark", R.drawable.ic_settings_palette),
    MISSION("Mission Mode", "UI Agent / Core LLM", R.drawable.ic_settings_apps),
    IDEAS("My Ideas", "Encrypted thought vault", R.drawable.ic_stack_memory),
    TEACHER("English Teacher AI", "Four personal trainers", R.drawable.ic_settings_language)
}

object DirectiveGrid {
    fun create(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        fun color(attr: Int): Int {
            val v = android.util.TypedValue(); context.theme.resolveAttribute(attr, v, true)
            return if (v.resourceId == 0) v.data else ContextCompat.getColor(context, v.resourceId)
        }
        setBackgroundColor(color(R.attr.themeBg))
        addView(TextView(context).apply { text = "MORE DIRECTIVES"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.attr.themeTextPrimary)); setPadding(0, pad, 0, pad) })
        val columns = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        val grid = GridLayout(context).apply { columnCount = columns }
        DirectiveId.values().forEachIndexed { index, directive ->
            val card = MaterialCardView(context).apply {
                radius = pad.toFloat(); cardElevation = 0f; strokeWidth = 1; setStrokeColor(color(R.attr.themeCardBorder))
                setCardBackgroundColor(color(R.attr.themeCardBg)); isClickable = true; isFocusable = true
                minimumHeight = (132 * resources.displayMetrics.density).toInt()
                contentDescription = "${directive.title}: ${directive.description}"
            }
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
            box.addView(ImageView(context).apply { setImageResource(directive.icon); setColorFilter(color(R.attr.themeAccent)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(pad * 2, pad * 2))
            box.addView(TextView(context).apply { text = directive.title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.attr.themeTextPrimary)) })
            val subtitle = TextView(context).apply { text = directive.description; textSize = 11f; setTextColor(color(R.attr.themeTextMuted)) }
            box.addView(subtitle); card.addView(box)
            card.setOnClickListener {
                if (directive == DirectiveId.THEME) {
                    val next = com.jarvis.assistant.util.ThemeManager.cycleHomeTheme(context)
                    subtitle.text = "Selected: ${next.replaceFirstChar { it.uppercase() }}"
                } else open(context, directive)
            }
            grid.addView(card, GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(index % columns, 1f); rowSpec = GridLayout.spec(index / columns)
                width = 0; height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT; setMargins(4, 4, 4, 4)
            })
        }
        addView(grid, LinearLayout.LayoutParams(-1, -2))
    }
    fun open(context: Context, id: DirectiveId) {
        val target: Class<*> = when (id) {
            DirectiveId.VOICE -> VoiceModeActivity::class.java
            DirectiveId.LENS -> com.jarvis.assistant.ui.vision.NeuralLensActivity::class.java
            DirectiveId.MAP -> com.jarvis.assistant.ui.map.AmoledMapActivity::class.java
            DirectiveId.AURA -> com.jarvis.assistant.ui.settings.AuraControlActivity::class.java
            DirectiveId.NOTIFICATIONS -> com.jarvis.assistant.ui.notification.NotificationHistoryActivity::class.java
            DirectiveId.MISSION -> MissionDeskActivity::class.java
            DirectiveId.IDEAS -> IdeasActivity::class.java
            DirectiveId.TEACHER -> EnglishTeacherActivity::class.java
            DirectiveId.THEME -> return
        }
        context.startActivity(Intent(context, target))
    }
}
