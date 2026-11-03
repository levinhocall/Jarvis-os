package com.jarvis.assistant.automation.notification

import org.junit.Assert.*
import org.junit.Test

class NotificationArchivePolicyTest {
    @Test fun archiveDropsOngoingSummaryAndOwnServiceNoise() {
        assertFalse(NotificationArchivePolicy.keep("other", "jarvis", true, false, true))
        assertFalse(NotificationArchivePolicy.keep("other", "jarvis", false, true, true))
        assertFalse(NotificationArchivePolicy.keep("jarvis", "jarvis", false, false, true))
        assertFalse(NotificationArchivePolicy.keep("other", "jarvis", false, false, false))
        assertTrue(NotificationArchivePolicy.keep("other", "jarvis", false, false, true))
    }
}
