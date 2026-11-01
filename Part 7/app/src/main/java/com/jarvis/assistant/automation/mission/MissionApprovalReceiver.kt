package com.jarvis.assistant.automation.mission

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MissionApprovalReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_APPROVE = "com.jarvis.assistant.action.MISSION_APPROVE"
        const val ACTION_REJECT = "com.jarvis.assistant.action.MISSION_REJECT"
        const val EXTRA_MISSION_ID = "mission_id"
        const val EXTRA_APPROVED = "approved"
        private const val TAG = "MissionApprovalReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val missionId = intent.getStringExtra(EXTRA_MISSION_ID)?.takeIf { it.isNotBlank() } ?: return
        val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                MissionEngine.resumeNonTerminalMissions(appContext, preferredMissionId = missionId)
                MissionEngine.resolveApproval(missionId, approved, appContext)
                MissionApprovalNotifier.cancel(appContext, missionId)
            } catch (error: Exception) {
                Log.e(TAG, "Could not resolve mission approval.", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
