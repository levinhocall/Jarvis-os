package com.jarvis.assistant.audio.wake

import java.io.File
import java.util.zip.ZipInputStream

object ModelArchive {
    const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
    val required = listOf("am/final.mdl","conf/model.conf","graph/HCLr.fst","graph/Gr.fst")
    fun unpack(archive: File, destination: File, checkpoint: () -> Unit = {}, limit: Long = MAX_EXPANDED_BYTES): File {
        check(destination.mkdirs()) { "Extraction directory already exists" }
        val base = destination.canonicalPath + File.separator
        var total = 0L; var entries = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkpoint()
                val entry = zip.nextEntry ?: break
                check(++entries <= 3000) { "Archive contains too many entries" }
                require(!entry.name.contains('\\') && !entry.name.startsWith('/')) { "Unsafe archive path" }
                val target = File(destination, entry.name)
                require(target.canonicalPath.startsWith(base)) { "Archive escapes installation directory" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    check(!target.exists()) { "Duplicate archive entry" }
                    target.outputStream().buffered().use { output ->
                        while (true) {
                            checkpoint()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= limit) { "Expanded model exceeds storage limit" }
                            output.write(buffer,0,count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val roots = destination.listFiles().orEmpty().filter { it.isDirectory && required.all { name -> File(it,name).length() > 0 } }
        check(roots.size == 1) { "Archive does not contain a supported English Vosk model" }
        return roots.single()
    }
}
