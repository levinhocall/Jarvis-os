package com.jarvis.assistant.data.memory.vector

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt

internal object UsearchNative {
    @Volatile var isNativeAvailable: Boolean = false
        private set

    init {
        isNativeAvailable = runCatching {
            System.loadLibrary("jarvis_vector")
            true
        }.getOrElse { false }
    }

    external fun nativeCreate(dimensions: Int): Long
    external fun nativeAdd(handle: Long, key: Long, vector: FloatArray): Boolean
    external fun nativeRemove(handle: Long, key: Long): Boolean
    external fun nativeSearch(handle: Long, query: FloatArray, limit: Int, outKeys: LongArray, outDistances: FloatArray): Int
    external fun nativeSize(handle: Long): Int
    external fun nativeDimensions(handle: Long): Int
    external fun nativeSave(handle: Long): ByteArray?
    external fun nativeLoad(handle: Long, payload: ByteArray): Boolean
    external fun nativeClose(handle: Long)
}

data class HnswSearchHit(
    val key: Long,
    val cosineSimilarity: Float
)

/**
 * High-speed HNSW vector index backed by native USearch (`libjarvis_vector.so`) on ARM64 Android,
 * with a clean normalized-cosine Kotlin fallback when executing in pure JVM test environments.
 */
class HnswVectorIndex(
    val dimensions: Int,
    forceKotlinFallback: Boolean = false
) : AutoCloseable {
    private var nativeHandle: Long = if (!forceKotlinFallback && UsearchNative.isNativeAvailable) {
        UsearchNative.nativeCreate(dimensions)
    } else 0L

    private val fallbackVectors = ConcurrentHashMap<Long, FloatArray>()

    @Synchronized
    fun upsert(key: Long, vector: FloatArray): Boolean {
        if (vector.size != dimensions || vector.any { !it.isFinite() }) return false
        val norm = normalize(vector) ?: return false
        return if (nativeHandle != 0L) {
            UsearchNative.nativeAdd(nativeHandle, key, norm)
        } else {
            fallbackVectors[key] = norm
            true
        }
    }

    @Synchronized
    fun remove(key: Long): Boolean {
        return if (nativeHandle != 0L) {
            UsearchNative.nativeRemove(nativeHandle, key)
        } else {
            fallbackVectors.remove(key) != null
        }
    }

    @Synchronized
    fun search(query: FloatArray, limit: Int): List<HnswSearchHit> {
        if (query.size != dimensions || limit <= 0) return emptyList()
        val normQuery = normalize(query) ?: return emptyList()
        if (nativeHandle != 0L) {
            val clamped = minOf(limit, size()).coerceAtLeast(0)
            if (clamped == 0) return emptyList()
            val keys = LongArray(clamped)
            val dists = FloatArray(clamped)
            val found = UsearchNative.nativeSearch(nativeHandle, normQuery, clamped, keys, dists)
            return (0 until found).map { idx ->
                // USearch cos_k returns distance = 1 - cosineSimilarity
                val cos = (1f - dists[idx]).coerceIn(-1f, 1f)
                HnswSearchHit(keys[idx], cos)
            }
        }
        return fallbackVectors.entries.map { (k, vec) ->
            var dot = 0f
            for (i in 0 until dimensions) dot += vec[i] * normQuery[i]
            HnswSearchHit(k, dot.coerceIn(-1f, 1f))
        }.sortedByDescending { it.cosineSimilarity }.take(limit)
    }

    @Synchronized
    fun size(): Int = if (nativeHandle != 0L) UsearchNative.nativeSize(nativeHandle) else fallbackVectors.size

    @Synchronized
    fun exportSerialized(): ByteArray? {
        if (nativeHandle != 0L) return UsearchNative.nativeSave(nativeHandle)
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(0x4A564831) // "JVH1"
            dos.writeInt(dimensions)
            dos.writeInt(fallbackVectors.size)
            for ((k, vec) in fallbackVectors) {
                dos.writeLong(k)
                for (f in vec) dos.writeFloat(f)
            }
        }
        return baos.toByteArray()
    }

    @Synchronized
    fun importSerialized(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        if (nativeHandle != 0L) return UsearchNative.nativeLoad(nativeHandle, payload)
        return runCatching {
            DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                val magic = dis.readInt()
                val dims = dis.readInt()
                val count = dis.readInt()
                if (magic != 0x4A564831 || dims != dimensions || count < 0) return false
                val loaded = HashMap<Long, FloatArray>(count)
                repeat(count) {
                    val key = dis.readLong()
                    val vec = FloatArray(dims) { dis.readFloat() }
                    loaded[key] = vec
                }
                fallbackVectors.clear()
                fallbackVectors.putAll(loaded)
                true
            }
        }.getOrElse { false }
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            UsearchNative.nativeClose(nativeHandle)
            nativeHandle = 0L
        }
        fallbackVectors.clear()
    }

    private fun normalize(v: FloatArray): FloatArray? {
        var sq = 0.0
        for (x in v) sq += x * x
        val norm = sqrt(sq).toFloat()
        if (norm <= 1e-8f) return null
        return FloatArray(v.size) { v[it] / norm }
    }
}

/**
 * Persists serialized USearch HNSW graphs strictly encrypted with AES-256-GCM
 * (never leaving plaintext semantic embeddings or graphs on shared storage).
 */
class EncryptedHnswStore(
    private val keyProvider: () -> SecretKey
) {
    companion object {
        private const val STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis_hnsw_vector_key_v1"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        fun androidKeystore(): EncryptedHnswStore = EncryptedHnswStore {
            val ks = KeyStore.getInstance(STORE).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return@EncryptedHnswStore existing
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
            gen.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            gen.generateKey()
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(plaintext)
        return byteArrayOf(VERSION) + cipher.iv + sealed
    }

    fun decrypt(payload: ByteArray): ByteArray? {
        if (payload.size <= 1 + IV_BYTES + 16 || payload[0] != VERSION) return null
        val iv = payload.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = payload.copyOfRange(1 + IV_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    fun saveEncrypted(targetFile: File, index: HnswVectorIndex): Boolean {
        val raw = index.exportSerialized() ?: return false
        val encrypted = encrypt(raw)
        raw.fill(0)
        targetFile.parentFile?.mkdirs()
        val temp = File(targetFile.parentFile, "${targetFile.name}.tmp")
        return try {
            FileOutputStream(temp).use { out ->
                out.write(encrypted)
                out.fd.sync()
            }
            temp.renameTo(targetFile)
        } finally {
            encrypted.fill(0)
            temp.delete()
        }
    }

    @Synchronized
    fun loadEncrypted(sourceFile: File, index: HnswVectorIndex): Boolean {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) return false
        val payload = sourceFile.readBytes()
        val raw = runCatching { decrypt(payload) }.getOrNull() ?: return false
        return try {
            index.importSerialized(raw)
        } finally {
            raw.fill(0)
            payload.fill(0)
        }
    }
}
