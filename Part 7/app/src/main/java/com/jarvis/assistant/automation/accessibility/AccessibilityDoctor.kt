package com.jarvis.assistant.automation.accessibility

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.util.PermissionHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ExitReasonInfo(
    val timestampMs: Long,
    val reasonCode: Int,
    val reasonName: String,
    val description: String?
)

data class FullAccessibilityDoctorReport(
    val buildStamp: String,
    val timestamp: String,
    val deviceModel: String,
    val sdkInt: Int,
    val isUserUnlocked: Boolean,
    val isRestrictedSettingsActive: Boolean,
    val isBatteryOptimizationsIgnored: Boolean,
    val masterSwitchEnabled: Boolean,
    val settingsStringContainsComponent: Boolean,
    val accessibilityManagerEnabled: Boolean,
    val accessibilityManagerListContainsComponent: Boolean,
    val instancePresent: Boolean,
    val healthState: String,
    val totalEventsReceived: Long,
    val lastEventAgeSec: Long,
    val liveProbeResult: LiveProbeResult,
    val liveProbeDetail: String,
    val truthfulStatus: TruthfulA11yStatus,
    val lastError: String?,
    val recentExitReasons: List<ExitReasonInfo>,
    val recentBreadcrumbs: List<String>
)

object AccessibilityDoctor {

    private val processStartTime = System.currentTimeMillis()

