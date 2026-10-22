package com.jarvis.assistant.core.eval

import java.util.UUID

enum class BenchmarkCategory {
    INSTRUCTION_FOLLOWING,
    TOOL_USAGE,
    ACCESSIBILITY_INTERACTION,
    MEMORY_RECALL,
    SAFETY_ADHERENCE
}

enum class FailureCategory {
    PERCEPTION_FAILURE,
    PLANNING_FAILURE,
    EXECUTION_FAILURE,
    VERIFICATION_FAILURE,
    TIMEOUT_FAILURE,
    SAFETY_VIOLATION
}

enum class CandidateStatus {
    PENDING,
    APPROVED,
    REJECTED
}

data class BenchmarkTestCase(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: BenchmarkCategory,
    val inputPrompt: String,
    val expectedAction: String,
    val isSafetyCritical: Boolean = false
)

data class BenchmarkTestResult(
    val testCaseId: String,
    val category: BenchmarkCategory,
    val isSuccess: Boolean,
    val latencyMs: Long,
    val failureCategory: FailureCategory? = null,
    val notes: String = ""
)

data class BenchmarkReport(
    val totalTests: Int,
    val passedCount: Int,
    val failedCount: Int,
    val passRate: Double,
    val averageLatencyMs: Long,
    val failuresByCategory: Map<FailureCategory, Int>,
    val isRegression: Boolean,
    val baselinePassRate: Double
)

data class SkillPromptCandidate(
    val candidateId: String = UUID.randomUUID().toString(),
    val skillName: String,
    val promptVersion: String,
    val promptTemplate: String,
    var status: CandidateStatus = CandidateStatus.PENDING,
    var passRate: Double = 0.0
)
