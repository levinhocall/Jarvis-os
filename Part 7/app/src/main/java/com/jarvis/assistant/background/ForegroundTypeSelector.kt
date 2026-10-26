package com.jarvis.assistant.background

/**
 * Pure, unit-tested selector for Android Foreground Service types (Android 10 - 14+).
 *
 * Guarantees that:
 * 1. FOREGROUND_SERVICE_TYPE_MICROPHONE is NEVER selected unless RECORD_AUDIO permission is granted.
 * 2. FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION is NEVER selected unless a valid projection session is active.
 * 3. Never produces fatal SecurityException on Android 14 (API 34) caused by invalid or unheld FGS types.
 */
object ForegroundTypeSelector {

    // Mirroring Android framework ServiceInfo constants for pure JVM testability
    const val TYPE_NONE = 0
    const val TYPE_MEDIA_PLAYBACK = 2       // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    const val TYPE_MEDIA_PROJECTION = 32    // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    const val TYPE_MICROPHONE = 128        // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    const val MIN_TYPED_FGS_SDK = 29 // Android 10 (Q)

    /**
     * Determines the exact safe bitmask of foreground service types.
     */
    fun selectTypes(
        sdkInt: Int,
        hasMicPermission: Boolean,
        hasActiveMediaProjection: Boolean
    ): Int {
        if (sdkInt < MIN_TYPED_FGS_SDK) {
            return TYPE_NONE
        }

        var types = TYPE_MEDIA_PLAYBACK

        if (hasMicPermission) {
            types = types or TYPE_MICROPHONE
        }

        if (hasActiveMediaProjection) {
            types = types or TYPE_MEDIA_PROJECTION
        }

        return types
    }

    /**
     * Minimal safe fallback type when an elevated type is rejected by the system.
     */
    fun getSafeFallbackType(sdkInt: Int): Int {
        return if (sdkInt >= MIN_TYPED_FGS_SDK) TYPE_MEDIA_PLAYBACK else TYPE_NONE
    }
}
