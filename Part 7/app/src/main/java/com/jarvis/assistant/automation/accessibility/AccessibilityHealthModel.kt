package com.jarvis.assistant.automation.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/** A read-only projection without a background scope, subscription or polling job. */
@OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
private class HealthField<T>(private val source: StateFlow<AccessibilityHealthSnapshot>, private val select: (AccessibilityHealthSnapshot) -> T) : StateFlow<T> {
    override val value: T get() = select(source.value)
    override val replayCache: List<T> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        var initialized = false
        var previous: T? = null
        return source.collect(object : FlowCollector<AccessibilityHealthSnapshot> {
            override suspend fun emit(value: AccessibilityHealthSnapshot) {
                val next = select(value)
                if (!initialized || previous != next) {
                    initialized = true; previous = next; collector.emit(next)
                }
            }
        })
    }
}

data class AccessibilityDiagnosticReport(
    val permissionEnabled: Boolean, val serviceConnected: Boolean, val eventsReceivedCount: Long,
    val lastEventTimestamp: Long, val isReceivingEvents: Boolean, val lastActionSucceeded: Boolean,
    val lastSuccessfulActionTimestamp: Long, val lastActionName: String?, val lastError: String?,
    val lastErrorTimestamp: Long, val state: AccessibilityState, val isOperational: Boolean,
    val deviceManufacturer: String, val oemAutostartRecommended: Boolean, val diagnosticSummary: String
)

/** Existing application-wide health facade; only real lifecycle callbacks establish ownership. */
object AccessibilityHealthModel {
    private const val TAG = "A11yHealthModel"
    private val health = AccessibilitySessionHealth { android.os.SystemClock.elapsedRealtime() }
    val snapshots = health.snapshots
    val state: StateFlow<AccessibilityState> = HealthField(snapshots) { it.state }
    val lastError: StateFlow<String?> = HealthField(snapshots) { issueText(it.lastIssue) }
    val lastErrorTimestamp: StateFlow<Long> = HealthField(snapshots) { it.lastIssueAt }
    val lastStateChange: StateFlow<Long> = HealthField(snapshots) { it.stateChangedAt }
    val lastEventReceivedTimestamp: StateFlow<Long> = HealthField(snapshots) { it.lastEventAt }
    val totalEventsReceived: StateFlow<Long> = HealthField(snapshots) { it.events }
    val lastGestureDispatchedTimestamp: StateFlow<Long> = HealthField(snapshots) { it.lastGestureAt }
    val lastActionVerifiedTimestamp: StateFlow<Long> = HealthField(snapshots) { it.lastVerifiedAt }
    val lastActionSucceeded: StateFlow<Boolean> = HealthField(snapshots) { it.lastActionAt > 0 && it.consecutiveFailures == 0 }
    val lastSuccessfulActionTimestamp: StateFlow<Long> = HealthField(snapshots) { it.lastActionAt }
    val lastActionName: StateFlow<String?> = HealthField(snapshots) { it.lastAction }
    // Indicates that an event has been observed, not a liveness promise on an idle screen.
    val isReceivingEvents: StateFlow<Boolean> = HealthField(snapshots) { it.lastEventAt > 0 }

    internal fun serviceConnected(): Long = health.connected()
    internal fun serviceDisconnected(token: Long) = health.disconnected(token)
    internal fun serviceConnecting() = health.connecting()
    internal fun owns(token: Long): Boolean = health.owns(token)
    private fun token(): Long = JarvisAccessibilityService.instance?.connectionToken ?: 0
    fun recordEventReceived(owner: Long = token()) = health.event(owner)
    fun recordSnapshot(readable: Boolean, issue: AccessibilityIssue = AccessibilityIssue.NO_ROOT, owner: Long = token()) = health.snapshot(owner, readable, issue)
    fun recordIssue(issue: AccessibilityIssue, owner: Long = token(), fatal: Boolean = false) = health.issue(owner, issue, fatal = fatal)
    fun recordDuration(elapsedMs: Long, owner: Long) = health.duration(owner, elapsedMs)
    internal fun recordRecovery(owner: Long) = health.recovery(owner)
    fun recordQueue(pending: Int, running: Boolean, owner: Long = token()) = health.queued(owner, pending, running)
    fun recordGestureDispatched(success: Boolean, details: String? = null, owner: Long = token()) {
        // Ignore arbitrary callback detail strings; they may contain UI labels or text.
        health.gesture(owner, success)
    }
    fun recordActionSuccess(actionName: String, owner: Long = token()) = health.action(owner, true, category(actionName))
    fun recordActionFailure(actionName: String, error: String, owner: Long = token()) = health.action(owner, false, category(actionName))
    fun recordActionVerified(isVerified: Boolean, details: String? = null, owner: Long = token()) =
        health.action(owner, isVerified, "verified", verified = isVerified)

