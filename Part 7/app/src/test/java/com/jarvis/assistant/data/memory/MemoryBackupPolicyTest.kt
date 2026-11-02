package com.jarvis.assistant.data.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryBackupPolicyTest {
    @Test fun `backups accept only local storage providers`() {
        assertTrue(MemoryBackupPolicy.isOnDeviceDocument("content", "com.android.externalstorage.documents"))
        assertTrue(MemoryBackupPolicy.isOnDeviceDocument("content", "com.android.providers.downloads.documents"))
        assertFalse(MemoryBackupPolicy.isOnDeviceDocument("content", "com.google.android.apps.docs.storage"))
        assertFalse(MemoryBackupPolicy.isOnDeviceDocument("https", "com.android.providers.downloads.documents"))
        assertFalse(MemoryBackupPolicy.isOnDeviceDocument(null, null))
    }
    @Test fun `bounded reader preserves valid UTF8`() {
        val json = "[{\"value\":\"नमस्ते\"}]"
        assertEquals(json, MemoryBackupPolicy.readBoundedJson(json.byteInputStream()))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `oversized backup is rejected before parsing`() {
        MemoryBackupPolicy.readBoundedJson(ByteArray(17).inputStream(), limitBytes = 16)
    }
}
