package com.jarvis.assistant.automation.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityManager
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

object AccessibilityHealthModel {

    private const val TAG = "A11yHealthModel"

    private val _state = MutableStateFlow(AccessibilityState.DISABLED)
    /** Current accessibility service state. */
    val state: StateFlow<AccessibilityState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Human-readable description of the last error / degradation cause, if any. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastStateChange = MutableStateFlow(System.currentTimeMillis())
    /** Epoch millis of the most recent state transition. */
    val lastStateChange: StateFlow<Long> = _lastStateChange.asStateFlow()

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
            if (_state.value != AccessibilityState.DEGRADED) {
                updateState(AccessibilityState.CONNECTED)
            }
            return
        }

        // 2. No live instance — check whether the service is enabled in Settings.
        val enabledInSettings = isServiceEnabledInSettings(context)

        if (enabledInSettings) {
            // Enabled in settings, but binder instance is not yet connected or is reconnecting
            updateState(AccessibilityState.ENABLED)
        } else {
            updateState(AccessibilityState.DISABLED)
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
