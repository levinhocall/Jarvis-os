package com.jarvis.assistant.data.memory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps a random SQLCipher passphrase with a non-exportable Android Keystore AES key. */
internal class DatabaseKeyStore(context: Context) {
    private val keyFile = File(context.applicationContext.noBackupFilesDir, "jarvis_room_database_key.bin")

    companion object {
        private const val ALIAS = "jarvis_room_database_key_v1"
        private const val STORE = "AndroidKeyStore"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }

    @Synchronized
    fun hasWrappedPassphrase(): Boolean = keyFile.isFile && keyFile.length() > 0

    @Synchronized
    fun loadExistingPassphrase(): ByteArray {
        check(hasWrappedPassphrase()) { "Database encryption key material is missing." }
        val bytes = keyFile.readBytes()
        require(bytes.size > 1 + IV_BYTES + 16 && bytes[0] == VERSION) {
            "Database encryption key material has an unsupported format."
        }
        val iv = bytes.copyOfRange(1, 1 + IV_BYTES)
        val encrypted = bytes.copyOfRange(1 + IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    @Synchronized
    fun createPassphraseForNewDatabase(): ByteArray {
        check(!hasWrappedPassphrase()) { "A database passphrase already exists." }
        val store = keyStore()
        check(!store.containsAlias(ALIAS)) {
            "Database Keystore key exists but its wrapped passphrase is missing."
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        val key = generator.generateKey()
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(passphrase)
            val payload = byteArrayOf(VERSION) + cipher.iv + encrypted
            val temp = File(keyFile.parentFile, keyFile.name + ".tmp")
            try {
                FileOutputStream(temp).use { out ->
                    out.write(payload)
                    out.fd.sync()
                }
                if (!temp.renameTo(keyFile)) error("Could not persist wrapped database key.")
            } finally {
                temp.delete()
                payload.fill(0)
                encrypted.fill(0)
            }
            return passphrase
        } catch (error: Exception) {
            runCatching { store.deleteEntry(ALIAS) }
            passphrase.fill(0)
            throw error
        }
    }

    private fun keystoreKey(): SecretKey =
        (keyStore().getKey(ALIAS, null) as? SecretKey)
            ?: throw IllegalStateException("Database Keystore key is unavailable or invalidated.")

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }
}
