package com.jarvis.assistant.automation.protocol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.data.memory.ConversationContextMemory
import java.util.Locale

enum class ProtocolType {
    CODING,
    BUSINESS,
    LOCKDOWN
}

data class ProtocolExecutionResult(
    val handled: Boolean,
    val spokenAnnouncement: String,
    val protocolType: ProtocolType? = null
)

object ProtocolManager {
    private const val TAG = "ProtocolManager"

    @Volatile
    var isLockdownActive = false
        private set

    fun isProtocolCommand(rawText: String): Boolean {
        val clean = rawText.lowercase(Locale.ROOT).trim()
        return clean.contains("coding protocol") ||
                clean.contains("business protocol") ||
                clean.contains("wholesale mode") ||
                clean.contains("lockdown protocol") ||
                clean.contains("unlock protocol")
    }

    fun executeProtocol(
        rawText: String,
        context: Context,
        phoneControlManager: PhoneControlManager?
    ): ProtocolExecutionResult {
        val clean = rawText.lowercase(Locale.ROOT).trim()
        val pm = phoneControlManager ?: PhoneControlManager(context)

        return when {
            clean.contains("coding protocol") -> {
                Log.i(TAG, "Executing Coding Protocol")
                isLockdownActive = false
                pm.setVolumePercent(40)
                
                // Launch Termux if installed, otherwise open GitHub repository in browser
                val termuxLaunched = pm.openApp("termux")
                if (termuxLaunched.contains("not found", ignoreCase = true) || termuxLaunched.contains("Could not", ignoreCase = true)) {
                    val githubUrl = "https://github.com/levinhocall/Jarvis-os"
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening GitHub: ${e.message}")
                    }
                }
                ProtocolExecutionResult(
                    handled = true,
                    spokenAnnouncement = "Coding protocol engaged, Nazib Sir. Terminal environment ready.",
                    protocolType = ProtocolType.CODING
                )
            }

            clean.contains("business protocol") || clean.contains("wholesale mode") -> {
                Log.i(TAG, "Executing Business Protocol")
                isLockdownActive = false
                
                // Launch WhatsApp or Calculator
                val appToLaunch = if (clean.contains("wholesale")) "calculator" else "whatsapp"
                pm.openApp(appToLaunch)
                
                val currentCategory = ConversationContextMemory.baseProductCategory
                val currentBrand = ConversationContextMemory.brand
                val contextHint = if (!currentCategory.isNullOrBlank()) {
                    " Active product in memory is $currentBrand $currentCategory."
                } else ""

                ProtocolExecutionResult(
                    handled = true,
                    spokenAnnouncement = "Business protocol active, Nazib Sir.$contextHint",
                    protocolType = ProtocolType.BUSINESS
                )
            }

            clean.contains("lockdown protocol") -> {
                Log.i(TAG, "Executing Lockdown Protocol")
                isLockdownActive = true
                
                // Mute all streams
                pm.muteVolume(true)
                
                // Minimize to home screen immediately
                JarvisAccessibilityService.instance?.performSystemGesture("home")
                
                ProtocolExecutionResult(
                    handled = true,
                    spokenAnnouncement = "Lockdown protocol engaged. All audio muted and screens secured.",
                    protocolType = ProtocolType.LOCKDOWN
                )
            }

            clean.contains("unlock protocol") -> {
                val isAuthorized = com.jarvis.assistant.security.SecurityGuardManager.isActionPermitted(
                    com.jarvis.assistant.security.SecurityAction.UNLOCK_LOCKDOWN,
                    rawText,
                    context
                )

                if (isAuthorized || !isLockdownActive) {
                    Log.i(TAG, "Disengaging Lockdown Protocol")
                    isLockdownActive = false
                    pm.muteVolume(false)
                    pm.setVolumePercent(60)
                    ProtocolExecutionResult(
                        handled = true,
                        spokenAnnouncement = "Welcome back, Nazib Sir. Systems unlocked and online."
                    )
                } else {
                    Log.w(TAG, "Unauthorized unlock attempt rejected")
                    com.jarvis.assistant.security.SecurityGuardManager.logIntruderAlert(
                        context,
                        "Unauthorized attempt to unlock lockdown: '$rawText'"
                    )
                    ProtocolExecutionResult(
                        handled = true,
                        spokenAnnouncement = "Security authorization failed. Lockdown protocol remains active, intruder logged."
                    )
                }
            }

            else -> ProtocolExecutionResult(false, "")
        }
    }
}
