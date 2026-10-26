package com.jarvis.assistant.agent

import android.graphics.RectF
import com.jarvis.assistant.agent.social.BlockerDetector
import com.jarvis.assistant.agent.social.ControlCommand
import com.jarvis.assistant.agent.social.MediaResolver
import com.jarvis.assistant.agent.social.Platform
import com.jarvis.assistant.agent.social.SocialAction
import com.jarvis.assistant.agent.social.SocialTaskCoordinator
import com.jarvis.assistant.agent.social.SocialTaskRequest
import com.jarvis.assistant.agent.social.TaskMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

class FakeScreenDriver : ScreenDriver {
    var currentObservation: ScreenObservation = ScreenObservation(
        observationId = UUID.randomUUID().toString(),
        packageName = "com.instagram.android",
        windowClass = "MainActivity",
        elements = emptyList(),
        screenSize = Pair(1080, 2400),
        windowStamp = System.currentTimeMillis()
    )

    val calls = mutableListOf<String>()

    override fun capture(): ScreenObservation {
        calls.add("capture")
        return currentObservation
    }

    override fun clickElement(obs: ScreenObservation, elementId: String): DriverResult {
        calls.add("clickElement:$elementId")
        if (obs.isStale(System.currentTimeMillis())) {
            return DriverResult.stale()
        }
        val el = obs.elements.find { it.id == elementId || it.text == elementId }
        return if (el != null) DriverResult.ok() else DriverResult.notFound()
    }

    override fun gestureTap(x: Float, y: Float): DriverResult {
        calls.add("gestureTap:$x,$y")
        return DriverResult.ok()
    }

    override fun setText(elementId: String, text: String): DriverResult {
        calls.add("setText:$elementId:$text")
        return DriverResult.ok()
    }

    override fun scroll(direction: String): DriverResult {
        calls.add("scroll:$direction")
        return DriverResult.ok()
    }

    override fun back(): DriverResult {
        calls.add("back")
        return DriverResult.ok()
    }

    override fun home(): DriverResult {
        calls.add("home")
        return DriverResult.ok()
    }
}

class SocialVisualAgentTest {

    private lateinit var fakeDriver: FakeScreenDriver
    private lateinit var coordinator: SocialTaskCoordinator

    @Before
    fun setUp() {
        fakeDriver = FakeScreenDriver()
        coordinator = SocialTaskCoordinator(fakeDriver)
    }

    @Test
    fun testLegalStateTransitions() {
        val sm = TaskStateMachine()
        assertEquals(TaskState.IDLE, sm.currentState)

        sm.moveTo(TaskState.OPENING_APP)
        sm.moveTo(TaskState.OBSERVING)
        sm.moveTo(TaskState.RESOLVING_TARGET)
        sm.moveTo(TaskState.VALIDATING_TARGET)
        sm.moveTo(TaskState.WAITING_FOR_CONFIRMATION)
        sm.moveTo(TaskState.EXECUTING_ACTION)
        sm.moveTo(TaskState.WAITING_FOR_UI)
        sm.moveTo(TaskState.VERIFYING)
        sm.moveTo(TaskState.NEXT_STEP)
        sm.moveTo(TaskState.COMPLETED)

        assertEquals(TaskState.COMPLETED, sm.currentState)
        assertTrue(sm.history.contains(TaskState.OPENING_APP))
        assertTrue(sm.history.contains(TaskState.WAITING_FOR_CONFIRMATION))
        assertTrue(sm.history.contains(TaskState.COMPLETED))
    }

