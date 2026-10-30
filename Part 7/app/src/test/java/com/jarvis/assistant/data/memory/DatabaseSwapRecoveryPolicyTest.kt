package com.jarvis.assistant.data.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseSwapRecoveryPolicyTest {

    @Test
    fun noRecoveryNeededWhenPrimaryDatabaseExists() {
        assertEquals(
            DatabaseSwapRecoveryAction.NONE,
            DatabaseSwapRecoveryPolicy.decide(
                databaseExists = true,
                backupExists = true,
                stagingExists = true,
                stagingVerified = true
            )
        )
    }

    @Test
    fun noRecoveryNeededWhenThereIsNoBackup() {
        assertEquals(
            DatabaseSwapRecoveryAction.NONE,
            DatabaseSwapRecoveryPolicy.decide(
                databaseExists = false,
                backupExists = false,
                stagingExists = true,
                stagingVerified = true
            )
        )
    }

    @Test
    fun promotesOnlyVerifiedEncryptedStagingFile() {
        assertEquals(
            DatabaseSwapRecoveryAction.PROMOTE_VERIFIED_ENCRYPTED_STAGING,
            DatabaseSwapRecoveryPolicy.decide(
                databaseExists = false,
                backupExists = true,
                stagingExists = true,
                stagingVerified = true
            )
        )
    }

    @Test
    fun restoresPlaintextBackupWhenStagingIsMissingOrUnverified() {
        assertEquals(
            DatabaseSwapRecoveryAction.RESTORE_PLAINTEXT_BACKUP,
            DatabaseSwapRecoveryPolicy.decide(
                databaseExists = false,
                backupExists = true,
                stagingExists = false,
                stagingVerified = false
            )
        )
        assertEquals(
            DatabaseSwapRecoveryAction.RESTORE_PLAINTEXT_BACKUP,
            DatabaseSwapRecoveryPolicy.decide(
                databaseExists = false,
                backupExists = true,
                stagingExists = true,
                stagingVerified = false
            )
        )
    }
}
