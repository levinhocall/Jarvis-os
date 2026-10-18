package com.jarvis.assistant.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jarvis.assistant.data.memory.RecallCoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SecurityAction {
    UNLOCK_LOCKDOWN,
    DELETE_MEMORY,
    DISPATCH_WHATSAPP,
    GENERAL_RESTRICTED
}

object SecurityGuardManager {
    private const val TAG = "SecurityGuardManager"
    private const val PREFS_NAME = "jarvis_security_prefs"
    private const val KEY_SECURITY_PIN = "security_pin"
    private const val KEY_ELEVATED_UNTIL = "elevated_until"
    private const val DEFAULT_PIN = "1024"

    // In-memory elevated session timestamp (valid for 60 seconds once authorized)
    @Volatile
    private var elevatedSessionExpiryTime: Long = 0L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) {}
    }

    private fun logW(tag: String, msg: String) {
        try { Log.w(tag, msg) } catch (_: Throwable) {}
    }

    /**
     * Checks if current session is elevated with active authorization.
     */
    fun isSessionElevated(): Boolean {
        return System.currentTimeMillis() < elevatedSessionExpiryTime
    }

    /**
     * Grants elevated security clearance for the specified duration (default 60 seconds).
     */
    fun grantElevatedAccess(durationMillis: Long = 60_000L) {
        elevatedSessionExpiryTime = System.currentTimeMillis() + durationMillis
        logI(TAG, "Elevated security clearance granted for ${durationMillis / 1000}s")
    }

    /**
     * Revokes any active elevated security session immediately.
     */
    fun revokeElevatedAccess() {
        elevatedSessionExpiryTime = 0L
        logI(TAG, "Elevated security clearance revoked")
    }

    /**
     * Verifies voice passphrase or PIN against authorized credentials.
     * Default valid passphrases: "Authorize Nazib", "Authorization Nazib", or PIN "1024".
     */
    fun verifyPassphrase(input: String, context: Context): Boolean {
        val clean = input.lowercase(Locale.ROOT).trim()
        val configuredPin = getPrefs(context).getString(KEY_SECURITY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

        val isAuthorized = clean.contains("authorize nazib") ||
                clean.contains("authorization nazib") ||
                clean.contains("stark override") ||
                clean.contains("nazib stark") ||
                clean.contains(configuredPin)

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        if (isAuthorized) {
            grantElevatedAccess()
            Log.i(TAG, "Passphrase authorization SUCCESS at $timeStr")
            return true
        } else {
            Log.w(TAG, "Passphrase authorization FAILED for query: '$input' at $timeStr")
            // Log security alert autonomously into Recall Core
            CoroutineScope(Dispatchers.IO).launch {
                val alertMsg = "[SECURITY ALERT: Unauthorized access attempt at $timeStr with query: '$input']"
                RecallCoreManager.saveFact(context, alertMsg)
            }
            return false
        }
    }

    /**
     * Determines whether a sensitive action is permitted.
     * If an active elevated session exists, returns true.
     * Otherwise verifies if the supplied command or passphrase matches.
     */
    fun isActionPermitted(
        action: SecurityAction,
        passphraseOrUtterance: String?,
        context: Context? = null
    ): Boolean {
        if (isSessionElevated()) {
            return true
        }

        if (passphraseOrUtterance != null && context != null && verifyPassphrase(passphraseOrUtterance, context)) {
            return true
        }

        return false
    }

    /**
     * Logs an explicit intruder trap warning when lockdown is active and unexpected actions occur.
     */
    fun logIntruderAlert(context: Context, details: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val alert = "[SECURITY INTRUDER ALERT at $timeStr: $details]"
        Log.w(TAG, alert)
        CoroutineScope(Dispatchers.IO).launch {
            RecallCoreManager.saveFact(context, alert)
        }
    }
}
