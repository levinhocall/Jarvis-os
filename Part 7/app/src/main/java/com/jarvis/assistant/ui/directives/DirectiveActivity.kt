package com.jarvis.assistant.ui.directives

import android.os.Bundle
import android.widget.*
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/** Shared native shell: no WebView, blocking I/O, or provider initialization on screen entry. */
abstract class DirectiveActivity : AppCompatActivity() {
    protected lateinit var page: LinearLayout
    override fun onCreate(state: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(state)
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(24)) }
        ThemeManager.applyDynamicBackground(page)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        action("‹ Back") { finish() }
    }
    protected fun heading(title: String) = label(title, 24f)
    protected fun label(text: String, size: Float = 14f): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(themeColor(R.attr.themeTextPrimary))
        setPadding(0, dp(8), 0, dp(8)); page.addView(this)
    }
    protected fun input(hint: String, lines: Int = 3): EditText = EditText(this).apply {
        this.hint = hint; minLines = lines; gravity = android.view.Gravity.TOP
        setTextColor(themeColor(R.attr.themeTextPrimary)); setHintTextColor(themeColor(R.attr.themeTextMuted))
        page.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    protected fun action(title: String, listener: (MaterialButton) -> Unit): MaterialButton = MaterialButton(this).apply {
        text = title; isAllCaps = false; minHeight = dp(48)
        setOnClickListener { listener(this) }; page.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    protected fun <T> options(items: List<T>, selected: T, label: (T) -> String = { it.toString() }, change: (T) -> Unit): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@DirectiveActivity, android.R.layout.simple_spinner_dropdown_item, items.map(label))
        setSelection(items.indexOf(selected).coerceAtLeast(0)); minimumHeight = dp(48)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { change(items[position]) }
        }
        page.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    protected fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue(); theme.resolveAttribute(attr, value, true)
        return if (value.resourceId == 0) value.data else ContextCompat.getColor(this, value.resourceId)
    }
    protected fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    protected fun notice(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
