package com.jarvis.assistant.automation.agent

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.jarvis.assistant.automation.mission.MissionRisk
import com.jarvis.assistant.automation.mission.ToolDefinition
import com.jarvis.assistant.automation.mission.ToolRegistry
import com.jarvis.assistant.security.sandbox.RiskPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Historical record of a tool execution during an LLM planner session.
 */
data class LlmPlannerStepRecord(
    val stepIndex: Int,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val isVerified: Boolean,
    val observation: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Keep private memory tool payloads out of subsequent cloud reasoning requests. */
internal fun cloudHistoryLine(record: LlmPlannerStepRecord): String {
    val tool = ToolRegistry.resolveToolName(record.toolName)
    return if (tool in setOf("query_recall_core", "log_recall_core")) {
        "Step ${record.stepIndex}: Tool '$tool' -> Verified=${record.isVerified}, private result retained on device."
    } else {
        "Step ${record.stepIndex}: Tool '${record.toolName}' with args ${record.arguments} -> Verified=${record.isVerified}, Observation: ${record.observation}"
    }
}

/**
 * Discrete decision outcome produced by an LLM function-calling provider.
 */
sealed class LlmStepDecision {
    data class InvokeTool(
        val toolName: String,
        val arguments: Map<String, Any?>,
        val rationale: String? = null
    ) : LlmStepDecision()

    data class Finish(val summary: String) : LlmStepDecision()
    data class Clarify(val question: String) : LlmStepDecision()
    data class Abort(val reason: String) : LlmStepDecision()
}

/**
 * Result of an end-to-end LLM planning mission execution.
 */
data class LlmMissionExecutionResult(
    val isSuccess: Boolean,
    val summary: String,
    val stepsExecuted: Int,
    val history: List<LlmPlannerStepRecord>,
    val abortReason: String? = null,
    val pendingApprovalTool: String? = null,
    val pendingApprovalArguments: Map<String, Any?> = emptyMap()
)

/** Exact tool invocation explicitly approved by the user for a single execution. */
data class ApprovedLlmToolCall(
    val toolName: String,
    val arguments: Map<String, Any?>
)

/**
 * Provider abstraction for LLM function-calling planning.
 */
interface LlmFunctionCallProvider {
    val providerId: String
    suspend fun isAvailable(): Boolean
    suspend fun predictStep(
        goal: String,
        history: List<LlmPlannerStepRecord>,
        availableTools: List<ToolDefinition>
    ): LlmStepDecision

    /** Text-only reasoning for bounded advisory sub-agents; it cannot execute tools. */
    suspend fun reason(prompt: String): String? = null
}

/**
 * Converts ToolRegistry definitions into standard Gemini function declarations.
 */
object GeminiSchemaConverter {
    fun convertTool(tool: ToolDefinition): Map<String, Any> {
        val properties = mutableMapOf<String, Any>()
        val required = mutableListOf<String>()
        for (param in tool.parameters) {
            val typeStr = when (param.type.lowercase()) {
                "int", "integer" -> "INTEGER"
                "boolean" -> "BOOLEAN"
                "number", "float", "double" -> "NUMBER"
                else -> "STRING"
            }
            properties[param.name] = mapOf(
                "type" to typeStr,
                "description" to param.description
            )
            if (param.required) {
                required.add(param.name)
            }
        }
        return mapOf(
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to mapOf(
                "type" to "OBJECT",
                "properties" to properties,
                "required" to required
            )
        )
    }

    fun convertTools(tools: List<ToolDefinition>): List<Map<String, Any>> {
        return listOf(
            mapOf("function_declarations" to tools.map { convertTool(it) })
        )
    }
}

/**
 * Preserve JSON types from Gemini function-call arguments. Converting every primitive to a
 * String breaks tools that require numeric, boolean, list, or nested-object parameters.
 */
internal fun JsonElement.toNativeToolValue(): Any? = when {
    isJsonNull -> null
    isJsonObject -> asJsonObject.entrySet().associate { (key, value) -> key to value.toNativeToolValue() }
    isJsonArray -> asJsonArray.map { it.toNativeToolValue() }
    isJsonPrimitive -> asJsonPrimitive.let { primitive ->
        when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asNumber
            else -> primitive.asString
        }
    }
    else -> null
}

/**
 * Production Gemini function-calling provider using OkHttp and Gson.
 */
