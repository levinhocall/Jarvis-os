package com.jarvis.assistant.automation.contacts

import org.junit.Assert.*
import org.junit.Test

class ContactChoiceResolverTest {
    private val home = ContactInfo("1", "Rahul Home", "10001")
    private val work = ContactInfo("2", "Rahul Work", "10002")
    private val contacts = listOf(home, work)

    @Test fun neverGuessesFirstOrLooseSubstring() {
        for (choice in listOf("", "Rahul", "something else", "wor", "99")) {
            assertNull(choice, ContactChoiceResolver.choose(contacts, choice))
        }
    }

    @Test fun exactNamesUniqueWordsAndExplicitOrdinalsWork() {
        for (choice in listOf("Rahul Work", "work", "2", "second", "the second one", "doosra", "दूसरा")) {
            assertEquals(choice, work, ContactChoiceResolver.choose(contacts, choice))
        }
        assertEquals(home, ContactChoiceResolver.choose(contacts, "पहला"))
    }

    @Test fun sameDisplayNameWithTwoNumbersStillNeedsNumberedChoice() {
        val duplicates = listOf(home.copy(name = "Rahul"), work.copy(name = "Rahul"))
        assertNull(ContactChoiceResolver.choose(duplicates, "Rahul"))
        assertEquals("10002", ContactChoiceResolver.choose(duplicates, "2")?.phoneNumber)
    }
}
