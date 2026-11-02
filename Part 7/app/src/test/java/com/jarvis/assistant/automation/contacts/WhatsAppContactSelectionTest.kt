package com.jarvis.assistant.automation.contacts

import com.jarvis.assistant.util.ContactCaller
import com.jarvis.assistant.util.WhatsAppMessenger
import org.junit.Assert.*
import org.junit.Test

class WhatsAppContactSelectionTest {
    @Test fun `unique exact contact wins over earlier partial match`() {
        val partial = ContactCaller.Contact("Alice Work", "111")
        val exact = ContactCaller.Contact("Alice", "222")
        assertEquals(exact, WhatsAppMessenger.selectUniqueContact(listOf(partial, exact), "Alice"))
    }
    @Test fun `same name with multiple numbers requires clarification`() {
        assertNull(WhatsAppMessenger.selectUniqueContact(listOf(
            ContactCaller.Contact("Alice", "111"), ContactCaller.Contact("Alice", "222")
        ), "Alice"))
    }
}
