package com.jarvis.assistant.ui.whiteboard

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.OutputStream

/**
 * A self-contained sketch board launched by an explicit in-app action.
 * Drawing remains local to the device; nothing is uploaded automatically.
 */
class WhiteboardActivity : AppCompatActivity() {

    private lateinit var board: DrawingBoardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(8, 13, 24))
        }
        val heading = TextView(this).apply {
            text = "WHITEBOARD"
            textSize = 20f
            setTextColor(Color.rgb(103, 232, 249))
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Whiteboard drawing workspace"
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(heading, LinearLayout.LayoutParams(-1, dp(48)))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolbar.addView(makeButton("Undo") { board.undo() }, weightedParams())
        toolbar.addView(makeButton("Clear") { board.clear() }, weightedParams())
        toolbar.addView(makeButton("Save") { saveDrawing() }, weightedParams())
        toolbar.addView(makeButton("Share") { shareDrawing() }, weightedParams())
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(52)))

        board = DrawingBoardView(this)
        board.contentDescription = "Drawing area. Drag a finger to draw."
        root.addView(board, LinearLayout.LayoutParams(-1, 0, 1f))

        val hint = TextView(this).apply {
            text = "Draw with your finger • Undo, clear, save or share"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(hint, LinearLayout.LayoutParams(-1, dp(32)))
        setContentView(root)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun weightedParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginEnd = dp(4)
    }

    private fun saveDrawing() {
        val bitmap = board.exportBitmap() ?: run {
            Toast.makeText(this, "Draw something first.", Toast.LENGTH_SHORT).show()
            return
        }
        var output: OutputStream? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "JARVIS-Whiteboard-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JARVIS")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create image entry")
            output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open image output")
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IllegalStateException("Could not encode drawing")
            }
            output.close()
            output = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                contentResolver.update(uri, ready, null, null)
            }
            Toast.makeText(this, "Saved to Pictures/JARVIS.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        } finally {
            try { output?.close() } catch (_: Exception) {}
            bitmap.recycle()
        }
    }

    private fun shareDrawing() {
        val bitmap = board.exportBitmap() ?: run {
            Toast.makeText(this, "Draw something first.", Toast.LENGTH_SHORT).show()
            return
        }
        // Save first so Android's Sharesheet can share a content URI safely.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "JARVIS-Whiteboard-share.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JARVIS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        var uri: Uri? = null
        try {
            uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create share image")
            contentResolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Could not encode drawing")
                }
            } ?: throw IllegalStateException("Could not open image output")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                contentResolver.update(uri, ready, null, null)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share whiteboard"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        } finally {
            bitmap.recycle()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class DrawingBoardView(context: android.content.Context) : View(context) {
    private data class Stroke(val path: Path, val paint: Paint)

    private val strokes = mutableListOf<Stroke>()
    private var activePath: Path? = null
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 23, 42)
        strokeWidth = 6f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setBackgroundColor(Color.WHITE)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.forEach { canvas.drawPath(it.path, it.paint) }
        activePath?.let { canvas.drawPath(it, activePaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePath = Path().apply { moveTo(x, y) }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                activePath?.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePath?.let { path ->
                    strokes.add(Stroke(Path(path), Paint(activePaint)))
                }
                activePath = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        invalidate()
    }

    fun clear() {
        strokes.clear()
        activePath = null
        invalidate()
    }

    fun exportBitmap(): Bitmap? {
        if (strokes.isEmpty() && activePath == null) return null
        val width = width.takeIf { it > 0 } ?: return null
        val height = height.takeIf { it > 0 } ?: return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            strokes.forEach { canvas.drawPath(it.path, it.paint) }
            activePath?.let { canvas.drawPath(it, activePaint) }
        }
    }
}
