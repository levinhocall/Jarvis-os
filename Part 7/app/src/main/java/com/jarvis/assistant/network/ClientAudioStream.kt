package com.jarvis.assistant.network

import java.util.Base64

/** Serializes documented Live audio/end hints. Server VAD remains enabled.
 * With client hints opted in, an ended stream stays closed until accepted speech resumes it.
 */
class ClientAudioStream(private val clientHints: Boolean, private val send: (String) -> Boolean) {
    private var ended=false
    private var hasAudio=false
    @Synchronized fun audio(pcm: ByteArray): Boolean {
        if(pcm.isEmpty() || clientHints && ended) return false
        val base64=Base64.getEncoder().encodeToString(pcm)
        val accepted=send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$base64\"}}}")
        if(accepted) hasAudio=true
        return accepted
    }
    @Synchronized fun end(): Boolean {
        if(!clientHints || ended || !hasAudio) return false
        val accepted=send("{\"realtimeInput\":{\"audioStreamEnd\":true}}")
        if(accepted) { ended=true;hasAudio=false }
        return accepted
    }
    @Synchronized fun resume() { ended=false }
    @Synchronized fun reset() { ended=false;hasAudio=false }
}
