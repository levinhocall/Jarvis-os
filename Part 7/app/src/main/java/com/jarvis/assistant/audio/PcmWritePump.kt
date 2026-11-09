package com.jarvis.assistant.audio

/** Writes every PCM byte, including when the hardware accepts only part of a chunk. */
object PcmWritePump {
    fun write(bytes: ByteArray, mayContinue: () -> Boolean, sink: (Int, Int) -> Int): Int {
        var offset = 0
        while (offset < bytes.size && mayContinue()) {
            val remaining = bytes.size - offset
            val written = sink(offset, remaining)
            if (written <= 0) return written
            check(written <= remaining) { "PCM sink returned an invalid byte count" }
            offset += written
        }
        return offset
    }
}
