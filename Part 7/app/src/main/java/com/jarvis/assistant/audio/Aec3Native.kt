package com.jarvis.assistant.audio

/** Original JNI wrapper around a pinned BSD WebRTC AEC3 source build. Fixed mono 16/24 kHz contract. */
class Aec3Native : EchoBackend {
    private var handle: Long
    init { System.loadLibrary("jarvis_aec3");handle=nativeCreate();check(handle != 0L) { "AEC3 initialization failed" } }
    @Synchronized override fun render24k(samples: ShortArray, delayMs: Int) { check(handle != 0L);nativeRender(handle,samples,delayMs) }
    @Synchronized override fun capture16k(input: ShortArray, output: ShortArray) { check(handle != 0L);nativeCapture(handle,input,output) }
    @Synchronized override fun reset() { check(handle != 0L);nativeReset(handle) }
    @Synchronized override fun close() { val old=handle;handle=0;if(old != 0L) nativeClose(old) }
    private external fun nativeCreate(): Long
    private external fun nativeRender(handle: Long, samples: ShortArray, delayMs: Int)
    private external fun nativeCapture(handle: Long, input: ShortArray, output: ShortArray)
    private external fun nativeReset(handle: Long)
    private external fun nativeClose(handle: Long)
}
