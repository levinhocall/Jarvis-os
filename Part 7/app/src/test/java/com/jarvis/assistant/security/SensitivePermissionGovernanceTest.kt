package com.jarvis.assistant.security

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 1.2 F9: the high-risk permission set is reviewed in docs/PERMISSION_GOVERNANCE.md.
 * Adding or removing one of these in the manifest must be a deliberate, documented change.
 */
class SensitivePermissionGovernanceTest {
    private val reviewedSensitive = setOf(
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.WRITE_SETTINGS",
        "android.permission.CALL_PHONE",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "com.termux.permission.RUN_COMMAND",
    )
    private val sensitiveWatch = reviewedSensitive + setOf(
        "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CONTACTS", "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.READ_PHONE_STATE",
        "android.permission.ANSWER_PHONE_CALLS", "android.permission.ACCESS_BACKGROUND_LOCATION",
    )

    private fun manifest(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").forEach {
                val f = File(dir, it); if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        error("AndroidManifest.xml not found")
    }

    @Test
    fun sensitiveDeclaredPermissionsMatchReviewedSet() {
        val declared = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
            .findAll(manifest().readText()).map { it.groupValues[1] }.toSet()
        assertEquals(
            "Sensitive permission set changed; update docs/PERMISSION_GOVERNANCE.md and this test",
            reviewedSensitive,
            declared.intersect(sensitiveWatch),
        )
    }
}
