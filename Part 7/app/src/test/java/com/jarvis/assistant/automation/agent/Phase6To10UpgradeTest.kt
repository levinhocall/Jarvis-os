package com.jarvis.assistant.automation.agent

import com.jarvis.assistant.audio.VoiceSynthesisRouter
import com.jarvis.assistant.automation.command.CommandParser
import com.jarvis.assistant.automation.command.JarvisCommand
import com.jarvis.assistant.automation.ledger.ExpenseLedgerTracker
import com.jarvis.assistant.automation.ledger.LedgerEntry
import com.jarvis.assistant.automation.ledger.TransactionType
import com.jarvis.assistant.automation.phone.CallScreeningReceiver
import com.jarvis.assistant.security.SecurityAction
import com.jarvis.assistant.security.SecurityGuardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase6To10UpgradeTest {

    @Before
    fun setUp() {
        ExpenseLedgerTracker.clearLedger()
        SecurityGuardManager.revokeElevatedAccess()
    }

    // --- PHASE 6 TESTS: Command & UI Wiring ---
    @Test
    fun testPhase6CommandParsing() {
        val recallCmd = CommandParser.parse("recall fridge price").command
        assertTrue(recallCmd is JarvisCommand.RecallFact)

        val stopKillSwitch = CommandParser.parse("bas karo").command
        assertTrue(stopKillSwitch is JarvisCommand.StopOrPause)
    }

    // --- PHASE 7 TESTS: Cinematic Neural Voice & Phonetics ---
    @Test
    fun testPhase7PhoneticFormatting() {
        val rawInput = "Bill amount is ₹1500 for order"
        val formatted = VoiceSynthesisRouter.normalizeForSpeech(rawInput)

        // ₹ symbol must be normalized to 'rupaye' for natural Hindi/Indian English TTS
        assertTrue(formatted.contains("rupaye"))
        assertFalse(formatted.contains("₹"))

        // Must append polite honorific Sir if missing
        assertTrue(formatted.endsWith("Sir."))
    }

    // --- PHASE 8 TESTS: Optical Camera Vision Parsing ---
    @Test
    fun testPhase8CameraVisionParsing() {
        val cmd1 = CommandParser.parse("camera se dekho").command
        assertTrue(cmd1 is JarvisCommand.CameraVisionScan)

        val cmd2 = CommandParser.parse("scan this bill").command
        assertTrue(cmd2 is JarvisCommand.CameraVisionScan)

        val cmd3 = CommandParser.parse("yeh kya hai").command
        assertTrue(cmd3 is JarvisCommand.CameraVisionScan)
    }

    // --- PHASE 9 TESTS: Security Guard & Voice Authorization ---
    @Test
    fun testPhase9SecurityGuardAuthorization() {
        assertFalse(SecurityGuardManager.isSessionElevated())

        // Elevated session grant and revoke
        SecurityGuardManager.grantElevatedAccess(5000L)
        assertTrue(SecurityGuardManager.isSessionElevated())
        assertTrue(SecurityGuardManager.isActionPermitted(SecurityAction.UNLOCK_LOCKDOWN, null))

        SecurityGuardManager.revokeElevatedAccess()
        assertFalse(SecurityGuardManager.isSessionElevated())

        // Identity phrases may be parsed, but they are never authorization credentials.
        val authCmd = CommandParser.parse("authorize nazib").command
        assertTrue(authCmd is JarvisCommand.SecurityAuthorize)
        assertFalse(SecurityGuardManager.isValidPinFormat("1024"))
        assertFalse(SecurityGuardManager.isValidPinFormat("1234567890123"))
        assertTrue(SecurityGuardManager.isValidPinFormat("123456"))
        assertEquals("123456", SecurityGuardManager.extractPinCandidate("123456"))
        assertEquals("123456", SecurityGuardManager.extractPinCandidate("enter security pin 123456"))
        assertEquals("123456", SecurityGuardManager.extractPinCandidate("security authorization 123456"))
        assertEquals(null, SecurityGuardManager.extractPinCandidate("authorize nazib"))
        assertEquals(null, SecurityGuardManager.extractPinCandidate("please authorize me 123456 now"))
        assertEquals(null, SecurityGuardManager.extractPinCandidate("my pin is 123456"))
    }

    // --- PHASE 10 TESTS: Smart Call Screening & Expense Ledger ---
    @Test
    fun testPhase10CallScreeningParsingAndDefaults() {
        val busyCmd = CommandParser.parse("busy hoon message daal do").command
        assertTrue(busyCmd is JarvisCommand.CallScreeningAction)
        assertEquals("REJECT_WITH_SMS", (busyCmd as JarvisCommand.CallScreeningAction).action)

        val rejectCmd = CommandParser.parse("reject call and send message").command
        assertTrue(rejectCmd is JarvisCommand.CallScreeningAction)

        assertEquals("Nazib Sir is currently in a meeting, will call back soon.", CallScreeningReceiver.DEFAULT_AUTO_REPLY)
    }

    @Test
    fun testPhase10ExpenseLedgerParsingAndCalculation() {
        // Notification detection
        val isFinancial = ExpenseLedgerTracker.isFinancialNotification(
            "com.phonepe.app",
            "Payment Successful",
            "Paid ₹450 to Swiggy on UPI"
        )
        assertTrue(isFinancial)

        // Parse notification
        val entry = ExpenseLedgerTracker.parseNotification(
            "com.phonepe.app",
            "Payment Successful",
            "Paid ₹450 to Swiggy on UPI"
        )
        assertNotNull(entry)
        assertEquals(TransactionType.DEBIT, entry?.type)
        assertEquals(450.0, entry?.amount ?: 0.0, 0.01)
        assertEquals("₹450", entry?.formattedAmount)
        assertEquals("PhonePe", entry?.source)

        // Credit transaction
        val creditEntry = ExpenseLedgerTracker.parseNotification(
            "com.google.android.apps.messaging",
            "HDFC Bank",
            "A/C credited with Rs. 1500 from Wholesale Client ref 98123"
        )
        assertNotNull(creditEntry)
        assertEquals(TransactionType.CREDIT, creditEntry?.type)
        assertEquals(1500.0, creditEntry?.amount ?: 0.0, 0.01)

        // Query Expense Ledger command
        val queryCmd = CommandParser.parse("aaj ka hisab batao").command
        assertTrue(queryCmd is JarvisCommand.QueryExpenseLedger)

        val queryCmd2 = CommandParser.parse("kharcha batao").command
        assertTrue(queryCmd2 is JarvisCommand.QueryExpenseLedger)
    }
}
