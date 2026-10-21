package com.jarvis.assistant.automation.contacts

import com.jarvis.assistant.util.ContactCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContactDisambiguationTest {

    @Test
    fun testCleanQueryString_removesHinglishAndEnglishPhrases() {
        assertEquals("gufran khan 4", ContactCaller.cleanQueryString("gufran khan 4 ko phone kro"))
        assertEquals("gufran khan 4", ContactCaller.cleanQueryString("gufran khan 4 ko call karo"))
        assertEquals("gufran khan", ContactCaller.cleanQueryString("call gufran khan"))
        assertEquals("gufran khan", ContactCaller.cleanQueryString("gufran khan ko call lagao"))
        assertEquals("gufran khan", ContactCaller.cleanQueryString("gufran khan ko phone lagao"))
        assertEquals("gufran khan", ContactCaller.cleanQueryString("gufran khan phone kro"))
        assertEquals("gufran khan", ContactCaller.cleanQueryString("phone gufran khan"))
    }

    @Test
    fun testParseIndexToken_supportsDigitsEnglishAndHindi() {
        // Digits
        assertEquals(1, ContactCaller.parseIndexToken("1"))
        assertEquals(4, ContactCaller.parseIndexToken("4"))
        assertEquals(10, ContactCaller.parseIndexToken("10"))

        // English Ordinals
        assertEquals(1, ContactCaller.parseIndexToken("1st"))
        assertEquals(2, ContactCaller.parseIndexToken("2nd"))
        assertEquals(3, ContactCaller.parseIndexToken("3rd"))
        assertEquals(4, ContactCaller.parseIndexToken("4th"))
        assertEquals(4, ContactCaller.parseIndexToken("fourth"))
        assertEquals(2, ContactCaller.parseIndexToken("second"))

        // Hindi / Hinglish Ordinals
        assertEquals(1, ContactCaller.parseIndexToken("pehla"))
        assertEquals(2, ContactCaller.parseIndexToken("doosra"))
        assertEquals(2, ContactCaller.parseIndexToken("dusra"))
        assertEquals(3, ContactCaller.parseIndexToken("teesra"))
        assertEquals(4, ContactCaller.parseIndexToken("chautha"))
        assertEquals(4, ContactCaller.parseIndexToken("chaar"))
        assertEquals(5, ContactCaller.parseIndexToken("paanchva"))

        // Invalid tokens
        assertNull(ContactCaller.parseIndexToken("invalid"))
        assertNull(ContactCaller.parseIndexToken("khan"))
    }

    @Test
    fun testExtractTrailingIndex_handlesVariousPatterns() {
        // Pattern 1: Direct trailing digit ("gufran khan 4")
        val (base1, idx1) = ContactCaller.extractTrailingIndex("gufran khan 4")
        assertEquals("gufran khan", base1)
        assertEquals(4, idx1)

        // Pattern 2: Trailing ordinal ("gufran khan 4th")
        val (base2, idx2) = ContactCaller.extractTrailingIndex("gufran khan 4th")
        assertEquals("gufran khan", base2)
        assertEquals(4, idx2)

        // Pattern 3: English word ("gufran khan fourth")
        val (base3, idx3) = ContactCaller.extractTrailingIndex("gufran khan fourth")
        assertEquals("gufran khan", base3)
        assertEquals(4, idx3)

        // Pattern 4: Phrased ("gufran khan number 4", "gufran khan no 4")
        val (base4, idx4) = ContactCaller.extractTrailingIndex("gufran khan number 4")
        assertEquals("gufran khan", base4)
        assertEquals(4, idx4)

        // Pattern 5: Hindi ordinal ("gufran khan chautha")
        val (base5, idx5) = ContactCaller.extractTrailingIndex("gufran khan chautha")
        assertEquals("gufran khan", base5)
        assertEquals(4, idx5)

        // Pattern 6: Leading ordinal ("4th gufran khan")
        val (base6, idx6) = ContactCaller.extractTrailingIndex("4th gufran khan")
        assertEquals("gufran khan", base6)
        assertEquals(4, idx6)

        // Pattern 7: Hindi reverse ("gufran khan 4 number")
        val (base7, idx7) = ContactCaller.extractTrailingIndex("gufran khan 4 number")
        assertEquals("gufran khan", base7)
        assertEquals(4, idx7)

        // Non-indexed name ("gufran khan")
        val (basePlain, idxPlain) = ContactCaller.extractTrailingIndex("gufran khan")
        assertEquals("gufran khan", basePlain)
        assertNull(idxPlain)
    }

    @Test
    fun testFullUtterance_withHinglishSuffix() {
        val (base, idx) = ContactCaller.extractTrailingIndex("gufran khan 4 ko phone kro")
        assertEquals("gufran khan", base)
        assertEquals(4, idx)
    }
}
