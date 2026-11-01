package com.jarvis.assistant.automation.dev

/**
 * The currently selected development workspace. Missions may inspect or mutate only this
 * explicitly selected project; no repository path is guessed by the execution engine.
 */
object DevProjectWorkspaceRegistry {
    @Volatile
    var activeProject: DevProject? = null
        private set

    fun setActiveProject(project: DevProject) {
        activeProject = project
    }

    fun clearActiveProject() {
        activeProject = null
    }
}
