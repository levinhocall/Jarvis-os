package com.jarvis.assistant.automation.agent

import com.jarvis.assistant.automation.command.CommandParser
import com.jarvis.assistant.automation.command.JarvisCommand
import com.jarvis.assistant.automation.protocol.ProtocolManager
import com.jarvis.assistant.data.memory.ConversationContextMemory
import com.jarvis.assistant.data.model.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TonyStarkProtocolsAndReflexTest {

    @Before
    fun setUp() {
        ConversationContextMemory.clear()
    }

    // --- PHASE 1 TESTS ---
    @Test
    fun testPhase1BargeInStateAndScrollMapping() {
        // ConversationState has INTERRUPTED
        assertEquals("INTERRUPTED", ConversationState.INTERRUPTED.name)
        assertEquals("listening", ConversationState.INTERRUPTED.subState)

        // Hinglish scroll direction parsing
        val downCmd = CommandParser.parse("screen upar lo").command
        assertTrue(downCmd is JarvisCommand.ScrollScreen)
        assertEquals("down", (downCmd as JarvisCommand.ScrollScreen).direction)

        val upCmd = CommandParser.parse("screen neeche lo").command
        assertTrue(upCmd is JarvisCommand.ScrollScreen)
        assertEquals("up", (upCmd as JarvisCommand.ScrollScreen).direction)

        // Kill switch instant hotwords
        val stopCmd = CommandParser.parse("ruko").command
        assertTrue(stopCmd is JarvisCommand.StopOrPause)
    }

    // --- PHASE 2 TESTS ---
    @Test
    fun testPhase2ProtocolRecognitionAndExecution() {
        assertTrue(ProtocolManager.isProtocolCommand("coding protocol"))
        assertTrue(ProtocolManager.isProtocolCommand("business protocol"))
        assertTrue(ProtocolManager.isProtocolCommand("wholesale mode"))
        assertTrue(ProtocolManager.isProtocolCommand("lockdown protocol"))
        assertFalse(ProtocolManager.isProtocolCommand("normal search"))

        val parsedCoding = CommandParser.parse("activate coding protocol").command
        assertTrue(parsedCoding is JarvisCommand.ExecuteProtocol)

        val parsedLockdown = CommandParser.parse("lockdown protocol chalu karo").command
        assertTrue(parsedLockdown is JarvisCommand.ExecuteProtocol)
    }

    @Test
    fun testPhase2OfflineReflexParsing() {
        // Battery / status queries
        val batteryQuery = CommandParser.parse("status report").command
        assertTrue(batteryQuery is JarvisCommand.ProactiveStatusReport)
    }

    // --- PHASE 3 TESTS ---
    @Test
    fun testPhase3WhatsAppCommandsParsing() {
        val unreadCmd = CommandParser.parse("unread messages batao").command
        assertTrue(unreadCmd is JarvisCommand.ReadUnreadWhatsApp)

        val replyCmd = CommandParser.parse("Rahul ko reply bhejo I am coming").command
        assertTrue(replyCmd is JarvisCommand.SendSilentWhatsAppReply)
        val waReply = replyCmd as JarvisCommand.SendSilentWhatsAppReply
        assertEquals("rahul", waReply.contact.lowercase())
        assertEquals("i am coming", waReply.message.lowercase())
    }

    // --- PHASE 4 TESTS ---
    @Test
    fun testPhase4TacticalScannerAndRecallCoreParsing() {
        val scanCmd = CommandParser.parse("scan this screen").command
        assertTrue(scanCmd is JarvisCommand.ScanScreenTactical)

        val analyzeCmd = CommandParser.parse("is page ko analyze karo").command
        assertTrue(analyzeCmd is JarvisCommand.ScanScreenTactical)

        val recallCmd = CommandParser.parse("recall Voltas freezer").command
        assertTrue(recallCmd is JarvisCommand.RecallFact)
    }

    // --- PHASE 5 TESTS ---
    @Test
    fun testPhase5CrossDeviceBridgeAndGoalAgentParsing() {
        val shareCmd = CommandParser.parse("send this to my pc").command
        assertTrue(shareCmd is JarvisCommand.ShareToPC)

        val shareLinkCmd = CommandParser.parse("yeh link share karo").command
        assertTrue(shareLinkCmd is JarvisCommand.ShareToPC)

        // Multi-turn context resolution for goal agent
        ConversationContextMemory.updateContext("Voltas 500L Deep Freezer", "flipkart")
        val resolved = ConversationContextMemory.resolveContextualQuery("sabse sasta wala")
        assertTrue(resolved.contains("Voltas", ignoreCase = true))
        assertTrue(resolved.contains("freezer", ignoreCase = true))
    }
}
