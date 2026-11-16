package com.jarvis.assistant.automation.accessibility

import org.junit.Assert.*
import org.junit.Test

class FuzzyLabelMatcherTest {
    @Test fun normalizesAccentsAndWhitespace() {
        assertTrue(FuzzyLabelMatcher.matches("  RESUME conversation ", "Résumé\u00a0conversation"))
        assertTrue(FuzzyLabelMatcher.matches("नमस्ते", "नमस्ते"))
    }
    @Test fun acceptsSmallTypoButNotDifferentAction() {
        assertTrue(FuzzyLabelMatcher.matches("continue conversation", "continue conversaton"))
        assertFalse(FuzzyLabelMatcher.matches("Send", "End"))
        assertFalse(FuzzyLabelMatcher.matches("delete message", "archive message"))
        assertFalse(FuzzyLabelMatcher.matches("", "Continue"))
    }
    @Test fun numericTargetsCannotFuzzilyMatch() {
        assertTrue(FuzzyLabelMatcher.matches("+91 12345", "+91-12345"))
        assertFalse(FuzzyLabelMatcher.matches("+91 12345", "+91 12346"))
        assertFalse(FuzzyLabelMatcher.matches("Pay 100", "Pay 101"))
    }
    @Test fun protectsExactFinancialPackagesOnly() {
        assertTrue(FinancialScreenPolicy.isProtected("com.phonepe.app"))
        assertFalse(FinancialScreenPolicy.isProtected("com.phonepe.app.fake"))
        assertFalse(FinancialScreenPolicy.isProtected("com.whatsapp"))
        assertFalse(FinancialScreenPolicy.isProtected(null))
    }
    @Test fun decimalPointsShortIdentifiersAndTruncatedLabelsCannotAlias() {
        assertFalse(FuzzyLabelMatcher.matches("1.23","12.3"))
        assertFalse(FuzzyLabelMatcher.matches("12345.67","123456.7"))
        assertFalse(FuzzyLabelMatcher.matches("123","1 23"))
        assertFalse(FuzzyLabelMatcher.matches("a".repeat(256),"a".repeat(256)+"delete"))
        assertFalse(FuzzyLabelMatcher.matches("मैं","मै"))
    }

}
