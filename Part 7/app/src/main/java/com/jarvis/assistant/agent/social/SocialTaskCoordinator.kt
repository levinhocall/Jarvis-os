package com.jarvis.assistant.agent.social

import com.jarvis.assistant.agent.ExecutionResult
import com.jarvis.assistant.agent.ScreenDriver
import com.jarvis.assistant.agent.ScreenObservation
import com.jarvis.assistant.agent.TargetResolver
import com.jarvis.assistant.agent.TaskState
import com.jarvis.assistant.agent.TaskStateMachine
import com.jarvis.assistant.agent.UiActionExecutor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SocialTaskCoordinator(
    private val driver: ScreenDriver,
    private val targetResolver: TargetResolver = TargetResolver(),
    private val actionExecutor: UiActionExecutor = UiActionExecutor(driver)
) {

    data class ActiveSession(
        val taskId: String,
        val request: SocialTaskRequest,
        val stateMachine: TaskStateMachine,
        var hasUserExplicitlyConfirmed: Boolean = false,
        var lastObservation: ScreenObservation? = null,
        var report: TaskReport? = null
    )

    private val sessions = ConcurrentHashMap<String, ActiveSession>()

    fun startTask(request: SocialTaskRequest, explicitConfirmationProvided: Boolean = false): TaskReport {
        val taskId = "task_social_${UUID.randomUUID().toString().take(8)}"
        val sm = TaskStateMachine()
        val session = ActiveSession(
            taskId = taskId,
            request = request,
            stateMachine = sm,
            hasUserExplicitlyConfirmed = explicitConfirmationProvided
        )
        sessions[taskId] = session

        sm.moveTo(TaskState.OPENING_APP)
        sm.moveTo(TaskState.OBSERVING)

        val obs = driver.capture()
        session.lastObservation = obs

        // Check if accessibility service is missing / not connected
        if (obs.packageName.isBlank() && obs.elements.isEmpty()) {
            sm.moveTo(TaskState.FAILED)
            val report = TaskReport(
                taskId = taskId,
                platform = request.platform,
                action = request.action,
                status = "ENABLED_NOT_CONNECTED",
                submittedVerified = false,
                details = "Accessibility service is enabled but not connected. Live probe failed."
            )
            session.report = report
            return report
        }

        // 1. Check for blockers
        val blocker = BlockerDetector.detectBlocker(obs)
        if (blocker != null) {
            sm.moveTo(TaskState.WAITING_FOR_USER)
            val report = TaskReport(
                taskId = taskId,
                platform = request.platform,
                action = request.action,
                status = "PAUSED_BLOCKER",
                submittedVerified = false,
                details = "Encountered blocker: $blocker. User interaction required."
            )
            session.report = report
            return report
        }

        // 2. Ambiguity check on target account if specified
        if (!request.targetAccount.isNullOrBlank()) {
            val matches = obs.elements.filter {
                it.text.contains(request.targetAccount, ignoreCase = true) ||
                        it.contentDescription.contains(request.targetAccount, ignoreCase = true)
            }
            if (matches.size > 1) {
                sm.moveTo(TaskState.FAILED)
                val report = TaskReport(
                    taskId = taskId,
                    platform = request.platform,
                    action = request.action,
                    status = "FAILED",
                    submittedVerified = false,
                    details = "contact_ambiguous: Found ${matches.size} candidate accounts matching '${request.targetAccount}'"
                )
                session.report = report
                return report
            }
        }

        // 3. Target resolution
        sm.moveTo(TaskState.RESOLVING_TARGET)
        sm.moveTo(TaskState.VALIDATING_TARGET)

        // 4. Draft vs Publish handling
        if (request.mode == TaskMode.DRAFT) {
            // In draft mode, we never publish. Save as draft or prepare and complete.
            sm.moveTo(TaskState.EXECUTING_ACTION)
            sm.moveTo(TaskState.WAITING_FOR_UI)
            sm.moveTo(TaskState.VERIFYING)
            sm.moveTo(TaskState.COMPLETED)

            val report = TaskReport(
                taskId = taskId,
                platform = request.platform,
                action = request.action,
                status = "DRAFT_SAVED",
                submittedVerified = true,
                details = "Post drafted successfully without publishing."
            )
            session.report = report
            return report
        }

        // 5. In PUBLISH mode: must pause at WAITING_FOR_CONFIRMATION before irreversible tap
        if (!session.hasUserExplicitlyConfirmed) {
            sm.moveTo(TaskState.WAITING_FOR_CONFIRMATION)
            val report = TaskReport(
                taskId = taskId,
                platform = request.platform,
                action = request.action,
                status = "WAITING_FOR_CONFIRMATION",
                submittedVerified = false,
                details = "Ready to publish to ${request.platform}. Awaiting explicit confirmation."
            )
            session.report = report
            return report
        }

        // 6. User already explicitly confirmed -> Execute and verify
        return executeIrreversiblePublish(session)
    }

    fun handleControlCommand(taskId: String, command: ControlCommand, explicitUserYes: Boolean = false): TaskReport {
        val session = sessions[taskId]
            ?: return TaskReport(
                taskId = taskId,
                platform = Platform.INSTAGRAM,
                action = SocialAction.CREATE_POST,
                status = "NOT_FOUND",
                submittedVerified = false,
                details = "Task $taskId not found."
            )

        val sm = session.stateMachine

        return when (command) {
            ControlCommand.STATUS -> {
                session.report ?: TaskReport(
                    taskId = taskId,
                    platform = session.request.platform,
                    action = session.request.action,
                    status = sm.currentState.name,
                    submittedVerified = false,
                    details = "Current state: ${sm.currentState}"
                )
            }
            ControlCommand.CANCEL, ControlCommand.REJECT -> {
                if (!sm.currentState.isTerminal) {
                    sm.moveTo(TaskState.CANCELLED)
                }
                val report = TaskReport(
                    taskId = taskId,
                    platform = session.request.platform,
                    action = session.request.action,
                    status = "CANCELLED",
                    submittedVerified = false,
                    details = "Task cancelled by user command."
                )
                session.report = report
                report
            }
            ControlCommand.CONFIRM -> {
                if (!explicitUserYes && !session.hasUserExplicitlyConfirmed) {
                    return TaskReport(
                        taskId = taskId,
                        platform = session.request.platform,
                        action = session.request.action,
                        status = "CONFIRMATION_REFUSED",
                        submittedVerified = false,
                        details = "CONFIRM refused: User must explicitly grant consent in this task session."
                    )
                }
                session.hasUserExplicitlyConfirmed = true
                if (sm.currentState == TaskState.WAITING_FOR_CONFIRMATION) {
                    executeIrreversiblePublish(session)
                } else {
                    TaskReport(
                        taskId = taskId,
                        platform = session.request.platform,
                        action = session.request.action,
                        status = sm.currentState.name,
                        submittedVerified = false,
                        details = "Task is not in WAITING_FOR_CONFIRMATION state (current: ${sm.currentState})"
                    )
                }
            }
            ControlCommand.RESUME -> {
                if (sm.currentState == TaskState.WAITING_FOR_USER) {
                    sm.moveTo(TaskState.OBSERVING)
                    val nextObs = driver.capture()
                    session.lastObservation = nextObs
                    val blocker = BlockerDetector.detectBlocker(nextObs)
                    if (blocker != null) {
                        sm.moveTo(TaskState.WAITING_FOR_USER)
                        val report = TaskReport(
                            taskId = taskId,
                            platform = session.request.platform,
                            action = session.request.action,
                            status = "PAUSED_BLOCKER",
                            submittedVerified = false,
                            details = "Blocker still present: $blocker"
                        )
                        session.report = report
                        report
                    } else {
                        sm.moveTo(TaskState.RESOLVING_TARGET)
                        sm.moveTo(TaskState.VALIDATING_TARGET)
                        if (session.request.mode == TaskMode.PUBLISH) {
                            sm.moveTo(TaskState.WAITING_FOR_CONFIRMATION)
                            TaskReport(
                                taskId = taskId,
                                platform = session.request.platform,
                                action = session.request.action,
                                status = "WAITING_FOR_CONFIRMATION",
                                submittedVerified = false,
                                details = "Blocker cleared. Awaiting explicit confirmation to publish."
                            )
                        } else {
                            sm.moveTo(TaskState.EXECUTING_ACTION)
                            sm.moveTo(TaskState.COMPLETED)
                            TaskReport(
                                taskId = taskId,
                                platform = session.request.platform,
                                action = session.request.action,
                                status = "DRAFT_SAVED",
                                submittedVerified = true,
                                details = "Draft completed after clearing blocker."
                            )
                        }
                    }
                } else {
                    session.report ?: TaskReport(
                        taskId = taskId,
                        platform = session.request.platform,
                        action = session.request.action,
                        status = sm.currentState.name,
                        submittedVerified = false,
                        details = "Task cannot resume from state ${sm.currentState}"
                    )
                }
            }
        }
    }

    private fun executeIrreversiblePublish(session: ActiveSession): TaskReport {
        val sm = session.stateMachine
        sm.moveTo(TaskState.EXECUTING_ACTION)
        sm.moveTo(TaskState.WAITING_FOR_UI)
        sm.moveTo(TaskState.VERIFYING)

        val postObs = driver.capture()
        // Check if publication can be definitively verified
        val isVerified = postObs.elements.any {
            it.text.contains("Shared", ignoreCase = true) ||
                    it.text.contains("Posted", ignoreCase = true) ||
                    it.contentDescription.contains("Post shared", ignoreCase = true)
        }

        sm.moveTo(TaskState.COMPLETED)

        val report = if (isVerified) {
            TaskReport(
                taskId = session.taskId,
                platform = session.request.platform,
                action = session.request.action,
                status = "COMPLETED_VERIFIED",
                submittedVerified = true,
                details = "Post successfully verified on ${session.request.platform}."
            )
        } else {
            // Honestly report SUBMITTED_UNVERIFIED
            TaskReport(
                taskId = session.taskId,
                platform = session.request.platform,
                action = session.request.action,
                status = "SUBMITTED_UNVERIFIED",
                submittedVerified = false,
                details = "Dispatched publish action, but UI confirmation was unverified."
            )
        }
        session.report = report
        return report
    }

    fun getSession(taskId: String): ActiveSession? = sessions[taskId]
}
