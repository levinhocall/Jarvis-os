package com.jarvis.assistant.automation.mission

import org.junit.Assert.*
import org.junit.Test

class ExplicitMessageAuthorizationTest {
    @Test fun directAuthorizationMakesOnlyItsOwnPlanRunnable() {
        val authorization=ExplicitMessageAuthorization.fromUtterance("send WhatsApp to Najeeb saying hi","Najeeb","hi")!!
        val planned=MissionEngine.planMission("send WhatsApp to Najeeb saying hi")
        assertEquals(MissionOverallStatus.WAITING_FOR_APPROVAL,planned.status)
        val accepted=authorization.applyTo(planned)
        assertEquals(MissionApprovalState.APPROVED,accepted.approvalState)
        assertEquals(MissionOverallStatus.PLANNING,accepted.status)
        assertNull(accepted.approvalPrompt)
        val other=MissionEngine.planMission("send WhatsApp to Someone saying bye")
        assertSame(other,authorization.applyTo(other))
        val mixed=planned.copy(steps=planned.steps+MissionStep(title="Delete",toolName="whatsapp_delete"))
        assertSame(mixed,authorization.applyTo(mixed))
        val previouslyRun=planned.copy(steps=planned.steps.map {it.copy(status=MissionStepStatus.COMPLETED)})
        assertSame(previouslyRun,authorization.applyTo(previouslyRun))
    }
    @Test fun directCommandsAuthorizeOnlyTheStatedPayload() {
        for (command in listOf("Open WhatsApp and send message to Najeeb hi", "send WhatsApp to Najeeb saying hi", "whatsapp pe Najeeb ko hi bhej do")) {
            val authorization=ExplicitMessageAuthorization.fromUtterance(command,"Najeeb","hi")
            assertNotNull(command,authorization)
            assertTrue(authorization!!.matches("Najeeb","hi"))
            assertFalse(authorization.matches("Someone else","hi"))
            assertFalse(authorization.matches("Najeeb","changed message"))
        }
    }
    @Test fun questionsNegationsAndQuotedCommandsAreNotAuthorization() {
        for (command in listOf("Don't send message to Najeeb hi", "Can you send message to Najeeb hi?", "He said send message to Najeeb hi", "", "send message to Najeeb bye")) {
            assertNull(command,ExplicitMessageAuthorization.fromUtterance(command,"Najeeb","hi"))
        }
    }
}
