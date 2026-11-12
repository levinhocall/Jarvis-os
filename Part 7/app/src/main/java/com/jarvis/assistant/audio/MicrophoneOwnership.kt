package com.jarvis.assistant.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class MicrophoneOwner { VOICE_SESSION, WAKE_WORD, VOICE_ENROLLMENT, LOCAL_DICTATION, LEARNING_SAMPLE }

/** Process-local exclusivity for existing capture implementations, not another audio engine. */
object MicrophoneOwnership {
    private var active: Lease? = null
    private val current = MutableStateFlow<MicrophoneOwner?>(null)
    val owner: StateFlow<MicrophoneOwner?> = current.asStateFlow()

    @Synchronized fun acquire(owner: MicrophoneOwner): Lease? {
        if (active != null) return null
        return Lease(owner).also { active = it; current.value = owner }
    }

    /** Wait for retiring capture cleanup; this never grants ownership. Re-acquire afterwards. */
    suspend fun awaitAvailable(timeoutMs: Long = 1_500): Boolean = withTimeoutOrNull(timeoutMs) {
        owner.first { it == null }
        true
    } ?: false

    class Lease internal constructor(val owner: MicrophoneOwner) : AutoCloseable {
        override fun close() = release(this)
    }

    @Synchronized private fun release(lease: Lease) {
        if (active !== lease) return
        active = null
        current.value = null
    }
}
