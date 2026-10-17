package com.jarvis.assistant.automation.tools

import com.jarvis.assistant.automation.tools.ApprovalLedger.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalLedgerTest {
    private var now = 1_000L
    private val ledger = ApprovalLedger(clock = { now }, ttlMs = 60_000L)
    private val identity = listOf("Rahul", "kal milte hain")

    @Test
    fun firstRequestIsNeverApprovedEvenWhenTheInstructionSoundsAffirmative() {
        assertEquals(Verdict.PENDING, ledger.check("send_whatsapp_message", identity, "haan Rahul ko bhej do kal milte hain"))
    }

    @Test
    fun modelCannotSelfApproveWithinTheSameUserTurn() {
        val turn = "Rahul ko message bhej do"
        assertEquals(Verdict.PENDING, ledger.check("send_whatsapp_message", identity, turn))
        assertEquals(Verdict.PENDING, ledger.check("send_whatsapp_message", identity, turn))
        assertEquals(Verdict.PENDING, ledger.check("send_whatsapp_message", identity, ""))
    }

    @Test
    fun affirmativeReplyInANewTurnApprovesExactlyOnce() {
        ledger.check("call_contact", listOf("Mom"), "call mom")
        assertEquals(Verdict.APPROVED, ledger.check("call_contact", listOf("Mom"), "yes please"))
        // approval is consumed: a repeated call needs a fresh confirmation
        assertEquals(Verdict.PENDING, ledger.check("call_contact", listOf("Mom"), "yes please"))
    }

    @Test
    fun hindiAndHinglishConfirmationsAreRecognised() {
        listOf("haan kar do", "हाँ", "ठीक है", "theek hai", "bilkul").forEach { reply ->
            val l = ApprovalLedger(clock = { now })
            l.check("call_contact", listOf("Papa"), "papa ko call lagao")
            assertEquals("reply=$reply", Verdict.APPROVED, l.check("call_contact", listOf("Papa"), reply))
        }
    }

    @Test
    fun negativeReplyDeniesAndWinsOverAffirmativeWords() {
        ledger.check("call_contact", listOf("Mom"), "call mom")
        assertEquals(Verdict.DENIED, ledger.check("call_contact", listOf("Mom"), "nahi mat karo, ok"))
        // denied request is gone: asking again starts over
        assertEquals(Verdict.PENDING, ledger.check("call_contact", listOf("Mom"), "yes"))
    }

    @Test
    fun unrelatedReplyKeepsTheRequestPending() {
        ledger.check("call_contact", listOf("Mom"), "call mom")
        assertEquals(Verdict.PENDING, ledger.check("call_contact", listOf("Mom"), "what is the weather today"))
        assertEquals(Verdict.APPROVED, ledger.check("call_contact", listOf("Mom"), "yes"))
    }

    @Test
    fun changingTheArgumentsRequiresANewConfirmation() {
        ledger.check("send_whatsapp_message", identity, "send it to rahul")
        assertEquals(
            Verdict.PENDING,
            ledger.check("send_whatsapp_message", listOf("Rahul", "different text"), "yes")
        )
    }

    @Test
    fun requestsExpire() {
        ledger.check("call_contact", listOf("Mom"), "call mom")
        now += 61_000L
        assertEquals(Verdict.PENDING, ledger.check("call_contact", listOf("Mom"), "yes"))
    }

    @Test
    fun wordMatchingDoesNotFireInsideOtherWords() {
        ledger.check("call_contact", listOf("Mom"), "call mom")
        // "know" contains "no" and "okra" contains "ok" but neither is a whole-word reply
        assertEquals(Verdict.PENDING, ledger.check("call_contact", listOf("Mom"), "i know okra"))
    }
}
