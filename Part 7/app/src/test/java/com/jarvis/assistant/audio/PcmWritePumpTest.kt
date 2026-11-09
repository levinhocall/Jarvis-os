package com.jarvis.assistant.audio

import org.junit.Assert.*
import org.junit.Test

class PcmWritePumpTest {
    @Test fun partialWritesPreserveEveryByteInOrder() {
        val calls = mutableListOf<Pair<Int,Int>>()
        val written = PcmWritePump.write(ByteArray(10), { true }) { offset, remaining ->
            calls.add(offset to remaining)
            minOf(4,remaining)
        }
        assertEquals(10,written)
        assertEquals(listOf(0 to 10,4 to 6,8 to 2),calls)
    }
    @Test fun cancellationStopsRemainderWithoutReplayingOldAudio() {
        var active = true
        assertEquals(4,PcmWritePump.write(ByteArray(10), { active }) { _, _ -> active=false;4 })
    }
    @Test fun hardwareFailureIsReturnedWithoutBusyLooping() {
        var calls=0
        assertEquals(-6,PcmWritePump.write(ByteArray(10), {true}) {_,_->calls++;-6})
        assertEquals(1,calls)
        assertEquals(0,PcmWritePump.write(ByteArray(10), {true}) {_,_->0})
    }
}
