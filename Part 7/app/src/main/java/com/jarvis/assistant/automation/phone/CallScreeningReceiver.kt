package com.jarvis.assistant.automation.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

        /**
         * Quick-reject action: Silences the ringer and dispatches an automated SMS reply.
         */
        fun rejectCallAndSendMessage(context: Context, customMessage: String? = null): Boolean {
            val number = lastRingingNumber
            val messageToSend = customMessage ?: DEFAULT_AUTO_REPLY

            // Silence ringer
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.ringerMode = AudioManager.RINGER_MODE_SILENT

            isRinging = false

            if (!number.isNullOrBlank()) {
                try {
                    val smsManager: SmsManager = context.getSystemService(SmsManager::class.java)
                        ?: SmsManager.getDefault()
                    smsManager.sendTextMessage(number, null, messageToSend, null, null)
                    Log.i(TAG, "Sent automated SMS response to $number: '$messageToSend'")
                    VoiceSynthesisRouter.speak("Call silenced and auto-reply sent to $number, Sir.")
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send auto-reply SMS: ${e.message}")
                    VoiceSynthesisRouter.speak("Call silenced. Could not send SMS, Sir.")
                    return false
                }
            } else {
                VoiceSynthesisRouter.speak("Call silenced, Sir.")
                return true
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        Log.i(TAG, "Phone state changed: $stateStr | incoming: $incomingNumber")

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
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
            }
        }
    }
}
