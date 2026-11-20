package com.jarvis.assistant.automation.macro

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

internal data class SavedWorkflow(val id: String, val name: String, val json: String)

/** Saved definitions are compiled again before every run. Storage is supplied by AppPreferences. */
internal class SavedWorkflowLibrary(
    private val read: () -> String?,
    private val write: (String) -> Unit
) {
    @Synchronized
    fun list(): List<SavedWorkflow> {
        val stored = read() ?: return emptyList()
        require(stored.length <= MAX_STORED_CHARS) { "Saved workflows exceed the storage limit." }
        val array = JsonParser.parseString(stored).asJsonArray
        require(array.size() <= MAX_WORKFLOWS)
        return array.map { element ->
            val entry = element.asJsonObject
            SavedWorkflow(entry.get("id").asString, entry.get("name").asString, entry.get("json").asString)
        }.also { entries -> require(entries.distinctBy { it.id }.size == entries.size) }
    }

    @Synchronized
    fun save(json: String, previous: SavedWorkflow? = null) {
        val plan = MacroCompiler.compile(json)
        val entries = list()
        if (previous != null) check(entries.any { it == previous }) { "This workflow changed. Reopen it before saving." }
        require(entries.none { it.id != previous?.id && it.name.equals(plan.name, ignoreCase = true) }) {
            "A saved workflow already has this name."
        }
        require(previous != null || entries.size < MAX_WORKFLOWS) { "The library holds up to $MAX_WORKFLOWS workflows." }
        val workflow = SavedWorkflow(previous?.id ?: UUID.randomUUID().toString(), plan.name, json)
        persist(listOf(workflow) + entries.filterNot { it.id == workflow.id })
    }

    @Synchronized
    fun delete(workflow: SavedWorkflow) {
        val entries = list()
        check(entries.any { it == workflow }) { "This workflow changed. Reopen the library before deleting it." }
        persist(entries.filterNot { it.id == workflow.id })
    }

    private fun persist(entries: List<SavedWorkflow>) {
        val array = JsonArray()
        entries.forEach { entry ->
            array.add(JsonObject().apply {
                addProperty("id", entry.id)
                addProperty("name", entry.name)
                addProperty("json", entry.json)
            })
        }
        val encoded = array.toString()
        require(encoded.length <= MAX_STORED_CHARS) { "Saved workflows exceed the storage limit." }
        write(encoded)
    }

    private companion object {
        const val MAX_WORKFLOWS = 20
        const val MAX_STORED_CHARS = 262_144
    }
}
