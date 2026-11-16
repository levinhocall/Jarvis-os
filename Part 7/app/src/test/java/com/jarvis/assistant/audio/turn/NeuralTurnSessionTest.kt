package com.jarvis.assistant.audio.turn

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NeuralTurnSessionTest {
    private class Model(var value: Float): TurnEndModel {
        var closes=0
        override fun probability(pcm: ByteArray)=value
        override fun close() { closes++ }
    }
    @Test fun confirmedCompletionAndIncompleteEvidenceAreBounded()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val model=Model(.99f);val session=NeuralTurnSession(scope) { model }
        try {
            session.initialize();assertEquals(280L,session.silenceTimeout(280))
            session.speech();session.accept(ByteArray(3200));session.request()
            withTimeout(5000) { while(session.silenceTimeout(280)!=180L) delay(5) }
            session.speech();assertEquals(280L,session.silenceTimeout(280))
            model.value=.01f;session.accept(ByteArray(3200));session.request()
            withTimeout(5000) { while(session.silenceTimeout(280)!=700L) delay(5) }
        } finally { session.close();session.close();scope.cancel() }
        assertEquals(1,model.closes)
    }
    @Test fun resumedSpeechInvalidatesInFlightPrediction()=runBlocking {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val session=NeuralTurnSession(scope) { object: TurnEndModel {
            override fun probability(pcm: ByteArray): Float { started.countDown();check(release.await(5,TimeUnit.SECONDS));return .99f }
            override fun close()=Unit
        } }
        try {
            session.initialize();session.speech();session.accept(ByteArray(3200));session.request()
            assertTrue(started.await(5,TimeUnit.SECONDS))
            session.speech();release.countDown();delay(50)
            assertEquals(280L,session.silenceTimeout(280))
        } finally { release.countDown();session.close();scope.cancel() }
    }
    @Test fun longUtteranceKeepsOnlyNewestEightSeconds()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val received=CompletableDeferred<ByteArray>()
        val session=NeuralTurnSession(scope) { object: TurnEndModel {
            override fun probability(pcm: ByteArray): Float { received.complete(pcm);return .5f }
            override fun close()=Unit
        } }
        try {
            session.initialize();session.speech()
            val chunk=ByteArray(4096) { it.toByte() }
            repeat(100) { session.accept(chunk) }
            session.request()
            val actual=withTimeout(5000) { received.await() }
            assertEquals(256000,actual.size)
            assertArrayEquals(ByteArray(256000) { it.toByte() },actual)
            session.discard();assertEquals(280L,session.silenceTimeout(280))
        } finally { session.close();scope.cancel() }
    }

}