    fun performLiveProbe(): Pair<LiveProbeResult, String> {
        val s = JarvisAccessibilityService.instance ?: return Pair(
            LiveProbeResult.NO_INSTANCE,
            "Service instance is null (binder not connected)"
        )

        return try {
            val root = s.rootInActiveWindow
            val windows = try { s.windows } catch (_: Throwable) { emptyList() }
            if (root != null) {
                Pair(
                    LiveProbeResult.SUCCESS,
                    "Live probe SUCCESS: root node '${root.className}' (windowId=${root.windowId}, childCount=${root.childCount})"
                )
            } else if (windows.isNotEmpty()) {
                Pair(
                    LiveProbeResult.SUCCESS,
                    "Live probe SUCCESS: ${windows.size} active window(s) accessible"
                )
            } else {
                Pair(
                    LiveProbeResult.NULL_ROOT,
                    "Live probe returned null root & 0 windows (display may be asleep or transitioning)"
                )
            }
        } catch (e: SecurityException) {
            Pair(LiveProbeResult.EXCEPTION, "SecurityException: ${e.message}")
        } catch (t: Throwable) {
            Pair(LiveProbeResult.EXCEPTION, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun buildReport(context: Context): FullAccessibilityDoctorReport {
        val (probeResult, probeDetail) = performLiveProbe()

        val masterEnabled = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }

        val settingsEnabled = AccessibilityHealthModel.isServiceEnabledInSettings(context)

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val amEnabled = am?.isEnabled == true
        val amListContains = am?.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )?.any { it.id.contains("JarvisAccessibilityService") || it.id.contains(context.packageName) } == true

        val instancePresent = JarvisAccessibilityService.instance != null
        val summary = AccessibilityHealthModel.getOperationalStatusSummary()
        val eventAgeSec = if (summary.lastEventReceivedAgeMs > 0) summary.lastEventReceivedAgeMs / 1000L else -1L

        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val isUnlocked = userManager?.isUserUnlocked ?: true

        val restrictedSettings = PermissionHelper.isRestrictedSettingsActive(context)
        val batteryIgnored = PermissionHelper.hasBatteryOptimizationPermission(context)

        val exitReasons = getHistoricalExitReasons(context)
        val recentCrashes = exitReasons.count { it.reasonCode == 4 /* REASON_CRASH */ || it.reasonCode == 5 /* REASON_CRASH_NATIVE */ }

        val truthfulStatus = A11yStatusDeriver.deriveStatus(
            settingsEnabled = settingsEnabled,
            instancePresent = instancePresent,
            probeResult = probeResult,
            msSinceEnabledSeen = System.currentTimeMillis() - processStartTime,
            isConnectedReceivingEvents = summary.isReceivingEvents,
            isDegraded = summary.state == AccessibilityState.DEGRADED,
            consecutiveCrashCount = recentCrashes
        )

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val buildStamp = "SHA: ${BuildConfig.BUILD_GIT_SHA} | Built: ${BuildConfig.BUILD_TIMESTAMP}"

        return FullAccessibilityDoctorReport(
            buildStamp = buildStamp,
            timestamp = sdf.format(Date()),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdkInt = Build.VERSION.SDK_INT,
            isUserUnlocked = isUnlocked,
            isRestrictedSettingsActive = restrictedSettings,
            isBatteryOptimizationsIgnored = batteryIgnored,
            masterSwitchEnabled = masterEnabled,
            settingsStringContainsComponent = settingsEnabled,
            accessibilityManagerEnabled = amEnabled,
            accessibilityManagerListContainsComponent = amListContains,
            instancePresent = instancePresent,
            healthState = summary.state.name,
            totalEventsReceived = AccessibilityHealthModel.totalEventsReceived.value,
            lastEventAgeSec = eventAgeSec,
            liveProbeResult = probeResult,
            liveProbeDetail = probeDetail,
            truthfulStatus = truthfulStatus,
            lastError = summary.lastError,
            recentExitReasons = exitReasons,
            recentBreadcrumbs = AccessibilityBreadcrumbs.getBreadcrumbs().takeLast(30)
        )
    }

    fun generateCopyableReport(context: Context): String {
        val rep = buildReport(context)
        return buildString {
            appendLine("=== JARVIS ACCESSIBILITY DOCTOR REPORT ===")
            appendLine("Build Stamp: ${rep.buildStamp}")
            appendLine("Report Timestamp: ${rep.timestamp}")
            appendLine("Device: ${rep.deviceModel} (SDK ${rep.sdkInt})")
            appendLine("User Unlocked: ${rep.isUserUnlocked}")
            appendLine("Restricted Settings: ${if (rep.isRestrictedSettingsActive) "BLOCKED" else "OK"}")
            appendLine("Battery Optimization: ${if (rep.isBatteryOptimizationsIgnored) "UNRESTRICTED" else "OPTIMIZED (May be killed)"}")
            appendLine()
            appendLine("--- System & Binder Status ---")
            appendLine("Master Switch (ACCESSIBILITY_ENABLED): ${rep.masterSwitchEnabled}")
            appendLine("Settings Component List: ${if (rep.settingsStringContainsComponent) "LISTED" else "NOT_LISTED"}")
            appendLine("AccessibilityManager Enabled: ${rep.accessibilityManagerEnabled}")
            appendLine("AccessibilityManager List Matched: ${rep.accessibilityManagerListContainsComponent}")
            appendLine("Service Instance: ${if (rep.instancePresent) "PRESENT" else "NULL (Not bound)"}")
            appendLine("Health Model State: ${rep.healthState}")
            appendLine("Total Events Received: ${rep.totalEventsReceived}")
            appendLine("Last Event Age: ${if (rep.lastEventAgeSec >= 0) "${rep.lastEventAgeSec}s ago" else "Never"}")
            appendLine()
            appendLine("--- Live Window Probe ---")
            appendLine("Probe Result: ${rep.liveProbeResult}")
            appendLine("Probe Detail: ${rep.liveProbeDetail}")
            appendLine("TRUTHFUL VERDICT: ${rep.truthfulStatus}")
            if (!rep.lastError.isNullOrBlank()) {
                appendLine("Last Recorded Error: ${rep.lastError}")
            }
            appendLine()
            appendLine("--- Historical Process Exit Reasons ---")
            if (rep.recentExitReasons.isEmpty()) {
                appendLine("None recorded or not supported on this Android version")
            } else {
                rep.recentExitReasons.forEach { reason ->
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(reason.timestampMs))
                    appendLine("• $dateStr: ${reason.reasonName} (${reason.description ?: "no desc"})")
                }
            }
            appendLine()
            appendLine("--- Recent Breadcrumbs (Last 30) ---")
            if (rep.recentBreadcrumbs.isEmpty()) {
                appendLine("No breadcrumbs recorded yet")
            } else {
                rep.recentBreadcrumbs.forEach { appendLine(it) }
            }
            appendLine("=== END REPORT ===")
        }
    }

    private fun getHistoricalExitReasons(context: Context): List<ExitReasonInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            reasons.map { exitInfo ->
                val name = when (exitInfo.reason) {
                    1 -> "REASON_EXIT_SELF"
                    2 -> "REASON_SIGNALED"
                    3 -> "REASON_LOW_MEMORY"
                    4 -> "REASON_CRASH"
                    5 -> "REASON_CRASH_NATIVE"
                    6 -> "REASON_ANR"
                    7 -> "REASON_INITIALIZATION_FAILURE"
                    8 -> "REASON_PERMISSION_CHANGE"
                    9 -> "REASON_EXCESSIVE_RESOURCE_USAGE"
                    10 -> "REASON_USER_REQUESTED"
                    11 -> "REASON_USER_STOPPED"
                    12 -> "REASON_DEPENDENCY_DIED"
                    13 -> "REASON_OTHER"
                    14 -> "REASON_FREEZER"
                    else -> "REASON_UNKNOWN (${exitInfo.reason})"
                }
                ExitReasonInfo(
                    timestampMs = exitInfo.timestamp,
                    reasonCode = exitInfo.reason,
                    reasonName = name,
                    description = exitInfo.description
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
