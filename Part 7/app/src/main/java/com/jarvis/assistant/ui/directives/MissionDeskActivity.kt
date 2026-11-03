package com.jarvis.assistant.ui.directives

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.automation.mission.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MissionDeskActivity : DirectiveActivity() {
    private val failures = kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
        if (::state.isInitialized) state.text = "The operation could not finish. Stored data has not been reset; please retry."
    }
    private var route = MissionExecutionRoute.UI_AGENT
    private lateinit var goal: EditText
    private lateinit var state: TextView
    private lateinit var history: TextView
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        heading("Mission Mode")
        label("Choose how this mission runs. Both routes keep approvals, verification and durable recovery. Nothing runs until you submit.")
        options(listOf(MissionExecutionRoute.UI_AGENT, MissionExecutionRoute.CORE_LLM), route,
            label = { if (it == MissionExecutionRoute.UI_AGENT) "UI-control Agent" else "Core LLM tools (no UI control)" }) { route = it }
        label("UI Agent uses user-enabled Accessibility and may send visible non-sensitive control labels to Gemini. Jarvis private screens and password windows are excluded.", 12f)
        goal = input("Describe a multi-step goal, including time, destination and constraints…").apply { isSaveEnabled = false }
        action("Run selected route") { button ->
            val command = goal.text.toString().trim()
            if (command.isBlank() || command.length > 6000) { notice("Enter a goal between 1 and 6,000 characters."); return@action }
            if (route == MissionExecutionRoute.UI_AGENT && !com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.isRunning()) {
                MaterialAlertDialogBuilder(this).setTitle("Accessibility is needed for UI control")
                    .setNegativeButton("Cancel", null).setPositiveButton("Open permissions") { _, _ ->
                        startActivity(android.content.Intent(this, com.jarvis.assistant.ui.permission.PermissionActivity::class.java))
                    }.show()
                return@action
            }
            val chosen = route
            button.isEnabled = false
            lifecycleScope.launch(failures) {
                try {
                    val started = MissionEngine.startMission(applicationContext, command, executionRoute = chosen)
                    if (started.status == MissionOverallStatus.FAILED) state.text = started.outcomeSummary ?: "Mission could not start."
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { state.text = "Mission could not start safely. No completion has been reported." }
                finally { button.isEnabled = true }
            }
        }
        state = label("No active mission.")
        action("Approve pending action") {
            val mission = MissionEngine.activeMission.value
            if (mission?.status != MissionOverallStatus.WAITING_FOR_APPROVAL) { notice("No action is waiting for approval."); return@action }
            MaterialAlertDialogBuilder(this).setTitle("Approve this exact mission action?")
                .setMessage(mission.approvalPrompt ?: mission.outcomeSummary ?: mission.goal)
                .setNegativeButton("Reject") { _, _ -> lifecycleScope.launch(failures) { MissionEngine.resolveApproval(mission.missionId, false, applicationContext) } }
                .setPositiveButton("Approve once") { _, _ -> lifecycleScope.launch(failures) { MissionEngine.resolveApproval(mission.missionId, true, applicationContext) } }.show()
        }
        action("Pause / resume") {
            val m = MissionEngine.activeMission.value ?: return@action
            lifecycleScope.launch(failures) {
                val result = if (m.status == MissionOverallStatus.PAUSED) MissionEngine.resumeMission(applicationContext, m.missionId) else MissionEngine.pauseMission(m.missionId)
                if (!result) notice("Mission cannot change state safely right now.")
            }
        }
        action("Revise a clarification request") {
            val mission = MissionEngine.activeMission.value ?: return@action
            if (mission.status != MissionOverallStatus.CLARIFICATION_REQUIRED) { notice("No clarification is pending."); return@action }
            MaterialAlertDialogBuilder(this).setTitle("Revise the mission safely?")
                .setMessage("This cancels the paused request and restores its goal for editing. Check any already completed steps before submitting again; nothing is replayed automatically.")
                .setNegativeButton("Keep paused", null).setPositiveButton("Edit goal") { _, _ -> lifecycleScope.launch(failures) {
                    MissionEngine.cancelMission(mission.missionId, "User chose to clarify and revise the goal")
                    goal.setText(mission.goal)
                    goal.requestFocus()
                    state.text = "Add the missing details, then submit when ready."
                } }.show()
        }
        action("Cancel mission") { MissionEngine.activeMission.value?.let { m -> lifecycleScope.launch(failures) { MissionEngine.cancelMission(m.missionId) } } }
        action("Acknowledge unknown outcome") {
            val m = MissionEngine.activeMission.value ?: return@action
            if (m.status != MissionOverallStatus.UNVERIFIED) { notice("There is no unknown outcome to acknowledge."); return@action }
            MaterialAlertDialogBuilder(this).setTitle("Acknowledge without replay?")
                .setMessage("Check the real app first. This clears the unknown-outcome lock; it does not execute the action again.")
                .setNegativeButton("Keep locked", null).setPositiveButton("Acknowledge") { _, _ -> lifecycleScope.launch(failures) { MissionEngine.acknowledgeUnverifiedMission(m.missionId) } }.show()
        }
        label("Future bookings need a supported app/provider and confirmation. A reminder is not a completed booking.", 12f)
        heading("Mission history")
        history = label("Loading encrypted history…")
        action("Refresh history") { refreshHistory() }
        refreshHistory()
        lifecycleScope.launch(failures) { repeatOnLifecycle(Lifecycle.State.STARTED) {
            MissionEngine.activeMission.collect { m ->
                state.text = m?.let {
                    "${MissionRoutePolicy.route(it.userConstraints).name.replace('_', ' ')} • ${it.status.name.replace('_', ' ')}\n${it.goal}\n${it.steps.count { s -> s.status == MissionStepStatus.COMPLETED }}/${it.steps.size} steps completed\n${it.clarificationRequest?.question ?: it.outcomeSummary.orEmpty()}"
                } ?: "No active mission."
                if (m?.isFinished == true) refreshHistory()
            }
        } }
    }
    private fun refreshHistory() { lifecycleScope.launch(failures) {
        try {
            val rows = withContext(Dispatchers.IO) { (application as JarvisApp).memoryDatabase.missionDao().getAllMissions() }
            history.text = rows.take(20).joinToString("\n\n") { "${it.status.replace('_', ' ')}\n${it.goal.take(160)}\n${it.outcomeSummary.orEmpty().take(240)}" }.ifBlank { "No saved missions yet." }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { history.text = "History could not be loaded. No stored mission was reset." }
    } }
}