    /** Compatibility entry point: callers cannot manufacture a live connection through a state enum. */
    fun updateState(newState: AccessibilityState, error: String? = null) {
        when (newState) {
            AccessibilityState.DISABLED -> health.reconcile(false, null)
            AccessibilityState.DISCONNECTED -> health.disconnected(token())
            AccessibilityState.CONNECTING -> health.connecting()
            AccessibilityState.ENABLED_NOT_CONNECTED -> health.reconcile(true, null)
            AccessibilityState.DEGRADED -> health.issue(token(), AccessibilityIssue.INTERRUPTED)
            AccessibilityState.ERROR -> health.issue(token(), AccessibilityIssue.INTERNAL, fatal = true)
            else -> Unit // Only serviceConnected / successful probes / real operations may promote state.
        }
    }

    fun refreshFromSystem(context: Context) {
        val live = JarvisAccessibilityService.instance?.connectionToken
        health.reconcile(isServiceEnabledInSettings(context), live)
    }
    fun isOperational(): Boolean = token().let { live -> health.owns(live) && state.value != AccessibilityState.ERROR }
    fun isConnecting(): Boolean = state.value in setOf(AccessibilityState.ENABLED_NOT_CONNECTED, AccessibilityState.CONNECTING)

    data class AccessibilityStatusSummary(val state: AccessibilityState, val isOperational: Boolean,
        val isReceivingEvents: Boolean, val lastEventReceivedAgeMs: Long, val lastGestureDispatchedAgeMs: Long,
        val lastActionVerifiedAgeMs: Long, val lastError: String?)

    fun getOperationalStatusSummary(): AccessibilityStatusSummary {
        val s = snapshots.value; val now = android.os.SystemClock.elapsedRealtime()
        fun age(time: Long) = if (time > 0) (now - time).coerceAtLeast(0) else -1L
        return AccessibilityStatusSummary(s.state, isOperational(), s.lastEventAt > 0 && age(s.lastEventAt) < 15_000,
            age(s.lastEventAt), age(s.lastGestureAt), age(s.lastVerifiedAt), issueText(s.lastIssue))
    }

    fun getDiagnosticReport(context: Context): AccessibilityDiagnosticReport {
        val s = snapshots.value; val now = android.os.SystemClock.elapsedRealtime()
        val enabled = isServiceEnabledInSettings(context); val connected = JarvisAccessibilityService.instance != null
        val recent = s.lastEventAt > 0 && now - s.lastEventAt < 60_000
        val summary = buildString {
            append("Settings: ").append(if (enabled) "ENABLED" else "DISABLED")
            append("\nService callback connection: ").append(if (connected) "CONNECTED" else "NOT CONNECTED")
            append("\nOperational state: ").append(s.state)
            append("\nEvents: ").append(s.events).append(if (recent) " (recent)" else " (idle; not proof of failure)")
            fun age(label: String, time: Long) { append("\n").append(label).append(if (time > 0) "${(now-time).coerceAtLeast(0)} ms ago" else "not observed") }
            age("Readable snapshot: ", s.lastSnapshotAt); age("Accepted mutation: ", s.lastActionAt)
            age("Verified app outcome: ", s.lastVerifiedAt)
            append("\nPending operations (including running): ").append(s.pending)
            append("\nCurrent operation duration: ").append(if (s.operationStartedAt > 0) (now-s.operationStartedAt).coerceAtLeast(0) else 0).append(" ms")
            append("\nLast completed operation duration: ").append(s.lastOperationDurationMs).append(" ms")
            append("\nRecovery attempts: ").append(s.recoveryAttempts)
            age("Last recovery: ", s.lastRecoveryAt)
            append("\nConsecutive failures: ").append(s.consecutiveFailures)
            append("\nLast category: ").append(s.lastIssue)
            append("\nRecent categories (max 32): ").append(s.recent.takeLast(8).joinToString { it.category.name })
            append("\n").append(getRecoveryGuidance() ?: "Connection is available. Individual actions still require a fresh supported screen and verification.")
        }
        return AccessibilityDiagnosticReport(enabled, connected, s.events, s.lastEventAt, recent,
            s.lastActionAt > 0 && s.consecutiveFailures == 0, s.lastActionAt, s.lastAction, issueText(s.lastIssue),
            s.lastIssueAt, s.state, isOperational(), Build.MANUFACTURER,
            PermissionHelper.isXiaomiDevice() || PermissionHelper.isVivoDevice(), summary)
    }

