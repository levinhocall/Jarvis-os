package com.jarvis.assistant.data.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseKeyStoreInstrumentedTest {
    @Test
    fun keystoreWrapsAndUnwrapsPassphraseAndRejectsTampering() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val alias = "jarvis_test_${UUID.randomUUID().toString().replace("-", "")}"
        val keyStore = DatabaseKeyStore(context, alias)
        val keyFile = File(context.noBackupFilesDir, "$alias.bin")
        val pendingFile = File(context.noBackupFilesDir, "$alias.pending")
        var original: ByteArray? = null

        try {
            original = keyStore.createPassphraseForNewDatabase()
            assertTrue(keyStore.hasWrappedPassphrase())
            assertTrue(keyStore.hasPendingInitialization())
            assertArrayEquals(original, keyStore.loadExistingPassphrase())

            keyStore.markInitializationComplete()
            assertFalse(keyStore.hasPendingInitialization())

            val tampered = keyFile.readBytes()
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0x01).toByte()
            keyFile.writeBytes(tampered)
            val rejected = runCatching { keyStore.loadExistingPassphrase() }.isFailure
            assertTrue("AES-GCM must reject modified wrapped key material", rejected)
        } finally {
            original?.fill(0)
            keyFile.delete()
            pendingFile.delete()
            File(keyFile.parentFile, keyFile.name + ".tmp").delete()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                if (containsAlias(alias)) deleteEntry(alias)
            }
        }
    }
}
