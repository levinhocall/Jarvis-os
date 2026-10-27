package com.jarvis.assistant.automation.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolverRankingTest {

    private val sampleContacts = listOf(
        ContactInfo("1", "Alex", "919876543210"),
        ContactInfo("2", "Alexander", "919876543211"),
        ContactInfo("3", "Bob Alexson", "919876543212"),
        ContactInfo("4", "Sam Smith", "919111111111"),
        ContactInfo("5", "Lisa Samuel", "919222222222"),
        ContactInfo("6", "Papa", "919333333333"),
        ContactInfo("7", "Ammi", "919444444444"),
        ContactInfo("8", "Gufran Khan", "919555555551"),
        ContactInfo("9", "Gufran Khan", "919555555552"),
        ContactInfo("10", "Gufran Khan", "919555555553"),
        ContactInfo("11", "Home 1", "919666666661"),
        ContactInfo("12", "Home 2", "919666666662")
    )

    // A dummy resolver whose Context won't be called during pure list tests
    private val resolver = ContactResolver(
        context = null,
        permissionChecker = { true }
    )

    @Test
    fun testExactMatch_rankedOverPrefixAndContains() {
        // "Alex" is an exact match for "Alex", prefix for "Alexander", and substring for "Bob Alexson".
        // Exact match must win!
        val matches = resolver.matchContacts("Alex", sampleContacts)
        assertEquals(1, matches.size)
        assertEquals("Alex", matches.first().name)
    }

    @Test
    fun testPrefixMatch_rankedOverContains() {
        // "Sam" is prefix of "Sam Smith", but substring/contains of "Lisa Samuel".
        // Prefix match must win over contains!
        val matches = resolver.matchContacts("Sam", sampleContacts)
        assertEquals(1, matches.size)
        assertEquals("Sam Smith", matches.first().name)

        // "Ale" (length >= 3) is prefix of "Alex" and "Alexander", but interior of "Bob Alexson".
        val aleMatches = resolver.matchContacts("Ale", sampleContacts)
        assertEquals(2, aleMatches.size)
        assertTrue(aleMatches.any { it.name == "Alex" })
        assertTrue(aleMatches.any { it.name == "Alexander" })
        assertTrue(aleMatches.none { it.name == "Bob Alexson" })
    }

    @Test
    fun testContainsMatch_whenNoExactOrPrefixMatches() {
        // "lexson" is only a substring of "Bob Alexson"
        val matches = resolver.matchContacts("lexson", sampleContacts)
        assertEquals(1, matches.size)
        assertEquals("Bob Alexson", matches.first().name)
    }

    @Test
    fun testPhoneNumberShortCircuit_directDigits() {
        // Querying phone number directly "+919876543210" or "9876543210"
        val res1 = resolver.resolveContactsList("+919876543210", sampleContacts)
        assertTrue(res1 is ContactResolutionResult.SingleMatch)
        assertEquals("Alex", (res1 as ContactResolutionResult.SingleMatch).contact.name)

        val res2 = resolver.resolveContactsList("9876543210", sampleContacts)
        assertTrue(res2 is ContactResolutionResult.SingleMatch)
        assertEquals("Alex", (res2 as ContactResolutionResult.SingleMatch).contact.name)
    }

    @Test
    fun testNicknameMapping() {
        // "dad" -> matches "Papa"
        val dadMatches = resolver.matchContacts("dad", sampleContacts)
        assertEquals(1, dadMatches.size)
        assertEquals("Papa", dadMatches.first().name)

        // "mom" -> matches "Ammi"
        val momMatches = resolver.matchContacts("mom", sampleContacts)
        assertEquals(1, momMatches.size)
        assertEquals("Ammi", momMatches.first().name)
    }

    @Test
    fun testTrailingIndexDisambiguation() {
        // "gufran khan 2" -> returns 2nd Gufran Khan
        val res2 = resolver.resolveContactsList("gufran khan 2", sampleContacts)
        assertTrue(res2 is ContactResolutionResult.SingleMatch)
        assertEquals("919555555552", (res2 as ContactResolutionResult.SingleMatch).contact.phoneNumber)

        // "gufran khan third" -> returns 3rd Gufran Khan
        val res3 = resolver.resolveContactsList("gufran khan third", sampleContacts)
        assertTrue(res3 is ContactResolutionResult.SingleMatch)
        assertEquals("919555555553", (res3 as ContactResolutionResult.SingleMatch).contact.phoneNumber)

        // "gufran khan 5" (out of range) -> returns multiple matches
        val resOut = resolver.resolveContactsList("gufran khan 5", sampleContacts)
        assertTrue(resOut is ContactResolutionResult.MultipleMatches)
        assertEquals(3, (resOut as ContactResolutionResult.MultipleMatches).candidates.size)
    }

    @Test
    fun testLiteralNameWithDigits() {
        // "Home 1" is literal exact match
        val res1 = resolver.resolveContactsList("home 1", sampleContacts)
        assertTrue(res1 is ContactResolutionResult.SingleMatch)
        assertEquals("Home 1", (res1 as ContactResolutionResult.SingleMatch).contact.name)
    }

    @Test
    fun testNoPermissionFallback() {
        val noPermResolver = ContactResolver(
            context = null,
            permissionChecker = { false }
        )
        val result = noPermResolver.resolveContact("Alex")
        assertTrue(result is ContactResolutionResult.PermissionDenied)
    }

    @Test
    fun testEmptyOrBlankQuery_returnsNoMatch() {
        val resEmpty = resolver.resolveContactsList("", sampleContacts)
        assertTrue(resEmpty is ContactResolutionResult.NoMatch)

        val resBlank = resolver.resolveContactsList("   ", sampleContacts)
        assertTrue(resBlank is ContactResolutionResult.NoMatch)
    }
}
