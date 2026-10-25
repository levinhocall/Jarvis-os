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

    val TOOL_ALIASES: Map<String, String> = mapOf(
        "send_whatsapp_message" to "whatsapp_send",
        "send_whatsapp" to "whatsapp_send",
        "make_phone_call" to "call_contact",
        "search_and_play_youtube" to "youtube_play",
        "search_youtube" to "youtube_play",
        "set_flashlight" to "device_control",
        "click_element" to "click_ui_element",
        "scroll" to "scroll_screen"
    )

    fun resolveToolName(name: String): String = TOOL_ALIASES[name] ?: name

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
                name = "delete_whatsapp_message",
                description = "Deletes a message or conversation in WhatsApp",
                parameters = listOf(
                    ToolParameter("contact", "String", "Contact name or chat identifier", required = true),
                    ToolParameter("target_text", "String", "Optional specific text of message to delete", required = false)
                ),
                requiredPermissions = listOf(
                    android.Manifest.permission.READ_CONTACTS
                ),
                requiresAccessibility = true,
                riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                isIdempotent = false
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
                name = "open_app",
                description = "Launches an installed Android application",
                parameters = listOf(
                    ToolParameter("app_name", "String", "Name or label of the application", required = true),
                    ToolParameter("package_name", "String", "Optional package identifier", required = false)
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
                name = "type_text",
                description = "Types text into the currently focused input field",
                parameters = listOf(
                    ToolParameter("text", "String", "Text content to type", required = true),
                    ToolParameter("target_text", "String", "Optional label or description of the field", required = false)
                ),
                requiresAccessibility = true,
                riskLevel = MissionRisk.LOW,
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
                name = "open_website",
                description = "Opens one or more websites/URLs in Google Chrome in separate tabs",
                parameters = listOf(
                    ToolParameter("urls", "List<String>", "Array of website URLs or domain names", required = false),
                    ToolParameter("query", "String", "Optional URL or query", required = false)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "search_in_chrome",
                description = "Searches for a topic or opens a URL in Google Chrome",
                parameters = listOf(
                    ToolParameter("query", "String", "Query or URL to search in Chrome", required = true)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "make_direct_phone_call",
                description = "Places a direct cellular phone call to a phone number",
                parameters = listOf(
                    ToolParameter("phone_number", "String", "Direct phone number to dial", required = true)
                ),
                requiredPermissions = listOf(android.Manifest.permission.CALL_PHONE),
                riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "take_photo",
                description = "Opens camera and captures a photo",
                parameters = listOf(
                    ToolParameter("camera", "String", "Camera to use: 'front' or 'back'", required = false)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "set_alarm",
                description = "Sets a system clock alarm",
                parameters = listOf(
                    ToolParameter("hour", "Int", "Hour in 24h format (0-23)", required = true),
                    ToolParameter("minute", "Int", "Minute (0-59)", required = true),
                    ToolParameter("message", "String", "Alarm label or description", required = false)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "set_timer",
                description = "Sets a countdown timer",
                parameters = listOf(
                    ToolParameter("seconds", "Int", "Timer duration in seconds", required = true),
                    ToolParameter("message", "String", "Timer label", required = false)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "search_wikipedia",
                description = "Searches Wikipedia for encyclopedic information",
                parameters = listOf(
                    ToolParameter("query", "String", "Topic or entity to search", required = true)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "get_weather",
                description = "Gets current live weather for a city",
                parameters = listOf(
                    ToolParameter("city", "String", "City or locality name", required = false)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "create_calendar_event",
                description = "Creates an event in the user's calendar",
                parameters = listOf(
                    ToolParameter("title", "String", "Event title", required = true),
                    ToolParameter("begin_time", "String", "Start time description", required = false)
                ),
                riskLevel = MissionRisk.MEDIUM,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "go_to_background",
                description = "Minimizes JARVIS to background standby mode",
                parameters = emptyList(),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "inspect_screen",
                description = "Reads accessible screen content and visible elements",
                parameters = emptyList(),
                requiresAccessibility = true,
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
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

        register(
            ToolDefinition(
                name = "log_recall_core",
                description = "Logs or updates long-term memory key-value context",
                parameters = listOf(
                    ToolParameter("key", "String", "Memory category or identifier", required = true),
                    ToolParameter("value", "String", "Memory content to store", required = true)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "query_recall_core",
                description = "Retrieves stored long-term memory context",
                parameters = listOf(
                    ToolParameter("query", "String", "Search query or category", required = true)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "task_completed",
                description = "Signals that the goal has been successfully accomplished",
                parameters = listOf(
                    ToolParameter("summary", "String", "Truthful final outcome report", required = true)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        // Dev & Coding capabilities
        register(
            ToolDefinition(
                name = "termux_execute",
                description = "Executes command in Termux development environment",
                parameters = listOf(
                    ToolParameter("command", "String", "Shell command to run", required = true)
                ),
                riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "inspect_code",
                description = "Reads or inspects codebase files",
                parameters = listOf(
                    ToolParameter("path", "String", "File path relative to repository root", required = true)
                ),
                riskLevel = MissionRisk.LOW,
                isIdempotent = true
            )
        )

        register(
            ToolDefinition(
                name = "edit_code",
                description = "Edits or patches codebase files",
                parameters = listOf(
                    ToolParameter("path", "String", "File path to modify", required = true),
                    ToolParameter("content", "String", "Content or replacement code", required = true)
                ),
                riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "run_git",
                description = "Runs git version control operations",
                parameters = listOf(
                    ToolParameter("subcommand", "String", "Git subcommand (e.g. status, diff, commit)", required = true)
                ),
                riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "execute_social_media_task",
                description = "Automates social media tasks on Instagram, Facebook, and WhatsApp",
                parameters = listOf(
                    ToolParameter("platform", "String", "Target platform: 'INSTAGRAM', 'FACEBOOK', or 'WHATSAPP'", required = true),
                    ToolParameter("action", "String", "Social action e.g. 'CREATE_POST', 'CREATE_REEL', 'CREATE_STORY', 'TEXT_POST', 'SEND_MESSAGE'", required = true),
                    ToolParameter("media_uri", "String", "Safe content:// URI of image/video to upload", required = false),
                    ToolParameter("caption", "String", "Caption, post text, or message text", required = false),
                    ToolParameter("mode", "String", "Task mode: 'PUBLISH' or 'DRAFT'", required = false),
                    ToolParameter("target_account", "String", "Target recipient or account name", required = false)
                ),
                requiresAccessibility = true,
                riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                isIdempotent = false
            )
        )

        register(
            ToolDefinition(
                name = "social_media_task_control",
                description = "Controls an active social media task (CONFIRM, REJECT, RESUME, CANCEL, STATUS)",
                parameters = listOf(
                    ToolParameter("command", "String", "Control command: 'CONFIRM', 'REJECT', 'RESUME', 'CANCEL', or 'STATUS'", required = true),
                    ToolParameter("task_id", "String", "Active task identifier", required = true)
                ),
                riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
                isIdempotent = false
            )
        )
    }

    fun register(tool: ToolDefinition) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): ToolDefinition? = tools[resolveToolName(name)]

    fun getAllTools(): List<ToolDefinition> = tools.values.distinctBy { it.name }

    fun getAllToolNamesWithAliases(): Set<String> {
        val set = tools.keys.toMutableSet()
        set.addAll(TOOL_ALIASES.keys)
        return set
    }

    /**
     * Strictly validates a proposed tool invocation before execution.
     */
    fun validate(
        toolName: String,
        parameters: Map<String, Any?>,
        context: Context? = null
    ): ToolValidationResult {
        val tool = getTool(toolName) ?: return ToolValidationResult.UnknownTool(toolName)

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
