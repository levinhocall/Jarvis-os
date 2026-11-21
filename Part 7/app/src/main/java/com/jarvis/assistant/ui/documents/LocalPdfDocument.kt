package com.jarvis.assistant.ui.documents

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import java.io.ByteArrayOutputStream

internal object LocalPdfDocument {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48
    private const val FOOTER = 32
    private const val TITLE_SIZE = 23f

    fun render(title: String, body: String): ByteArray {
        require(title.length in 1..120 && body.length in 1..12_000)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TITLE_SIZE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 12f
        }
        fun layout(text: String, paint: TextPaint): StaticLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, PAGE_WIDTH - MARGIN * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(3f, 1f)
            .build()
        val heading = layout(title, titlePaint)
        val content = layout(body, bodyPaint)
        val available = PAGE_HEIGHT - MARGIN * 2 - FOOTER
        require(heading.height + 16 < available)

        val document = PdfDocument()
        try {
            var line = 0
            var pageNumber = 1
            while (line < content.lineCount) {
                require(pageNumber <= 100) { "Document exceeds the page limit" }
                val headerHeight = if (pageNumber == 1) heading.height + 16 else 0
                val offset = content.getLineTop(line)
                var endLine = line + 1
                while (endLine < content.lineCount && content.getLineTop(endLine + 1) - offset <= available - headerHeight) {
                    endLine++
                }
                val visibleHeight = content.getLineTop(endLine) - offset
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                try {
                    val canvas = page.canvas
                    if (pageNumber == 1) {
                        canvas.withTranslation(MARGIN.toFloat(), MARGIN.toFloat()) {
                            heading.draw(this)
                        }
                    }
                    canvas.withTranslation(MARGIN.toFloat(), (MARGIN + headerHeight).toFloat()) {
                        withClip(0, 0, content.width, visibleHeight) {
                            withTranslation(0f, -offset.toFloat()) { content.draw(this) }
                        }
                    }
                    canvas.drawText(pageNumber.toString(), MARGIN.toFloat(), (PAGE_HEIGHT - MARGIN / 2).toFloat(), bodyPaint)
                } finally {
                    document.finishPage(page)
                }
                line = endLine
                pageNumber++
            }
            return ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }
}