class GeminiFunctionCallProvider(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { com.jarvis.assistant.data.model.GeminiConstants.MODEL_3_8_FLASH },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson(),
    private val onApiKeyRateLimited: (String) -> Unit = {},
    private val onApiKeyInvalid: (String) -> Unit = {},
    private val onApiKeySuccess: (String) -> Unit = {}
) : LlmFunctionCallProvider {

    override val providerId: String = "gemini_function_calling"

    override suspend fun isAvailable(): Boolean {
        return apiKeyProvider().isNotBlank()
    }

    override suspend fun reason(prompt: String): String? = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank() || prompt.isBlank()) return@withContext null
        try {
            val model = modelProvider().removePrefix("models/").ifBlank {
                com.jarvis.assistant.data.model.GeminiConstants.MODEL_3_8_FLASH.removePrefix("models/")
            }
            val payload = mapOf(
                "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt.take(12000))))),
                "generationConfig" to mapOf("temperature" to 0.2, "maxOutputTokens" to 400)
            )
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            call.timeout().timeout(17, TimeUnit.SECONDS)
            call.awaitCouncilResponse().let { response ->
                val body = response.body
                if (!response.isSuccessful) {
                    if (response.code == 401 || body.contains("API_KEY_INVALID", true)) runCatching { onApiKeyInvalid(apiKey) }
                    else if (response.code == 429 || body.contains("RESOURCE_EXHAUSTED", true)) runCatching { onApiKeyRateLimited(apiKey) }
                    return@withContext null
                }
                runCatching { onApiKeySuccess(apiKey) }
                val json = gson.fromJson(body, JsonObject::class.java)
                val candidates = json.getAsJsonArray("candidates") ?: return@withContext null
                val parts = candidates.firstOrNull()?.asJsonObject?.getAsJsonObject("content")?.getAsJsonArray("parts")
                    ?: return@withContext null
                parts.mapNotNull { part -> part.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString }
                    .joinToString("\n").trim().takeIf { it.isNotBlank() }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("GeminiCouncilAgent", "Advisory reasoning unavailable (" + error.javaClass.simpleName + ").")
            null
        }
    }
    override suspend fun predictStep(
        goal: String,
        history: List<LlmPlannerStepRecord>,
        availableTools: List<ToolDefinition>
    ): LlmStepDecision = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) {
            return@withContext LlmStepDecision.Abort("Gemini API key is not configured.")
        }

        try {
            val model = modelProvider().removePrefix("models/").ifBlank { com.jarvis.assistant.data.model.GeminiConstants.MODEL_3_8_FLASH.removePrefix("models/") }
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            val contents = mutableListOf<Map<String, Any>>()

            // System / goal instruction
            val historyContext = if (history.isEmpty()) {
                "Goal: $goal\nSelect the next appropriate tool to achieve this goal, or call 'task_completed' when finished."
            } else {
                buildString {
                    appendLine("Goal: $goal")
                    appendLine("Action History & Observations:")
                    history.forEach { rec ->
                        appendLine(cloudHistoryLine(rec))
                    }
                    appendLine("Based on the above observation, choose the next tool or replan if previous failed, or call 'task_completed'.")
                }
            }

            contents.add(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to historyContext))
                )
            )

            val payload = mapOf(
                "contents" to contents,
                "tools" to GeminiSchemaConverter.convertTools(availableTools)
            )

            val jsonBody = gson.toJson(payload)
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            response.use {
                val respBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val explicitlyInvalidKey = response.code == 401 ||
                        respBody.contains("API_KEY_INVALID", ignoreCase = true) ||
                        respBody.contains("API key not valid", ignoreCase = true) ||
                        respBody.contains("invalid API key", ignoreCase = true)
                    val permissionDenied = response.code == 403 ||
                        respBody.contains("PERMISSION_DENIED", ignoreCase = true)
                    val quotaExceeded = response.code == 429 ||
                        respBody.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        respBody.contains("quota", ignoreCase = true)

                    val reason = when {
                        explicitlyInvalidKey -> {
                            runCatching { onApiKeyInvalid(apiKey) }
                            "Gemini explicitly rejected the API key (HTTP ${response.code})."
                        }
                        permissionDenied -> "Gemini denied access to this API key or project (HTTP ${response.code}); this does not prove the key is invalid."
                        quotaExceeded -> {
                            runCatching { onApiKeyRateLimited(apiKey) }
                            "Gemini rate limit or quota reached (HTTP ${response.code}); the key was placed into cooldown."
                        }
                        response.code == 404 -> "The selected Gemini model or endpoint was not found (HTTP 404)."
                        response.code == 400 -> "Gemini rejected the request configuration (HTTP 400); check the model and function-call schema."
                        response.code in 500..599 -> "Gemini service is temporarily unavailable (HTTP ${response.code})."
                        else -> "Gemini API request failed with HTTP ${response.code}."
                    }
                    return@withContext LlmStepDecision.Abort(reason)
                }
                runCatching { onApiKeySuccess(apiKey) }

                val json = gson.fromJson(respBody, JsonObject::class.java)
            val candidates = json.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                return@withContext LlmStepDecision.Abort("Empty candidates from Gemini")
            }

            val content = candidates.get(0).asJsonObject.getAsJsonObject("content")
            val parts = content?.getAsJsonArray("parts")
            if (parts == null || parts.size() == 0) {
                return@withContext LlmStepDecision.Abort("No content parts in Gemini candidate")
            }

            for (i in 0 until parts.size()) {
                val part = parts.get(i).asJsonObject
                if (part.has("functionCall")) {
                    val fn = part.getAsJsonObject("functionCall")
                    val fnName = fn.get("name")?.asString ?: ""
                    val argsMap = mutableMapOf<String, Any?>()
                    if (fn.has("args") && fn.get("args").isJsonObject) {
                        fn.getAsJsonObject("args").entrySet().forEach { (key, value) ->
                            argsMap[key] = value.toNativeToolValue()
                        }
                    }

                    if (fnName == "task_completed") {
                        val summary = argsMap["summary"]?.toString() ?: "Task completed successfully."
                        return@withContext LlmStepDecision.Finish(summary)
                    }

                    return@withContext LlmStepDecision.InvokeTool(fnName, argsMap)
                }

                // Natural-language claims such as "completed" are not execution evidence.
                // Only the task_completed function call can request finalization.
            }

            LlmStepDecision.Abort("No actionable function call returned by model.")
            }
        } catch (e: Exception) {
            Log.e("GeminiPlannerProvider", "Function-call request failed (${e.javaClass.simpleName}).")
            LlmStepDecision.Abort("Provider execution failed (${e.javaClass.simpleName}).")
        }
    }
}

