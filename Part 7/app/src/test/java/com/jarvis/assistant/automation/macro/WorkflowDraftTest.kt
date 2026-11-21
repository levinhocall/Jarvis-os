package com.jarvis.assistant.automation.macro

import com.jarvis.assistant.automation.mission.ToolRegistry
import org.junit.Assert.*
import org.junit.Test

class WorkflowDraftTest {
    @Test fun textIsKeptLiteralEvenWhenItResemblesVariableSyntax() {
        val literal = "Welcome \${name}\nनमस्ते"
        val text = WorkflowDraftStep(requireNotNull(ToolRegistry.getTool("type_text")), mapOf("text" to literal))
        val workflow = MacroCompiler.compile(buildWorkflowJson("Greeting", listOf(text)))
        assertEquals(literal, workflow.steps.single().parameters["text"])
    }

    @Test fun formInputsCompileIntoTypedArgumentsInStepOrder() {
        val open = WorkflowDraftStep(requireNotNull(ToolRegistry.getTool("open_app")), mapOf("app_name" to "Maps"))
        val wait = WorkflowDraftStep(requireNotNull(ToolRegistry.getTool("wait_for_screen_text")), mapOf(
            "package_name" to "com.google.android.apps.maps", "text" to "Search here", "present" to "false", "timeout_ms" to "1500"
        ))
        val workflow = MacroCompiler.compile(buildWorkflowJson("Maps", listOf(open, wait)))
        assertEquals(listOf("open_app", "wait_for_screen_text"), workflow.steps.map { it.toolName })
        assertEquals(mapOf("app_name" to "Maps"), workflow.steps[0].parameters)
        assertEquals(false, workflow.steps[1].parameters["present"])
        assertEquals(1500, (workflow.steps[1].parameters["timeout_ms"] as Number).toInt())
    }

    @Test fun rejectsMissingFieldsMalformedNumbersAndOutOfScopeTools() {
        val scroll = requireNotNull(ToolRegistry.getTool("scroll_screen"))
        val invalidSteps = listOf(
            WorkflowDraftStep(scroll, emptyMap()),
            WorkflowDraftStep(scroll, mapOf("direction" to "up", "distance" to "fifty")),
            WorkflowDraftStep(requireNotNull(ToolRegistry.getTool("termux_execute")), emptyMap())
        )
        invalidSteps.forEach { step ->
            assertThrows(IllegalArgumentException::class.java) { buildWorkflowJson("Invalid", listOf(step)) }
        }
    }
}
