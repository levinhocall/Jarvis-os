package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceLatencyTrackerTest {

    @Test
    fun `test sequential turn produces correct TTFT, TTFA, and total duration`() {
        var mockTime = 1000L
        val tracker = VoiceLatencyTracker { mockTime }

        tracker.markUserSpeechEnded(1000L)

        mockTime = 1350L
        tracker.markFirstTextReceived(1350L)

        mockTime = 1450L
        tracker.markFirstAudioReceived(1450L)

        mockTime = 2200L
        val completed = tracker.markTurnCompleted(2200L)

        assertNotNull(completed)
        assertEquals(350L, completed!!.timeToFirstTokenMs)
        assertEquals(450L, completed.timeToFirstAudioMs)
        assertEquals(1200L, completed.totalTurnDurationMs)

        assertEquals(350L, tracker.getAverageTtftMs())
        assertEquals(450L, tracker.getAverageTtfaMs())
    }

    @Test
    fun `test out-of-order marks are safely ignored`() {
        val tracker = VoiceLatencyTracker { 2000L }

        // 1. Text or Audio arriving before any turn started
        tracker.markFirstTextReceived(1500L)
        tracker.markFirstAudioReceived(1500L)
        assertNull(tracker.getLatestTurn())

        // 2. Mark turn speech ended at t=2000
        tracker.markUserSpeechEnded(2000L)

        // 3. Late chunk claiming timestamp before speech ended (t=1800) should be ignored
        tracker.markFirstTextReceived(1800L)

        // 4. Proper text at t=2300
        tracker.markFirstTextReceived(2300L)

        // 5. Duplicate text chunk at t=2500 should not overwrite the first token time
        tracker.markFirstTextReceived(2500L)

        val completed = tracker.markTurnCompleted(3000L)
        assertNotNull(completed)
        assertEquals(300L, completed!!.timeToFirstTokenMs)
    }

    @Test
    fun `test cancelCurrentTurn discards in-flight metrics`() {
        val tracker = VoiceLatencyTracker()
        tracker.markUserSpeechEnded(1000L)
        tracker.markFirstTextReceived(1200L)

        tracker.cancelCurrentTurn()
        val completed = tracker.markTurnCompleted(1500L)
        assertNull(completed)
        assertNull(tracker.getLatestTurn())
    }
}
