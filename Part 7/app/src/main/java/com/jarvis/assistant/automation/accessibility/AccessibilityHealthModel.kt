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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lifecycle-aware health model for the Jarvis Accessibility Service.
 *
 * Tracks the service through granular states beyond the legacy binary
 * `isRunning()` / `isEnabled()` checks, enabling callers to distinguish
 * between "enabled in settings but not yet connected", "connected and
 * fully operational", "connected but degraded", and error/disconnected
 * conditions.
 */
enum class AccessibilityState {
    /** Not enabled in Android Settings. */
    DISABLED,
    /** Enabled in Settings but service not yet connected (onServiceConnected not called). */
    ENABLED,
    /** onServiceConnected() called — fully operational. */
    CONNECTED,
    /** Was connected but onUnbind / onDestroy called. */
    DISCONNECTED,
    /** Connected but some capabilities are unavailable (e.g., gesture dispatch failing, service interrupted). */
    DEGRADED,
    /** Crash or unexpected state. */
    ERROR
}

data class AccessibilityDiagnosticReport(
    val permissionEnabled: Boolean,
    val serviceConnected: Boolean,
    val eventsReceivedCount: Long,
    val lastEventTimestamp: Long,
    val isReceivingEvents: Boolean,
    val lastActionSucceeded: Boolean,
    val lastSuccessfulActionTimestamp: Long,
    val lastActionName: String?,
    val lastError: String?,
    val lastErrorTimestamp: Long,
    val state: AccessibilityState,
    val isOperational: Boolean,
    val deviceManufacturer: String,
    val oemAutostartRecommended: Boolean,
    val diagnosticSummary: String
)

object AccessibilityHealthModel {

    private const val TAG = "A11yHealthModel"
    private val processStartTime = System.currentTimeMillis()

    private val _state = MutableStateFlow(AccessibilityState.DISABLED)
    /** Current accessibility service state. */
    val state: StateFlow<AccessibilityState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Human-readable description of the last error / degradation cause, if any. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastErrorTimestamp = MutableStateFlow(0L)
    val lastErrorTimestamp: StateFlow<Long> = _lastErrorTimestamp.asStateFlow()

    private val _lastStateChange = MutableStateFlow(System.currentTimeMillis())
    /** Epoch millis of the most recent state transition. */
    val lastStateChange: StateFlow<Long> = _lastStateChange.asStateFlow()

    private val _lastEventReceivedTimestamp = MutableStateFlow(0L)
    /** Epoch millis of the most recent accessibility event received. */
    val lastEventReceivedTimestamp: StateFlow<Long> = _lastEventReceivedTimestamp.asStateFlow()

    private val _totalEventsReceived = MutableStateFlow(0L)
    /** Total accessibility events processed since service started. */
    val totalEventsReceived: StateFlow<Long> = _totalEventsReceived.asStateFlow()

    private val _lastGestureDispatchedTimestamp = MutableStateFlow(0L)
    /** Epoch millis of the most recent gesture dispatched. */
    val lastGestureDispatchedTimestamp: StateFlow<Long> = _lastGestureDispatchedTimestamp.asStateFlow()

    private val _lastActionVerifiedTimestamp = MutableStateFlow(0L)
    /** Epoch millis of the most recent successful closed-loop action verification. */
    val lastActionVerifiedTimestamp: StateFlow<Long> = _lastActionVerifiedTimestamp.asStateFlow()

    private val _lastActionSucceeded = MutableStateFlow(false)
    val lastActionSucceeded: StateFlow<Boolean> = _lastActionSucceeded.asStateFlow()

    private val _lastSuccessfulActionTimestamp = MutableStateFlow(0L)
    val lastSuccessfulActionTimestamp: StateFlow<Long> = _lastSuccessfulActionTimestamp.asStateFlow()

    private val _lastActionName = MutableStateFlow<String?>(null)
    val lastActionName: StateFlow<String?> = _lastActionName.asStateFlow()

