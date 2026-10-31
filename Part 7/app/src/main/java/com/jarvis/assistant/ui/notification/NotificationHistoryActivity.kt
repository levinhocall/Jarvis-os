package com.jarvis.assistant.ui.notification

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.memory.MemoryDatabase
import com.jarvis.assistant.data.notification.NotificationHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** User-controlled, encrypted notification history. Persistence is opt-in and disabled by default. */
class NotificationHistoryActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var showUnreadOnly = false
    private var historyJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val prefs = JarvisApp.instance.preferences
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(18))
            setBackgroundColor(com.jarvis.assistant.util.ThemeManager.getThemeBgColor(prefs.appTheme, prefs.customThemeColor))
        }
        root.addView(TextView(this).apply {
            text = "NOTIFICATION HISTORY"
            textSize = 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextPrimary))
        })
        root.addView(TextView(this).apply {
            text = "Notification text can contain private messages. Nothing is saved unless enabled here. History stays in the app's encrypted database and is limited to 30 days."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(14))
            setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextMuted))
        })
        root.addView(Switch(this).apply {
            text = "Save notification history"
            isChecked = prefs.notificationHistoryEnabled
            setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextPrimary))
            setOnCheckedChangeListener { _, enabled ->
                if (enabled) {
                    prefs.notificationHistoryEnabled = true
                    observeHistory()
                } else {
                    MaterialAlertDialogBuilder(this@NotificationHistoryActivity)
                        .setTitle("Disable notification history?")
                        .setMessage("This stops future storage and permanently clears saved notification history.")
                        .setNegativeButton("Keep enabled") { _, _ -> isChecked = true }
                        .setPositiveButton("Disable and clear") { _, _ ->
                            prefs.notificationHistoryEnabled = false
                            lifecycleScope.launch(Dispatchers.IO) {
                                MemoryDatabase.getInstance(applicationContext).notificationHistoryDao().clearAll()
                                withContext(Dispatchers.Main) { renderEmpty("History is off. No notification text is being saved.") }
                            }
                        }.show()
                }
            }
        })
        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextMuted))
        }
        root.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "All"; setOnClickListener { showUnreadOnly = false; observeHistory() } })
        actions.addView(Button(this).apply { text = "Unread"; setOnClickListener { showUnreadOnly = true; observeHistory() } })
        actions.addView(Button(this).apply {
            text = "Mark all read"
            setOnClickListener { lifecycleScope.launch(Dispatchers.IO) { MemoryDatabase.getInstance(applicationContext).notificationHistoryDao().markAllRead() } }
        })
        actions.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@NotificationHistoryActivity)
                    .setTitle("Clear notification history?")
                    .setMessage("This cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) { MemoryDatabase.getInstance(applicationContext).notificationHistoryDao().clearAll() }
                    }.show()
            }
        })
        root.addView(actions)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        observeHistory()
    }

    private fun observeHistory() {
        if (!JarvisApp.instance.preferences.notificationHistoryEnabled) {
            renderEmpty("History is off. Enable the switch above to save future notifications.")
            return
        }
        lifecycleScope.launch {
            val dao = withContext(Dispatchers.IO) { MemoryDatabase.getInstance(applicationContext).notificationHistoryDao() }
            val stream = if (showUnreadOnly) dao.observeUnread() else dao.observeRecent()
            stream.collect { items -> render(items) }
        }
    }

    private fun render(items: List<NotificationHistoryEntity>) {
        content.removeAllViews()
        status.text = items.size.toString() + if (showUnreadOnly) " unread saved item(s)" else " saved item(s)"
        if (items.isEmpty()) {
            renderEmpty(if (showUnreadOnly) "No unread notifications." else "No saved notifications yet.")
            return
        }
        items.forEach { item ->
            val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(item.timestamp))
            val card = TextView(this).apply {
                text = (if (item.isRead) "✓  " else "●  ") + (item.sender.ifBlank { item.packageName }) +
                    "\n" + item.message + "\n" + time + " • " + item.packageName
                textSize = 14f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextPrimary))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        MemoryDatabase.getInstance(applicationContext).notificationHistoryDao().markRead(item.id)
                    }
                }
            }
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun renderEmpty(message: String) {
        if (!::content.isInitialized) return
        content.removeAllViews()
        status.text = message
    }

    private fun resolveColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
