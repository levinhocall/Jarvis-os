package com.jarvis.assistant.ui.orb

import android.app.ActivityManager
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive

@Composable
fun VoiceOrb(state: OrbState, levels: OrbAudioLevels, modifier: Modifier = Modifier, size: Dp = 240.dp,
             palette: OrbPalette = OrbPalette.Cyan, onTap: () -> Unit = {}, onLongPress: () -> Unit = {}) {
    // Value API is convenient; use the State overload below to avoid audio recompositions.
    val latest=rememberUpdatedState(levels)
    VoiceOrb(state,latest,modifier,size,palette,onTap,onLongPress)
}

/** Preferred service integration: levels.value is read in draw, not composition. */
@Composable
fun VoiceOrb(state: OrbState, levels: State<OrbAudioLevels>, modifier: Modifier = Modifier, size: Dp = 240.dp,
             palette: OrbPalette = OrbPalette.Cyan, onTap: () -> Unit = {}, onLongPress: () -> Unit = {}) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val renderer=remember { OrbRenderer() }
    val motion=remember { OrbMotion(state) }
    val counters=LocalOrbDebugCounters.current
    val reflection=LocalOrbReflection.current
    SideEffect { counters?.let { it.compositions++ } }
    val input=rememberUpdatedState(state)
    var reduced by remember { mutableStateOf(com.jarvis.assistant.util.VisualMotionSettings.isReduced(context)) }
    DisposableEffect(context,renderer) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange:Boolean){reduced=com.jarvis.assistant.util.VisualMotionSettings.isReduced(context)}
        }
        val prefs=context.getSharedPreferences("jarvis_visual_settings",android.content.Context.MODE_PRIVATE)
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key=="reduced_motion")reduced=com.jarvis.assistant.util.VisualMotionSettings.isReduced(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener);context.contentResolver.unregisterContentObserver(observer);renderer.release()}
    }
    val low=remember { context.getSystemService(ActivityManager::class.java).isLowRamDevice || context.getSystemService(PowerManager::class.java).isPowerSaveMode }
    var frame by remember { mutableLongStateOf(0L) }
    val label=when(state){OrbState.WAKE->"Jarvis is waking";OrbState.IDLE->"Jarvis is ready";OrbState.LISTENING->"Jarvis is listening";OrbState.THINKING->"Jarvis is thinking";OrbState.SPEAKING->"Jarvis is speaking";OrbState.ERROR->"Jarvis encountered an error";OrbState.MUTED->"Jarvis microphone is muted";OrbState.OFFLINE->"Jarvis is offline"}
    LaunchedEffect(owner,reduced) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if(context.getSystemService(PowerManager::class.java).isPowerSaveMode)renderer.reducedQuality=true
            var previous=0L;var slow=0
            while(isActive && !reduced){withFrameNanos { now ->
                if(previous!=0L){val ms=(now-previous)/1_000_000f;slow=if(ms>22f)slow+1 else 0;if(slow>=30)renderer.reducedQuality=true}
                previous=now;frame=now
            }}
        }
    }
    val previousDraw=remember { LongArray(1) }
    val slowDraws=remember { IntArray(1) }
    Canvas(modifier.size(size).combinedClickable(onClick=onTap,onLongClick=onLongPress).semantics { contentDescription=label }) {
        val now=frame
        val dt=if(previousDraw[0]==0L||reduced)1f/60f else ((now-previousDraw[0])/1e9f).coerceIn(0f,.1f)
        previousDraw[0]=now
        motion.step(dt,input.value,if(reduced)OrbAudioLevels.Zero else levels.value,reduced)
        renderer.reducedQuality=renderer.reducedQuality||low
        renderer.reflection=reflection
        val begin=System.nanoTime()
        renderer.draw(drawContext.canvas.nativeCanvas,this.size.width,this.size.height,motion,palette,reduced)
        counters?.let { it.draws++ }
        slowDraws[0]=if(System.nanoTime()-begin>12_000_000L)slowDraws[0]+1 else 0
        if(slowDraws[0]>=30)renderer.reducedQuality=true
    }
}
