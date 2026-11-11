package com.jarvis.assistant.automation.mission

import org.junit.Assert.assertEquals
import org.junit.Test

class MissionStartupLoadingTest {
    @Test fun constructionDoesNotReadStorageAndRestoreLoadsOnlyOnce() {
        var reads=0
        val backing=InMemoryCheckpointStorage()
        val storage=object:CheckpointStorage by backing {
            override fun getAllMissionStates():List<LongHorizonMissionState> {
                reads++
                return backing.getAllMissionStates()
            }
        }
        val coordinator=LongHorizonMissionCoordinator(storage)
        assertEquals(0,reads)
        coordinator.restorePersistedState()
        assertEquals(1,reads)
        coordinator.restorePersistedState()
        coordinator.getMission("absent")
        assertEquals(1,reads)
    }
}
