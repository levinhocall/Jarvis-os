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

    companion object {
        private const val TAG = "JarvisNotification"
        
        @Volatile
        var instance: JarvisNotificationListenerService? = null
            private set

        private val recentNotifications = ConcurrentHashMap<String, CachedNotification>()
        private val notificationOrder = ConcurrentLinkedDeque<String>()
        private const val MAX_CACHE_SIZE = 50

        fun isConnected(): Boolean = instance != null

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
                Log.i(TAG, "Silently sent WhatsApp reply to '${target.sender}': '$replyMessage'")
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
            sender = title,
            message = text,
            timestamp = System.currentTimeMillis(),
            replyAction = replyAction,
            remoteInput = replyRemoteInput
        )

        recentNotifications[sbn.key] = cached
        notificationOrder.add(sbn.key)

        // Process financial/UPI transactions into Expense Ledger
        com.jarvis.assistant.automation.ledger.ExpenseLedgerTracker.processNotification(
            packageName = pkg,
            title = title,
            text = text,
            context = applicationContext
        )

        // Proactive Headset Whisper for incoming WhatsApp messages
        if (pkg == "com.whatsapp" && title.isNotBlank() && text.isNotBlank()) {
            com.jarvis.assistant.audio.HeadsetWhisperManager.whisperNotification(title, text, applicationContext)
        }

        // Evict older entries
        while (notificationOrder.size > MAX_CACHE_SIZE) {
            val oldest = notificationOrder.poll() ?: break
            recentNotifications.remove(oldest)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        recentNotifications.remove(sbn.key)
        notificationOrder.remove(sbn.key)
    }
}