/**
 * Autonomous LLM-driven task planner and execution coordinator.
 * Features hard step bounds, loop detection, timeout protection, and graceful failure recovery.
 */
class LlmPlanner(
    private val provider: LlmFunctionCallProvider,
    val maxSteps: Int = 15,
    val perStepTimeoutMs: Long = 15_000L,
    val totalMissionTimeoutMs: Long = 120_000L
) {
    companion object {
        private const val TAG = "LlmPlanner"
        const val MAX_CONSECUTIVE_IDENTICAL_CALLS = 3
    }

    private val isCancelled = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)

    fun cancel() {
        isCancelled.set(true)
    }

    fun resetCancellation() {
        isCancelled.set(false)
        pauseRequested.set(false)
    }

    /** Stops at the next safe boundary, after any in-flight tool call has returned. */
    fun requestPause() {
        pauseRequested.set(true)
    }

    fun clearPauseRequest() {
        pauseRequested.set(false)
    }

    fun isAvailable(): Boolean = !isCancelled.get()

    suspend fun isProviderReady(): Boolean =
        !isCancelled.get() && provider.isAvailable()

    /** Bounded text-only model call for advisory council sub-agents. */
    suspend fun reasonAsSubAgent(prompt: String): String? {
        if (isCancelled.get() || !provider.isAvailable()) return null
        return provider.reason(prompt)
    }

    /**
     * Executes the autonomous reasoning-action loop.
     */
    suspend fun planAndExecute(
        goal: String,
        context: Context? = null,
        initialHistory: List<LlmPlannerStepRecord> = emptyList(),
        approvedToolCall: ApprovedLlmToolCall? = null,
        allowedToolNames: Set<String>? = null,
        onStepStarted: suspend (toolName: String, args: Map<String, Any?>) -> Unit = { _, _ -> },
        onStepRecorded: suspend (record: LlmPlannerStepRecord) -> Unit = {},
        executor: suspend (toolName: String, args: Map<String, Any?>) -> Pair<Boolean, String>
    ): LlmMissionExecutionResult {
        val history = initialHistory.toMutableList()
        val startTime = System.currentTimeMillis()
        val availableTools = ToolRegistry.getAllTools().filter { allowedToolNames == null || it.name in allowedToolNames }
        val recentCallSignatures = mutableListOf<String>().apply {
            initialHistory.forEach { record ->
                add("${ToolRegistry.resolveToolName(record.toolName)}:${record.arguments.entries.sortedBy { it.key }}")
            }
        }

        if (isCancelled.get()) {
            return LlmMissionExecutionResult(false, "Mission cancelled before execution began.", history.size, history, "CANCELLED")
        }
        if (pauseRequested.compareAndSet(true, false)) {
            return LlmMissionExecutionResult(false, "Mission paused before execution began.", history.size, history, "PAUSED")
        }

        // An approved consequential call is executed once before asking the model to continue.
        // The approval is bound to the exact canonical tool name and complete argument map.
        if (approvedToolCall != null) {
            val canonicalName = ToolRegistry.resolveToolName(approvedToolCall.toolName)
            if (allowedToolNames != null && canonicalName !in allowedToolNames) return LlmMissionExecutionResult(false, "Approved action is outside the saved mission route.", history.size, history, "ROUTE_VIOLATION")
            val definition = ToolRegistry.getTool(canonicalName)
                ?: return LlmMissionExecutionResult(
                    false, "Approved tool is no longer registered; no action was executed.",
                    history.size, history, "APPROVED_TOOL_UNAVAILABLE"
                )
            val risk = RiskPolicy.evaluateRisk(canonicalName, approvedToolCall.arguments, goal)
            if (!risk.requiresExplicitConfirmation && definition.riskLevel != MissionRisk.HIGH_CONSEQUENTIAL) {
                return LlmMissionExecutionResult(
                    false, "Approval token did not match a consequential tool; no action was executed.",
                    history.size, history, "APPROVAL_SCOPE_MISMATCH"
                )
            }
            val validation = ToolRegistry.validate(canonicalName, approvedToolCall.arguments, context)
            if (validation !is com.jarvis.assistant.automation.mission.ToolValidationResult.Valid) {
                return LlmMissionExecutionResult(
                    false, "Approved action is currently blocked by tool validation: $validation",
                    history.size, history, "APPROVED_TOOL_VALIDATION_FAILED"
                )
            }

            try {
                onStepStarted(canonicalName, approvedToolCall.arguments)
            } catch (checkpointError: Exception) {
                return LlmMissionExecutionResult(
                    false, "Could not persist the approved action checkpoint; the action was not executed.",
                    history.size, history, "CHECKPOINT_FAILED_BEFORE_EXECUTION"
                )
            }
            val outcome = withTimeoutOrNull(perStepTimeoutMs) {
                runCatching { executor(canonicalName, approvedToolCall.arguments) }
                    .getOrElse { false to "Approved action failed (${it.javaClass.simpleName})." }
            } ?: (false to "Approved action timed out after ${perStepTimeoutMs}ms.")
            history.add(
                LlmPlannerStepRecord(
                    stepIndex = history.size + 1,
                    toolName = canonicalName,
                    arguments = approvedToolCall.arguments,
                    isVerified = outcome.first,
                    observation = outcome.second
                )
            )
            try {
                onStepRecorded(history.last())
            } catch (checkpointError: Exception) {
                return LlmMissionExecutionResult(
                    false, "The approved action returned, but its checkpoint could not be persisted.",
                    history.size, history, "CHECKPOINT_FAILED_AFTER_EXECUTION"
                )
            }
            recentCallSignatures.add("$canonicalName:${approvedToolCall.arguments.entries.sortedBy { it.key }}")
            if (!outcome.first) {
                return LlmMissionExecutionResult(
                    false,
                    "The user-approved action was attempted but its result was not verified: ${outcome.second}",
                    history.size,
                    history,
                    "APPROVED_ACTION_UNVERIFIED"
                )
            }
        }

        Log.i(TAG, "Starting bounded LLM mission loop.")

        for (stepIndex in 1..maxSteps) {
            // 1. Cancellation check
            if (isCancelled.get()) {
                return LlmMissionExecutionResult(
                    isSuccess = false,
                    summary = "Mission cancelled by user or kill-switch.",
                    stepsExecuted = history.size,
                    history = history,
                    abortReason = "CANCELLED"
                )
            }

            if (pauseRequested.compareAndSet(true, false)) {
                return LlmMissionExecutionResult(
                    isSuccess = false,
                    summary = "Mission paused at a safe step boundary. Completed tool results are preserved.",
                    stepsExecuted = history.size,
                    history = history,
                    abortReason = "PAUSED"
                )
            }

            // 2. Total mission timeout check
            if (System.currentTimeMillis() - startTime > totalMissionTimeoutMs) {
                return LlmMissionExecutionResult(
                    isSuccess = false,
                    summary = "Mission aborted: total timeout of ${totalMissionTimeoutMs}ms exceeded.",
                    stepsExecuted = history.size,
                    history = history,
                    abortReason = "TOTAL_TIMEOUT"
                )
            }

            // 3. Provider decision
            val decision = provider.predictStep(goal, history, availableTools)
            if (isCancelled.get()) {
                return LlmMissionExecutionResult(false, "Mission cancelled before the next tool decision.", history.size, history, "CANCELLED")
            }
            if (pauseRequested.compareAndSet(true, false)) {
                return LlmMissionExecutionResult(
                    isSuccess = false,
                    summary = "Mission paused before the next tool decision. Completed tool results are preserved.",
                    stepsExecuted = history.size,
                    history = history,
                    abortReason = "PAUSED"
                )
            }
            when (decision) {
                is LlmStepDecision.Finish -> {
                    val unverified = history.filterNot { it.isVerified }
                    val hasEvidence = history.isNotEmpty() && unverified.isEmpty()
                    return LlmMissionExecutionResult(
                        isSuccess = hasEvidence,
                        summary = if (hasEvidence) {
                            decision.summary
                        } else {
                            "The planner requested completion, but verified execution evidence is missing. The mission is not marked complete."
                        },
                        stepsExecuted = history.size,
                        history = history,
                        abortReason = if (hasEvidence) null else "UNVERIFIED_FINISH"
                    )
                }
                is LlmStepDecision.Clarify -> {
                    return LlmMissionExecutionResult(
                        isSuccess = false,
                        summary = "Clarification required: ${decision.question}",
                        stepsExecuted = history.size,
                        history = history,
                        abortReason = "CLARIFICATION_REQUIRED"
                    )
                }
                is LlmStepDecision.Abort -> {
                    return LlmMissionExecutionResult(
                        isSuccess = false,
                        summary = "Mission aborted by planner: ${decision.reason}",
                        stepsExecuted = history.size,
                        history = history,
                        abortReason = decision.reason
                    )
                }
                is LlmStepDecision.InvokeTool -> {
                    val resolvedName = ToolRegistry.resolveToolName(decision.toolName)
                    if (allowedToolNames != null && resolvedName !in allowedToolNames) return LlmMissionExecutionResult(false, "Planner requested a tool outside the selected route; no action was executed.", history.size, history, "ROUTE_VIOLATION")
                    val toolDef = ToolRegistry.getTool(resolvedName)
                    if (toolDef == null) {
                        history.add(
                            LlmPlannerStepRecord(
                                stepIndex = stepIndex,
                                toolName = decision.toolName,
                                arguments = decision.arguments,
                                isVerified = false,
                                observation = "Tool '${decision.toolName}' is not recognized in ToolRegistry."
                            )
                        )
                        continue
                    }

                    // 4. Loop Detection Check: same tool + args 3 times
                    val signature = "$resolvedName:${decision.arguments.entries.sortedBy { it.key }}"
                    recentCallSignatures.add(signature)
                    val identicalCount = recentCallSignatures.count { it == signature }
                    if (identicalCount >= MAX_CONSECUTIVE_IDENTICAL_CALLS) {
                        val loopMsg = "Loop detected: tool '$resolvedName' invoked $identicalCount times with identical arguments."
                        Log.w(TAG, loopMsg)
                        return LlmMissionExecutionResult(
                            isSuccess = false,
                            summary = loopMsg,
                            stepsExecuted = history.size,
                            history = history,
                            abortReason = "LOOP_DETECTED"
                        )
                    }

                    // Consequential operations must stop for explicit user approval before execution.
                    val risk = RiskPolicy.evaluateRisk(resolvedName, decision.arguments, goal)
                    if (risk.requiresExplicitConfirmation || toolDef.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL) {
                        history.add(
                            LlmPlannerStepRecord(
                                stepIndex = stepIndex,
                                toolName = resolvedName,
                                arguments = decision.arguments,
                                isVerified = false,
                                observation = "Awaiting explicit user approval; this action has not been executed."
                            )
                        )
                        return LlmMissionExecutionResult(
                            isSuccess = false,
                            summary = risk.confirmationSummary.orEmpty().ifBlank { "Approval required before JARVIS can execute '$resolvedName'. No action has been performed." },
                            stepsExecuted = history.size,
                            history = history,
                            abortReason = "APPROVAL_REQUIRED",
                            pendingApprovalTool = resolvedName,
                            pendingApprovalArguments = decision.arguments
                        )
                    }

                    // 5. Pre-execution ToolRegistry Validation
                    val validation = ToolRegistry.validate(resolvedName, decision.arguments, context)
                    if (validation !is com.jarvis.assistant.automation.mission.ToolValidationResult.Valid) {
                        val reason = validation.toString()
                        history.add(
                            LlmPlannerStepRecord(
                                stepIndex = stepIndex,
                                toolName = resolvedName,
                                arguments = decision.arguments,
                                isVerified = false,
                                observation = "Tool validation failed: $reason"
                            )
                        )
                        // If it's a high consequential tool, do not silently retry
                        if (toolDef.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL) {
                            return LlmMissionExecutionResult(
                                isSuccess = false,
                                summary = "Consequential tool '$resolvedName' blocked: $reason",
                                stepsExecuted = history.size,
                                history = history,
                                abortReason = "CONSEQUENTIAL_BLOCKED"
                            )
                        }
                        continue
                    }

                    // Re-check cancellation immediately before invoking a real-world tool.
                    if (isCancelled.get()) {
                        return LlmMissionExecutionResult(false, "Mission cancelled before tool execution.", history.size, history, "CANCELLED")
                    }

                    // Persist the in-flight action before invoking a real-world tool.
                    try {
                        onStepStarted(resolvedName, decision.arguments)
                    } catch (checkpointError: Exception) {
                        return LlmMissionExecutionResult(false, "Could not persist the tool checkpoint; the action was not executed.", history.size, history, "CHECKPOINT_FAILED_BEFORE_EXECUTION")
                    }

                    // 6. Execute step with bounded timeout
                    Log.i(TAG, "Step $stepIndex: Invoking registered tool '$resolvedName'.")
                    val executionOutcome = withTimeoutOrNull(perStepTimeoutMs) {
                        try {
                            executor(resolvedName, decision.arguments)
                        } catch (e: Exception) {
                            Pair(false, "Execution exception: ${e.message}")
                        }
                    }

                    val (verified, observation) = executionOutcome ?: Pair(false, "Step execution timed out after ${perStepTimeoutMs}ms")

                    val record = LlmPlannerStepRecord(
                        stepIndex = stepIndex,
                        toolName = resolvedName,
                        arguments = decision.arguments,
                        isVerified = verified,
                        observation = observation
                    )
                    history.add(record)
                    try {
                        onStepRecorded(record)
                    } catch (checkpointError: Exception) {
                        return LlmMissionExecutionResult(false, "The tool returned, but its execution checkpoint could not be persisted.", history.size, history, "CHECKPOINT_FAILED_AFTER_EXECUTION")
                    }

                    // Honor stop requests immediately after the current action has been
                    // durably recorded, including when this was the final allowed step.
                    if (isCancelled.get()) {
                        return LlmMissionExecutionResult(false, "Mission cancelled after the current action returned.", history.size, history, "CANCELLED")
                    }
                    if (pauseRequested.compareAndSet(true, false)) {
                        return LlmMissionExecutionResult(
                            false,
                            "Mission paused after the current action reached a durable safe boundary.",
                            history.size,
                            history,
                            "PAUSED"
                        )
                    }

                    // 7. Consequential failure guard: Never blindly retry consequential failures
                    if (!verified && toolDef.riskLevel == MissionRisk.HIGH_CONSEQUENTIAL) {
                        Log.w(TAG, "Consequential action '$resolvedName' failed verification. Halting automatic retries.")
                        return LlmMissionExecutionResult(
                            isSuccess = false,
                            summary = "Consequential action '$resolvedName' failed verification: $observation",
                            stepsExecuted = history.size,
                            history = history,
                            abortReason = "CONSEQUENTIAL_UNVERIFIED"
                        )
                    }
                }
            }
        }

        return LlmMissionExecutionResult(
            isSuccess = false,
            summary = "Mission aborted: maximum step limit ($maxSteps) reached without completion.",
            stepsExecuted = history.size,
            history = history,
            abortReason = "STEP_LIMIT_EXCEEDED"
        )
    }
}
