package com.jarvis.assistant.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.assistant.data.memory.RecallCoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
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
    private const val SECURE_PREFS_NAME = "jarvis_secure_security_prefs"
    private const val KEY_SECURITY_PIN = "security_pin"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LOCKED_UNTIL = "locked_until"
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L

    @Volatile
    private var elevatedSessionExpiryTime: Long = 0L

    private fun getLegacyPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getSecurePrefs(context: Context): SharedPreferences? = try {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { secure ->
            migrateLegacyPin(appContext, secure)
        }
    } catch (error: Exception) {
        Log.e(TAG, "Encrypted security storage unavailable; authorization fails closed.", error)
        null
    }

    private fun migrateLegacyPin(context: Context, secure: SharedPreferences) {
        val legacy = getLegacyPrefs(context)
        val oldPin = legacy.getString(KEY_SECURITY_PIN, null)?.trim().orEmpty()
        if (oldPin.isEmpty()) return

        // The old built-in default is public knowledge, not a user credential.
        if (oldPin == "1024") {
            legacy.edit().remove(KEY_SECURITY_PIN).commit()
            return
        }

        try {
            if (secure.getString(KEY_SECURITY_PIN, null).isNullOrEmpty() &&
                oldPin.matches(Regex("\\d{6,12}"))) {
                check(secure.edit().putString(KEY_SECURITY_PIN, oldPin).commit())
            }
            // Remove plaintext even when migration cannot be completed. Fail closed.
            legacy.edit().remove(KEY_SECURITY_PIN).commit()
        } catch (error: Exception) {
            Log.e(TAG, "Security PIN migration failed; plaintext value removed.", error)
            legacy.edit().remove(KEY_SECURITY_PIN).commit()
        }
    }

    /** Configure a user-owned 6–12 digit PIN. There is deliberately no default PIN. */
    fun configureSecurityPin(context: Context, pin: String): Boolean {
        val cleanPin = pin.trim()
        if (!cleanPin.matches(Regex("\\d{6,12}"))) return false
        val secure = getSecurePrefs(context) ?: return false
        return try {
            secure.edit()
                .putString(KEY_SECURITY_PIN, cleanPin)
                .remove(KEY_FAILED_ATTEMPTS)
                .remove(KEY_LOCKED_UNTIL)
                .commit()
        } catch (error: Exception) {
            Log.e(TAG, "Could not save security PIN securely.", error)
            false
        }
    }

    fun hasConfiguredSecurityPin(context: Context): Boolean {
        val secure = getSecurePrefs(context) ?: return false
        return !secure.getString(KEY_SECURITY_PIN, null).isNullOrBlank()
    }

    fun isSessionElevated(): Boolean =
        System.currentTimeMillis() < elevatedSessionExpiryTime

    fun grantElevatedAccess(durationMillis: Long = 60_000L) {
        elevatedSessionExpiryTime = System.currentTimeMillis() + durationMillis.coerceIn(1_000L, 60_000L)
        Log.i(TAG, "Elevated security session granted for a limited duration.")
    }

    fun revokeElevatedAccess() {
        elevatedSessionExpiryTime = 0L
        Log.i(TAG, "Elevated security session revoked.")
    }

    /**
     * Accepts only a configured PIN, either alone or in the explicit
     * "enter security pin <digits>" command. Identity phrases are not credentials.
     */
    fun verifyPassphrase(input: String, context: Context): Boolean {
        val clean = input.trim().lowercase(Locale.ROOT)
        val suppliedPin = when {
            clean.matches(Regex("\\d{6,12}")) -> clean
            else -> Regex("^(?:enter security pin|security authorization|authorize me)\\s+(\\d{6,12})$")
                .matchEntire(clean)?.groupValues?.getOrNull(1)
        }

        val secure = getSecurePrefs(context)
        val configuredPin = secure?.getString(KEY_SECURITY_PIN, null)
        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        if (secure == null || configuredPin.isNullOrBlank() || suppliedPin == null) {
            recordFailedAttempt(context, secure, now, timeStr)
            return false
        }

        val isLocked = secure.getLong(KEY_LOCKED_UNTIL, 0L) > now
        if (isLocked) {
            Log.w(TAG, "Security authorization rejected during temporary lockout at $timeStr")
            return false
        }

        val matches = MessageDigest.isEqual(
            configuredPin.toByteArray(Charsets.UTF_8),
            suppliedPin.toByteArray(Charsets.UTF_8)
        )
        if (matches) {
            secure.edit().putInt(KEY_FAILED_ATTEMPTS, 0).putLong(KEY_LOCKED_UNTIL, 0L).apply()
            grantElevatedAccess()
            Log.i(TAG, "PIN authorization successful at $timeStr")
            return true
        }

        recordFailedAttempt(context, secure, now, timeStr)
        return false
    }

    private fun recordFailedAttempt(
        context: Context,
        secure: SharedPreferences?,
        now: Long,
        timeStr: String
    ) {
        if (secure != null) {
            val attempts = secure.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            val editor = secure.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                editor.putLong(KEY_LOCKED_UNTIL, now + LOCKOUT_DURATION_MS)
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
            }
            editor.apply()
        }
        // Never persist or log the supplied phrase/PIN.
        Log.w(TAG, "Security authorization failed at $timeStr")
        CoroutineScope(Dispatchers.IO).launch {
            RecallCoreManager.saveFact(context, "[SECURITY ALERT: Failed authorization attempt at $timeStr]")
        }
    }

    fun isActionPermitted(
        action: SecurityAction,
        passphraseOrUtterance: String?,
        context: Context? = null
    ): Boolean {
        if (isSessionElevated()) return true
        return passphraseOrUtterance != null &&
            context != null &&
            verifyPassphrase(passphraseOrUtterance, context)
    }

    fun logIntruderAlert(context: Context, details: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        // Do not persist arbitrary command text, which may contain credentials or private data.
        Log.w(TAG, "Security intruder alert at $timeStr")
        CoroutineScope(Dispatchers.IO).launch {
            RecallCoreManager.saveFact(context, "[SECURITY INTRUDER ALERT at $timeStr]")
        }
    }
}
