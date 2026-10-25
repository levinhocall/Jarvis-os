package com.jarvis.assistant.agent

enum class TaskState {
    IDLE,
    OPENING_APP,
    OBSERVING,
    RESOLVING_TARGET,
    VALIDATING_TARGET,
    EXECUTING_ACTION,
    WAITING_FOR_UI,
    VERIFYING,
    NEXT_STEP,
    RETRY,
    REOBSERVE,
    WAITING_FOR_CONFIRMATION,
    WAITING_FOR_USER,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

class TaskStateMachine(initialState: TaskState = TaskState.IDLE) {

    private val _history = mutableListOf<TaskState>()
    var currentState: TaskState = initialState
        private set

    init {
        _history.add(initialState)
    }

    val history: List<TaskState>
        get() = synchronized(_history) { _history.toList() }

    @Synchronized
    fun moveTo(targetState: TaskState) {
        if (currentState == targetState) return

        if (currentState.isTerminal) {
            throw IllegalStateException("Cannot transition from terminal state $currentState to $targetState")
        }

        // FAILED and CANCELLED are always reachable from any non-terminal state
        if (targetState == TaskState.FAILED || targetState == TaskState.CANCELLED) {
            transitionTo(targetState)
            return
        }

        val isValid = when (currentState) {
            TaskState.IDLE -> targetState in setOf(TaskState.OPENING_APP, TaskState.OBSERVING)
            TaskState.OPENING_APP -> targetState in setOf(TaskState.OBSERVING, TaskState.WAITING_FOR_UI)
            TaskState.OBSERVING -> targetState in setOf(TaskState.RESOLVING_TARGET, TaskState.WAITING_FOR_USER, TaskState.WAITING_FOR_CONFIRMATION)
            TaskState.RESOLVING_TARGET -> targetState in setOf(TaskState.VALIDATING_TARGET, TaskState.WAITING_FOR_USER, TaskState.RETRY, TaskState.REOBSERVE)
            TaskState.VALIDATING_TARGET -> targetState in setOf(TaskState.WAITING_FOR_CONFIRMATION, TaskState.EXECUTING_ACTION, TaskState.RETRY, TaskState.REOBSERVE)
            TaskState.WAITING_FOR_CONFIRMATION -> targetState in setOf(TaskState.EXECUTING_ACTION, TaskState.WAITING_FOR_USER)
            TaskState.WAITING_FOR_USER -> targetState in setOf(TaskState.OBSERVING, TaskState.RESOLVING_TARGET)
            TaskState.EXECUTING_ACTION -> targetState in setOf(TaskState.WAITING_FOR_UI, TaskState.VERIFYING)
            TaskState.WAITING_FOR_UI -> targetState in setOf(TaskState.VERIFYING, TaskState.OBSERVING, TaskState.EXECUTING_ACTION)
            TaskState.VERIFYING -> targetState in setOf(TaskState.NEXT_STEP, TaskState.COMPLETED, TaskState.RETRY, TaskState.REOBSERVE)
            TaskState.NEXT_STEP -> targetState in setOf(TaskState.RESOLVING_TARGET, TaskState.OBSERVING, TaskState.EXECUTING_ACTION, TaskState.COMPLETED)
            TaskState.RETRY -> targetState in setOf(TaskState.RESOLVING_TARGET, TaskState.EXECUTING_ACTION, TaskState.OBSERVING, TaskState.REOBSERVE)
            TaskState.REOBSERVE -> targetState in setOf(TaskState.OBSERVING)
            TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED -> false
        }

        if (!isValid) {
            throw IllegalStateException("Illegal transition from $currentState to $targetState")
        }

        transitionTo(targetState)
    }

    private fun transitionTo(state: TaskState) {
        currentState = state
        _history.add(state)
    }
}
