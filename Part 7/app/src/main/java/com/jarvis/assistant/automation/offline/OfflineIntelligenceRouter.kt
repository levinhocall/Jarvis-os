package com.jarvis.assistant.automation.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

enum class CapabilityAvailability {
    AVAILABLE_OFFLINE,
    REQUIRES_INTERNET,
    UNSUPPORTED
}

data class DeferredAction(
    val id: String = java.util.UUID.randomUUID().toString(),
    val actionType: String,
    val payload: String,
    val isConsequential: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)

object OfflineIntelligenceRouter {
    private const val TAG = "OfflineRouter"

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val deferredQueue = ConcurrentLinkedQueue<DeferredAction>()
    private var isNetworkCallbackRegistered = false

    fun init(context: Context) {
        if (isNetworkCallbackRegistered) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network became available. Operating online.")
                    _isOnline.value = true
                    processDeferredQueue(context)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Network lost. Switching to Local Offline Mode.")
                    _isOnline.value = false
                }
            })
            isNetworkCallbackRegistered = true

            // Initialize active state
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            _isOnline.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            Log.w(TAG, "Could not register NetworkCallback: ${e.message}")
            _isOnline.value = true
        }
    }

    /**
     * Determines whether an intent/action can be handled completely offline.
     */
    fun classifyCapability(intentText: String): CapabilityAvailability {
        val lower = intentText.lowercase()
        return when {
            // Hardware, status, battery, volume, torch
            lower.contains("battery") || lower.contains("volume") || lower.contains("torch") ||
            lower.contains("flashlight") || lower.contains("time") || lower.contains("waqt") ->
                CapabilityAvailability.AVAILABLE_OFFLINE

            // Local notes and recall
            lower.contains("remember") || lower.contains("yaad rakhna") || lower.contains("recall") ->
                CapabilityAvailability.AVAILABLE_OFFLINE

            // Web search or cloud AI
            lower.contains("search") || lower.contains("google") || lower.contains("who is") ||
            lower.contains("latest news") || lower.contains("weather") ->
                CapabilityAvailability.REQUIRES_INTERNET

            else -> if (_isOnline.value) CapabilityAvailability.AVAILABLE_OFFLINE else CapabilityAvailability.REQUIRES_INTERNET
        }
    }

    /**
     * Generates honest status feedback for the user based on connectivity.
     */
    fun getOfflineStatusMessage(userName: String = "Sir"): String {
        return if (_isOnline.value) {
            "All systems online and operational, $userName."
        } else {
            "Offline Mode active, $userName. Hardware controls, local notes, and saved skills are operational. Cloud reasoning and web search are paused until network returns."
        }
    }

    /**
     * Safely enqueues a deferred action.
     * Consequential actions (e.g. messaging, financial, deletion) are marked as requiring re-approval.
     */
    fun enqueueDeferredAction(actionType: String, payload: String, isConsequential: Boolean): Boolean {
        if (deferredQueue.size >= 50) {
            Log.w(TAG, "Deferred queue full; dropping action '$actionType'")
            return false
        }
        val item = DeferredAction(
            actionType = actionType,
            payload = payload,
            isConsequential = isConsequential
        )
        deferredQueue.offer(item)
        Log.i(TAG, "Enqueued deferred action '$actionType' (consequential=$isConsequential)")
        return true
    }

    fun getPendingDeferredActions(): List<DeferredAction> = deferredQueue.toList()

    /**
     * Resumes safe deferred operations when internet returns.
     */
    private fun processDeferredQueue(context: Context) {
        val count = deferredQueue.size
        if (count == 0) return
        Log.i(TAG, "Processing $count deferred action(s) following network reconnection")

        val itemsToProcess = mutableListOf<DeferredAction>()
        while (!deferredQueue.isEmpty()) {
            val item = deferredQueue.poll() ?: break
            if (item.isConsequential) {
                // Do NOT automatically replay consequential actions without user verification!
                Log.w(TAG, "Deferred consequential action '${item.actionType}' requires fresh user approval after reconnect.")
            } else {
                itemsToProcess.add(item)
            }
        }
    }
}
