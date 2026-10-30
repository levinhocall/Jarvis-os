package com.jarvis.assistant.data.memory

/**
 * Deterministic recovery decision for the two-rename plaintext-to-encrypted database swap.
 * This policy is deliberately filesystem-agnostic so its crash-window behavior can be unit tested.
 */
internal enum class DatabaseSwapRecoveryAction {
    NONE,
    PROMOTE_VERIFIED_ENCRYPTED_STAGING,
    RESTORE_PLAINTEXT_BACKUP
}

internal object DatabaseSwapRecoveryPolicy {
    fun decide(
        databaseExists: Boolean,
        backupExists: Boolean,
        stagingExists: Boolean,
        stagingVerified: Boolean
    ): DatabaseSwapRecoveryAction {
        if (databaseExists || !backupExists) return DatabaseSwapRecoveryAction.NONE
        return if (stagingExists && stagingVerified) {
            DatabaseSwapRecoveryAction.PROMOTE_VERIFIED_ENCRYPTED_STAGING
        } else {
            DatabaseSwapRecoveryAction.RESTORE_PLAINTEXT_BACKUP
        }
    }
}
