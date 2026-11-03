package com.jarvis.assistant.automation.notification

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class CachedNotification(
    val key: String,
    val packageName: String,
    val sender: String,
    val message: String,
    val timestamp: Long,
    val replyAction: Notification.Action? = null,
    val remoteInput: RemoteInput? = null
)

class JarvisNotificationListenerService : NotificationListenerService() {
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "JarvisNotification"
        
        @Volatile
        var instance: JarvisNotificationListenerService? = null
            private set

        private val recentNotifications = ConcurrentHashMap<String, CachedNotification>()
        private val notificationOrder = ConcurrentLinkedDeque<String>()
        private const val MAX_CACHE_SIZE = 50

        fun isConnected(): Boolean = instance != null

        /** Returns a read-only snapshot of the bounded in-memory notification cache. */
        fun getRecentNotifications(): List<CachedNotification> =
            notificationOrder.mapNotNull { key -> recentNotifications[key] }
                .sortedByDescending { it.timestamp }

        fun getUnreadWhatsAppCount(): Int {
            return recentNotifications.values.count { it.packageName == "com.whatsapp" }
        }

        fun getUnreadWhatsAppSummary(): String {
            val waMessages = recentNotifications.values
                .filter { it.packageName == "com.whatsapp" }
                .sortedByDescending { it.timestamp }
                .take(5)

            if (waMessages.isEmpty()) {
                return "Abhi WhatsApp par koi naye unread messages nahi hain, Nazib Sir."
            }

            val summaryList = waMessages.joinToString("; ") { "${it.sender} ne kaha: '${it.message}'" }
            return "WhatsApp par ${waMessages.size} unread messages hain: $summaryList"
        }

        fun sendSilentReply(contactName: String, replyMessage: String, context: Context): Boolean {
            val target = recentNotifications.values
                .filter { it.packageName == "com.whatsapp" && it.replyAction != null && it.remoteInput != null }
                .sortedByDescending { it.timestamp }
                .firstOrNull { it.sender.contains(contactName, ignoreCase = true) }

            if (target == null || target.replyAction == null || target.remoteInput == null) {
                Log.w(TAG, "No matching WhatsApp notification with reply action found for '$contactName'")
                return false
            }

            return try {
                val action = target.replyAction
                val remoteInput = target.remoteInput
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(remoteInput.resultKey, replyMessage)
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
                action.actionIntent.send(context, 0, intent)
                Log.i(TAG, "Sent a notification quick reply; message content and sender were not logged.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send silent reply: ${e.message}", e)
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        persistenceScope.launch(Dispatchers.IO) {
            if (!com.jarvis.assistant.data.preferences.AppPreferences(applicationContext).notificationHistoryEnabled) return@launch
            runCatching {
                val dao = com.jarvis.assistant.data.memory.MemoryDatabase.getInstance(applicationContext).notificationHistoryDao()
                dao.getAllForPrivacyAudit().forEach { item ->
                    if (NotificationPrivacyFilter.sanitize(item.sender, item.message).containsCredential) {
                        dao.delete(item.id)
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Notification privacy audit could not complete (${error.javaClass.simpleName}).")
            }
        }
        Log.i(TAG, "JarvisNotificationListenerService connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        Log.i(TAG, "JarvisNotificationListenerService disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val pkg = sbn.packageName ?: ""
        // Capture title (sender) and text (message)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) return
        val sanitized = NotificationPrivacyFilter.sanitize(title, text)
        val safeTitle = sanitized.sender
        val safeText = sanitized.message

        // Extract direct-reply action if available
        var replyAction: Notification.Action? = null
        var replyRemoteInput: RemoteInput? = null

        val actions = notif.actions
        if (actions != null) {
            for (action in actions) {
                val inputs = action.remoteInputs
                if (inputs != null && inputs.isNotEmpty()) {
                    replyAction = action
                    replyRemoteInput = inputs.firstOrNull()
                    break
                }
            }
        }

        val cached = CachedNotification(
            key = sbn.key,
            packageName = pkg,
            sender = safeTitle,
            message = safeText,
            timestamp = System.currentTimeMillis(),
            replyAction = if (sanitized.containsCredential) null else replyAction,
            remoteInput = if (sanitized.containsCredential) null else replyRemoteInput
        )

        recentNotifications[sbn.key] = cached

        // Notification text is sensitive: persist only after an explicit in-app opt-in.
        val archiveRelevant = NotificationArchivePolicy.keep(pkg, packageName,
            (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0, title.isNotBlank() || text.isNotBlank())
        if (archiveRelevant && !sanitized.containsCredential && com.jarvis.assistant.data.preferences.AppPreferences(applicationContext).notificationHistoryEnabled) {
            persistenceScope.launch {
                // Re-check opt-in on the IO dispatcher: the user may disable history
                // after the notification callback but before this queued write runs.
                if (!com.jarvis.assistant.data.preferences.AppPreferences(applicationContext).notificationHistoryEnabled) {
                    return@launch
                }
                runCatching {
                    val database = com.jarvis.assistant.data.memory.MemoryDatabase.getInstance(applicationContext)
                    database.notificationHistoryDao().insert(
                        com.jarvis.assistant.data.notification.NotificationHistoryEntity(
                            id = sbn.key,
                            packageName = pkg,
                            sender = safeTitle,
                            message = safeText,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    database.notificationHistoryDao().deleteOlderThan(
                        System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Notification history persistence failed (${error.javaClass.simpleName}).")
                }
            }
        }

        notificationOrder.remove(sbn.key)
        notificationOrder.add(sbn.key)

        // Process financial/UPI transactions into Expense Ledger
        if (!sanitized.containsCredential) {
            com.jarvis.assistant.automation.ledger.ExpenseLedgerTracker.processNotification(
                packageName = pkg,
                title = safeTitle,
                text = safeText,
                context = applicationContext
            )
        }

        // Explicitly configured notification-keyword triggers are evaluated in memory only.
        if (!sanitized.containsCredential) {
            (applicationContext as? com.jarvis.assistant.JarvisApp)?.triggerRepository
                ?.onNotificationPosted(pkg, safeTitle, safeText)
        }

        // Proactive Headset Whisper for incoming WhatsApp messages
        if (!sanitized.containsCredential && pkg == "com.whatsapp" && safeTitle.isNotBlank() && safeText.isNotBlank()) {
            com.jarvis.assistant.audio.HeadsetWhisperManager.whisperNotification(safeTitle, safeText, applicationContext)
        }

        // Evict older entries
        while (notificationOrder.size > MAX_CACHE_SIZE) {
            val oldest = notificationOrder.poll() ?: break
            recentNotifications.remove(oldest)
        }
    }

    override fun onDestroy() {
        persistenceScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        recentNotifications.remove(sbn.key)
        notificationOrder.remove(sbn.key)
    }
}