    @Test
    fun testIllegalStateTransitionsThrow() {
        val sm = TaskStateMachine()
        try {
            sm.moveTo(TaskState.COMPLETED)
            fail("Expected IllegalStateException on illegal transition from IDLE to COMPLETED")
        } catch (_: IllegalStateException) {}

        try {
            sm.moveTo(TaskState.VERIFYING)
            fail("Expected IllegalStateException on illegal transition from IDLE to VERIFYING")
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun testTerminalStatesNeverExit() {
        val sm = TaskStateMachine()
        sm.moveTo(TaskState.FAILED)
        assertTrue(sm.currentState.isTerminal)

        try {
            sm.moveTo(TaskState.IDLE)
            fail("Terminal state must never exit")
        } catch (_: IllegalStateException) {}

        try {
            sm.moveTo(TaskState.COMPLETED)
            fail("Terminal state must never exit")
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun testFailedAndCancelledReachableFromAnyNonTerminalState() {
        val sm1 = TaskStateMachine()
        sm1.moveTo(TaskState.OPENING_APP)
        sm1.moveTo(TaskState.FAILED)
        assertEquals(TaskState.FAILED, sm1.currentState)

        val sm2 = TaskStateMachine()
        sm2.moveTo(TaskState.OBSERVING)
        sm2.moveTo(TaskState.CANCELLED)
        assertEquals(TaskState.CANCELLED, sm2.currentState)
    }

    @Test
    fun testBlockerPausesAtWaitingForUser() {
        fakeDriver.currentObservation = ScreenObservation(
            observationId = "obs_login",
            packageName = "com.instagram.android",
            windowClass = "LoginActivity",
            elements = listOf(
                UiElement(id = "tv_title", text = "Please Log in to continue", isClickable = false),
                UiElement(id = "et_pass", text = "Password", isEditable = true)
            ),
            screenSize = Pair(1080, 2400),
            windowStamp = System.currentTimeMillis()
        )

        val request = SocialTaskRequest(
            platform = Platform.INSTAGRAM,
            action = SocialAction.CREATE_POST,
            mediaUri = "content://media/external/images/media/42",
            mode = TaskMode.PUBLISH
        )

        val report = coordinator.startTask(request)
        assertEquals("PAUSED_BLOCKER", report.status)
        assertFalse(report.submittedVerified)
        val session = coordinator.getSession(report.taskId)
        assertNotNull(session)
        assertEquals(TaskState.WAITING_FOR_USER, session?.stateMachine?.currentState)
    }

    @Test
    fun testDraftNeverPublishes() {
        fakeDriver.currentObservation = ScreenObservation(
            observationId = "obs_draft",
            packageName = "com.facebook.katana",
            windowClass = "ComposerActivity",
            elements = listOf(
                UiElement(id = "btn_save_draft", text = "Save Draft", isClickable = true)
            ),
            screenSize = Pair(1080, 2400),
            windowStamp = System.currentTimeMillis()
        )

        val request = SocialTaskRequest(
            platform = Platform.FACEBOOK,
            action = SocialAction.CREATE_POST,
            mediaUri = "content://media/external/images/media/99",
            caption = "Test Draft Caption",
            mode = TaskMode.DRAFT
        )

        val report = coordinator.startTask(request)
        assertEquals("DRAFT_SAVED", report.status)
        assertTrue(report.submittedVerified)
        val session = coordinator.getSession(report.taskId)
        assertEquals(TaskState.COMPLETED, session?.stateMachine?.currentState)
    }

    @Test
    fun testStaleObservationRejected() {
        val staleObservation = ScreenObservation(
            observationId = "obs_stale",
            packageName = "com.instagram.android",
            windowClass = "MainActivity",
            elements = listOf(UiElement(id = "btn_post", text = "Share", isClickable = true)),
            screenSize = Pair(1080, 2400),
            windowStamp = System.currentTimeMillis() - 20_000L // 20 seconds old
        )

        val executor = UiActionExecutor(fakeDriver)
        val target = staleObservation.elements.first()
        val result = executor.executeClick(staleObservation, target)
        assertEquals(ExecutionResult.Stale, result)
    }

    @Test
    fun testConfirmWithoutPriorYesRefused() {
        fakeDriver.currentObservation = ScreenObservation(
            observationId = "obs_post",
            packageName = "com.instagram.android",
            windowClass = "ShareActivity",
            elements = listOf(UiElement(id = "btn_share", text = "Share", isClickable = true)),
            screenSize = Pair(1080, 2400),
            windowStamp = System.currentTimeMillis()
        )

        val request = SocialTaskRequest(
            platform = Platform.INSTAGRAM,
            action = SocialAction.CREATE_POST,
            mediaUri = "content://media/external/images/media/10",
            mode = TaskMode.PUBLISH
        )

        val startReport = coordinator.startTask(request)
        assertEquals("WAITING_FOR_CONFIRMATION", startReport.status)

        // Attempt CONFIRM without explicit user consent
        val confirmReport = coordinator.handleControlCommand(startReport.taskId, ControlCommand.CONFIRM, explicitUserYes = false)
        assertEquals("CONFIRMATION_REFUSED", confirmReport.status)

        // Now supply explicit user consent
        val approvedReport = coordinator.handleControlCommand(startReport.taskId, ControlCommand.CONFIRM, explicitUserYes = true)
        assertTrue(approvedReport.status.startsWith("COMPLETED") || approvedReport.status.startsWith("SUBMITTED"))
    }

    @Test
    fun testUnverifiedSubmissionReporting() {
        // Screen shows no confirmation banner ("Shared" or "Posted")
        fakeDriver.currentObservation = ScreenObservation(
            observationId = "obs_unverified",
            packageName = "com.instagram.android",
            windowClass = "MainActivity",
            elements = listOf(UiElement(id = "feed_item", text = "Old Post")),
            screenSize = Pair(1080, 2400),
            windowStamp = System.currentTimeMillis()
        )

        val request = SocialTaskRequest(
            platform = Platform.INSTAGRAM,
            action = SocialAction.CREATE_POST,
            mediaUri = "content://media/external/images/media/10",
            mode = TaskMode.PUBLISH
        )

        val startReport = coordinator.startTask(request, explicitConfirmationProvided = true)
        assertEquals("SUBMITTED_UNVERIFIED", startReport.status)
        assertFalse(startReport.submittedVerified)
    }

    @Test
    fun testContactAmbiguityFails() {
        fakeDriver.currentObservation = ScreenObservation(
            observationId = "obs_ambiguous",
            packageName = "com.whatsapp",
            windowClass = "SelectContactActivity",
            elements = listOf(
                UiElement(id = "contact_1", text = "John Doe (Home)", isClickable = true),
                UiElement(id = "contact_2", text = "John Doe (Work)", isClickable = true)
            ),
            screenSize = Pair(1080, 2400),
            windowStamp = System.currentTimeMillis()
        )

        val request = SocialTaskRequest(
            platform = Platform.WHATSAPP,
            action = SocialAction.SEND_MESSAGE,
            targetAccount = "John Doe"
        )

        val report = coordinator.startTask(request)
        assertEquals("FAILED", report.status)
        assertTrue(report.details.contains("contact_ambiguous"))
    }

    @Test
    fun testSocialTaskRequestValidation() {
        // Valid content:// URI
        assertTrue(MediaResolver.isValidMediaUri("content://media/external/images/media/123"))
        assertFalse(MediaResolver.isValidMediaUri("/storage/emulated/0/DCIM/photo.jpg"))
        assertFalse(MediaResolver.isValidMediaUri("https://example.com/photo.jpg"))

        // Missing platform
        val resNoPlatform = SocialTaskRequest.parse(mapOf("action" to "CREATE_POST"))
        assertTrue(resNoPlatform.isFailure)

        // Invalid media URI (not content://)
        val resBadUri = SocialTaskRequest.parse(
            mapOf(
                "platform" to "INSTAGRAM",
                "action" to "CREATE_POST",
                "media_uri" to "/data/data/photo.png"
            )
        )
        assertTrue(resBadUri.isFailure)

        // Valid parse
        val resValid = SocialTaskRequest.parse(
            mapOf(
                "platform" to "INSTAGRAM",
                "action" to "CREATE_POST",
                "media_uri" to "content://media/external/images/media/55",
                "mode" to "DRAFT"
            )
        )
        assertTrue(resValid.isSuccess)
        val req = resValid.getOrThrow()
        assertEquals(Platform.INSTAGRAM, req.platform)
        assertEquals(SocialAction.CREATE_POST, req.action)
        assertEquals(TaskMode.DRAFT, req.mode)
    }

    @Test
    fun testA11yDisconnectedReportsEnabledNotConnected() {
        fakeDriver.currentObservation = ScreenObservation(
            observationId = "obs_empty",
            packageName = "",
            windowClass = "",
            elements = emptyList(),
            screenSize = Pair(1080, 2400),
            windowStamp = System.currentTimeMillis()
        )

        val request = SocialTaskRequest(
            platform = Platform.INSTAGRAM,
            action = SocialAction.CREATE_POST,
            mediaUri = "content://media/external/images/media/55",
            mode = TaskMode.PUBLISH
        )

        val report = coordinator.startTask(request)
        assertEquals("ENABLED_NOT_CONNECTED", report.status)
        assertFalse(report.submittedVerified)
        assertTrue(report.details.contains("Accessibility service"))
    }
}
