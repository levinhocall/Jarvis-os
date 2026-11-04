package com.jarvis.assistant.ui.notification

import android.os.Bundle
import androidx.recyclerview.widget.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
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
    private val storageErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
        val message = com.jarvis.assistant.core.diagnostics.StorageHealth.record(error)
        runOnUiThread { if (::status.isInitialized) status.text = message }
    }
    private lateinit var content: RecyclerView
    private val notificationAdapter = NotificationAdapter()
    private val query = MutableStateFlow("")
    private lateinit var status: TextView
    private var showUnreadOnly = false
    private var historyJob: Job? = null
    private val pendingCredentialCleanup = mutableSetOf<String>()

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
            text = "Notification text can contain private messages. Nothing is saved unless enabled here. History stays in the app's encrypted database for up to 30 days. OTPs, passwords and other credential notifications are always excluded."
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
                            historyJob?.cancel()
                            lifecycleScope.launch(Dispatchers.IO + storageErrors) {
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
        val actions = android.widget.GridLayout(this).apply { columnCount = 2 }
        actions.addView(Button(this).apply { text = "All"; setOnClickListener { showUnreadOnly = false; observeHistory() } })
        actions.addView(Button(this).apply { text = "Unread"; setOnClickListener { showUnreadOnly = true; observeHistory() } })
        actions.addView(Button(this).apply {
            text = "Mark all read"
            setOnClickListener { lifecycleScope.launch(Dispatchers.IO + storageErrors) { MemoryDatabase.getInstance(applicationContext).notificationHistoryDao().markAllRead() } }
        })
        actions.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@NotificationHistoryActivity)
                    .setTitle("Clear notification history?")
                    .setMessage("This cannot be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO + storageErrors) { MemoryDatabase.getInstance(applicationContext).notificationHistoryDao().clearAll() }
                    }.show()
            }
        })
        for (i in 0 until actions.childCount) {
            actions.getChildAt(i).layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = -2; columnSpec = android.widget.GridLayout.spec(i % 2, 1f)
                rowSpec = android.widget.GridLayout.spec(i / 2)
            }
        }
        root.addView(actions)
        root.addView(Button(this).apply {
            text = "Notification access settings"
            setOnClickListener { startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        })
        root.addView(android.widget.EditText(this).apply {
            hint = "Filter by app, sender or message"; setSingleLine(true)
            setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextPrimary))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query.value = s?.toString().orEmpty() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        })
        content = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@NotificationHistoryActivity); adapter = notificationAdapter }
        root.addView(content, LinearLayout.LayoutParams(-1, dp(380)))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        observeHistory()
    }

    private fun observeHistory() {
        historyJob?.cancel()
        if (!JarvisApp.instance.preferences.notificationHistoryEnabled) {
            renderEmpty("History is off. Enable the switch above to save future notifications.")
            return
        }
        historyJob = lifecycleScope.launch(storageErrors) {
            val dao = withContext(Dispatchers.IO) { MemoryDatabase.getInstance(applicationContext).notificationHistoryDao() }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val stream = if (showUnreadOnly) dao.observeUnread() else dao.observeRecent()
                combine(stream, query) { items, search -> items to search }.collectLatest { (items, search) ->
                    val filtered = withContext(Dispatchers.Default) {
                        items.filter { search.isBlank() || "${it.packageName} ${it.sender} ${it.message}".contains(search, true) }
                    }
                    render(filtered)
                }
            }
        }
    }

    private suspend fun render(items: List<NotificationHistoryEntity>) {
        val audited = withContext(Dispatchers.Default) {
            items.partition { !com.jarvis.assistant.automation.notification.NotificationPrivacyFilter.sanitize(it.sender, it.message).containsCredential }
        }
        notificationAdapter.submitList(audited.first)
        for (item in audited.second) {
            if (pendingCredentialCleanup.add(item.id)) lifecycleScope.launch(Dispatchers.IO + storageErrors) {
                try { MemoryDatabase.getInstance(applicationContext).notificationHistoryDao().delete(item.id) }
                finally { withContext(Dispatchers.Main) { pendingCredentialCleanup.remove(item.id) } }
            }
        }
        status.text = if (audited.first.isEmpty()) "No matching saved notifications." else "${audited.first.size} ${if (showUnreadOnly) "unread" else "saved"} notifications"
    }

    private inner class NotificationAdapter : ListAdapter<NotificationHistoryEntity, NotificationHolder>(object : DiffUtil.ItemCallback<NotificationHistoryEntity>() {
        override fun areItemsTheSame(a: NotificationHistoryEntity, b: NotificationHistoryEntity) = a.id == b.id
        override fun areContentsTheSame(a: NotificationHistoryEntity, b: NotificationHistoryEntity) = a == b
    }) {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int): NotificationHolder = NotificationHolder(TextView(parent.context).apply {
            textSize = 14f; setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(resolveColor(com.jarvis.assistant.R.attr.themeTextPrimary)); isClickable = true; isFocusable = true
        })
        override fun onBindViewHolder(holder: NotificationHolder, position: Int) { holder.bind(getItem(position)) }
    }
    private inner class NotificationHolder(private val text: TextView) : RecyclerView.ViewHolder(text) {
        fun bind(item: NotificationHistoryEntity) {
            val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(item.timestamp))
            text.text = "${if (item.isRead) "✓" else "●"} ${item.sender.ifBlank { item.packageName }}\n${item.message}\n$time · ${item.packageName}"
            text.setOnClickListener { lifecycleScope.launch(Dispatchers.IO + storageErrors) { MemoryDatabase.getInstance(applicationContext).notificationHistoryDao().markRead(item.id) } }
        }
    }

    private fun renderEmpty(message: String) {
        if (!::content.isInitialized) return
        notificationAdapter.submitList(emptyList())
        status.text = message
    }

    private fun resolveColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
