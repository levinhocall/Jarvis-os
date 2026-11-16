package com.jarvis.assistant.audio.turn

import kotlin.math.*

/** Smart Turn v3 Whisper contract: 16kHz, left-padded last 8s, normalized, 80x800 Slaney log-mel.
 * Original fixed-size mixed-radix FFT; mathematical preprocessing follows Pipecat/Transformers.
 * See assets/licenses/whisper-turn-NOTICE.txt. Buffers are reused, one analysis worker only.
 */
class WhisperTurnFeatures {
    private val waveform=DoubleArray(128000)
    private val window=DoubleArray(400) { .5-.5*cos(2*PI*it/400) }
    private val cosines=DoubleArray(400) { cos(2*PI*it/400) }
    private val sines=DoubleArray(400) { -sin(2*PI*it/400) }
    private val input=DoubleArray(400)
    private val real=DoubleArray(400)
    private val imaginary=DoubleArray(400)
    private val scratchR=Array(7) { DoubleArray(400) }
    private val scratchI=Array(7) { DoubleArray(400) }
    private val power=DoubleArray(201)
    private val filters=DoubleArray(80*201)
    private val output=FloatArray(80*800)
    init {
        val maxMel=15+ln(8.0)*27/ln(6.4)
        val edges=DoubleArray(82) { index ->
            val m=maxMel*index/81
            if(m < 15) 200*m/3 else 1000*exp((m-15)*ln(6.4)/27)
        }
        for(m in 0 until 80) for(bin in 0..200) {
            val hz=bin*40.0
            filters[m*201+bin]=max(0.0,min((hz-edges[m])/(edges[m+1]-edges[m]),
                (edges[m+2]-hz)/(edges[m+2]-edges[m+1])))*2/(edges[m+2]-edges[m])
        }
    }
    /** Returns reused output; caller must consume it before another invocation. */
    fun extract(pcm: ByteArray): FloatArray {
        require(pcm.size % 2 == 0)
        waveform.fill(0.0)
        val count=min(pcm.size/2,waveform.size)
        val byteStart=pcm.size-count*2
        val start=waveform.size-count
        var mean=0.0
        for(i in 0 until count) {
            val offset=byteStart+i*2
            val sample=((pcm[offset].toInt() and 255) or (pcm[offset+1].toInt() shl 8)).toShort()
            waveform[start+i]=sample/32768.0;mean+=waveform[start+i]
        }
        mean/=waveform.size
        var variance=0.0
        for(v in waveform) variance+=(v-mean)*(v-mean)
        val scale=sqrt(variance/waveform.size+1e-7)
        for(i in waveform.indices) waveform[i]=((waveform[i]-mean)/scale).toFloat().toDouble()
        var maximum=Float.NEGATIVE_INFINITY
        for(frame in 0 until 800) {
            for(j in 0 until 400) {
                var index=frame*160+j-200
                if(index < 0) index=-index
                if(index >= waveform.size) index=2*waveform.size-2-index
                input[j]=waveform[index]*window[j]
            }
            fft(0,1,400,0,0)
            for(bin in 0..200) power[bin]=real[bin]*real[bin]+imaginary[bin]*imaginary[bin]
            for(m in 0 until 80) {
                var value=0.0
                for(bin in 0..200) value+=power[bin]*filters[m*201+bin]
                val log=log10(max(1e-10,value)).toFloat()
                output[m*800+frame]=log;maximum=max(maximum,log)
            }
        }
        for(i in output.indices) output[i]=(max(output[i],maximum-8)+4)/4
        return output
    }
    private fun fft(offset: Int, stride: Int, n: Int, out: Int, depth: Int) {
        if(n == 1) { real[out]=input[offset];imaginary[out]=0.0;return }
        val radix=if(n%2 == 0) 2 else 5
        val part=n/radix
        for(r in 0 until radix) fft(offset+r*stride,stride*radix,part,out+r*part,depth+1)
        val rr=scratchR[depth];val ii=scratchI[depth]
        for(k in 0 until n) {
            var re=0.0;var im=0.0
            for(r in 0 until radix) {
                val source=out+r*part+k%part
                val angle=(r*k*(400/n))%400
                re+=real[source]*cosines[angle]-imaginary[source]*sines[angle]
                im+=real[source]*sines[angle]+imaginary[source]*cosines[angle]
            }
            rr[k]=re;ii[k]=im
        }
        for(k in 0 until n) { real[out+k]=rr[k];imaginary[out+k]=ii[k] }
    }
}
