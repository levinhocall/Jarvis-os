package com.jarvis.assistant.ui.documents

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalPdfDocumentTest {
    @Test fun exportedDocumentOpensAsARealMultipagePdf() {
        val body = List(120) { "Line ${it + 1} — local notes नमस्ते" }.joinToString("\n")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File.createTempFile("jarvis-document", ".pdf", context.cacheDir)
        try {
            output.writeBytes(LocalPdfDocument.render("Project notes", body))
            val descriptor = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount >= 3)
                renderer.openPage(renderer.pageCount - 1).use { page ->
                    assertEquals(595, page.width)
                    assertEquals(842, page.height)
                }
            }
        } finally {
            output.delete()
        }
    }
}
