package com.jarvis.assistant.automation.mission

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import java.util.concurrent.ConcurrentHashMap

sealed class ToolValidationResult {
    object Valid : ToolValidationResult()
    data class UnknownTool(val toolName: String) : ToolValidationResult()
    data class MissingParameters(val missingParams: List<String>) : ToolValidationResult()
    data class MissingPermissions(val missingPermissions: List<String>) : ToolValidationResult()
    data class AccessibilityUnavailable(val guidance: String) : ToolValidationResult()
}

/**
 * Capability-aware Tool Registry for JARVIS OS.
 * Validates availability, parameters, permissions, and accessibility readiness before any tool execution.
 */
object ToolRegistry {

    private val tools = ConcurrentHashMap<String, ToolDefinition>()

    init {
        registerBuiltInTools()
    }

    private fun registerBuiltInTools() {
        register(
            ToolDefinition(
                name = "call_contact",
                description = "Places a standard cellular phone call to a saved contact",
                parameters = listOf(
                    ToolParameter("contact_name", "String", "The name of the contact to call", required = true),
                    ToolParameter("contact_index", "Int", "1-based index if multiple contacts match", required = false)
                ),
                requiredPermissions = listOf(
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.CALL_PHONE
                ),
                riskLevel = MissionRisk.MEDIUM,
                isIdempotent = false
            )
        )

        val whatsappSendDef = ToolDefinition(
            name = "whatsapp_send",
            description = "Dispatches a WhatsApp message to a contact",
            parameters = listOf(
                ToolParameter("contact", "String", "Recipient name or phone number", required = true),
                ToolParameter("message", "String", "Text message body to send", required = true)
            ),
            requiredPermissions = listOf(
                android.Manifest.permission.READ_CONTACTS
            ),
            requiresAccessibility = true,
            riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
            isIdempotent = false
        )
        register(whatsappSendDef)
        register(whatsappSendDef.copy(name = "send_whatsapp"))

        register(
            ToolDefinition(
                name = "whatsapp_prepare",
                description = "Stages and validates a WhatsApp draft without sending",
                parameters = listOf(
                    ToolParameter("contact", "String", "Recipient name or phone number", required = true),
                    ToolParameter("message", "String", "Text message body to stage", required = true)
                ),
                riskLevel = MissionRisk.MEDIUM,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "search_web",
                description = "Searches the web via browser or search provider",
                parameters = listOf(
                    ToolParameter("query", "String", "Search query terms", required = true)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "youtube_play",
                description = "Searches and streams media on YouTube",
                parameters = listOf(
                    ToolParameter("query", "String", "Video title, song name, or channel", required = true)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "device_control",
                description = "Controls device hardware (flashlight, volume, brightness)",
                parameters = listOf(
                    ToolParameter("command", "String", "Action description (e.g. 'turn on flashlight')", required = true)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "click_ui_element",
                description = "Clicks a visible on-screen UI element by semantic text",
                parameters = listOf(
                    ToolParameter("target_text", "String", "Label or description of element to click", required = true)
                ),
                requiresAccessibility = true,
                riskLevel = MissionRisk.MEDIUM,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "scroll_screen",
                description = "Scrolls the currently active screen",
                parameters = listOf(
                    ToolParameter("direction", "String", "Direction: 'up' or 'down'", required = true),
                    ToolParameter("distance", "Int", "Relative scroll units", required = false)
                ),
                requiresAccessibility = true,
                riskLevel = MissionRisk.LOW,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "navigate_home",
                description = "Returns to device home screen using native Android Intent",
                parameters = emptyList(),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "navigate_back",
                description = "Dispatches system back navigation gesture",
                parameters = emptyList(),
                requiresAccessibility = true,
                riskLevel = MissionRisk.LOW,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "read_unread_notifications",
                description = "Reads unread incoming WhatsApp or messaging notifications",
                parameters = emptyList(),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )
    }

    fun register(tool: ToolDefinition) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): ToolDefinition? = tools[name]

    fun getAllTools(): List<ToolDefinition> = tools.values.toList()

    /**
     * Strictly validates a proposed tool invocation before execution.
     */
    fun validate(
        toolName: String,
        parameters: Map<String, Any?>,
        context: Context? = null
    ): ToolValidationResult {
        val tool = tools[toolName] ?: return ToolValidationResult.UnknownTool(toolName)

        // 1. Validate required parameters
        val missing = mutableListOf<String>()
        for (param in tool.parameters) {
            if (param.required) {
                val value = parameters[param.name]
                if (value == null || (value is String && value.isBlank())) {
                    missing.add(param.name)
                }
            }
        }
        if (missing.isNotEmpty()) {
            return ToolValidationResult.MissingParameters(missing)
        }

        // 2. Validate platform permissions (if context provided)
        if (context != null && tool.requiredPermissions.isNotEmpty()) {
            val missingPerms = tool.requiredPermissions.filter { perm ->
                ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
            }
            if (missingPerms.isNotEmpty()) {
                return ToolValidationResult.MissingPermissions(missingPerms)
            }
        }

        // 3. Validate Accessibility operational state (if tool requires it)
        if (tool.requiresAccessibility) {
            val isOperational = JarvisAccessibilityService.isRunning() || AccessibilityHealthModel.isOperational()
            if (!isOperational) {
                val guidance = AccessibilityHealthModel.getRecoveryGuidance()
                    ?: "Accessibility Service is required for this action. Please enable JARVIS in Settings."
                return ToolValidationResult.AccessibilityUnavailable(guidance)
            }
        }

        return ToolValidationResult.Valid
    }
}
