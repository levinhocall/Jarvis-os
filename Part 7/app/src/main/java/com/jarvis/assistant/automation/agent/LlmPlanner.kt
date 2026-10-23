package com.jarvis.assistant.automation.agent

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jarvis.assistant.automation.mission.MissionRisk
import com.jarvis.assistant.automation.mission.ToolDefinition
import com.jarvis.assistant.automation.mission.ToolRegistry
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
    val abortReason: String? = null
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
 * Production Gemini function-calling provider using OkHttp and Gson.
 */
class GeminiFunctionCallProvider(
    private val apiKeyProvider: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) : LlmFunctionCallProvider {

    override val providerId: String = "gemini_function_calling"

    override suspend fun isAvailable(): Boolean {
        return apiKeyProvider().isNotBlank()
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
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            val contents = mutableListOf<Map<String, Any>>()

            // System / goal instruction
            val historyContext = if (history.isEmpty()) {
                "Goal: $goal\nSelect the next appropriate tool to achieve this goal, or call 'task_completed' when finished."
            } else {
                buildString {
                    appendLine("Goal: $goal")
                    appendLine("Action History & Observations:")
                    history.forEach { rec ->
                        appendLine("Step ${rec.stepIndex}: Tool '${rec.toolName}' with args ${rec.arguments} -> Verified=${rec.isVerified}, Observation: ${rec.observation}")
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
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext LlmStepDecision.Abort("Gemini API error ${response.code}: $respBody")
            }

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
                        fn.getAsJsonObject("args").entrySet().forEach { (k, v) ->
                            argsMap[k] = if (v.isJsonPrimitive) v.asString else v.toString()
                        }
                    }

                    if (fnName == "task_completed") {
                        val summary = argsMap["summary"]?.toString() ?: "Task completed successfully."
                        return@withContext LlmStepDecision.Finish(summary)
                    }

                    return@withContext LlmStepDecision.InvokeTool(fnName, argsMap)
                }

                if (part.has("text")) {
                    val text = part.get("text").asString
                    if (text.contains("completed", ignoreCase = true) || text.contains("finished", ignoreCase = true)) {
                        return@withContext LlmStepDecision.Finish(text)
                    }
                }
            }

            LlmStepDecision.Abort("No actionable function call returned by model.")
        } catch (e: Exception) {
            Log.e("GeminiPlannerProvider", "Function call prediction failed", e)
            LlmStepDecision.Abort("Provider execution error: ${e.message}")
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

    fun cancel() {
        isCancelled.set(true)
    }

    fun isAvailable(): Boolean {
        return !isCancelled.get()
    }

    /**
     * Executes the autonomous reasoning-action loop.
     */
    suspend fun planAndExecute(
        goal: String,
        context: Context? = null,
        executor: suspend (toolName: String, args: Map<String, Any?>) -> Pair<Boolean, String>
    ): LlmMissionExecutionResult {
        val history = mutableListOf<LlmPlannerStepRecord>()
        val startTime = System.currentTimeMillis()
        val availableTools = ToolRegistry.getAllTools()
        val recentCallSignatures = mutableListOf<String>()

        Log.i(TAG, "Starting LLM mission loop for goal: '$goal'")

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
            when (decision) {
                is LlmStepDecision.Finish -> {
                    Log.i(TAG, "Mission finished by LLM: ${decision.summary}")
                    return LlmMissionExecutionResult(
                        isSuccess = true,
                        summary = decision.summary,
                        stepsExecuted = history.size,
                        history = history
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

                    // 6. Execute step with bounded timeout
                    Log.i(TAG, "Step $stepIndex: Invoking '$resolvedName' with args: ${decision.arguments}")
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
