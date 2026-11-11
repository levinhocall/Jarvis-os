package com.jarvis.assistant.ui.orb

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.annotation.MainThread
import com.jarvis.assistant.audio.MicrophoneOwner
import com.jarvis.assistant.audio.MicrophoneOwnership
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Standalone foreground UI capture is optional. Existing-service users feed levels instead. */
class AudioLevelTracker(context: Context, private val lifecycle:Lifecycle?=captureLifecycle(context)) : AutoCloseable {
    private val context=context.applicationContext
    private val output=MutableStateFlow(OrbAudioLevels.Zero)
    val levels: StateFlow<OrbAudioLevels> = output.asStateFlow()
    private val running=AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    private val requested=AtomicBoolean(false)
    private val mainHandler=Handler(Looper.getMainLooper())
    private val restart=Runnable { if(requested.get()&&!closed.get())start() }
    @Volatile private var recorder:AudioRecord?=null
    @Volatile private var thread:Thread?=null
    private val observer=object:DefaultLifecycleObserver {
        override fun onStop(owner:LifecycleOwner){stop()}
        override fun onDestroy(owner:LifecycleOwner){close()}
    }
    init { lifecycle?.addObserver(observer) }
    @MainThread @Synchronized fun start() {
        if(closed.get() || lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)!=true){output.value=OrbAudioLevels.Zero;return}
        if(thread!=null){if(!running.get())requested.set(true);return}
        requested.set(false)
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){output.value=OrbAudioLevels.Zero;return}
        val lease=MicrophoneOwnership.acquire(MicrophoneOwner.VOICE_SESSION) ?: run {output.value=OrbAudioLevels.Zero;return}
        running.set(true)
        thread=Thread({
            val pcm=ShortArray(1024);val analyzer=SpectrumAnalyzer()
            var audio:AudioRecord?=null
            try {
                val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                if(min<=0)return@Thread
                audio=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,max(min,4096))
                recorder=audio
                if(!running.get()||audio.state!=AudioRecord.STATE_INITIALIZED)return@Thread
                audio.startRecording()
                while(running.get()){
                    var count=0
                    while(count<1024&&running.get()){
                        val n=audio.read(pcm,count,1024-count,AudioRecord.READ_BLOCKING)
                        if(n<=0)throw IllegalStateException("Audio capture stopped")
                        count+=n
                    }
                    if(running.get()&&count==1024)output.value=analyzer.analyze(pcm)
                }
            }catch(_:Exception){output.value=OrbAudioLevels.Zero}
            finally{
                runCatching { audio?.stop() };runCatching { audio?.release() };lease.close()
                synchronized(this){recorder=null;thread=null;running.set(false);output.value=OrbAudioLevels.Zero}
                if(requested.get()&&!closed.get())mainHandler.post(restart)
            }
        },"Jarvis-Orb-Spectrum")
        thread?.start()
    }
    fun stop(){requested.set(false);running.set(false);runCatching { recorder?.stop() };output.value=OrbAudioLevels.Zero}
    fun feedExternalLevels(levels:OrbAudioLevels) {
        // TODO: For another TTS engine, feed its measured PCM RMS/bands here, never a fabricated waveform.
        // Jarvis's existing service is wired by JarvisOrbAdapter and requires no second recorder.
        if(!closed.get()&&thread==null)output.value=levels.sanitized()
    }
    @MainThread override fun close(){closed.set(true);stop();mainHandler.removeCallbacks(restart);lifecycle?.removeObserver(observer)}
}

private fun captureLifecycle(context:Context):Lifecycle? {
    var current=context
    repeat(12){
        val candidate=current
        if(candidate is LifecycleOwner)return candidate.lifecycle
        val next=(candidate as?ContextWrapper)?.baseContext ?: return null
        if(next===candidate)return null
        current=next
    }
    return null
}

/** Reused radix-2 FFT buffers and Hann window. One immutable StateFlow value per frame. */
internal class SpectrumAnalyzer {
    private val real=FloatArray(1024);private val imaginary=FloatArray(1024)
    private val window=FloatArray(1024){(.5-.5*cos(2*PI*it/1023)).toFloat()}
    private val cosine=FloatArray(512){cos(-2*PI*it/1024).toFloat()}
    private val sine=FloatArray(512){sin(-2*PI*it/1024).toFloat()}
    private val smoothed=FloatArray(4)
    fun analyze(pcm:ShortArray):OrbAudioLevels {
        require(pcm.size==1024)
        var sum=0.0
        for(i in 0..1023){val v=pcm[i]/32768f;sum+=v*v;real[i]=v*window[i];imaginary[i]=0f}
        var j=0
        for(i in 1..1023){var bit=512;while(j and bit !=0){j=j xor bit;bit=bit shr 1};j=j xor bit
            if(i<j){val v=real[i];real[i]=real[j];real[j]=v}}
        var len=2
        while(len<=1024){val half=len/2;val stride=1024/len
            var base=0
            while(base<1024){for(k in 0 until half){val a=base+k;val b=a+half;val q=k*stride
                val tr=real[b]*cosine[q]-imaginary[b]*sine[q];val ti=real[b]*sine[q]+imaginary[b]*cosine[q]
                real[b]=real[a]-tr;imaginary[b]=imaginary[a]-ti;real[a]+=tr;imaginary[a]+=ti};base+=len}
            len*=2
        }
        var bass=0.0;var mid=0.0;var treble=0.0
        for(i in 1..512){val power=(real[i].toDouble()*real[i]+imaginary[i].toDouble()*imaginary[i])*2/(1024.0*1024*.375)
            val hz=i*16000f/1024
            if(hz<250)bass+=power else if(hz<=2000)mid+=power else treble+=power}
        smooth(0,normalize(sqrt(sum/1024).toFloat()));smooth(1,normalize(sqrt(bass).toFloat()))
        smooth(2,normalize(sqrt(mid).toFloat()));smooth(3,normalize(sqrt(treble).toFloat()))
        return OrbAudioLevels(smoothed[0],smoothed[1],smoothed[2],smoothed[3])
    }
    private fun smooth(i:Int,target:Float){smoothed[i]=OrbMotion.smoothLevel(smoothed[i],target,.064f)}
    companion object {
        fun normalize(rms:Float):Float {
            if(!rms.isFinite()||rms<=0)return 0f
            val db=20f*log10(rms)
            val x=((db+55)/49).coerceIn(0f,1f)
            return x*x*(3-2*x)
        }
    }
}
