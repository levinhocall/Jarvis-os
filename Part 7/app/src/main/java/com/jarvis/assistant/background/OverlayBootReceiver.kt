package com.jarvis.assistant.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.PermissionHelper

/**
 * Restores only the ALWAYS-mode floating avatar after a normal device boot or app update.
 * Microphone capture and wake-word listening are deliberately never started from this receiver.
 */
class OverlayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as? JarvisApp ?: return
        val preferences = runCatching { app.preferences }.getOrNull() ?: return
        if (!preferences.isTaskOverlayEnabled || preferences.taskOverlayMode != "always") return
        if (!PermissionHelper.hasOverlayPermission(context)) {
            Log.i(TAG, "Overlay boot restore skipped: draw-over-other-apps access is not enabled.")
            return
        }

        val restoreIntent = Intent(context, JarvisConversationService::class.java)
            .setAction(JarvisConversationService.ACTION_RESTORE_OVERLAY)
        runCatching {
            ContextCompat.startForegroundService(context, restoreIntent)
        }.onFailure {
            Log.w(TAG, "Could not schedule overlay-only boot recovery.", it)
        }
    }

    private companion object {
        const val TAG = "OverlayBootReceiver"
    }
}
