package com.jarvis.assistant.automation.routing

import com.jarvis.assistant.data.preferences.ApiKeyRecord
import com.jarvis.assistant.data.preferences.ApiKeyState
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles round-robin rotation, exponential backoff cooldown on 429 rate limits,
 * and permanent invalidation for API keys.
 */
class ApiKeyRotator(
    private val providerId: String = "gemini"
) {
    private val keys = mutableListOf<ApiKeyRecord>()
    private val roundRobinIndex = AtomicInteger(0)

    @Synchronized
    fun setKeys(newKeys: List<ApiKeyRecord>) {
        keys.clear()
        keys.addAll(newKeys)
    }

    @Synchronized
    fun getKeys(): List<ApiKeyRecord> = keys.toList()

    @Synchronized
    fun addKey(key: String): Boolean {
        val clean = key.trim()
        if (clean.isBlank() || keys.any { it.key == clean }) return false
        keys.add(ApiKeyRecord(key = clean, providerId = providerId))
        return true
    }

    @Synchronized
    fun removeKey(key: String): Boolean {
        val clean = key.trim()
        return keys.removeAll { it.key == clean }
    }

    @Synchronized
    fun getNextActiveKey(now: Long = System.currentTimeMillis(), fallbackKey: String? = null): String? {
        val usable = keys.filter { record ->
            when (record.state) {
                ApiKeyState.ACTIVE -> true
                ApiKeyState.COOLDOWN -> {
                    if (now >= record.cooldownUntilTimestamp) {
                        record.state = ApiKeyState.ACTIVE
                        true
                    } else false
                }
                ApiKeyState.INVALID -> false
            }
        }

        if (usable.isEmpty()) {
            return fallbackKey?.takeIf { it.isNotBlank() }
        }

        val idx = Math.floorMod(roundRobinIndex.getAndIncrement(), usable.size)
        return usable[idx].key
    }

    @Synchronized
    fun reportRateLimit(key: String, now: Long = System.currentTimeMillis()) {
        val record = keys.find { it.key == key } ?: return
        record.failureCount++
        val baseMs = 60_000L * (1 shl (record.failureCount - 1).coerceAtMost(4))
        val jitterMs = (Math.random() * 5000L).toLong()
        record.state = ApiKeyState.COOLDOWN
        record.cooldownUntilTimestamp = now + baseMs + jitterMs
    }

    @Synchronized
    fun reportInvalid(key: String) {
        val record = keys.find { it.key == key } ?: return
        record.state = ApiKeyState.INVALID
    }
}
