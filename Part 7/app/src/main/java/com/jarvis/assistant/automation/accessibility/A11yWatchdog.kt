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
     * Expands the short component spelling ("pkg/.Cls") to the full one ("pkg/pkg.Cls") so that
     * both spellings that different Android builds/OEMs store in ENABLED_ACCESSIBILITY_SERVICES compare equal.
     * Pure string logic (no Android classes) so it stays unit-testable on the JVM.
     */
    fun normalizeComponent(component: String): String {
        val trimmed = component.trim()
        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.length - 1) return trimmed
        val pkg = trimmed.substring(0, slash)
        val cls = trimmed.substring(slash + 1)
        val fullCls = if (cls.startsWith(".")) pkg + cls else cls
        return "$pkg/$fullCls"
    }

    /**
     * Pure component rebind string transformer.
     * Toggles ONLY targetComponent (in either spelling) in and out of the colon-separated string.
     */
    fun removeComponentFromSettings(currentSettings: String, targetComponent: String): String {
        val normalizedTarget = normalizeComponent(targetComponent)
        return currentSettings.split(":")
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizeComponent(it) != normalizedTarget }
            .joinToString(":")
    }

    fun addComponentToSettings(currentSettings: String, targetComponent: String): String {
        val cleaned = removeComponentFromSettings(currentSettings, targetComponent)
        return if (cleaned.isBlank()) targetComponent else "$cleaned:$targetComponent"
    }

    /** Single background thread: the watchdog may sleep (binding grace, auto-rebind) and must never run on the main thread. */
    internal val watchdogExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "a11y-watchdog").apply { isDaemon = true }
        }

    private val lastRunElapsedMs = java.util.concurrent.atomic.AtomicLong(0L)
    private const val MIN_RUN_INTERVAL_MS = 5_000L
    private const val BINDING_GRACE_MS = 8_000L
    private const val WATCHDOG_PREFS = "a11y_watchdog"
    private const val PREF_LAST_NOTIFY = "last_notification_ms"

    /**
     * Entry point (called on every activity resume). Never blocks the caller: the real work runs on
     * [watchdogExecutor], and runs are throttled to one per [MIN_RUN_INTERVAL_MS].
     */
    fun inspectAndRecover(context: Context, isAppForeground: Boolean = true) {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val previous = lastRunElapsedMs.get()
        if (previous != 0L && nowElapsed - previous < MIN_RUN_INTERVAL_MS) return
        lastRunElapsedMs.set(nowElapsed)
        val appContext = context.applicationContext
        try {
            watchdogExecutor.execute {
                try {
                    inspectAndRecoverBlocking(appContext)
                } catch (t: Throwable) {
                    AccessibilityBreadcrumbs.recordException(t, "watchdog:inspectAndRecover")
                }
            }
        } catch (t: Throwable) {
            AccessibilityBreadcrumbs.recordException(t, "watchdog:submit")
        }
    }

    /** Runs on a background thread only. Safe to sleep here. */
    internal fun inspectAndRecoverBlocking(context: Context) {
        if (!AccessibilityHealthModel.isServiceEnabledInSettings(context) ||
            JarvisAccessibilityService.instance != null
        ) {
            return
        }

        // Right after process start / boot the system may simply not have bound the service yet:
        // wait out a short grace period (using REAL process uptime) and re-check before declaring it stale.
        val uptimeMs = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        if (uptimeMs < BINDING_GRACE_MS) {
            try {
                Thread.sleep(BINDING_GRACE_MS - uptimeMs)
            } catch (_: InterruptedException) {
                return
            }
            if (JarvisAccessibilityService.instance != null) return
        }

        val now = System.currentTimeMillis()
        AccessibilityBreadcrumbs.record("WATCHDOG", "Stale binding detected: settings=ENABLED, instance=NULL")

        // 1. Optional auto-rebind (opt-in, needs WRITE_SECURE_SETTINGS granted via adb)
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

        // 2. Notification, rate-limited across process restarts (persisted, not memory-only)
        val watchdogPrefs = context.getSharedPreferences(WATCHDOG_PREFS, Context.MODE_PRIVATE)
        val lastNotified = maxOf(lastNotificationTimestamp, watchdogPrefs.getLong(PREF_LAST_NOTIFY, 0L))
        val uptimeNow = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        if (shouldNotifyUser(
                settingsEnabled = true,
                instancePresent = false,
                nowMs = now,
                lastNotifiedMs = lastNotified,
                uptimeMs = uptimeNow
            )
        ) {
            lastNotificationTimestamp = now
            watchdogPrefs.edit().putLong(PREF_LAST_NOTIFY, now).apply()
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
                // Keep the receiver alive while the (background) watchdog waits for the system to bind the service.
                val pending = goAsync()
                val appContext = context.applicationContext
                try {
                    watchdogExecutor.execute {
                        try {
                            inspectAndRecoverBlocking(appContext)
                        } catch (t: Throwable) {
                            AccessibilityBreadcrumbs.recordException(t, "watchdog:receiver")
                        } finally {
                            pending.finish()
                        }
                    }
                } catch (t: Throwable) {
                    AccessibilityBreadcrumbs.recordException(t, "watchdog:receiver_submit")
                    pending.finish()
                }
            }
        }
    }
}