    private val _isReceivingEvents = MutableStateFlow(false)
    val isReceivingEvents: StateFlow<Boolean> = _isReceivingEvents.asStateFlow()

    @Volatile
    private var consecutiveActionFailures = 0

    /**
     * Records arrival of an active AccessibilityEvent from system_server.
     */
    fun recordEventReceived() {
        val now = System.currentTimeMillis()
        _lastEventReceivedTimestamp.value = now
        _totalEventsReceived.value += 1
        _isReceivingEvents.value = true

        // Self-heal: If service was transiently marked DEGRADED or ERROR, active event flow
        // confirms the system binder IPC is operational.
        if (JarvisAccessibilityService.instance != null &&
            (_state.value == AccessibilityState.DEGRADED || _state.value == AccessibilityState.ERROR) &&
            consecutiveActionFailures == 0) {
            updateState(AccessibilityState.CONNECTED)
        }
    }

    /**
     * Records a gesture dispatch outcome.
     */
    fun recordGestureDispatched(success: Boolean, details: String? = null) {
        val now = System.currentTimeMillis()
        _lastGestureDispatchedTimestamp.value = now
        if (success) {
            recordActionSuccess("GestureDispatch")
        } else {
            if (details != null) {
                Log.w(TAG, "Gesture dispatch failure: $details")
                recordActionFailure("GestureDispatch", details)
            }
        }
    }

    /**
     * Records a successful action outcome (e.g. click, scroll, paste, gesture).
     */
    fun recordActionSuccess(actionName: String) {
        val now = System.currentTimeMillis()
        _lastActionSucceeded.value = true
        _lastSuccessfulActionTimestamp.value = now
        _lastActionName.value = actionName
        consecutiveActionFailures = 0

        if (JarvisAccessibilityService.instance != null &&
            (_state.value == AccessibilityState.DEGRADED || _state.value == AccessibilityState.ERROR)) {
            updateState(AccessibilityState.CONNECTED)
        }
    }

    /**
     * Records an action failure with bounded retry counting before marking DEGRADED.
     */
    fun recordActionFailure(actionName: String, error: String) {
        consecutiveActionFailures++
        _lastError.value = "Action '$actionName' failed: $error"
        _lastErrorTimestamp.value = System.currentTimeMillis()

        // Only transition to DEGRADED after repeated consecutive failures (e.g. 3) to prevent
        // temporary display transitions or locked screens from permanently breaking status.
        if (consecutiveActionFailures >= 3 && _state.value == AccessibilityState.CONNECTED) {
            updateState(AccessibilityState.DEGRADED, _lastError.value)
        }
    }

    /**
     * Records a closed-loop verification outcome for an automated UI action.
     */
    fun recordActionVerified(isVerified: Boolean, details: String? = null) {
        val now = System.currentTimeMillis()
        if (isVerified) {
            _lastActionVerifiedTimestamp.value = now
            recordActionSuccess(details ?: "VerifiedAction")
        } else if (details != null) {
            recordActionFailure("VerifiedAction", details)
        }
        Log.d(TAG, "Action verified=$isVerified: ${details ?: "No details"}")
    }

    data class AccessibilityStatusSummary(
        val state: AccessibilityState,
        val isOperational: Boolean,
        val isReceivingEvents: Boolean,
        val lastEventReceivedAgeMs: Long,
        val lastGestureDispatchedAgeMs: Long,
        val lastActionVerifiedAgeMs: Long,
        val lastError: String?
    )

    fun getOperationalStatusSummary(): AccessibilityStatusSummary {
        val now = System.currentTimeMillis()
        val eventAge = if (_lastEventReceivedTimestamp.value > 0) now - _lastEventReceivedTimestamp.value else -1L
        val gestureAge = if (_lastGestureDispatchedTimestamp.value > 0) now - _lastGestureDispatchedTimestamp.value else -1L
        val verifiedAge = if (_lastActionVerifiedTimestamp.value > 0) now - _lastActionVerifiedTimestamp.value else -1L
        val receiving = _lastEventReceivedTimestamp.value > 0 && eventAge < 15_000L

        return AccessibilityStatusSummary(
            state = _state.value,
            isOperational = isOperational(),
            isReceivingEvents = receiving,
            lastEventReceivedAgeMs = eventAge,
            lastGestureDispatchedAgeMs = gestureAge,
            lastActionVerifiedAgeMs = verifiedAge,
            lastError = _lastError.value
        )
    }

