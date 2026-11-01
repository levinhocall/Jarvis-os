package com.jarvis.assistant.audio

import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import com.jarvis.assistant.automation.notification.JarvisNotificationListenerService
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

object HeadsetWhisperManager {
    private const val TAG = "HeadsetWhisperManager"

    @Volatile
    var isWhisperModeActive = false
        private set

    private val isAwaitingVoiceReply = AtomicBoolean(false)
    private val isAwaitingReplyContent = AtomicBoolean(false)
    private var pendingReplySender: String? = null
    private var scope: CoroutineScope? = null
    private var replyWindowJob: Job? = null

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    val connected = state == 1
                    updateWhisperMode(connected, context)
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    val connected = state == BluetoothProfile.STATE_CONNECTED
                    updateWhisperMode(connected, context)
                }
            }
        }
    }

    fun init(context: Context, coroutineScope: CoroutineScope) {
        scope = coroutineScope
        VoiceSynthesisRouter.init(context)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        try {
            context.registerReceiver(headsetReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver registration warning: ${e.message}")
        }

        // Check initial headset status
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isWired = am?.isWiredHeadsetOn == true
        val isBt = am?.isBluetoothA2dpOn == true || am?.isBluetoothScoOn == true
        updateWhisperMode(isWired || isBt, context)
    }

    private fun updateWhisperMode(connected: Boolean, context: Context) {
        isWhisperModeActive = connected
        Log.i(TAG, "Headset Whisper Mode changed: active=$connected")
        if (connected) {
            // Lower volume slightly for comfortable earphone listening
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            am?.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.35f).toInt(), 0)
        }
    }

    /**
     * Proactively whispers an incoming high-priority message directly into the headset,
     * then opens a 6-second hands-free voice reply window without turning on the screen.
     */
    fun whisperNotification(sender: String, message: String, context: Context) {
        if (!isWhisperModeActive) return
        val app = context.applicationContext as? com.jarvis.assistant.JarvisApp ?: return
        if (!app.preferences.headsetWhisperEnabled) return

        val whisperText = "Sir, message from $sender: '$message'. Reply bhejna hai?"
        VoiceSynthesisRouter.speak(whisperText) {
            openVoiceReplyWindow(sender, context)
        }
    }

    private fun openVoiceReplyWindow(sender: String, context: Context) {
        pendingReplySender = sender
        isAwaitingReplyContent.set(false)
        isAwaitingVoiceReply.set(true)
        Log.i(TAG, "Opened 6-second voice reply window for: $sender")

        replyWindowJob?.cancel()
        replyWindowJob = scope?.launch {
            delay(6000L)
            if (isAwaitingVoiceReply.compareAndSet(true, false)) {
                pendingReplySender = null
                Log.i(TAG, "Voice reply window timed out without response.")
            }
        }
    }

    /**
     * Checks if user utterance is intended as a voice reply to the pending sender.
     */
    fun handleSpokenReply(userUtterance: String, context: Context): Boolean {
        if (!isAwaitingVoiceReply.get() || pendingReplySender == null) return false

        val sender = pendingReplySender ?: return false
        val clean = userUtterance.trim()
        if (clean.isBlank()) return false
        val normalized = clean.lowercase().replace(Regex("[.!?,]+"), " ").trim().replace(Regex("\\s+"), " ")

        val isNegativeConfirmation = normalized in setOf("no", "no thanks", "no reply", "not now", "cancel", "nahi", "nahin", "nahi bhejna", "nahin bhejna", "mat bhejo", "reply mat bhejo", "don't send", "do not send")
        if (isNegativeConfirmation) {
            isAwaitingVoiceReply.set(false)
            isAwaitingReplyContent.set(false)
            pendingReplySender = null
            replyWindowJob?.cancel()
            VoiceSynthesisRouter.speak("Understood, reply cancelled, Sir.")
            return true
        }

        val isPositiveOnly = normalized in setOf("yes", "yes please", "haan", "han", "ha", "haan bolo", "send", "send it", "send reply", "reply bhejo", "bhej do", "yes send")
        if (!isAwaitingReplyContent.get() && isPositiveOnly) {
            isAwaitingReplyContent.set(true)
            replyWindowJob?.cancel()
            VoiceSynthesisRouter.speak("What should I reply to $sender, Sir?")
            replyWindowJob = scope?.launch {
                delay(10_000L)
                if (isAwaitingVoiceReply.compareAndSet(true, false)) {
                    isAwaitingReplyContent.set(false)
                    pendingReplySender = null
                    Log.i(TAG, "Voice reply content window timed out without a message.")
                }
            }
            return true
        }

        // A dictated message in response to the explicit reply question is the user's
        // content and send intent. A bare affirmative is never sent as message text.
        val messageToSend = if (!isAwaitingReplyContent.get()) {
            clean.replace(Regex("(?i)^(haan|han|ha|yes|send|reply karo|bhejo|bolo)[\\s,:-]*"), "").trim()
        } else clean
        if (messageToSend.isBlank()) {
            isAwaitingReplyContent.set(true)
            VoiceSynthesisRouter.speak("Please say the message you want me to send, Sir.")
            return true
        }

        val sent = JarvisNotificationListenerService.sendSilentReply(sender, messageToSend, context)
        isAwaitingVoiceReply.set(false)
        isAwaitingReplyContent.set(false)
        pendingReplySender = null
        replyWindowJob?.cancel()

        if (sent) {
            VoiceSynthesisRouter.speak("Reply sent to $sender, Sir.")
        } else {
            VoiceSynthesisRouter.speak("Could not send inline reply to $sender, Sir.")
        }
        return true
    }
}
