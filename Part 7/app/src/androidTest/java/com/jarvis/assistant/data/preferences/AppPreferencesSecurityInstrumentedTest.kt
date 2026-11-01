package com.jarvis.assistant.data.preferences

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppPreferencesSecurityInstrumentedTest {
    @Test
    fun ownerVoiceProfileMigratesToEncryptedStorageAndNeverWritesPlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val legacyName = "jarvis_test_plain_$suffix"
        val encryptedName = "jarvis_test_secure_$suffix"
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val profileJson = "[0.1,0.2,0.3]"
        val samplesJson = "[[0.1,0.2,0.3],[0.2,0.3,0.4]]"

        legacy.edit()
            .putString("key_owner_voice_profile", profileJson)
            .putString("key_owner_voice_samples", samplesJson)
            .putInt("key_owner_voice_sample_count", 2)
            .putFloat("key_owner_voice_threshold", 0.72f)
            .putBoolean("key_owner_voice_enabled", true)
            .commit()

        val preferences = AppPreferences(context, legacyName, encryptedName)
        assertTrue(preferences.isOwnerVoiceEnabled)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), preferences.getOwnerVoiceProfile(), 0.0001f)
        assertEquals(2, preferences.getOwnerVoiceSamples().size)
        assertEquals(2, preferences.ownerVoiceSampleCount)
        assertFalse(legacy.contains("key_owner_voice_profile"))
        assertFalse(legacy.contains("key_owner_voice_samples"))
        assertFalse(legacy.contains("key_owner_voice_enabled"))

        preferences.saveOwnerVoiceProfile(floatArrayOf(0.4f, 0.5f), 3)
        assertArrayEquals(floatArrayOf(0.4f, 0.5f), preferences.getOwnerVoiceProfile(), 0.0001f)
        assertFalse(legacy.contains("key_owner_voice_profile"))

        preferences.clearOwnerVoiceProfile()
        assertFalse(preferences.hasOwnerVoiceProfile())
        assertFalse(preferences.isOwnerVoiceEnabled)
    }
}
