package com.jarvis.assistant.automation.mission

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Encrypted process-persistent mission snapshots and checkpoints. */
class PersistentMissionCheckpointStorage(context: Context) : CheckpointStorage {
    private val lock = ReentrantLock()
    private val gson = Gson()
    private val preferences: SharedPreferences

    init {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        preferences = EncryptedSharedPreferences.create(
            appContext,
            PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun saveMissionState(mission: LongHorizonMissionState) = lock.withLock {
        persist(KEY_MISSION_PREFIX + mission.missionId, gson.toJson(mission))
    }

    override fun getAllMissionStates(): List<LongHorizonMissionState> = lock.withLock {
        preferences.all.keys
            .filter { it.startsWith(KEY_MISSION_PREFIX) }
            .mapNotNull { key ->
                runCatching {
                    gson.fromJson(preferences.getString(key, null), LongHorizonMissionState::class.java)
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    override fun saveCheckpoint(checkpoint: MissionCheckpoint) = lock.withLock {
        val key = KEY_CHECKPOINT_PREFIX + checkpoint.missionId + ":" +
            checkpoint.timestamp.toString().padStart(20, '0') + ":" + checkpoint.checkpointId
        persist(key, gson.toJson(checkpoint))
    }

    override fun getLatestCheckpoint(missionId: String): MissionCheckpoint? =
        getAllCheckpoints(missionId).maxByOrNull { it.timestamp }

    override fun getAllCheckpoints(missionId: String): List<MissionCheckpoint> = lock.withLock {
        val prefix = KEY_CHECKPOINT_PREFIX + missionId + ":"
        preferences.all.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { key ->
                runCatching {
                    gson.fromJson(preferences.getString(key, null), MissionCheckpoint::class.java)
                }.getOrNull()
            }
            .sortedBy { it.timestamp }
    }

    override fun clear(missionId: String) = lock.withLock {
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it == KEY_MISSION_PREFIX + missionId || it.startsWith(KEY_CHECKPOINT_PREFIX + missionId + ":") }
            .forEach(editor::remove)
        check(editor.commit()) { "Could not durably clear mission $missionId." }
    }

    private fun persist(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit()) {
            "Could not durably persist mission data. Mission execution must not continue."
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "jarvis_long_horizon_missions"
        private const val KEY_MISSION_PREFIX = "mission:"
        private const val KEY_CHECKPOINT_PREFIX = "checkpoint:"
    }
}
