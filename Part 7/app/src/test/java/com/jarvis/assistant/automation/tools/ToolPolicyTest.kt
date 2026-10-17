package com.jarvis.assistant.automation.tools

import com.jarvis.assistant.automation.tools.ToolPolicy.Outcome
import com.jarvis.assistant.automation.tools.ToolPolicy.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicyTest {
    private fun invalid(name: String, args: Map<String, Any?>): Validation.Invalid =
        ToolPolicy.validate(name, args) as Validation.Invalid

    @Test
    fun unknownToolsAreUnsupportedAndThereIsNoShellTool() {
        assertEquals(Outcome.UNSUPPORTED, invalid("run_shell", mapOf("command" to "rm -rf /")).outcome)
        assertEquals("UNKNOWN_TOOL", invalid("exec", emptyMap()).code)
        assertFalse(ToolPolicy.knownTools.any { it.contains("shell") || it.contains("exec") || it.contains("sudo") })
    }

    @Test
    fun allDeclaredToolsAreRegistered() {
        val declared = setOf(
            "open_app", "open_website", "search_in_chrome", "search_and_play_youtube", "search_youtube",
            "youtube_accessibility_action", "media_playback_control", "send_whatsapp_message", "whatsapp_call",
            "call_contact", "set_volume", "set_brightness", "builtin_chrome_search", "go_to_background",
            "set_flashlight", "click_ui_element", "type_text", "scroll_screen", "press_key", "task_completed",
            "inspect_screen"
        )
        assertEquals(declared, ToolPolicy.knownTools)
    }

    @Test
    fun missingOrBlankRequiredArgumentsAreBlocked() {
        assertEquals("MISSING_ARGUMENT", invalid("open_app", emptyMap()).code)
        assertEquals("MISSING_ARGUMENT", invalid("open_app", mapOf("app_name" to "   ")).code)
        assertEquals("MISSING_ARGUMENT", invalid("send_whatsapp_message", mapOf("recipient_name" to "Rahul")).code)
        assertEquals("MISSING_ARGUMENT", invalid("set_flashlight", emptyMap()).code)
    }

    @Test
    fun validCallsPassIncludingLenientNumericAndBooleanStrings() {
        assertEquals(Validation.Valid, ToolPolicy.validate("open_app", mapOf("app_name" to "WhatsApp", "app_number" to 2)))
        assertEquals(Validation.Valid, ToolPolicy.validate("set_volume", mapOf("action" to "set", "percentage" to "40")))
        assertEquals(Validation.Valid, ToolPolicy.validate("set_flashlight", mapOf("enabled" to "true")))
        assertEquals(Validation.Valid, ToolPolicy.validate("inspect_screen", emptyMap()))
        assertEquals(Validation.Valid, ToolPolicy.validate("go_to_background", mapOf("unexpected_extra" to 1)))
    }

    @Test
    fun wrongTypesAndOversizedValuesAreRejected() {
        assertEquals("INVALID_ARGUMENT", invalid("set_volume", mapOf("action" to "set", "percentage" to "loud")).code)
        assertEquals("INVALID_ARGUMENT", invalid("set_flashlight", mapOf("enabled" to "maybe")).code)
        assertEquals("INVALID_ARGUMENT", invalid("type_text", mapOf("text" to "x".repeat(ToolPolicy.MAX_STRING_LENGTH + 1))).code)
        assertEquals("INVALID_ARGUMENT", invalid("open_website", mapOf("urls" to "not-a-list")).code)
        assertEquals("INVALID_ARGUMENT", invalid("click_ui_element", mapOf("target_name" to "OK", "x" to 1.5)).code)
    }

    @Test
    fun onlyMessagingAndCallingToolsAreSensitive() {
        val sensitive = ToolPolicy.knownTools.filter { ToolPolicy.isSensitive(it) }.toSet()
        assertEquals(setOf("send_whatsapp_message", "whatsapp_call", "call_contact"), sensitive)
        assertTrue(ToolPolicy.isSensitive("call_contact"))
        assertFalse(ToolPolicy.isSensitive("set_volume"))
    }
}
