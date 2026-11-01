package com.jarvis.assistant.automation.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import com.jarvis.assistant.audio.VoiceSynthesisRouter

class CallScreeningReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallScreeningReceiver"
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
                VoiceSynthesisRouter.speak("The ringtone is muted. The call is not rejected, and no SMS was sent.")
                return CallScreeningResult(true, false, "Ringtone muted only; the call is not rejected. No SMS was sent because no explicit message was provided.")
            }

            if (number.isNullOrBlank()) {
                VoiceSynthesisRouter.speak("The ringtone is muted. The caller number is unavailable, so no SMS draft was opened.")
                return CallScreeningResult(true, false, "Ringtone muted only; the caller number is unavailable, so no SMS draft was opened.")
            }

            return try {
                val smsIntent = Intent(
                    Intent.ACTION_SENDTO,
                    Uri.parse("smsto:${Uri.encode(number)}")
                ).apply {
                    putExtra("sms_body", messageToSend)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (smsIntent.resolveActivity(context.packageManager) == null) {
                    VoiceSynthesisRouter.speak("The ringtone is muted. No SMS app is available to open a draft.")
                    CallScreeningResult(true, false, "Ringtone muted only; no SMS composer is available.")
                } else {
                    context.startActivity(smsIntent)
                    VoiceSynthesisRouter.speak("The ringtone is muted. A draft message is open for your review; it has not been sent.")
                    CallScreeningResult(true, true, "Ringtone muted only; the call is not rejected. Draft SMS opened for review; tap Send in your SMS app to send it.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not open the SMS composer (${e.javaClass.simpleName}).")
                VoiceSynthesisRouter.speak("The ringtone is muted. The SMS draft could not be opened.")
                CallScreeningResult(true, false, "Ringtone muted only; the SMS draft could not be opened.")
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
                lastRingingNumber = incomingNumber.takeIf { it.isNotBlank() }
                val resolvedName = resolveContactName(context, incomingNumber)
                lastCallerName = resolvedName

                // Spoken announcement
                val announcement = "Sir, incoming call from $resolvedName. Ringer mute karun aur message bhejun, ya call answer karenge?"
                VoiceSynthesisRouter.speak(announcement)
            }
            TelephonyManager.EXTRA_STATE_IDLE, TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                isRinging = false
                restoreRingerIfStillJarvisMuted(context)
                lastRingingNumber = null
                lastCallerName = null
            }
        }
    }
}


data class CallScreeningResult(
    val ringerSilenced: Boolean,
    val smsComposerOpened: Boolean,
    val detail: String
)
