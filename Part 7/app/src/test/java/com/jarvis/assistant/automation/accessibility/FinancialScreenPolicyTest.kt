package com.jarvis.assistant.automation.accessibility
import org.junit.Assert.*
import org.junit.Test
class FinancialScreenPolicyTest {
    @Test fun protectedWindowLatchesUntilOrdinaryAppWindow() {
        try {
            FinancialScreenPolicy.observeWindow("com.phonepe.app")
            assertTrue(FinancialScreenPolicy.projectionBlocked)
            FinancialScreenPolicy.observeWindow("com.android.systemui")
            FinancialScreenPolicy.observeWindow("com.google.android.inputmethod.latin")
            FinancialScreenPolicy.observeWindow(null)
            assertTrue(FinancialScreenPolicy.projectionBlocked)
            FinancialScreenPolicy.observeWindow("com.whatsapp")
            assertFalse(FinancialScreenPolicy.projectionBlocked)
        } finally { FinancialScreenPolicy.observeWindow("com.jarvis.assistant") }
    }
}
