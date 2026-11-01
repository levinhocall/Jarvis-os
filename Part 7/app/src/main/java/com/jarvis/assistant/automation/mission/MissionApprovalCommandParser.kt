package com.jarvis.assistant.automation.mission

import java.util.Locale

/**
 * Accepts only explicit approval/rejection phrases. Generic "yes" or "no" must not
 * accidentally approve a pending consequential mission during unrelated conversation.
 */
object MissionApprovalCommandParser {
    fun parse(text: String): Boolean? {
        val normalized = text.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[.!?]+$"), "")
            .replace(Regex("\\s+"), " ")

        val approve = setOf(
            "approve", "approve mission", "confirm", "confirm mission",
            "confirm it", "confirm kar do", "haan confirm", "haan approve karo",
            "yes confirm", "proceed with mission"
        )
        val reject = setOf(
            "reject", "reject mission", "decline", "cancel mission",
            "cancel it", "no cancel", "do not proceed", "don't proceed",
            "mat karo", "nahi cancel"
        )
        return when (normalized) {
            in approve -> true
            in reject -> false
            else -> null
        }
    }
}
