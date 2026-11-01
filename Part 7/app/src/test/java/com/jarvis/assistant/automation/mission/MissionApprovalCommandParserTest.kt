package com.jarvis.assistant.automation.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissionApprovalCommandParserTest {
    @Test
    fun onlyExplicitApprovalPhrasesApproveMission() {
        assertEquals(true, MissionApprovalCommandParser.parse("Confirm mission"))
        assertEquals(true, MissionApprovalCommandParser.parse("Haan approve karo!"))
        assertNull(MissionApprovalCommandParser.parse("yes"))
        assertNull(MissionApprovalCommandParser.parse("okay"))
        assertNull(MissionApprovalCommandParser.parse("sounds good"))
    }

    @Test
    fun explicitRejectionPhrasesRejectMission() {
        assertEquals(false, MissionApprovalCommandParser.parse("cancel mission"))
        assertEquals(false, MissionApprovalCommandParser.parse("mat karo"))
        assertNull(MissionApprovalCommandParser.parse("no"))
    }
}
