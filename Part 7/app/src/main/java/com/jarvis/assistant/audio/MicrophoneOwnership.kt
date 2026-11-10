package com.jarvis.assistant.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MicrophoneOwner { VOICE_SESSION, WAKE_WORD, VOICE_ENROLLMENT }

/** Process-local exclusivity for existing capture implementations, not another audio engine. */
object MicrophoneOwnership {
    private var active: Lease? = null
    private val current = MutableStateFlow<MicrophoneOwner?>(null)
    val owner: StateFlow<MicrophoneOwner?> = current.asStateFlow()

    @Synchronized fun acquire(owner: MicrophoneOwner): Lease? {
        if (active != null) return null
        return Lease(owner).also { active = it; current.value = owner }
    }

    class Lease internal constructor(val owner: MicrophoneOwner) : AutoCloseable {
        override fun close() = release(this)
    }

    @Synchronized private fun release(lease: Lease) {
        if (active !== lease) return
        active = null
        current.value = null
    }
}
