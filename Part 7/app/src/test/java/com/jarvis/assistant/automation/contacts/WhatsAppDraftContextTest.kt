package com.jarvis.assistant.automation.contacts

import android.content.ContextWrapper
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import org.junit.Assert.*
import org.junit.Test

class WhatsAppDraftContextTest {
    private val contacts = listOf(ContactInfo("1", "Rahul Home", "10001"), ContactInfo("2", "Rahul Work", "10002"))
    private class Lookup(private val answers: Map<String, ContactResolutionResult>) : ContactLookup {
        val queries = mutableListOf<String>()
        override fun resolveContact(query: String, explicitIndex: Int?): ContactResolutionResult {
            queries.add(query)
            return answers[query] ?: ContactResolutionResult.NoMatch
        }
    }
    private fun assistant(lookup: ContactLookup) = WhatsAppAssistant(ContextWrapper(null), lookup)

    @Test fun ambiguousNewRequestCannotSendAnOldDraft() {
        val lookup = Lookup(mapOf("Asha" to ContactResolutionResult.SingleMatch(ContactInfo("3", "Asha", "10003")),
            "Rahul" to ContactResolutionResult.MultipleMatches(contacts)))
        val assistant = assistant(lookup)
        assistant.createDraft("Asha", "old message")
        assertTrue(assistant.hasPendingDraft())
        val response = assistant.sendMessageDirectly("Rahul", "new message")
        assertTrue(response.contains("Which"))
        assertFalse(assistant.hasPendingDraft())
        assertTrue(assistant.isAwaitingDisambiguation())
        assertTrue(assistant.confirmAndSend().contains("Nothing was sent"))
    }

    @Test fun invalidChoiceDoesNotChooseFirstAndValidChoiceKeepsOriginalMessage() {
        val assistant = assistant(Lookup(mapOf("Rahul" to ContactResolutionResult.MultipleMatches(contacts))))
        assistant.createDraft("Rahul", "main 10 minute late aaunga")
        assertTrue(assistant.handleDisambiguationChoice("anything").contains("unique contact"))
        assertFalse(assistant.hasPendingDraft())
        val response = assistant.handleDisambiguationChoice("second")
        assertTrue(response.contains("Rahul Work"))
        assertTrue(response.contains("main 10 minute late aaunga"))
        assertFalse(response.contains("Hello"))
        assertTrue(assistant.hasPendingDraft())
    }

    @Test fun missingMessageRetainsRecipientAndCancellationClearsIt() {
        val lookup = Lookup(mapOf("Rahul" to ContactResolutionResult.SingleMatch(contacts.first())))
        val assistant = assistant(lookup)
        assertTrue(assistant.createDraft("Rahul", "").contains("What message"))
        assertTrue(assistant.createDraft("", "10 minute late").contains("Draft ready"))
        assertEquals(listOf("Rahul"), lookup.queries)
        assistant.createDraft("Rahul", "")
        assistant.cancelDraft()
        assertTrue(assistant.createDraft("", "new message").contains("Who"))
    }

    @Test fun callChoiceCannotInventHelloMessageAndCancelClearsChoices() {
        val assistant = assistant(Lookup(mapOf("Rahul" to ContactResolutionResult.MultipleMatches(contacts))))
        assistant.createDraft("Rahul", "message from prior task")
        assistant.makeCall("Rahul", false)
        assertTrue(assistant.handleDisambiguationChoice("2").contains("No message draft"))
        assertFalse(assistant.hasPendingDraft())
        assistant.cancelDraft()
        assertFalse(assistant.isAwaitingDisambiguation())
    }
}
