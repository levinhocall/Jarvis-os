package com.jarvis.assistant.background

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents the granular lifecycle state of the AI conversation session.
 */
enum class SessionLifecycleState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    LIVE,
    RECONNECTING,
    ERROR
}

/**
 * Manages conversation session lifecycle, WebSocket connection state,
 * error tracking, and exponential backoff reconnects for JARVIS OS.
 */
class VoiceSessionManager(
    private val scope: CoroutineScope,
    private val onConnectRequest: suspend () -> Boolean,
    private val onDisconnectRequest: suspend () -> Unit
) {
    companion object {
        private const val TAG = "VoiceSessionManager"
        private const val BASE_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }

    private val _sessionState = MutableStateFlow(SessionLifecycleState.DISCONNECTED)
    val sessionState: StateFlow<SessionLifecycleState> = _sessionState.asStateFlow()

    private val _connectionStatus = MutableStateFlow("OFFLINE")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val consecutiveFailures = AtomicInteger(0)
    private var reconnectJob: Job? = null
    private var lastStateChangeTimestamp = SystemClock.elapsedRealtime()

    val isLive: Boolean
        get() = _sessionState.value == SessionLifecycleState.LIVE

    val isSessionActive: Boolean
        get() = _sessionState.value in listOf(
            SessionLifecycleState.CONNECTING,
            SessionLifecycleState.AUTHENTICATING,
            SessionLifecycleState.LIVE,
            SessionLifecycleState.RECONNECTING
        )

    /**
     * Initiates a new conversation session.
     */
    fun startSession() {
        if (isSessionActive) {
            Log.d(TAG, "Session already active or connecting.")
            return
        }

        reconnectJob?.cancel()
        consecutiveFailures.set(0)
        _lastError.value = null
        transitionState(SessionLifecycleState.CONNECTING, "STARTING")

        scope.launch(Dispatchers.IO) {
            try {
                val success = onConnectRequest()
                if (success) {
                    transitionState(SessionLifecycleState.LIVE, "LIVE")
                    consecutiveFailures.set(0)
                } else {
                    handleConnectionFailure("Initial connection handshake failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting voice session", e)
                handleConnectionFailure(e.message ?: "Unknown connection error")
            }
        }
    }

    /**
     * Stops the conversation session and cancels scheduled reconnects.
     */
    fun stopSession() {
        reconnectJob?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                onDisconnectRequest()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting session", e)
            } finally {
                transitionState(SessionLifecycleState.DISCONNECTED, "OFFLINE")
            }
        }
    }

    /**
     * Reports successful authentication and session readiness.
     */
    fun onSessionEstablished() {
        consecutiveFailures.set(0)
        transitionState(SessionLifecycleState.LIVE, "LIVE")
    }

    /**
     * Reports an unexpected connection disruption.
     */
    fun onConnectionDropped(reason: String) {
        if (!isSessionActive) return
        Log.w(TAG, "Connection dropped: $reason")
        handleConnectionFailure(reason)
    }

    /**
     * Handles connection failures with exponential backoff and jitter.
     */
    private fun handleConnectionFailure(errorMessage: String) {
        val attempts = consecutiveFailures.incrementAndGet()
        _lastError.value = errorMessage

        if (attempts > MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "Max reconnect attempts reached ($attempts). Aborting reconnect.")
            transitionState(SessionLifecycleState.ERROR, "ERROR")
            return
        }

        transitionState(SessionLifecycleState.RECONNECTING, "RECONNECTING ($attempts)")

        val backoff = (BASE_BACKOFF_MS * (1L shl (attempts - 1))).coerceAtMost(MAX_BACKOFF_MS)
        val jitter = (Math.random() * 1000).toLong()
        val totalDelay = backoff + jitter

        Log.d(TAG, "Scheduling reconnect #$attempts in ${totalDelay}ms")
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(totalDelay)
            if (_sessionState.value == SessionLifecycleState.RECONNECTING) {
                try {
                    val reconnected = onConnectRequest()
                    if (reconnected) {
                        transitionState(SessionLifecycleState.LIVE, "LIVE")
                        consecutiveFailures.set(0)
                    } else {
                        handleConnectionFailure("Reconnect attempt #$attempts failed")
                    }
                } catch (e: Exception) {
                    handleConnectionFailure(e.message ?: "Reconnect exception")
                }
            }
        }
    }

    /**
     * Clears cached live error state.
     */
    fun clearError() {
        _lastError.value = null
        if (_sessionState.value == SessionLifecycleState.ERROR) {
            transitionState(SessionLifecycleState.DISCONNECTED, "OFFLINE")
        }
    }

    private fun transitionState(newState: SessionLifecycleState, statusText: String) {
        lastStateChangeTimestamp = SystemClock.elapsedRealtime()
        _sessionState.value = newState
        _connectionStatus.value = statusText
        Log.d(TAG, "Session state -> $newState ($statusText)")
    }
}
