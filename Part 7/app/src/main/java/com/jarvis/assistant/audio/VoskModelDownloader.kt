package com.jarvis.assistant.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Optional first-run model installer. Wake recognition itself stays fully offline.
 * Run via WorkManager while the user has explicitly opted into downloading.
 * URL and SHA-256 must come from a trusted, pinned application configuration.
 */
class VoskModelDownloader(private val context: Context) {
    suspend fun install(
        source: URL,
        expectedSha256: String,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(source.protocol == "https") { "HTTPS is required" }
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "SHA-256 required" }
        val base = File(context.filesDir, "vosk-downloaded")
        val archive = File(context.cacheDir, "vosk-model-download.part")
        val staging = File(context.filesDir, "vosk-downloaded.staging")
        archive.delete()
        staging.deleteRecursively()
        try {
            val conn = source.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            try {
                check(conn.responseCode == HttpURLConnection.HTTP_OK) {
                    "Model server returned HTTP ${conn.responseCode}"
                }
                val total = conn.contentLengthLong
                require(total in 1..MAX_ARCHIVE_BYTES) { "Unknown or invalid archive length" }
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    archive.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            check(done <= MAX_ARCHIVE_BYTES) { "Model archive exceeds size limit" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            onProgress((done * 70 / total).toInt().coerceIn(0, 70))
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(expectedSha256, ignoreCase = true)) { "Model SHA-256 mismatch" }
            } finally {
                conn.disconnect()
            }
            check(staging.mkdirs()) { "Unable to create staging directory" }
            var expanded = 0L
            var entries = 0
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    check(entries <= 20_000) { "Too many ZIP entries" }
                    val dest = File(staging, entry.name)
                    check(dest.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        "Unsafe ZIP path"
                    }
                    if (entry.isDirectory) {
                        check(dest.mkdirs() || dest.isDirectory)
                    } else {
                        check(dest.parentFile?.mkdirs() == true || dest.parentFile?.isDirectory == true)
                        dest.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                expanded += read
                                check(expanded <= MAX_EXPANDED_BYTES) { "Extracted model exceeds size limit" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                    onProgress((70 + entries.coerceAtMost(1000) * 29 / 1000).coerceAtMost(99))
                }
            }
            val modelDir = staging.walkTopDown()
                .firstOrNull { it.isDirectory && File(it, "am/final.mdl").isFile &&
                    File(it, "conf/model.conf").isFile && File(it, "graph/HCLr.fst").isFile &&
                    File(it, "graph/Gr.fst").isFile }
                ?: error("Missing required Vosk model files")
            // Normalize archive layouts (flat ZIP or a single top-level model directory).
            val normalized = File(context.filesDir, "vosk-downloaded.ready")
            normalized.deleteRecursively()
            if (modelDir == staging) {
                check(staging.renameTo(normalized)) { "Unable to finalize model" }
            } else {
                check(modelDir.renameTo(normalized)) { "Unable to move extracted model" }
            }
            File(normalized, ".jarvis-complete").writeText(expectedSha256.lowercase())
            check(!base.exists() || base.deleteRecursively()) { "Cannot replace existing model" }
            check(normalized.renameTo(base)) { "Unable to activate downloaded model" }
            onProgress(100)
            base
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 200L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 750L * 1024 * 1024
    }
}
