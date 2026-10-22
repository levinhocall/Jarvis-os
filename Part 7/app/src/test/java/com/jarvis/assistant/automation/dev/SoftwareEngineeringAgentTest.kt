package com.jarvis.assistant.automation.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SoftwareEngineeringAgentTest {

    private lateinit var devAgent: SoftwareEngineeringAgent

    @Before
    fun setUp() {
        devAgent = SoftwareEngineeringAgent()
    }

    @Test
    fun `auditGitOperation flags destructive operations and permits safe ones`() {
        val dangerous1 = devAgent.auditGitOperation("git push origin main --force")
        assertTrue(dangerous1.isDestructive)
        assertTrue(dangerous1.requiresExplicitApproval)
        assertNotNull(dangerous1.warningMessage)

        val dangerous2 = devAgent.auditGitOperation("git reset --hard HEAD~1")
        assertTrue(dangerous2.isDestructive)
        assertTrue(dangerous2.requiresExplicitApproval)

        val dangerous3 = devAgent.auditGitOperation("git branch -D feature-branch")
        assertTrue(dangerous3.isDestructive)
        assertTrue(dangerous3.requiresExplicitApproval)

        val safe1 = devAgent.auditGitOperation("git status")
        assertFalse(safe1.isDestructive)
        assertFalse(safe1.requiresExplicitApproval)

        val safe2 = devAgent.auditGitOperation("git checkout -b new-feature")
        assertFalse(safe2.isDestructive)
        assertFalse(safe2.requiresExplicitApproval)
    }

    @Test
    fun `extractSymbols extracts classes, functions, and properties`() {
        val code = """
            package com.example.test
            
            data class UserProfile(val id: String)
            
            interface PaymentProcessor {
                fun process()
            }
            
            class EngineService {
                val status = "READY"
                fun start() {
                    println("Started")
                }
            }
        """.trimIndent()

        val symbols = devAgent.extractSymbols("TestFile.kt", code)
        val names = symbols.map { it.name }

        assertTrue(names.contains("UserProfile"))
        assertTrue(names.contains("PaymentProcessor"))
        assertTrue(names.contains("EngineService"))
        assertTrue(names.contains("status"))
        assertTrue(names.contains("start"))

        val classSymbol = symbols.find { it.name == "UserProfile" }
        assertEquals(SymbolKind.CLASS, classSymbol?.kind)

        val interfaceSymbol = symbols.find { it.name == "PaymentProcessor" }
        assertEquals(SymbolKind.INTERFACE, interfaceSymbol?.kind)

        val funSymbol = symbols.find { it.name == "start" }
        assertEquals(SymbolKind.FUNCTION, funSymbol?.kind)
    }

    @Test
    fun `applyMultiFilePatch rejects directory traversal outside workspace root`() {
        val files = mutableMapOf("FileA.kt" to "val a = 1")
        val patch = MultiFilePatch(
            description = "Malicious traversal patch",
            hunks = listOf(
                PatchHunk(
                    targetFilePath = "../../../etc/passwd",
                    originalSnippet = "root",
                    replacementSnippet = "hacked"
                )
            )
        )

        val result = devAgent.applyMultiFilePatch("/workspace/project", files, patch)
        assertTrue(result is PatchApplicationResult.RejectedBoundaryViolation)
    }

    @Test
    fun `applyMultiFilePatch successfully updates multiple files atomically`() {
        val files = mutableMapOf(
            "src/A.kt" to "class A { val x = 1 }",
            "src/B.kt" to "class B { val y = 2 }"
        )
        val patch = MultiFilePatch(
            description = "Increment constants",
            hunks = listOf(
                PatchHunk(targetFilePath = "src/A.kt", originalSnippet = "val x = 1", replacementSnippet = "val x = 10"),
                PatchHunk(targetFilePath = "src/B.kt", originalSnippet = "val y = 2", replacementSnippet = "val y = 20")
            )
        )

        val result = devAgent.applyMultiFilePatch("/workspace/project", files, patch)
        assertTrue(result is PatchApplicationResult.Success)

        assertEquals("class A { val x = 10 }", files["src/A.kt"])
        assertEquals("class B { val y = 20 }", files["src/B.kt"])
    }

    @Test
    fun `applyMultiFilePatch fails and performs atomic rollback on drift mismatch`() {
        val files = mutableMapOf(
            "src/A.kt" to "class A { val x = 1 }",
            "src/B.kt" to "class B { val y = 2 }"
        )
        val patch = MultiFilePatch(
            description = "Partial mismatch patch",
            hunks = listOf(
                PatchHunk(targetFilePath = "src/A.kt", originalSnippet = "val x = 1", replacementSnippet = "val x = 10"),
                PatchHunk(targetFilePath = "src/B.kt", originalSnippet = "val nonexistent = 99", replacementSnippet = "val y = 20")
            )
        )

        val result = devAgent.applyMultiFilePatch("/workspace/project", files, patch)
        assertTrue(result is PatchApplicationResult.Failed)
        assertTrue((result as PatchApplicationResult.Failed).rolledBack)

        // Verifies File A was NOT modified because hunk B failed pre-validation
        assertEquals("class A { val x = 1 }", files["src/A.kt"])
        assertEquals("class B { val y = 2 }", files["src/B.kt"])
    }

    @Test
    fun `explainPatch generates clear non-technical explanation`() {
        val patch = MultiFilePatch(
            description = "Fix audio volume scaling bug",
            hunks = listOf(
                PatchHunk(targetFilePath = "Audio.kt", originalSnippet = "vol * 1", replacementSnippet = "vol * 2")
            )
        )
        val explanation = devAgent.explainPatch(patch)

        assertTrue(explanation.plainEnglishSummary.contains("Fix audio volume scaling bug"))
        assertEquals("LOW", explanation.riskLevel)
        assertEquals(listOf("Audio.kt"), explanation.affectedFiles)
    }
}
