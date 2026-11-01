package com.jarvis.assistant.automation.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.jarvis.assistant.audio.VoiceSynthesisRouter

class CallScreeningReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallScreeningReceiver"
        const val DEFAULT_AUTO_REPLY = "Nazib Sir is currently in a meeting, will call back soon."

        @Volatile
        var isRinging: Boolean = false
            private set

        @Volatile
        var lastRingingNumber: String? = null
            private set

        @Volatile
        var lastCallerName: String? = null
            private set

        /**
         * Resolves contact name from ContactsContract using phone number.
         */
        fun resolveContactName(context: Context, phoneNumber: String): String {
            if (phoneNumber.isBlank()) return "Unknown Caller"
            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber)
                )
                val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
                val cursor: Cursor? = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = it.getString(nameIndex)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Contact lookup warning: ${e.message}")
            }
            return phoneNumber
        }

        private const val RINGER_STATE_PREFS = "call_screening_ringer_state"
        private const val KEY_MUTE_APPLIED = "mute_applied_by_jarvis"
        private const val KEY_PREVIOUS_RINGER_MODE = "previous_ringer_mode"
        private const val KEY_PREVIOUS_RING_VOLUME = "previous_ring_volume"
        private const val KEY_PREVIOUS_STREAM_MUTED = "previous_stream_muted"

        /**
         * Temporarily mutes the ring stream and optionally sends only an explicitly supplied SMS.
         * This does not reject/end the carrier call; Android's call-screening role is required for that.
         */
        fun silenceRingerAndSendMessage(context: Context, customMessage: String? = null): CallScreeningResult {
            if (!isRinging) {
                return CallScreeningResult(false, false, "There is no active incoming call to silence.")
            }

            val number = lastRingingNumber
            val ringerSilenced = silenceRinger(context)
            if (!ringerSilenced) {
                return CallScreeningResult(false, false, "Android did not allow the incoming-call ringer to be muted.")
            }

            val messageToSend = customMessage?.trim()?.takeIf { it.isNotEmpty() }
            if (messageToSend == null) {
                VoiceSynthesisRouter.speak("Incoming call silenced. No SMS was sent.")
                return CallScreeningResult(true, false, "Incoming call silenced. No SMS was sent because no explicit message was provided.")
            }

            if (number.isNullOrBlank()) {
                VoiceSynthesisRouter.speak("Incoming call silenced. The caller number is unavailable, so no SMS was sent.")
                return CallScreeningResult(true, false, "Incoming call silenced, but the caller number is unavailable; no SMS was sent.")
            }

            if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                VoiceSynthesisRouter.speak("Incoming call silenced. SMS permission is not granted.")
                return CallScreeningResult(true, false, "Incoming call silenced, but SEND_SMS permission is not granted.")
            }

            return try {
                val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                val parts = smsManager.divideMessage(messageToSend)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(number, null, messageToSend, null, null)
                }
                Log.i(TAG, "Explicit incoming-call SMS dispatched.")
                VoiceSynthesisRouter.speak("Incoming call silenced and your message was sent.")
                CallScreeningResult(true, true, "Incoming call ringer silenced and the explicitly supplied SMS was dispatched.")
            } catch (e: Exception) {
                Log.e(TAG, "Could not dispatch the explicitly requested incoming-call SMS (${e.javaClass.simpleName}).")
                VoiceSynthesisRouter.speak("Incoming call silenced. The SMS could not be sent.")
                CallScreeningResult(true, false, "Incoming call silenced, but SMS dispatch failed (${e.javaClass.simpleName}).")
            }
        }

        private fun silenceRinger(context: Context): Boolean {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val prefs = context.getSharedPreferences(RINGER_STATE_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MUTE_APPLIED, false)) return true

            val originalMode = audioManager.ringerMode
            val wasMuted = audioManager.isStreamMute(AudioManager.STREAM_RING)
            val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            if (originalMode != AudioManager.RINGER_MODE_NORMAL || wasMuted || originalVolume <= 0) {
                return true
            }

            val saved = prefs.edit()
                .putBoolean(KEY_MUTE_APPLIED, true)
                .putInt(KEY_PREVIOUS_RINGER_MODE, originalMode)
                .putInt(KEY_PREVIOUS_RING_VOLUME, originalVolume)
                .putBoolean(KEY_PREVIOUS_STREAM_MUTED, wasMuted)
                .commit()
            if (!saved) return false

            return try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                if (audioManager.isStreamMute(AudioManager.STREAM_RING)) {
                    true
                } else {
                    prefs.edit().clear().commit()
                    false
                }
            } catch (_: Exception) {
                prefs.edit().clear().commit()
                false
            }
        }

        private fun restoreRingerIfStillJarvisMuted(context: Context) {
            val prefs = context.getSharedPreferences(RINGER_STATE_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_MUTE_APPLIED, false)) return

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val previousMode = prefs.getInt(KEY_PREVIOUS_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
                val previousVolume = prefs.getInt(KEY_PREVIOUS_RING_VOLUME, 0)
                val wasPreviouslyMuted = prefs.getBoolean(KEY_PREVIOUS_STREAM_MUTED, false)

                // Restore only if the user has not changed the ringer mode while the call was active.
                if (!wasPreviouslyMuted &&
                    audioManager.ringerMode == previousMode &&
                    audioManager.isStreamMute(AudioManager.STREAM_RING)
                ) {
                    runCatching {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                        if (previousVolume > 0 && audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0) {
                            audioManager.setStreamVolume(AudioManager.STREAM_RING, previousVolume, 0)
                        }
                    }.onFailure {
                        Log.w(TAG, "Could not restore the previous ring volume (${it.javaClass.simpleName}).")
                    }
                }
            }

            prefs.edit()
                .remove(KEY_MUTE_APPLIED)
                .remove(KEY_PREVIOUS_RINGER_MODE)
                .remove(KEY_PREVIOUS_RING_VOLUME)
                .remove(KEY_PREVIOUS_STREAM_MUTED)
                .commit()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        Log.i(TAG, "Phone state changed: $stateStr")

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Recover a stale mute snapshot if Android did not deliver the previous call's end event.
                restoreRingerIfStillJarvisMuted(context)
                isRinging = true
                lastRingingNumber = incomingNumber.ifBlank { lastRingingNumber }
                val resolvedName = resolveContactName(context, incomingNumber)
                lastCallerName = resolvedName

                // Spoken announcement
                val announcement = "Sir, incoming call from $resolvedName. Reject karke message bhejun ya answer karenge?"
                VoiceSynthesisRouter.speak(announcement)
            }
            TelephonyManager.EXTRA_STATE_IDLE, TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                isRinging = false
                restoreRingerIfStillJarvisMuted(context)
            }
        }
    }
}


data class CallScreeningResult(
    val ringerSilenced: Boolean,
    val smsSent: Boolean,
    val detail: String
)
