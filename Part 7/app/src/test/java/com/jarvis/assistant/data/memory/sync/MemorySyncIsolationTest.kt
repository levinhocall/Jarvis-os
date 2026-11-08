package com.jarvis.assistant.data.memory.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Phase 1.2 F8: the legacy memory-sync worker and remote-embedding adapter are retained only for
 * backward compatibility. They must stay network-free so private memory can never be transmitted.
 */
class MemorySyncIsolationTest {
    private fun memoryDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            listOf("src/main/java", "app/src/main/java").forEach { rel ->
                val c = File(dir, "$rel/com/jarvis/assistant/data/memory")
                if (c.isDirectory) return c
            }
            dir = dir.parentFile
        }
        error("memory source dir not found")
    }

    private val networkMarkers = listOf(
        "okhttp3", "java.net.", "HttpURLConnection", "HttpsURLConnection", "OkHttpClient", "retrofit", "BuildConfig.MEMORY_SYNC_ENDPOINT"
    )

    private fun assertNetworkFree(file: File) {
        val text = file.readText()
        networkMarkers.forEach { marker ->
            assertFalse("${file.name} must not reference $marker", text.contains(marker))
        }
    }

    @Test
    fun syncWorkerIsNetworkFreeNoOp() {
        val worker = File(memoryDir(), "sync/MemorySyncWorker.kt")
        assertNetworkFree(worker)
        assertEquals(true, worker.readText().contains("Result.success()"))
    }

    @Test
    fun embeddingProvidersAreNetworkFree() {
        assertNetworkFree(File(memoryDir(), "vector/EmbeddingProvider.kt"))
    }

    @Test
    fun noMemoryCodeReadsSyncEndpoint() {
        val offenders = memoryDir().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("MEMORY_SYNC_ENDPOINT") }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
