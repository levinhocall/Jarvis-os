package com.jarvis.assistant.ui.directives

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.format.DateFormat
import android.widget.EditText
import android.widget.TextView
import android.widget.ScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.macro.CompiledMacro
import com.jarvis.assistant.automation.macro.MacroCompiler
import com.jarvis.assistant.automation.macro.SavedWorkflow
import com.jarvis.assistant.automation.mission.*
import com.jarvis.assistant.automation.proactive.CalendarReminderSchedule
import com.jarvis.assistant.automation.proactive.ScheduledTrigger
import com.jarvis.assistant.automation.proactive.TriggerType
import com.jarvis.assistant.automation.research.ResearchJob
import java.text.DateFormat as DisplayDateFormat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MissionDeskActivity : DirectiveActivity() {
    private val workflows get() = (application as JarvisApp).preferences.savedWorkflows

    private val reminderRepository get() = (application as JarvisApp).triggerRepository

    private fun showRoutines() {
        val routines = reminderRepository.getAllTriggers()
            .mapNotNull { trigger ->
                val schedule = trigger.calendarSchedule ?: return@mapNotNull null
                if (trigger.triggerType != TriggerType.CALENDAR_REMINDER) return@mapNotNull null
                trigger to schedule
            }
            .sortedBy { it.first.goalOrAction }
        val dateFormat = DisplayDateFormat.getDateTimeInstance(DisplayDateFormat.MEDIUM, DisplayDateFormat.SHORT)
        val now = System.currentTimeMillis()
        val rows = routines.map { (trigger, schedule) ->
            dateFormat.timeZone = TimeZone.getTimeZone(schedule.zoneId)
            getString(R.string.routine_next_run, trigger.goalOrAction, dateFormat.format(Date(schedule.nextAfter(now))), schedule.zoneId)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routines_title)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.routine_add) { _, _ -> editRoutineText() }
        if (rows.isEmpty()) {
            dialog.setMessage(R.string.routines_empty)
        } else {
            dialog.setItems(rows.toTypedArray()) { _, index ->
                val (trigger, _) = routines[index]
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.routine_delete)
                    .setMessage(trigger.goalOrAction)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.routine_remove) { _, _ ->
                        lifecycleScope.launch(failures) {
                            val removed = withContext(Dispatchers.IO) {
                                reminderRepository.cancelTrigger(trigger.triggerId)
                            }
                            if (!removed) notice(getString(R.string.routine_failed))
                            showRoutines()
                        }
                    }.show()
            }
        }
        dialog.show()
    }

    private fun editRoutineText() {
        val editor = EditText(this).apply {
            hint = getString(R.string.routine_text_hint)
            filters = arrayOf(InputFilter.LengthFilter(300))
            isSaveEnabled = false
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routine_add)
            .setView(editor)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.routine_next, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val message = editor.text.toString().trim()
            if (message.isEmpty()) {
                editor.error = getString(R.string.routine_text_hint)
            } else {
                dialog.dismiss()
                chooseRoutineRecurrence(message)
            }
        }
    }

    private fun chooseRoutineRecurrence(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routine_repeat)
            .setItems(arrayOf(R.string.routine_weekly, R.string.routine_monthly, R.string.routine_interval)
                .map { getString(it) }.toTypedArray()) { _, index ->
                if (index == 0) {
                    val days = DayOfWeek.entries
                    val selected = BooleanArray(days.size)
                    val weekdays = MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.routine_weekly)
                        .setMultiChoiceItems(days.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }.toTypedArray(), selected) { clicked, day, checked ->
                            selected[day] = checked
                            (clicked as androidx.appcompat.app.AlertDialog)
                                .getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = selected.any { it }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.routine_next, null)
                        .show()
                    val next = weekdays.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    next.isEnabled = false
                    next.setOnClickListener {
                        weekdays.dismiss()
                        chooseRoutineTime(message, days.filterIndexed { index, _ -> selected[index] }.toSet())
                    }
                } else if (index == 1) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.routine_monthly)
                        .setItems((1..31).map(Int::toString).toTypedArray()) { _, day ->
                            chooseRoutineTime(message, monthDay = day + 1)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    chooseRoutineInterval(message)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseRoutineInterval(message: String) {
        val field = EditText(this).apply {
            hint = getString(R.string.routine_interval_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSaveEnabled = false
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routine_interval)
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.routine_next, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val interval = field.text.toString().toIntOrNull()
            if (interval == null || interval !in 15..1_439) {
                field.error = getString(R.string.routine_interval_hint)
                return@setOnClickListener
            }
            dialog.dismiss()
            val use24Hour = DateFormat.is24HourFormat(this)
            TimePickerDialog(this, { _, hour, minute ->
                TimePickerDialog(this, { _, endHour, endMinute ->
                    saveRoutine(message, CalendarReminderSchedule(
                        hour, minute, intervalMinutes = interval, windowEndMinutes = endHour * 60 + endMinute
                    ))
                }, 18, 0, use24Hour).apply { setTitle(R.string.routine_end_time) }.show()
            }, 8, 0, use24Hour).apply { setTitle(R.string.routine_start_time) }.show()
        }
    }

    private fun chooseRoutineTime(message: String, weekdays: Set<DayOfWeek> = emptySet(), monthDay: Int? = null) {
        TimePickerDialog(this, { _, hour, minute ->
            saveRoutine(message, CalendarReminderSchedule(hour, minute, weekdays, monthDay))
        }, 8, 0, DateFormat.is24HourFormat(this)).show()
    }

    private fun saveRoutine(message: String, schedule: CalendarReminderSchedule) = lifecycleScope.launch(failures) {
        if (!schedule.isValid()) {
            notice(getString(R.string.routine_failed))
            return@launch
        }
        val saved = withContext(Dispatchers.IO) {
            reminderRepository.scheduleTrigger(ScheduledTrigger(
                goalOrAction = message,
                triggerType = TriggerType.CALENDAR_REMINDER,
                calendarSchedule = schedule
            ))
        }
        notice(getString(if (saved) R.string.routine_saved else R.string.routine_failed))
        if (saved) showRoutines()
    }

    private val researchJobs get() = (application as JarvisApp).preferences.researchJobs

    private fun showResearchJobs(): Job = lifecycleScope.launch(failures) {
        val jobs = withContext(Dispatchers.IO) { researchJobs.list(applicationContext) }
        val builder = MaterialAlertDialogBuilder(this@MissionDeskActivity)
            .setTitle(R.string.research_jobs_title)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.research_jobs_refresh) { _, _ -> showResearchJobs() }
            .setPositiveButton(R.string.research_jobs_new) { _, _ -> newResearchJob() }
        if (jobs.isEmpty()) {
            builder.setMessage(R.string.research_jobs_empty)
        } else {
            builder.setItems(jobs.map { job ->
                getString(R.string.research_job_row, job.query, researchStatus(job))
            }.toTypedArray()) { _, index -> showResearchJob(jobs[index]) }
        }
        builder.show()
    }

    private fun researchStatus(job: ResearchJob): String = getString(when {
        job.report != null -> R.string.research_job_ready
        job.state == WorkInfo.State.RUNNING -> R.string.research_job_running
        job.state == WorkInfo.State.ENQUEUED || job.state == WorkInfo.State.BLOCKED -> R.string.research_job_queued
        else -> R.string.research_job_unavailable
    })

    private fun showResearchJob(job: ResearchJob) {
        val body = TextView(this).apply {
            text = job.report ?: researchStatus(job)
            setTextIsSelectable(true)
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, 0, inset, inset)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(job.query)
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(R.string.research_jobs_title) { _, _ -> showResearchJobs() }
            .setNeutralButton(R.string.research_job_remove) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.research_job_remove_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.research_job_remove) { _, _ ->
                        lifecycleScope.launch(failures) {
                            withContext(Dispatchers.IO) { researchJobs.remove(applicationContext, job.id) }
                            showResearchJobs()
                        }
                    }.show()
            }.show()
    }

    private fun newResearchJob() {
        if (!com.jarvis.assistant.ui.aura.AuraOptions.enabled("backgroundSearch")) {
            notice(getString(R.string.research_jobs_enable_search))
            return
        }
        val editor = EditText(this).apply {
            hint = getString(R.string.research_job_topic)
            filters = arrayOf(InputFilter.LengthFilter(2_000))
            isSaveEnabled = false
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.research_jobs_new)
            .setView(editor)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.research_job_start, null)
            .show()
        val start = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        start.setOnClickListener {
            val query = editor.text.toString().trim()
            if (query.isEmpty()) {
                editor.error = getString(R.string.research_job_topic)
                return@setOnClickListener
            }
            start.isEnabled = false
            lifecycleScope.launch(failures) {
                try {
                    withContext(Dispatchers.IO) { researchJobs.enqueue(applicationContext, query) }
                    dialog.dismiss()
                    showResearchJobs()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IllegalStateException) {
                    notice(error.message ?: getString(R.string.research_job_unavailable))
                } finally {
                    start.isEnabled = true
                }
            }
        }
    }

    private fun handleWorkflowShortcut(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REVIEW_WORKFLOW)
        val libraryRequested = intent.getBooleanExtra(EXTRA_SHOW_LIBRARY, false)
        intent.removeExtra(EXTRA_REVIEW_WORKFLOW)
        intent.removeExtra(EXTRA_SHOW_LIBRARY)
        if (id != null) lifecycleScope.launch(failures) {
            val saved = withContext(Dispatchers.IO) { workflows.list().singleOrNull { it.id == id } }
            if (saved != null) reviewWorkflow(saved.json, saved)
            else notice(getString(R.string.workflow_missing))
        } else if (libraryRequested) showSavedWorkflows()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWorkflowShortcut(intent)
    }

    companion object {
        private const val EXTRA_REVIEW_WORKFLOW = "review_workflow"
        private const val EXTRA_SHOW_LIBRARY = "show_workflow_library"

        internal fun reviewIntent(context: Context, id: String) = Intent(context, MissionDeskActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_REVIEW_WORKFLOW, id)

        internal fun libraryIntent(context: Context) = Intent(context, MissionDeskActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_SHOW_LIBRARY, true)
    }

    private fun showWorkflowEditor(saved: SavedWorkflow? = null) {
        val editor = EditText(this).apply {
            minLines = 6; maxLines = 12; isSaveEnabled = false
            hint = getString(R.string.workflow_input_hint)
            contentDescription = getString(R.string.workflow_editor)
            filters = arrayOf(android.text.InputFilter.LengthFilter(32_768))
            setText(saved?.json ?: getString(R.string.macro_example_json))
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.workflow_editor)
            .setView(editor).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.workflow_review) { _, _ -> reviewWorkflow(editor.text.toString(), saved) }
            .show()
    }

    private fun reviewWorkflow(json: String, saved: SavedWorkflow?) = lifecycleScope.launch(failures) {
        val plan = withContext(Dispatchers.Default) { runCatching { MacroCompiler.compile(json) }.getOrNull() }
        if (plan == null) {
            notice(getString(R.string.workflow_invalid))
            return@launch
        }
        val preview = plan.steps.joinToString("\n\n") { step ->
            step.title + "\n" + step.parameters.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
        val review = MaterialAlertDialogBuilder(this@MissionDeskActivity).setTitle(plan.name)
            .setMessage(getString(R.string.workflow_review_steps, preview))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.workflow_start) { _, _ -> startWorkflow(plan) }
        if (saved?.json != json) review.setNeutralButton(R.string.workflow_save) { _, _ -> saveWorkflow(json, saved) }
        review.show()
    }

    private fun startWorkflow(plan: CompiledMacro) = lifecycleScope.launch(failures) {
        val mission = MissionEngine.startPreparedMission(applicationContext, plan.name, steps = plan.steps,
            userConstraints = listOf("Execute the reviewed JSON steps only; never invent missing steps."))
        if (mission.status == MissionOverallStatus.FAILED) {
            notice(mission.outcomeSummary ?: getString(R.string.mission_start_failed))
        }
    }

    private fun saveWorkflow(json: String, saved: SavedWorkflow?) = lifecycleScope.launch(failures) {
        val result = withContext(Dispatchers.IO) { runCatching { workflows.save(json, saved) } }
        val message = when (val error = result.exceptionOrNull()) {
            null -> getString(R.string.workflow_saved)
            is IllegalArgumentException -> error.message ?: getString(R.string.workflow_library_unavailable)
            else -> getString(R.string.workflow_library_unavailable)
        }
        notice(message)
    }

    private fun showSavedWorkflows() = lifecycleScope.launch(failures) {
        val items = withContext(Dispatchers.IO) { runCatching { workflows.list() }.getOrNull() }
        if (items.isNullOrEmpty()) {
            notice(getString(if (items == null) R.string.workflow_library_unavailable else R.string.workflow_library_empty))
            return@launch
        }
        MaterialAlertDialogBuilder(this@MissionDeskActivity).setTitle(R.string.workflow_library)
            .setItems(items.map { it.name }.toTypedArray()) { _, index ->
                val saved = items[index]
                MaterialAlertDialogBuilder(this@MissionDeskActivity).setTitle(saved.name)
                    .setItems(arrayOf(getString(R.string.workflow_review), getString(R.string.workflow_edit), getString(R.string.workflow_delete))) { _, action ->
                        when (action) {
                            0 -> reviewWorkflow(saved.json, saved)
                            1 -> showWorkflowEditor(saved)
                            2 -> confirmDeleteWorkflow(saved)
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmDeleteWorkflow(saved: SavedWorkflow) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.workflow_delete).setMessage(saved.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.workflow_delete) { _, _ ->
                lifecycleScope.launch(failures) {
                    val removed = withContext(Dispatchers.IO) { runCatching { workflows.delete(saved) }.isSuccess }
                    notice(getString(if (removed) R.string.workflow_deleted else R.string.workflow_library_unavailable))
                }
            }.show()
    }
    private val failures = kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
        if (::state.isInitialized) state.text = getString(com.jarvis.assistant.R.string.mission_operation_retry)
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
                catch (_: Exception) { state.text = getString(com.jarvis.assistant.R.string.mission_start_failed) }
                finally { button.isEnabled = true }
            }
        }
        action(getString(R.string.workflow_build)) { showWorkflowBuilder { reviewWorkflow(it, null) } }
        action(getString(R.string.workflow_new)) { showWorkflowEditor() }
        action(getString(R.string.workflow_library)) { showSavedWorkflows() }
        action(getString(R.string.routines_title)) { showRoutines() }
        action(getString(R.string.research_jobs_title)) { showResearchJobs() }
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
                    state.text = getString(com.jarvis.assistant.R.string.mission_add_details)
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
        handleWorkflowShortcut(intent)
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
        catch (_: Exception) { history.text = getString(com.jarvis.assistant.R.string.mission_history_unavailable) }
    } }
}
