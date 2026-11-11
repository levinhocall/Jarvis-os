package com.jarvis.assistant.ui.orb

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

fun ConversationState.toVoiceOrbState(muted:Boolean=false,online:Boolean=true):OrbState = when {
    !online->OrbState.OFFLINE
    this==ConversationState.SPEAKING->OrbState.SPEAKING
    muted->OrbState.MUTED
    this==ConversationState.CONNECTING->OrbState.WAKE
    this==ConversationState.ERROR||this==ConversationState.FAILED->OrbState.ERROR
    this==ConversationState.LISTENING||this==ConversationState.CONNECTED||this==ConversationState.INTERRUPTED->OrbState.LISTENING
    this==ConversationState.THINKING||this==ConversationState.EXECUTING||this==ConversationState.RECOVERING->OrbState.THINKING
    else->OrbState.IDLE
}

/** Optional mount point. Observes existing PCM-derived amplitude; never starts a microphone. */
@Composable
fun JarvisVoiceCore(modifier:Modifier=Modifier,muted:Boolean=false,onTap:()->Unit={},onLongPress:()->Unit={}) {
    val owner=LocalLifecycleOwner.current
    var state by remember { mutableStateOf(OrbState.IDLE) }
    val levels=remember { mutableStateOf(OrbAudioLevels.Zero) }
    LaunchedEffect(owner,muted){owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
        combine(JarvisConversationService.conversationState,JarvisConversationService.connectionStatus) { source,connection -> source.toVoiceOrbState(muted,connection!="OFFLINE") }.collectLatest { visual ->
            state=visual
            levels.value=OrbAudioLevels.Zero // Clear stale playback before observing a new turn.
            JarvisConversationService.audioLevel.collect { rms ->
                // Service publishes measured playback/mic RMS. It does not publish true FFT bands.
                // Do not invent three spectral bands by multiplying RMS.
                levels.value=OrbAudioLevels(rms=rms)
            }
        }
    }}
    VoiceOrb(state,levels,modifier=modifier,onTap=onTap,onLongPress=onLongPress)
}
