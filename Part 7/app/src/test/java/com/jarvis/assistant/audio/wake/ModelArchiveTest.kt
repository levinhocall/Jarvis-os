package com.jarvis.assistant.audio.wake

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelArchiveTest {
    private fun archive(root:File, paths:List<String>):File = File(root,"fixture.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> paths.forEach { zip.putNextEntry(ZipEntry(it));zip.write(ByteArray(32));zip.closeEntry() } }
    }
    @Test fun traversalCannotEscapeStaging() {
        val root=Files.createTempDirectory("vosk-zip").toFile()
        try { assertThrows(IllegalArgumentException::class.java) { ModelArchive.unpack(archive(root,listOf("../escaped")),File(root,"out")) };assertFalse(File(root,"escaped").exists()) } finally {root.deleteRecursively()}
    }
    @Test fun expandedBytesAreBounded() {
        val root=Files.createTempDirectory("vosk-zip").toFile()
        try { assertThrows(IllegalStateException::class.java) { ModelArchive.unpack(archive(root,listOf("model/large")),File(root,"out"),limit=16) } } finally {root.deleteRecursively()}
    }
    @Test fun completeModelLayoutAcceptedAndIncompleteRejected() {
        val root=Files.createTempDirectory("vosk-zip").toFile()
        try {
            assertEquals("model",ModelArchive.unpack(archive(root,ModelArchive.required.map{"model/$it"}),File(root,"valid")).name)
            assertThrows(IllegalStateException::class.java) { ModelArchive.unpack(archive(root,listOf("model/README")),File(root,"invalid")) }
        } finally {root.deleteRecursively()}
    }
}
