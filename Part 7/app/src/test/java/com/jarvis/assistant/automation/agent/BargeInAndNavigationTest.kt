package com.jarvis.assistant.automation.agent

import com.jarvis.assistant.automation.command.CommandParser
import com.jarvis.assistant.automation.command.JarvisCommand
import com.jarvis.assistant.data.memory.ConversationContextMemory
import com.jarvis.assistant.data.model.ConversationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BargeInAndNavigationTest {

    @Before
    fun setUp() {
        ConversationContextMemory.clear()
    }

    @Test
    fun testConversationStateIncludesInterrupted() {
        val interrupted = ConversationState.INTERRUPTED
        assertEquals("INTERRUPTED", interrupted.name)
        assertEquals("INTERRUPTED", interrupted.key)
        assertEquals("listening", interrupted.subState)
    }

    @Test
    fun testInstantHotwordKillSwitchParsing() {
        val killKeywords = listOf("ruko", "stop", "bas", "wait", "hold on", "ruk jao", "bas karo")
        for (kw in killKeywords) {
            val cmd = CommandParser.parse(kw).command
            assertNotNull("Expected command for '$kw'", cmd)
            assertTrue("Expected StopOrPause for '$kw', got ${cmd?.javaClass?.simpleName}", cmd is JarvisCommand.StopOrPause)
        }
    }

    @Test
    fun testNaturalHinglishScrollGestures() {
        // "screen upar lo" / "upar karo" -> swipes content up so user sees lower content (ScrollScreen "down")
        val scrollDownPhrases = listOf("screen upar lo", "upar karo", "scroll down", "aur dikhao", "down karo")
        for (phrase in scrollDownPhrases) {
            val cmd = CommandParser.parse(phrase).command
            assertNotNull("Expected command for '$phrase'", cmd)
            assertTrue("Expected ScrollScreen for '$phrase'", cmd is JarvisCommand.ScrollScreen)
            assertEquals("down", (cmd as JarvisCommand.ScrollScreen).direction)
        }

        // "screen neeche lo" / "scroll up" -> swipes content down so user sees upper content (ScrollScreen "up")
        val scrollUpPhrases = listOf("screen neeche lo", "scroll up", "upar dikhao")
        for (phrase in scrollUpPhrases) {
            val cmd = CommandParser.parse(phrase).command
            assertNotNull("Expected command for '$phrase'", cmd)
            assertTrue("Expected ScrollScreen for '$phrase'", cmd is JarvisCommand.ScrollScreen)
            assertEquals("up", (cmd as JarvisCommand.ScrollScreen).direction)
        }
    }

    @Test
    fun testNavigationGuards() {
        val backPhrases = listOf("back", "back karo", "peeche jao", "peeche lo", "go back")
        for (phrase in backPhrases) {
            val cmd = CommandParser.parse(phrase).command
            assertNotNull("Expected command for '$phrase'", cmd)
            assertTrue("Expected NavigateBack for '$phrase'", cmd is JarvisCommand.NavigateBack)
        }

        val homePhrases = listOf("home screen", "home jao", "go home")
        for (phrase in homePhrases) {
            val cmd = CommandParser.parse(phrase).command
            assertNotNull("Expected command for '$phrase'", cmd)
            assertTrue("Expected NavigateHome for '$phrase'", cmd is JarvisCommand.NavigateHome)
        }
    }

    @Test
    fun testMultiTurnContextualMemoryResolution() {
        // Turn 1: Initial product search
        val initialQuery = "samsung double door fridge search karo flipkart par"
        val resolvedInitial = ConversationContextMemory.resolveContextualQuery(initialQuery)
        assertTrue(resolvedInitial.contains("Samsung", ignoreCase = true))
        assertTrue(resolvedInitial.contains("double door", ignoreCase = true))
        assertEquals("Flipkart", ConversationContextMemory.targetPlatform)
        assertEquals("Samsung", ConversationContextMemory.brand)

        // Turn 2: Follow-up clarification ("Yeh wala nahi, ice cream wala")
        val followUp1 = "Yeh wala nahi, ice cream wala"
        val resolvedFollowUp1 = ConversationContextMemory.resolveContextualQuery(followUp1)
        assertTrue(resolvedFollowUp1.contains("Samsung", ignoreCase = true))
        assertTrue(resolvedFollowUp1.contains("ice cream", ignoreCase = true))

        // Turn 3: Size/specification refinement ("500 liter wala")
        val followUp2 = "500 liter wala"
        val resolvedFollowUp2 = ConversationContextMemory.resolveContextualQuery(followUp2)
        assertTrue(resolvedFollowUp2.contains("Samsung", ignoreCase = true))
        assertTrue(resolvedFollowUp2.contains("500L", ignoreCase = true))

        // Turn 4: Brand switch ("Voltas ka dekho")
        val brandSwitch = "Voltas ka dekho"
        val resolvedBrandSwitch = ConversationContextMemory.resolveContextualQuery(brandSwitch)
        assertTrue(resolvedBrandSwitch.contains("Voltas", ignoreCase = true))
        assertEquals("Voltas", ConversationContextMemory.brand)
    }

    @Test
    fun testContextualSearchCommandParsing() {
        ConversationContextMemory.clear()
        ConversationContextMemory.updateContext("samsung fridge", "flipkart")

        val cmd = CommandParser.parse("Yeh wala nahi, ice cream wala").command
        assertNotNull("Expected ContextualSearch command", cmd)
        assertTrue("Expected ContextualSearch", cmd is JarvisCommand.ContextualSearch)
        val searchCmd = cmd as JarvisCommand.ContextualSearch
        assertEquals("flipkart", searchCmd.platform?.lowercase())
        assertTrue(searchCmd.query.contains("ice cream", ignoreCase = true))
        assertTrue(searchCmd.query.contains("samsung", ignoreCase = true))
    }
}
