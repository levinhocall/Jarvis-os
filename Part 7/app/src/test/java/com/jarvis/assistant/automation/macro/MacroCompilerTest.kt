package com.jarvis.assistant.automation.macro

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MacroCompilerTest {
    @Test fun variablesCompileToExistingToolsWithoutExecution() {
        val plan = MacroCompiler.compile("""{"name":"Open browser","variables":{"app":"Chrome"},"steps":[{"tool":"open_app","arguments":{"app_name":"${'$'}{app}"}}]}""")
        assertEquals("Chrome", plan.steps.single().parameters["app_name"])
        assertTrue(plan.steps.single().isIdempotent)
    }
    @Test fun rejectsAuthorityAndUnknownTools() {
        for (json in listOf("""{"name":"x","requiresApproval":false,"steps":[]}""",
            """{"name":"x","steps":[{"tool":"shell","arguments":{}}]}""",
            """{"name":"x","steps":[{"tool":"open_app","arguments":{"app_name":12}}]}""")) {
            assertTrue(runCatching { MacroCompiler.compile(json) }.isFailure)
        }
    }
    @Test fun unknownOrUnreadableScreenCannotProveAbsence() = runTest {
        assertFalse(ScreenTextWaiter.await("com.example.app", "Loading", false, 600) {
            WaitScreen("com.example.app", emptyList(), false)
        })
        assertFalse(ScreenTextWaiter.await("com.example.app", "Ready", true, 600) {
            WaitScreen("com.other.app", listOf("Ready"), true)
        })
    }
    @Test fun requiresTwoReadableObservationsAndCanCancel() = runTest {
        var reads = 0
        assertTrue(ScreenTextWaiter.await("com.example.app", "Ready", true, 1000) {
            reads++; WaitScreen("com.example.app", listOf("Ready"), true)
        })
        assertEquals(2,reads)
        val wait = async { ScreenTextWaiter.await("com.example.app", "Ready", true, 10000) {
            WaitScreen("com.example.app", listOf("Loading"),true)
        } }
        wait.cancelAndJoin(); assertTrue(wait.isCancelled)
    }
}