    /**
     * Builds a comprehensive, truthful diagnostic report for status inspection.
     */
    fun getDiagnosticReport(context: Context): AccessibilityDiagnosticReport {
        val isEnabledInSettings = isServiceEnabledInSettings(context)
        val isConnected = JarvisAccessibilityService.instance != null
        val now = System.currentTimeMillis()
        val eventAge = if (_lastEventReceivedTimestamp.value > 0) now - _lastEventReceivedTimestamp.value else -1L
        val receiving = _lastEventReceivedTimestamp.value > 0 && eventAge < 60_000L
        val oemRecommended = PermissionHelper.isXiaomiDevice() || PermissionHelper.isVivoDevice()

        val summary = buildString {
            append("• Permission in Settings: ").append(if (isEnabledInSettings) "ENABLED" else "DISABLED").append("\n")
            append("• Service Binder: ").append(if (isConnected) "CONNECTED" else "NOT CONNECTED").append("\n")
            append("• Events Received: ").append(_totalEventsReceived.value).append(if (receiving) " (Active)" else " (Idle)").append("\n")
            append("• Last Action: ").append(_lastActionName.value ?: "None")
            if (_lastSuccessfulActionTimestamp.value > 0) {
                append(" (Succeeded ${(now - _lastSuccessfulActionTimestamp.value) / 1000}s ago)")
            }
            append("\n")
            append("• Last Error: ").append(_lastError.value ?: "None")
            if (oemRecommended) {
                append("\n• OEM Notice: Background optimizer detected. Grant Autostart to prevent idle unbinding.")
            }
        }

        return AccessibilityDiagnosticReport(
            permissionEnabled = isEnabledInSettings,
            serviceConnected = isConnected,
            eventsReceivedCount = _totalEventsReceived.value,
            lastEventTimestamp = _lastEventReceivedTimestamp.value,
            isReceivingEvents = receiving,
            lastActionSucceeded = _lastActionSucceeded.value,
            lastSuccessfulActionTimestamp = _lastSuccessfulActionTimestamp.value,
            lastActionName = _lastActionName.value,
            lastError = _lastError.value,
            lastErrorTimestamp = _lastErrorTimestamp.value,
            state = _state.value,
            isOperational = isOperational(),
            deviceManufacturer = Build.MANUFACTURER,
            oemAutostartRecommended = oemRecommended,
            diagnosticSummary = summary
        )
    }

    /**
     * Transition to [newState], optionally recording an [error] message.
     *
     * Duplicate transitions to the same state with the same error are
     * suppressed to avoid log noise.
     */
    fun updateState(newState: AccessibilityState, error: String? = null) {
        val previous = _state.value
        if (previous == newState && _lastError.value == error) return // no-op
        _state.value = newState
        _lastError.value = error
        if (error != null) {
            _lastErrorTimestamp.value = System.currentTimeMillis()
        }
        _lastStateChange.value = System.currentTimeMillis()
        Log.i(TAG, "State transition: $previous → $newState" + (error?.let { " ($it)" } ?: ""))
    }

