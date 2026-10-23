package com.jarvis.assistant.security.sandbox

import com.jarvis.assistant.automation.mission.MissionRisk
import com.jarvis.assistant.automation.mission.ToolRegistry

data class RiskEvaluation(
    val tier: MissionRisk,
    val requiresExplicitConfirmation: Boolean,
    val confirmationSummary: String? = null,
    val allowRememberOption: Boolean = true
)

/**
 * Word-boundary matching and argument-aware security risk evaluator.
 * Replaces naive substring searches to prevent false positives like matching 'display' for 'pay'.
 */
class RiskPolicy {
    companion object {
        // Strict word boundary regex patterns
        private val SENSITIVE_WORD_PATTERNS = listOf(
            Regex("\\bpay\\b", RegexOption.IGNORE_CASE),
            Regex("\\bpayment\\b", RegexOption.IGNORE_CASE),
            Regex("\\bupi\\b", RegexOption.IGNORE_CASE),
            Regex("\\btransfer\\s+money\\b", RegexOption.IGNORE_CASE),
            Regex("\\bdelete\\b", RegexOption.IGNORE_CASE),
            Regex("\\bremove\\b", RegexOption.IGNORE_CASE),
            Regex("\\bfactory\\s+reset\\b", RegexOption.IGNORE_CASE),
            Regex("\\bformat\\b", RegexOption.IGNORE_CASE),
            Regex("\\bsend\\s+money\\b", RegexOption.IGNORE_CASE),
            Regex("\\bbuy\\b", RegexOption.IGNORE_CASE),
            Regex("\\bpurchase\\b", RegexOption.IGNORE_CASE)
        )

        private val BANKING_OR_PAYMENT_TOOLS = setOf(
            "gpay_pay", "phonepe_pay", "paytm_pay", "bank_transfer", "send_upi_payment"
        )

        /**
         * Checks if the text matches any sensitive keyword using strict word boundaries.
         */
        fun containsSensitiveKeyword(text: String): Boolean {
            return SENSITIVE_WORD_PATTERNS.any { it.containsMatchIn(text) }
        }

        /**
         * Evaluates invocation risk taking tool tier, argument sensitivity, and banking/payment rules into account.
         */
        fun evaluateRisk(
            toolName: String,
            arguments: Map<String, Any?> = emptyMap(),
            title: String = ""
        ): RiskEvaluation {
            val resolvedName = ToolRegistry.resolveToolName(toolName)
            val toolDef = ToolRegistry.getTool(resolvedName)

            val isPaymentOrBanking = BANKING_OR_PAYMENT_TOOLS.contains(resolvedName) ||
                containsSensitiveKeyword(title) ||
                arguments.values.any { it != null && containsSensitiveKeyword(it.toString()) }

            if (isPaymentOrBanking) {
                return RiskEvaluation(
                    tier = MissionRisk.HIGH_CONSEQUENTIAL,
                    requiresExplicitConfirmation = true,
                    confirmationSummary = "Financial transaction or payment detected: '$toolName' ($arguments). Explicit confirmation required.",
                    allowRememberOption = false // Never allow 'remember this' for payments/UPI
                )
            }

            val baseRisk = toolDef?.riskLevel ?: MissionRisk.LOW
            val requiresConfirm = baseRisk == MissionRisk.HIGH_CONSEQUENTIAL

            val summary = if (requiresConfirm) {
                "Consequential action: '$resolvedName' with parameters $arguments"
            } else null

            return RiskEvaluation(
                tier = baseRisk,
                requiresExplicitConfirmation = requiresConfirm,
                confirmationSummary = summary,
                allowRememberOption = true
            )
        }
    }
}
