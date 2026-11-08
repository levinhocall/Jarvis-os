package com.jarvis.assistant.automation.mission

import com.jarvis.assistant.automation.tooling.ToolRegistry

enum class MissionExecutionRoute { AUTO, UI_AGENT, CORE_LLM }

/** Persisted in existing mission constraints so approval/recovery cannot silently change route. */
object MissionRoutePolicy {
    private const val PREFIX = "execution_route="
    private val uiTools = setOf("inspect_screen", "open_app", "click_ui_element", "type_text", "scroll_screen", "navigate_home", "navigate_back", "task_completed")
    fun constraints(existing: List<String>, route: MissionExecutionRoute): List<String> =
        existing.filterNot { it.startsWith(PREFIX) } + if (route == MissionExecutionRoute.AUTO) emptyList() else listOf(PREFIX + route.name)
    fun route(constraints: List<String>): MissionExecutionRoute {
        val value = constraints.firstOrNull { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return MissionExecutionRoute.AUTO
        return MissionExecutionRoute.valueOf(value)
    }
    fun allowedTools(constraints: List<String>): Set<String>? = when (route(constraints)) {
        MissionExecutionRoute.AUTO -> null
        MissionExecutionRoute.UI_AGENT -> uiTools
        MissionExecutionRoute.CORE_LLM -> ToolRegistry.getAllTools().filter { !it.requiresAccessibility && ToolRegistry.resolveToolName(it.name) !in (uiTools + setOf("whatsapp_call", "whatsapp_send", "whatsapp_prepare", "delete_whatsapp_message", "youtube_play")) }.map { it.name }.toSet() + "task_completed"
    }
}
