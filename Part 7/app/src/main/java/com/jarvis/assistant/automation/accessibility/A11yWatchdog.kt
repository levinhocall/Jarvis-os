package com.jarvis.assistant.automation.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.accessibility.AccessibilityFixActivity
import java.util.concurrent.TimeUnit

/**
 * Watchdog for detecting "Enabled in Settings but disconnected/stale binder" conditions.
 *
 * Responsibilities:
 * 1. Checks on app foreground and system broadcasts (BOOT_COMPLETED, MY_PACKAGE_REPLACED).
 * 2. Emits a rate-limited low-priority notification (max 1 per 6 hours) with a deep link to AccessibilityFixActivity.
 * 3. Optional opt-in Auto-Repair (default OFF): Toggles our own component in ENABLED_ACCESSIBILITY_SERVICES
 *    if the user has granted WRITE_SECURE_SETTINGS via ADB.
 */
object A11yWatchdog {

    private const val TAG = "A11yWatchdog"
    private const val NOTIFICATION_CHANNEL_ID = "jarvis_a11y_recovery"
    private const val NOTIFICATION_ID = 9042
    private val SIX_HOURS_MS = TimeUnit.HOURS.toMillis(6)
    private val ONE_HOUR_MS = TimeUnit.HOURS.toMillis(1)
    private const val MAX_REBIND_ATTEMPTS_PER_HOUR = 3

    @Volatile
    var lastNotificationTimestamp: Long = 0L

    @Volatile
    var rebindAttemptsInLastHour: Int = 0
    @Volatile
    var lastRebindResetHour: Long = 0L

    /**
     * Pure watchdog decision function for unit testability without Android framework mocks.
     */
    fun shouldNotifyUser(
        settingsEnabled: Boolean,
        instancePresent: Boolean,
        nowMs: Long,
        lastNotifiedMs: Long,
        uptimeMs: Long,
        minGracePeriodMs: Long = 5000L
    ): Boolean {
        if (!settingsEnabled || instancePresent) return false
        if (uptimeMs < minGracePeriodMs) return false
        return (nowMs - lastNotifiedMs) >= SIX_HOURS_MS
    }

    /**
     * Pure auto-repair permission and rate-limit guard.
     */
    fun shouldAttemptAutoRebind(
        hasWriteSecureSettingsPermission: Boolean,
        isAutoRepairPrefEnabled: Boolean,
        truthfulStatus: TruthfulA11yStatus,
        rebindCountThisHour: Int
    ): Boolean {
        if (!hasWriteSecureSettingsPermission) return false
        if (!isAutoRepairPrefEnabled) return false
        if (truthfulStatus != TruthfulA11yStatus.ENABLED_NOT_CONNECTED) return false
        return rebindCountThisHour < MAX_REBIND_ATTEMPTS_PER_HOUR
    }

    /**
     * Pure component rebind string transformer.
     * Toggles ONLY targetComponent in and out of the colon-separated string.
     */
    fun removeComponentFromSettings(currentSettings: String, targetComponent: String): String {
        return currentSettings.split(":")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != targetComponent }
            .joinToString(":")
    }

    fun addComponentToSettings(currentSettings: String, targetComponent: String): String {
        val cleaned = removeComponentFromSettings(currentSettings, targetComponent)
        return if (cleaned.isBlank()) targetComponent else "$cleaned:$targetComponent"
    }

    fun inspectAndRecover(context: Context, isAppForeground: Boolean = true) {
        val now = System.currentTimeMillis()
        val settingsEnabled = AccessibilityHealthModel.isServiceEnabledInSettings(context)
        val instancePresent = JarvisAccessibilityService.instance != null

        if (!settingsEnabled || instancePresent) {
            return
        }

        AccessibilityBreadcrumbs.record("WATCHDOG", "Stale binding detected: settings=ENABLED, instance=NULL")

        // 1. Check optional auto-rebind
        val hasWritePerm = context.checkCallingOrSelfPermission(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val prefs = com.jarvis.assistant.data.preferences.AppPreferences(context)
        val isAutoRepairEnabled = prefs.isA11yAutoRepairEnabled

        val status = AccessibilityDoctor.buildReport(context).truthfulStatus

        checkRebindHourlyReset(now)

        if (shouldAttemptAutoRebind(hasWritePerm, isAutoRepairEnabled, status, rebindAttemptsInLastHour)) {
            performAutoRebind(context)
            return
        }

        // 2. Post notification if rate limit allows
        if (shouldNotifyUser(settingsEnabled, instancePresent, now, lastNotificationTimestamp, 10000L)) {
            lastNotificationTimestamp = now
            postRecoveryNotification(context)
        }
    }

    private fun checkRebindHourlyReset(now: Long) {
        if (now - lastRebindResetHour >= ONE_HOUR_MS) {
            rebindAttemptsInLastHour = 0
            lastRebindResetHour = now
        }
    }

    private fun performAutoRebind(context: Context) {
        rebindAttemptsInLastHour++
        val cr = context.contentResolver
        val targetComponent = ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
        try {
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val withoutOurs = removeComponentFromSettings(current, targetComponent)
            val withOurs = addComponentToSettings(current, targetComponent)

            AccessibilityBreadcrumbs.record("WATCHDOG", "Executing auto-rebind attempt $rebindAttemptsInLastHour of $MAX_REBIND_ATTEMPTS_PER_HOUR")

            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withoutOurs)
            Thread.sleep(1500L)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withOurs)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

            AccessibilityBreadcrumbs.record("WATCHDOG", "Auto-rebind settings update complete")
        } catch (t: Throwable) {
            AccessibilityBreadcrumbs.recordException(t, "auto_rebind_failed")
            Log.w(TAG, "Auto-rebind failed: ${t.message}")
        }
    }

    private fun postRecoveryNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Accessibility Recovery",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Guidance when Accessibility Service needs a quick restart"
            }
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, AccessibilityFixActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_settings_shield)
            .setContentTitle("Accessibility needs a quick restart")
            .setContentText("Service is enabled in Settings but disconnected. Tap for guided fix.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    class BootAndPackageReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            AccessibilityBreadcrumbs.record("RECEIVER", "Broadcast received: $action")
            if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                inspectAndRecover(context, isAppForeground = false)
            }
        }
    }
}
