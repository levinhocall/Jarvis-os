package com.jarvis.assistant.data.mission

import androidx.room.*

@Entity(
    tableName = "missions",
    indices = [
        Index("status"),
        Index("updatedAt")
    ]
)
data class MissionEntity(
    @PrimaryKey
    val id: String,
    val goal: String,
    val status: String,
    val planJson: String,
    val currentStepIndex: Int,
    val approvalState: String,
    val updatedAt: Long,
    val deduplicationKeysJson: String = "[]",
    val outcomeSummary: String? = null
)

@Dao
interface MissionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(mission: MissionEntity)

    @Query("SELECT * FROM missions WHERE id = :id")
    suspend fun getMissionById(id: String): MissionEntity?

    @Query("SELECT * FROM missions WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') ORDER BY updatedAt DESC")
    suspend fun getNonTerminalMissions(): List<MissionEntity>

    @Query("SELECT * FROM missions ORDER BY updatedAt DESC")
    suspend fun getAllMissions(): List<MissionEntity>

    @Delete
    suspend fun delete(mission: MissionEntity)
}