    fun getRecoveryGuidance(): String? = when (state.value) {
        AccessibilityState.DISABLED -> "Jarvis Accessibility Service is disabled. Enable it in Android Settings → Accessibility → Jarvis."
        AccessibilityState.ENABLED_NOT_CONNECTED, AccessibilityState.CONNECTING -> "Enabled but the service hasn't connected. Recheck Android Accessibility settings. If sideload restrictions apply, allow restricted settings in App info only when you trust this app."
        AccessibilityState.DISCONNECTED -> "Service disconnected. Android must bind it again; re-enable it manually in Accessibility Settings if needed."
        AccessibilityState.ERROR -> "Service operation failed. Cancel the task and refresh the screen. If still disconnected, re-enable it manually in Settings."
        AccessibilityState.DEGRADED -> "Connected with limited screen/action availability. Unlock the device, close obstructing dialogs or use a supported screen, then refresh. Protected/custom-rendered content may remain unreadable."
        else -> null
    }
    private fun category(value: String): String = when {
        value.startsWith("click", true) || value.startsWith("tap", true) -> "tap"
        value.startsWith("scroll", true) -> "scroll"
        value.startsWith("type", true) -> "type"
        else -> "ui"
    }
    private fun issueText(issue: AccessibilityIssue): String? = if (issue == AccessibilityIssue.NONE) null else issue.name

    fun isServiceEnabledInSettings(context: Context): Boolean {
        // Check 1: Settings.Secure with master switch
        try {
            val cr = context.contentResolver
            val isMasterEnabled = try {
                Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            } catch (_: Exception) {
                -1 // unknown; try the supported AccessibilityManager fallback
            }

            if (isMasterEnabled == 0) return false
            if (isMasterEnabled == 1) {
                val enabledServices = Settings.Secure.getString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (!enabledServices.isNullOrBlank()) {
                    val expectedComponent = ComponentName(context, JarvisAccessibilityService::class.java)

                    val colonSplitter = TextUtils.SimpleStringSplitter(':')
                    colonSplitter.setString(enabledServices)
                    while (colonSplitter.hasNext()) {
                        val rawComponent = colonSplitter.next().trim()
                        if (rawComponent.isEmpty()) continue

                        // Normalized unflattening (handles both full and short formats)
                        val unflattened = ComponentName.unflattenFromString(rawComponent)
                        if (unflattened != null) {
                            if (unflattened == expectedComponent) {
                                return true
                            }
                        }
                    }
                    return false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking accessibility secure settings: ${e.javaClass.simpleName}")
        }

        // Check 2: AccessibilityManager IPC fallback (checks both generic and all masks)
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val checkMasks = intArrayOf(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                    AccessibilityServiceInfo.FEEDBACK_GENERIC
                )
                for (mask in checkMasks) {
                    val services = am.getEnabledAccessibilityServiceList(mask)
                    if (!services.isNullOrEmpty()) {
                        val matched = services.any { info ->
                            val sInfo = info.resolveInfo?.serviceInfo
                            sInfo?.packageName == context.packageName &&
                            sInfo.name == JarvisAccessibilityService::class.java.name
                        }
                        if (matched) return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking AccessibilityManager service list: ${e.javaClass.simpleName}")
        }

        return false
    }
}