    /**
     * Re-derive the state by inspecting Android system settings and the
     * live companion [JarvisAccessibilityService.instance].
     *
     * Call this from UI code (e.g., onResume) to reconcile the model with
     * reality after the user may have toggled the setting externally.
     */
    fun refreshFromSystem(context: Context) {
        val instance = JarvisAccessibilityService.instance

        // 1. If the live instance is present, the service is connected.
        if (instance != null) {
            if (_state.value != AccessibilityState.DEGRADED || consecutiveActionFailures == 0) {
                updateState(AccessibilityState.CONNECTED)
            }
            return
        }

        // 2. No live instance — check whether the service is enabled in Settings.
        val enabledInSettings = isServiceEnabledInSettings(context)

        if (enabledInSettings) {
            val uptime = System.currentTimeMillis() - processStartTime
            if (uptime < 3000L) {
                // Process just started: binder connection is pending in system_server
                updateState(AccessibilityState.ENABLED, "Service enabled in Settings; connecting background binder...")
            } else {
                updateState(AccessibilityState.ENABLED, "Service enabled in Settings but binder instance is not connected. Toggle Off and On to restart.")
            }
        } else {
            updateState(AccessibilityState.DISABLED, "Service is disabled in Android Accessibility Settings.")
        }
    }

    /** Returns `true` when the service is in a state that can execute commands. */
    fun isOperational(): Boolean {
        return _state.value == AccessibilityState.CONNECTED ||
               _state.value == AccessibilityState.DEGRADED
    }

    fun isConnecting(): Boolean = _state.value == AccessibilityState.ENABLED

    /**
     * Returns user-facing guidance on how to recover from the current
     * non-operational state, or `null` if the service is already operational.
     */
    fun getRecoveryGuidance(): String? {
        return when (_state.value) {
            AccessibilityState.DISABLED ->
                "Jarvis Accessibility Service is disabled. Please enable it in " +
                "Settings → Accessibility → Installed services → Jarvis."
            AccessibilityState.ENABLED ->
                "The service is enabled but hasn't connected yet. " +
                "Try toggling it off and on, or restart the app."
            AccessibilityState.DISCONNECTED ->
                "The Accessibility Service was disconnected. " +
                "Please re-enable it in Settings → Accessibility → Jarvis."
            AccessibilityState.ERROR -> {
                val detail = _lastError.value?.let { " Last error: $it" } ?: ""
                "The Accessibility Service encountered an error.$detail " +
                "Try toggling the service off and on in Settings."
            }
            AccessibilityState.CONNECTED,
            AccessibilityState.DEGRADED -> null
        }
    }

    // ------------------------------------------------------------------
    // Resilient System Settings Verification (Supports Stock AOSP & OEM ROMs)
    // ------------------------------------------------------------------

    fun isServiceEnabledInSettings(context: Context): Boolean {
        // Check 1: Settings.Secure with master switch
        try {
            val cr = context.contentResolver
            val isMasterEnabled = try {
                Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            } catch (_: Exception) {
                true // fallback
            }

            if (isMasterEnabled) {
                val enabledServices = Settings.Secure.getString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (!enabledServices.isNullOrBlank()) {
                    val expectedComponent = ComponentName(context, JarvisAccessibilityService::class.java)
                    val expectedPackage = context.packageName
                    val expectedSimpleName = JarvisAccessibilityService::class.java.simpleName

                    val colonSplitter = TextUtils.SimpleStringSplitter(':')
                    colonSplitter.setString(enabledServices)
                    while (colonSplitter.hasNext()) {
                        val rawComponent = colonSplitter.next().trim()
                        if (rawComponent.isEmpty()) continue

                        // Normalized unflattening (handles both full and short formats)
                        val unflattened = ComponentName.unflattenFromString(rawComponent)
                        if (unflattened != null) {
                            if (unflattened == expectedComponent ||
                                (unflattened.packageName.equals(expectedPackage, ignoreCase = true) &&
                                 unflattened.className.endsWith(expectedSimpleName, ignoreCase = true))) {
                                return true
                            }
                        }

                        // Fuzzy fallback matching
                        if (rawComponent.contains(expectedPackage, ignoreCase = true) &&
                            rawComponent.contains(expectedSimpleName, ignoreCase = true)) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking accessibility secure settings: ${e.message}")
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
                            (sInfo.name == JarvisAccessibilityService::class.java.name ||
                             info.id.startsWith("${context.packageName}/") ||
                             info.id.contains("JarvisAccessibilityService"))
                        }
                        if (matched) return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking AccessibilityManager service list: ${e.message}")
        }

        return false
    }
}
