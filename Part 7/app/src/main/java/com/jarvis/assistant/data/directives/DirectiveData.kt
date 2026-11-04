package com.jarvis.assistant.data.directives

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** These tables share MemoryDatabase's SQLCipher/Keystore boundary. No separate plaintext store. */
@Entity(tableName = "ideas", indices = [Index("updatedAt")])
data class IdeaEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val refinement: String? = null
)

@Entity(tableName = "learning_profile")
data class LearningProfileEntity(
    @PrimaryKey val id: Int = 1,
    val trainer: String = "Aisha",
    val level: String = "Starter",
    val goal: String = "Confidence"
)

interface ScoredPractice {
    val createdAt: Long
    val grammar: Int?
    val fluency: Int?
    val pronunciation: Int?
    val audioAssessed: Boolean
}

data class LearningMetricRow(override val createdAt: Long, override val grammar: Int?, override val fluency: Int?, override val pronunciation: Int?, override val audioAssessed: Boolean) : ScoredPractice

@Entity(tableName = "learning_attempts", indices = [Index("createdAt")])
data class LearningAttemptEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    override val createdAt: Long = System.currentTimeMillis(),
    val trainer: String,
    val level: String,
    val goal: String,
    val userText: String,
    val reply: String,
    val correction: String,
    override val grammar: Int?,
    override val fluency: Int?,
    override val pronunciation: Int?,
    override val audioAssessed: Boolean = false
) : ScoredPractice

@Dao
interface DirectiveDao {
    @Insert suspend fun addDecision(decision: AutonomyDecisionEntity)
    @Query("SELECT * FROM autonomy_decisions ORDER BY createdAt DESC LIMIT 100")
    fun observeDecisions(): Flow<List<AutonomyDecisionEntity>>
    @Query("DELETE FROM autonomy_decisions WHERE id NOT IN (SELECT id FROM autonomy_decisions ORDER BY createdAt DESC LIMIT 500)")
    suspend fun trimDecisions()
    @Query("DELETE FROM autonomy_decisions") suspend fun clearDecisions()

    @Query("SELECT * FROM ideas ORDER BY updatedAt DESC LIMIT :limit")
    fun observeIdeas(limit: Int = 100): Flow<List<IdeaEntity>>
    @Query("SELECT * FROM ideas WHERE refinement IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun unreviewedIdeas(limit: Int): List<IdeaEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addIdea(idea: IdeaEntity)
    @Query("DELETE FROM ideas WHERE id = :id")
    suspend fun deleteIdea(id: String)
    @Query("UPDATE ideas SET refinement = :refinement WHERE id = :id AND updatedAt = :expectedVersion")
    suspend fun saveRefinement(id: String, expectedVersion: Long, refinement: String): Int
    @Query("SELECT * FROM learning_profile WHERE id = 1")
    suspend fun profile(): LearningProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: LearningProfileEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addAttempt(attempt: LearningAttemptEntity)
    @Query("SELECT * FROM learning_attempts ORDER BY createdAt DESC LIMIT 500")
    fun observeAttempts(): Flow<List<LearningAttemptEntity>>
    @Query("SELECT createdAt, grammar, fluency, pronunciation, audioAssessed FROM learning_attempts")
    fun observeMetrics(): Flow<List<LearningMetricRow>>
    @Query("DELETE FROM learning_attempts")
    suspend fun clearAttempts()
}

@Entity(tableName = "autonomy_decisions", indices = [Index("createdAt")])
data class AutonomyDecisionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val reason: String,
    val action: String
)
