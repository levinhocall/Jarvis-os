package com.jarvis.assistant.automation.tooling

// Tool model shared by the planner (automation.agent), mission engine, risk policy
// (security.sandbox), skills and routing. Kept in a neutral package so none of those
// depend on each other just to describe a tool.

enum class MissionRisk {
    LOW,
    MEDIUM,
    HIGH_CONSEQUENTIAL // Deletion, external communications, financial, permissions
}

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val requiredPermissions: List<String> = emptyList(),
    val riskLevel: MissionRisk = MissionRisk.LOW,
    val requiresAccessibility: Boolean = false,
    val isIdempotent: Boolean = true
)
