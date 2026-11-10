package com.jarvis.assistant.automation.mission

import java.math.BigDecimal

/** No coercion: model strings, arrays or objects cannot impersonate numeric/boolean inputs. */
object ToolArgumentValidator {
    fun invalidFields(tool: ToolDefinition, arguments: Map<String, Any?>): List<String> {
        return tool.parameters.mapNotNull { parameter ->
            val value = arguments[parameter.name] ?: return@mapNotNull null
            val valid = when (parameter.type.lowercase(java.util.Locale.ROOT)) {
                "string" -> value is String && value.length <= 64 * 1024
                "int", "integer" -> value is Number && runCatching {
                    BigDecimal(value.toString()).intValueExact(); true
                }.getOrDefault(false)
                "number", "float", "double" -> value is Number && value.toDouble().isFinite()
                "boolean", "bool" -> value is Boolean
                "array", "list" -> value is List<*>
                "object", "map" -> value is Map<*, *>
                else -> false
            }
            if (valid) null else parameter.name
        }
    }
}
