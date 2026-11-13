package com.jarvis.assistant.audio

import com.jarvis.assistant.data.model.AudioTimingConfidence
import org.junit.Assert.*
import org.junit.Test

/** No AudioTrack is opened here. These test observation ownership, not hardware playback. */
class PlaybackObservationLifecycleTest {
    @Test fun pauseAndSameEpochFlushRejectAlreadyProducedObservations() {
        val events = mutableListOf<AudioPlayer.PlaybackObservation>()
        val player = AudioPlayer({}, {}, onPlaybackObservation = { events += it })
        val before = AudioPlayer.PlaybackObservation(0, 0, true, .5f, AudioTimingConfidence.PLAYBACK_HEAD)
        assertTrue(player.isObservationCurrent(before))
        player.pause()
        assertFalse(player.isObservationCurrent(before))
        assertFalse(events.last().playing)
        val paused = events.last()
        player.flush(0)
        assertFalse(player.isObservationCurrent(paused))
        assertFalse(events.last().playing)
        player.release()
    }
    @Test fun releaseIsTerminalAndPublishesQuiet() {
        val events = mutableListOf<AudioPlayer.PlaybackObservation>()
        val player = AudioPlayer({}, {}, onPlaybackObservation = { events += it })
        player.flush(7)
        val stale = events.last().copy(playing = true, level = 1f)
        player.release(); player.release(); player.start()
        assertFalse(player.isObservationCurrent(stale))
        assertFalse(events.last().playing)
        assertEquals(0f, events.last().level, 0f)
        assertEquals(0, player.audioSessionId)
    }
}
