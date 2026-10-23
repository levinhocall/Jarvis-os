package com.jarvis.assistant.data.mission

import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.jarvis.assistant.automation.mission.*
import com.jarvis.assistant.automation.proactive.ScheduledTrigger
import com.jarvis.assistant.automation.proactive.TriggerRepository
import com.jarvis.assistant.automation.proactive.TriggerType
import com.jarvis.assistant.data.memory.MemoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class MissionPersistenceAndTriggerTest {

    private val gson = Gson()

    class FakeMissionDao : MissionDao {
        val missions = mutableMapOf<String, MissionEntity>()

        override suspend fun insertOrUpdate(mission: MissionEntity) {
            missions[mission.id] = mission
        }

        override suspend fun getMissionById(id: String): MissionEntity? = missions[id]

        override suspend fun getNonTerminalMissions(): List<MissionEntity> {
            return missions.values.filter {
                it.status !in listOf("COMPLETED", "FAILED", "CANCELLED")
            }
        }

        override suspend fun getAllMissions(): List<MissionEntity> = missions.values.toList()

        override suspend fun delete(mission: MissionEntity) {
            missions.remove(mission.id)
        }
    }

    private lateinit var fakeDao: FakeMissionDao

    @Before
    fun setUp() {
        fakeDao = FakeMissionDao()
        MissionEngine.missionDao = fakeDao
    }

    @Test
    fun testMigration3To4CreatesMissionsTable() {
        val executedSql = mutableListOf<String>()

        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0].toString())
            }
            null
        } as SupportSQLiteDatabase

        MemoryDatabase.MIGRATION_3_4.migrate(dbProxy)

        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS missions") })
        assertTrue(executedSql.any { it.contains("index_missions_status") })
        assertTrue(executedSql.any { it.contains("index_missions_updatedAt") })
    }

    @Test
    fun testResumeAfterProcessDeathMarksInFlightConsequentialStepAsUnverified() = runBlocking {
        val step1 = MissionStep(
            stepId = "step_1",
            title = "Open WhatsApp",
            toolName = "open_app",
            parameters = mapOf("app_name" to "WhatsApp"),
            isIdempotent = true,
            status = MissionStepStatus.COMPLETED
        )

        val inFlightConsequentialStep = MissionStep(
            stepId = "step_2",
            title = "Send message",
            toolName = "whatsapp_send",
            parameters = mapOf("contact" to "Bob", "message" to "Hello"),
            isIdempotent = false,
            riskLevel = MissionRisk.HIGH_CONSEQUENTIAL,
            status = MissionStepStatus.RUNNING
        )

        val steps = listOf(step1, inFlightConsequentialStep)
        val entity = MissionEntity(
            id = "mission_crash_1",
            goal = "Send WhatsApp to Bob",
            status = "EXECUTING",
            planJson = gson.toJson(steps),
            currentStepIndex = 1,
            approvalState = "APPROVED",
            updatedAt = System.currentTimeMillis() - 5000L
        )

        fakeDao.insertOrUpdate(entity)

        val resumed = MissionEngine.resumeNonTerminalMissions(null)
        assertEquals(1, resumed.size)

        val resumedMission = resumed.first()
        val inFlightStepAfterResume = resumedMission.steps[1]

        // Strict verification: Consequential in-flight step must NEVER be silently retried!
        assertEquals("In-flight non-idempotent step must be marked UNVERIFIED",
            MissionStepStatus.UNVERIFIED, inFlightStepAfterResume.status)
        assertEquals("Mission overall must halt for user approval",
            MissionOverallStatus.WAITING_FOR_APPROVAL, resumedMission.status)
        assertEquals("Approval state must revert to PENDING_APPROVAL",
            MissionApprovalState.PENDING_APPROVAL, resumedMission.approvalState)
        assertTrue(inFlightStepAfterResume.failureReason?.contains("Process died while step was in flight") == true)
    }

    @Test
    fun testConsequentialDedupKeysSurviveRestart() = runBlocking {
        val actionKey = "whatsapp_send:{contact=Bob, message=Hello}"
        val entity = MissionEntity(
            id = "mission_dedup_1",
            goal = "Send message",
            status = "WAITING_FOR_APPROVAL",
            planJson = "[]",
            currentStepIndex = 0,
            approvalState = "PENDING_APPROVAL",
            updatedAt = System.currentTimeMillis(),
            deduplicationKeysJson = gson.toJson(listOf(actionKey))
        )
        fakeDao.insertOrUpdate(entity)

        MissionEngine.resumeNonTerminalMissions(null)

        // Verify that the restored mission exists and was persisted with its keys intact
        val persisted = fakeDao.getMissionById("mission_dedup_1")
        assertNotNull(persisted)
        assertTrue(persisted!!.deduplicationKeysJson.contains("whatsapp_send"))
    }

    @Test
    fun testTriggerRepositoryManagesScheduledTriggers() {
        val repo = TriggerRepository(context = null, workManagerProvider = { null })
        val trigger = ScheduledTrigger(
            triggerId = "trig_morning_brief",
            goalOrAction = "Read morning unread notifications",
            triggerType = TriggerType.RECURRING_DAILY,
            dailyHour = 8,
            dailyMinute = 0,
            isUnattended = true
        )

        repo.scheduleTrigger(trigger)
        val loaded = repo.getTrigger("trig_morning_brief")
        assertNotNull(loaded)
        assertEquals("Read morning unread notifications", loaded?.goalOrAction)
        assertTrue(loaded?.isUnattended == true)

        repo.cancelTrigger("trig_morning_brief")
        assertNull(repo.getTrigger("trig_morning_brief"))
    }
}
