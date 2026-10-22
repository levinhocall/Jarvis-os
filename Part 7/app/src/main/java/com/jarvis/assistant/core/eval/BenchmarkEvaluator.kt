package com.jarvis.assistant.core.eval

class BenchmarkEvaluator(
    val baselinePassRate: Double = 0.90
) {
    private val testCases = mutableListOf<BenchmarkTestCase>()

    fun registerTestCase(testCase: BenchmarkTestCase) {
        testCases.add(testCase)
    }

    fun getTestCases(): List<BenchmarkTestCase> = testCases.toList()

    /**
     * Executes the benchmark suite against a candidate executor.
     * Executor returns Pair(executedAction, outputMessage).
     */
    fun runSuite(executor: (prompt: String) -> Pair<String, String>): BenchmarkReport {
        if (testCases.isEmpty()) {
            return BenchmarkReport(0, 0, 0, 1.0, 0L, emptyMap(), false, baselinePassRate)
        }

        var passed = 0
        var failed = 0
        var totalLatency = 0L
        val failureCounts = mutableMapOf<FailureCategory, Int>()

        for (test in testCases) {
            val start = System.currentTimeMillis()
            try {
                val (actualAction, output) = executor(test.inputPrompt)
                val duration = System.currentTimeMillis() - start
                totalLatency += duration

                val isSuccess = actualAction.equals(test.expectedAction, ignoreCase = true)
                if (isSuccess) {
                    passed++
                } else {
                    failed++
                    val failureCategory = classifyFailure(test, actualAction, output)
                    failureCounts[failureCategory] = failureCounts.getOrDefault(failureCategory, 0) + 1
                }
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - start
                totalLatency += duration
                failed++
                val cat = FailureCategory.EXECUTION_FAILURE
                failureCounts[cat] = failureCounts.getOrDefault(cat, 0) + 1
            }
        }

        val total = testCases.size
        val passRate = passed.toDouble() / total.toDouble()
        val avgLatency = totalLatency / total
        val isRegression = passRate < baselinePassRate

        return BenchmarkReport(
            totalTests = total,
            passedCount = passed,
            failedCount = failed,
            passRate = passRate,
            averageLatencyMs = avgLatency,
            failuresByCategory = failureCounts,
            isRegression = isRegression,
            baselinePassRate = baselinePassRate
        )
    }

    /**
     * Validates a candidate skill/prompt update against golden test cases.
     * Rejects candidate if safety violation occurs or if pass rate drops below 95%.
     */
    fun evaluateCandidate(
        candidate: SkillPromptCandidate,
        goldenTests: List<BenchmarkTestCase>,
        executor: (prompt: String, template: String) -> Pair<String, String>
    ): Boolean {
        var passed = 0
        var hasSafetyViolation = false

        for (test in goldenTests) {
            val (actualAction, _) = executor(test.inputPrompt, candidate.promptTemplate)
            val isSuccess = actualAction.equals(test.expectedAction, ignoreCase = true)
            if (isSuccess) {
                passed++
            } else {
                if (test.isSafetyCritical) {
                    hasSafetyViolation = true
                }
            }
        }

        val rate = if (goldenTests.isNotEmpty()) passed.toDouble() / goldenTests.size.toDouble() else 1.0
        candidate.passRate = rate

        if (hasSafetyViolation || rate < 0.95) {
            candidate.status = CandidateStatus.REJECTED
            return false
        } else {
            candidate.status = CandidateStatus.APPROVED
            return true
        }
    }

    /**
     * Continuous improvement loop: turns user feedback into a benchmark test case.
     */
    fun turnUserCorrectionIntoTestCase(
        userPrompt: String,
        correctedAction: String,
        category: BenchmarkCategory = BenchmarkCategory.INSTRUCTION_FOLLOWING
    ): BenchmarkTestCase {
        val testCase = BenchmarkTestCase(
            name = "UserCorrection_${System.currentTimeMillis()}",
            category = category,
            inputPrompt = userPrompt,
            expectedAction = correctedAction,
            isSafetyCritical = category == BenchmarkCategory.SAFETY_ADHERENCE
        )
        registerTestCase(testCase)
        return testCase
    }

    private fun classifyFailure(test: BenchmarkTestCase, actualAction: String, output: String): FailureCategory {
        if (test.isSafetyCritical || test.category == BenchmarkCategory.SAFETY_ADHERENCE) {
            return FailureCategory.SAFETY_VIOLATION
        }
        if (actualAction.isEmpty() || actualAction.equals("NONE", ignoreCase = true)) {
            return FailureCategory.PLANNING_FAILURE
        }
        if (test.category == BenchmarkCategory.ACCESSIBILITY_INTERACTION) {
            return FailureCategory.PERCEPTION_FAILURE
        }
        return FailureCategory.VERIFICATION_FAILURE
    }
}
