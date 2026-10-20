package com.jarvis.assistant.automation.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
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
            // Enabled but not yet connected (or was disconnected).
            if (_state.value != AccessibilityState.DISCONNECTED &&
                _state.value != AccessibilityState.ERROR
            ) {
                updateState(AccessibilityState.ENABLED)
            }
        } else {
            updateState(AccessibilityState.DISABLED)
        }
    }

    /** Returns `true` when the service is in a state that can execute commands. */
    fun isOperational(): Boolean {
        return _state.value == AccessibilityState.CONNECTED ||
               _state.value == AccessibilityState.DEGRADED
    }

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
    // Internal helpers
    // ------------------------------------------------------------------

    private fun isServiceEnabledInSettings(context: Context): Boolean {
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expected = ComponentName(context, JarvisAccessibilityService::class.java)
            val fullFlattened = expected.flattenToString()
            val shortFlattened = expected.flattenToShortString()

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val component = splitter.next()
                if (component.equals(fullFlattened, ignoreCase = true) ||
                    component.equals(shortFlattened, ignoreCase = true) ||
                    (component.contains(context.packageName) &&
                     component.contains("JarvisAccessibilityService"))
                ) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking accessibility settings: ${e.message}")
        }
        return false
    }
}
