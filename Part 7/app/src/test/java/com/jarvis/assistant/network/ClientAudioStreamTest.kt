package com.jarvis.assistant.network
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ClientAudioStreamTest {
    @Test fun defaultServerVadPathRemainsContinuous() {
        val frames=mutableListOf<String>();val stream=ClientAudioStream(false) { frames.add(it);true }
        stream.audio(byteArrayOf(1,2));assertFalse(stream.end());stream.audio(byteArrayOf(3,4))
        assertEquals(2,frames.size)
    }
    @Test fun acceptedSpeechControlsEndAndReopenWithoutSilenceReopeningIt() {
        val frames=mutableListOf<String>();val stream=ClientAudioStream(true) { frames.add(it);true }
        assertFalse(stream.end())
        assertTrue(stream.audio(byteArrayOf(1,2)))
        assertTrue(stream.end());assertFalse(stream.end())
        assertFalse(stream.audio(ByteArray(640)))
        stream.resume();assertTrue(stream.audio(byteArrayOf(3,4)))
        assertEquals(3,frames.size)
        assertTrue(JsonParser.parseString(frames[1]).asJsonObject.getAsJsonObject("realtimeInput").get("audioStreamEnd").asBoolean)
        assertArrayEquals(byteArrayOf(3,4),Base64.getDecoder().decode(JsonParser.parseString(frames[2]).asJsonObject.getAsJsonObject("realtimeInput").getAsJsonObject("audio").get("data").asString))
    }
    @Test fun reconnectResetDropsPreviousInputState() {
        val stream=ClientAudioStream(true) { true }
        stream.audio(byteArrayOf(1,2));stream.end();stream.reset()
        assertFalse(stream.end());assertTrue(stream.audio(byteArrayOf(3,4)))
    }
    @Test fun rejectedWritesDoNotCreateFictitiousAudioOrEndState() {
        var accept=false;val stream=ClientAudioStream(true) { accept }
        assertFalse(stream.audio(byteArrayOf(1,2)));assertFalse(stream.end())
        accept=true;assertTrue(stream.audio(byteArrayOf(1,2)))
        accept=false;assertFalse(stream.end())
        accept=true;assertTrue(stream.end())
    }
}
