package com.jarvis.assistant.automation.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Repairs the "Accessibility is ON in Settings but the service is not connected" state.
 *
 * Android keeps the service listed as enabled while its process was killed, crashed or was
 * never re-bound (typical after reboot, app update or an aggressive OEM task killer). In that
 * state `JarvisAccessibilityService.instance` stays null and every UI automation reports
 * "not working" even though the toggle looks ON.
 *
 * With the one-time, user-granted `WRITE_SECURE_SETTINGS` permission the app can force
 * system_server to rebind the service (and even re-enable it) by itself, with no taps:
 *
 *     adb shell pm grant com.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS
 *
 * Without that permission nothing here changes system state; callers fall back to the
 * existing guidance in [AccessibilityHealthModel.getRecoveryGuidance].
 */
object AccessibilitySelfHeal {

    private const val TAG = "A11ySelfHeal"
    private const val MIN_INTERVAL_MS = 20_000L
    private const val REBIND_GAP_MS = 600L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastAttemptAt = 0L

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED

    private fun componentFlat(context: Context): String =
        ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()

    private fun readEnabledList(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun writeEnabledList(context: Context, list: List<String>): Boolean = try {
        val cr = context.contentResolver
        val ok = Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        if (list.isNotEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        ok
    } catch (t: Throwable) {
        Log.w(TAG, "Could not write accessibility settings: ${t.message}")
        false
    }

    /** Turns the service on without any user action. Needs WRITE_SECURE_SETTINGS. */
    fun enableSelf(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        val flat = componentFlat(context)
        val current = readEnabledList(context)
        if (current.any { it.equals(flat, ignoreCase = true) }) return true
        return writeEnabledList(context, current + flat)
    }

    /**
     * If the service is enabled in Settings but not connected, force a rebind.
     * Rate limited, safe to call from anywhere and from any thread.
     *
     * @return true when a heal was started.
     */
    fun healIfNeeded(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext
        if (JarvisAccessibilityService.instance != null) return false
        if (!canWriteSecureSettings(app)) return false

        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < MIN_INTERVAL_MS) return false
        lastAttemptAt = now

        val flat = componentFlat(app)
        val current = readEnabledList(app)
        val listed = current.any { it.equals(flat, ignoreCase = true) }

        if (!listed) {
            // User turned it off or the system dropped it after a crash: switch it back on.
            Log.i(TAG, "Service not listed as enabled; enabling it")
            return writeEnabledList(app, current + flat)
        }

        // Listed but not connected: remove it, then add it back so system_server rebinds it.
        Log.i(TAG, "Service listed but not connected; forcing rebind")
        val without = current.filterNot { it.equals(flat, ignoreCase = true) }
        if (!writeEnabledList(app, without)) return false
        main.postDelayed({
            try {
                writeEnabledList(app, readEnabledList(app).let { if (it.any { c -> c.equals(flat, ignoreCase = true) }) it else it + flat })
            } catch (t: Throwable) {
                Log.w(TAG, "Re-enable after rebind failed: ${t.message}")
            }
        }, REBIND_GAP_MS)
        AccessibilityHealthModel.updateState(
            AccessibilityState.ENABLED,
            "Service was enabled but not connected; rebinding automatically..."
        )
        return true
    }
}
