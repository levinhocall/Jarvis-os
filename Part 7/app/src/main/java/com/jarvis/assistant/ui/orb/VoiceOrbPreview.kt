package com.jarvis.assistant.ui.orb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.sin

@Preview(name="Idle",showBackground=true,backgroundColor=0xFF050B16)
@Composable private fun IdlePreview(){VoiceOrb(OrbState.IDLE,OrbAudioLevels.Zero)}
@Preview(name="Wake",showBackground=true,backgroundColor=0xFF050B16)
@Composable private fun WakePreview(){VoiceOrb(OrbState.WAKE,OrbAudioLevels.Zero)}
@Preview(name="Listening",showBackground=true,backgroundColor=0xFF050B16)
@Composable private fun ListeningPreview(){VoiceOrb(OrbState.LISTENING,OrbAudioLevels(.6f,.4f,.5f,.3f))}
@Preview(name="Thinking",showBackground=true,backgroundColor=0xFF050B16)
@Composable private fun ThinkingPreview(){VoiceOrb(OrbState.THINKING,OrbAudioLevels.Zero)}
@Preview(name="Speaking",showBackground=true,backgroundColor=0xFF050B16)
@Composable private fun SpeakingPreview(){VoiceOrb(OrbState.SPEAKING,OrbAudioLevels(.8f,.5f,.4f,.7f))}
@Preview(name="Error",showBackground=true,backgroundColor=0xFF050B16)
@Composable private fun ErrorPreview(){VoiceOrb(OrbState.ERROR,OrbAudioLevels.Zero)}
@Preview(name="Muted",showBackground=true,backgroundColor=0xFF050B16)
@Composable private fun MutedPreview(){VoiceOrb(OrbState.MUTED,OrbAudioLevels.Zero)}
@Preview(name="Offline",showBackground=true,backgroundColor=0xFF050B16)
@Composable private fun OfflinePreview(){VoiceOrb(OrbState.OFFLINE,OrbAudioLevels.Zero)}

/** Synthetic audio exists only in this explicitly labelled design preview. */
@Preview(name="Preview-only state cycler",showBackground=true,backgroundColor=0xFF050B16)
@Composable private fun StateCyclerPreview(){
    var state by remember {mutableStateOf(OrbState.IDLE)}
    val levels=remember {mutableStateOf(OrbAudioLevels.Zero)}
    LaunchedEffect(Unit){var start=0L;while(true){withFrameNanos {now->
        if(start==0L)start=now
        val seconds=(now-start)/1e9f
        state=OrbState.entries[(seconds/3).toInt()%8]
        val v=.5f+.5f*sin(seconds*5)
        levels.value=OrbAudioLevels(v,v*.7f,v*.5f,v*.3f)
    }}}
    MaterialTheme {Column(Modifier.background(Color(0xFF050B16))){Text("DESIGN PREVIEW • synthetic audio",color=Color.White);VoiceOrb(state,levels)}}
}
