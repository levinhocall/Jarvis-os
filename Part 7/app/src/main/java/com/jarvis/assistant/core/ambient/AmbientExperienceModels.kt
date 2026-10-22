package com.jarvis.assistant.core.ambient

import com.jarvis.assistant.data.model.ConversationState

data class OngoingMissionInfo(
    val missionId: String,
    val title: String,
    val currentStepDescription: String,
    val progressFraction: Float, // 0.0f to 1.0f
    val isCancellable: Boolean = true,
    val startTimeMs: Long = System.currentTimeMillis()
)

data class AmbientSnapshot(
    val conversationState: ConversationState,
    val activeMissions: List<OngoingMissionInfo>,
    val isExecutionLocked: Boolean,
    val isQuietHoursActive: Boolean,
    val isMicrophoneActive: Boolean,
    val isSpeakerActive: Boolean
)
