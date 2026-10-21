package com.jarvis.assistant.automation.skills

import com.jarvis.assistant.automation.mission.MissionRisk
import com.jarvis.assistant.automation.mission.MissionStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SkillForgeTest {

    @Before
    fun setUp() {
        // Reset or clear custom skills
        for (skill in SkillForgeManager.listSkills()) {
            if (skill.isUserCreated) {
                SkillForgeManager.deleteSkill(skill.id)
            }
        }
    }

    @Test
    fun testValidateSkill_validSkill_passes() {
        val skill = SkillDefinition(
            name = "Web Research Skill",
            purpose = "Searches web for query",
            parameters = listOf(SkillParameter("query", isRequired = true)),
            steps = listOf(
                MissionStep(
                    title = "Search information",
                    description = "Query \${query}",
                    toolName = "search_web",
                    parameters = mapOf("query" to "\${query}"),
                    riskLevel = MissionRisk.LOW
                )
            ),
            riskClassification = MissionRisk.LOW
        )

        val result = SkillForgeManager.validateSkill(skill)
        assertTrue(result is SkillValidationResult.Valid)
    }

    @Test
    fun testValidateSkill_undeclaredTool_failsValidationSafely() {
        val badSkill = SkillDefinition(
            name = "Malicious Skill",
            purpose = "Attempts undeclared action",
            steps = listOf(
                MissionStep(
                    title = "Hack system",
                    description = "Execute undeclared action",
                    toolName = "root_shell_exec_xyz",
                    parameters = emptyMap(),
                    riskLevel = MissionRisk.LOW
                )
            )
        )

        val result = SkillForgeManager.validateSkill(badSkill)
        assertTrue(result is SkillValidationResult.Invalid)
        val reasons = (result as SkillValidationResult.Invalid).reasons
        assertTrue(reasons.any { it.contains("undeclared tool 'root_shell_exec_xyz'") })
    }

    @Test
    fun testValidateSkill_unacknowledgedRiskEscalation_fails() {
        val riskySkill = SkillDefinition(
            name = "Auto Message",
            purpose = "Sends message",
            steps = listOf(
                MissionStep(
                    title = "Send message",
                    toolName = "whatsapp_send",
                    parameters = mapOf("contact" to "Alice", "message" to "Hello"),
                    riskLevel = MissionRisk.HIGH_CONSEQUENTIAL
                )
            ),
            riskClassification = MissionRisk.LOW // Mismatched risk!
        )

        val result = SkillForgeManager.validateSkill(riskySkill)
        assertTrue(result is SkillValidationResult.Invalid)
        val reasons = (result as SkillValidationResult.Invalid).reasons
        assertTrue(reasons.any { it.contains("HIGH_CONSEQUENTIAL") })
    }

    @Test
    fun testDryRun_parameterBindingAndDefaults() {
        val skill = SkillDefinition(
            id = "test_whatsapp_skill",
            name = "Quick Message",
            purpose = "Sends quick WhatsApp message",
            parameters = listOf(
                SkillParameter("recipient", isRequired = true, description = "Recipient name"),
                SkillParameter("note", isRequired = false, defaultValue = "On my way", description = "Message body")
            ),
            steps = listOf(
                MissionStep(
                    title = "Message \${recipient}",
                    description = "Send \${note} to \${recipient}",
                    toolName = "whatsapp_send",
                    parameters = mapOf("contact" to "\${recipient}", "message" to "\${note}"),
                    riskLevel = MissionRisk.HIGH_CONSEQUENTIAL
                )
            ),
            riskClassification = MissionRisk.HIGH_CONSEQUENTIAL,
            requiresApproval = true
        )
        SkillForgeManager.saveSkill(skill)

        // 1. Missing required argument
        val failDryRun = SkillForgeManager.dryRunSkill("test_whatsapp_skill", emptyMap())
        assertFalse(failDryRun.isValid)
        assertTrue(failDryRun.validationError?.contains("recipient") == true)

        // 2. Successful dry run with default value
        val successDryRun = SkillForgeManager.dryRunSkill("test_whatsapp_skill", mapOf("recipient" to "Bob"))
        assertTrue(successDryRun.isValid)
        assertEquals(1, successDryRun.resolvedSteps.size)
        val step = successDryRun.resolvedSteps.first()
        assertEquals("Message Bob", step.title)
        assertEquals("Send On my way to Bob", step.description)
        assertEquals("Bob", step.parameters["contact"])
        assertEquals("On my way", step.parameters["message"])
        assertTrue(successDryRun.requiresApproval)
    }

    @Test
    fun testSkillEnableDisable_governsExecution() = runBlocking {
        val skill = SkillDefinition(
            id = "toggle_test_skill",
            name = "Toggle Test",
            purpose = "Testing enable/disable toggle",
            steps = listOf(
                MissionStep(
                    title = "Battery Check",
                    toolName = "device_control",
                    parameters = mapOf("command" to "battery status"),
                    riskLevel = MissionRisk.LOW
                )
            )
        )
        SkillForgeManager.saveSkill(skill)

        // Disable skill
        SkillForgeManager.setSkillEnabled("toggle_test_skill", false)
        val disabledSkill = SkillForgeManager.getSkill("toggle_test_skill")
        assertFalse(disabledSkill?.isEnabled == true)

        val resultDisabled = SkillForgeManager.executeSkill(null, "toggle_test_skill")
        assertFalse(resultDisabled.success)
        assertTrue(resultDisabled.message.contains("disabled", ignoreCase = true))

        // Re-enable skill
        SkillForgeManager.setSkillEnabled("toggle_test_skill", true)
        val resultEnabled = SkillForgeManager.executeSkill(null, "toggle_test_skill")
        assertTrue(resultEnabled.success)
    }

    @Test
    fun testExportAndImportSkillsAsJson() {
        val customSkill = SkillDefinition(
            id = "custom_export_skill",
            name = "Exported Skill",
            purpose = "JSON export test",
            parameters = listOf(SkillParameter("target", isRequired = true)),
            steps = listOf(
                MissionStep(
                    title = "Search \${target}",
                    toolName = "search_web",
                    parameters = mapOf("query" to "\${target}"),
                    riskLevel = MissionRisk.LOW
                )
            )
        )
        SkillForgeManager.saveSkill(customSkill)

        val json = SkillForgeManager.exportSkillsAsJson()
        assertTrue(json.contains("Exported Skill"))
        assertTrue(json.contains("search_web"))

        // Delete and re-import
        SkillForgeManager.deleteSkill("custom_export_skill")
        assertNull(SkillForgeManager.getSkill("custom_export_skill"))

        val importedCount = SkillForgeManager.importSkillsFromJson(json)
        assertTrue(importedCount > 0)
        val restored = SkillForgeManager.getSkill("custom_export_skill")
        assertNotNull(restored)
        assertEquals("Exported Skill", restored?.name)
    }

    @Test
    fun testRepeatedTaskPattern_promotesToSkill() {
        val steps = listOf(
            MissionStep(
                title = "Check Wi-Fi",
                toolName = "device_control",
                parameters = mapOf("command" to "wifi on"),
                riskLevel = MissionRisk.LOW
            )
        )

        // Runs 1 and 2: no promotion
        assertNull(SkillForgeManager.trackTaskAndSuggestSkill("morning routine", steps))
        assertNull(SkillForgeManager.trackTaskAndSuggestSkill("morning routine", steps))

        // Run 3: promotes to skill
        val suggested = SkillForgeManager.trackTaskAndSuggestSkill("morning routine", steps)
        assertNotNull(suggested)
        assertEquals("Morning routine", suggested?.name)
        assertTrue(suggested?.isUserCreated == true)
        assertNotNull(SkillForgeManager.getSkill(suggested!!.id))
    }
}
