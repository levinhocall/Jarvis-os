package com.jarvis.assistant.data.memory

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** User-directed backups must stay in on-device document providers. */
object MemoryBackupPolicy {
    fun isOnDeviceDocument(scheme: String?, authority: String?): Boolean =
        scheme == "content" && authority in setOf(
            "com.android.externalstorage.documents", "com.android.providers.downloads.documents"
        )

    fun readBoundedJson(input: InputStream, limitBytes: Int = 5_000_000): String {
        require(limitBytes > 0)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limitBytes) { "Memory backup is too large." }
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }
}
