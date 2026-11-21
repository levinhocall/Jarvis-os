package com.jarvis.assistant.automation.macro

import org.junit.Assert.*
import org.junit.Test

class SavedWorkflowLibraryTest {
    private var stored: String? = null
    private fun library() = SavedWorkflowLibrary(read = { stored }, write = { stored = it })
    private fun workflow(name: String, app: String = "Maps") =
        """{"name":"$name","steps":[{"tool":"open_app","arguments":{"app_name":"$app"}}]}"""

    @Test fun savedWorkflowSurvivesReloadAndCanBeEditedAndDeleted() {
        library().save(workflow("Morning"))
        val saved = library().list().single()
        assertEquals("Maps", MacroCompiler.compile(saved.json).steps.single().parameters["app_name"])
        library().save(workflow("Morning commute", "Calendar"), saved)
        val updated = library().list().single()
        assertEquals(saved.id, updated.id)
        assertEquals("Morning commute", updated.name)
        assertEquals("Calendar", MacroCompiler.compile(updated.json).steps.single().parameters["app_name"])
        library().delete(updated)
        assertTrue(library().list().isEmpty())
    }

    @Test fun invalidDuplicateAndStaleChangesPreserveSavedDefinitions() {
        val library = library()
        library.save(workflow("Morning"))
        val original = library.list().single()
        val before = stored
        assertThrows(IllegalArgumentException::class.java) { library.save(workflow("morning")) }
        assertThrows(IllegalArgumentException::class.java) {
            library.save("""{"name":"Invalid","steps":[{"tool":"shell","arguments":{}}]}""")
        }
        assertEquals(before, stored)
        library.save(workflow("Morning", "Calendar"), original)
        val latest = stored
        assertThrows(IllegalStateException::class.java) { library.delete(original) }
        assertThrows(IllegalStateException::class.java) { library.save(workflow("Stale"), original) }
        assertEquals(latest, stored)
    }

    @Test fun nameLookupResolvesOnlyAnExactSavedName() {
        val library = library()
        library.save(workflow("Morning commute"))
        library.save(workflow("Morning notes"))
        assertEquals("Morning commute", library.findByName("  morning commute  ")?.name)
        assertNull(library.findByName("Morning"))
    }

    @Test fun corruptedStorageIsNeverSilentlyReplaced() {
        stored = "{"
        assertTrue(runCatching { library().save(workflow("Morning")) }.isFailure)
        assertEquals("{", stored)
    }
}
