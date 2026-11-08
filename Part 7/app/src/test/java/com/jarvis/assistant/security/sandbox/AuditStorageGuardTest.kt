package com.jarvis.assistant.security.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1.2 F3/F5: the audit database must not be openable as plaintext. */
class AuditStorageGuardTest {
    private fun mainRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/java/com/jarvis/assistant")
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "app/src/main/java/com/jarvis/assistant")
            if (nested.isDirectory) return nested
            dir = dir.parentFile
        }
        error("main source root not found")
    }

    @Test
    fun plaintextBuilderIsGone() {
        val source = File(mainRoot(), "security/sandbox/AuditEntities.kt").readText()
        assertFalse(source.contains("Room.databaseBuilder"))
    }

    @Test
    fun getInstanceFailsClosedInSource() {
        val source = File(mainRoot(), "security/sandbox/AuditEntities.kt").readText()
        assertTrue(source.contains("error("))
        assertTrue(source.contains("until it is encrypted"))
    }

    @Test
    fun noProductionCodeOpensAuditDatabase() {
        val offenders = mainRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AuditEntities.kt" }
            .filter { it.readText().contains("AuditDatabase") }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
