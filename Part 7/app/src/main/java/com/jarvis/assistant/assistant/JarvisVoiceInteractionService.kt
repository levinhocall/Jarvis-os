package com.jarvis.assistant.assistant

import android.service.voice.VoiceInteractionService

/** Android assistant-role binding only. Audio remains owned by JarvisConversationService. */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(android.os.Bundle(),android.service.voice.VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE)
    }
}
