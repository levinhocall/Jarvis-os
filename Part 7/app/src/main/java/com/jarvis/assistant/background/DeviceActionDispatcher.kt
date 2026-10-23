package com.jarvis.assistant.background

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.util.Log

sealed class ActionDispatchResult {
    data class Success(val message: String) : ActionDispatchResult()
    data class Failed(val error: String) : ActionDispatchResult()
    data class PermissionRequired(val permission: String, val reason: String) : ActionDispatchResult()
}

/**
 * Dispatches hardware actions, system intents, telephony controls,
 * and app launching with permission validation and safe error handling.
 */
class DeviceActionDispatcher(
    private val context: Context
) {
    companion object {
        private const val TAG = "DeviceActionDispatcher"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    /**
     * Toggles hardware flashlight state.
     */
    fun setFlashlight(enabled: Boolean): ActionDispatchResult {
        val cm = cameraManager ?: return ActionDispatchResult.Failed("Camera service unavailable")
        return try {
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: return ActionDispatchResult.Failed("No camera found on device")
            cm.setTorchMode(cameraId, enabled)
            val state = if (enabled) "ON" else "OFF"
            ActionDispatchResult.Success("Flashlight turned $state")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flashlight", e)
            ActionDispatchResult.Failed("Flashlight error: ${e.message}")
        }
    }

    /**
     * Adjusts system media volume.
     */
    fun adjustVolume(direction: Int): ActionDispatchResult {
        val am = audioManager ?: return ActionDispatchResult.Failed("Audio service unavailable")
        return try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
            ActionDispatchResult.Success("Volume adjusted")
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting volume", e)
            ActionDispatchResult.Failed("Volume error: ${e.message}")
        }
    }

    /**
     * Sets media volume to an absolute percentage (0 - 100).
     */
    fun setVolumePercent(percent: Int): ActionDispatchResult {
        val am = audioManager ?: return ActionDispatchResult.Failed("Audio service unavailable")
        return try {
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((percent.coerceIn(0, 100) / 100.0) * maxVol).toInt()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            ActionDispatchResult.Success("Media volume set to $percent%")
        } catch (e: Exception) {
            ActionDispatchResult.Failed("Failed to set volume: ${e.message}")
        }
    }

    /**
     * Launches an application by package name or display label.
     */
    fun launchApp(packageOrName: String): ActionDispatchResult {
        val pm = context.packageManager
        // 1. Try direct package launch
        var intent = pm.getLaunchIntentForPackage(packageOrName)

        // 2. Try label lookup if not a package
        if (intent == null) {
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val match = apps.firstOrNull { app ->
                pm.getApplicationLabel(app).toString().equals(packageOrName, ignoreCase = true)
            }
            if (match != null) {
                intent = pm.getLaunchIntentForPackage(match.packageName)
            }
        }

        return if (intent != null) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionDispatchResult.Success("Launched application '$packageOrName'")
            } catch (e: Exception) {
                ActionDispatchResult.Failed("Failed to launch application: ${e.message}")
            }
        } else {
            ActionDispatchResult.Failed("Application '$packageOrName' is not installed")
        }
    }

    /**
     * Initiates a phone call to a given number.
     */
    fun placePhoneCall(phoneNumber: String): ActionDispatchResult {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isBlank()) {
            return ActionDispatchResult.Failed("Invalid phone number provided")
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionDispatchResult.Success("Calling $cleanNumber")
        } catch (e: SecurityException) {
            ActionDispatchResult.PermissionRequired(
                android.Manifest.permission.CALL_PHONE,
                "CALL_PHONE permission is required to place direct phone calls."
            )
        } catch (e: Exception) {
            ActionDispatchResult.Failed("Call initiation failed: ${e.message}")
        }
    }

    /**
     * Opens SMS app composed to a phone number.
     */
    fun composeSms(phoneNumber: String, messageText: String = ""): ActionDispatchResult {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
                if (messageText.isNotBlank()) {
                    putExtra("sms_body", messageText)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionDispatchResult.Success("Opened SMS composer for $phoneNumber")
        } catch (e: Exception) {
            ActionDispatchResult.Failed("Failed to open SMS composer: ${e.message}")
        }
    }
}
