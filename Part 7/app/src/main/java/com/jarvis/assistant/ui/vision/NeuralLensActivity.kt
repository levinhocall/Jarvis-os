package com.jarvis.assistant.ui.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.vision.CameraVisionAnalyzer
import com.jarvis.assistant.vision.TacticalScannerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Camera/gallery image capture and real-image analysis entry point for Neural Lens. */
class NeuralLensActivity : AppCompatActivity() {
    private var selectedBitmap: Bitmap? = null
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var progress: ProgressBar
    private lateinit var analyzeButton: Button

    private val cameraCapture = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) {
            status.text = "Camera capture was cancelled or no image was returned."
        } else {
            showImage(bitmap, "Camera image captured. Review it, then analyze.")
        }
    }

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            status.text = "Image selection cancelled."
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            status.text = "Loading selected image…"
            val bitmap = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { it2 -> BitmapFactory.decodeStream(it2, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                    var sample = 1
                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeStream(input, null, options)
                }
            }
            if (bitmap == null) {
                status.text = "Could not decode this image. Choose a supported JPEG, PNG or WebP file."
            } else {
                showImage(bitmap, "Gallery image loaded. Review it, then analyze.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val prefs = com.jarvis.assistant.JarvisApp.instance.preferences
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            setBackgroundColor(ThemeManager.getThemeBgColor(prefs.appTheme, prefs.customThemeColor))
        }
        root.addView(TextView(this).apply {
            text = "NEURAL LENS"
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(color(R.attr.themeTextPrimary))
        })
        root.addView(TextView(this).apply {
            text = "Capture a real camera image or select a photo. Analysis is sent to the configured Gemini vision provider."
            textSize = 13f
            setPadding(0, dp(6), 0, dp(12))
            setTextColor(color(R.attr.themeTextMuted))
        })
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Selected camera or gallery image preview"
            setBackgroundColor(0xFF080B12.toInt())
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(280)))
        root.addView(action("Open camera and capture") { cameraCapture.launch(null) })
        root.addView(action("Choose image from gallery") { galleryPicker.launch(arrayOf("image/*")) })
        analyzeButton = action("Analyze selected image") { analyzeSelectedImage() }.apply { isEnabled = false }
        root.addView(analyzeButton)
        root.addView(action("Scan current screen (OCR)") { scanCurrentScreen() })
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress)
        status = TextView(this).apply {
            text = "No image selected."
            textSize = 12f
            setPadding(0, dp(10), 0, dp(8))
            setTextColor(color(R.attr.themeTextMuted))
        }
        root.addView(status)
        result = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.attr.themeTextPrimary))
            setPadding(0, dp(8), 0, dp(12))
            textIsSelectable = true
        }
        root.addView(result)
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun showImage(bitmap: Bitmap, message: String) {
        selectedBitmap?.takeIf { it !== bitmap }?.recycle()
        selectedBitmap = bitmap
        preview.setImageBitmap(bitmap)
        analyzeButton.isEnabled = true
        status.text = message
        result.text = ""
    }

    private fun analyzeSelectedImage() {
        val bitmap = selectedBitmap
        if (bitmap == null || bitmap.isRecycled) {
            status.text = "Capture or select an image before analysis."
            return
        }
        setBusy(true, "Analyzing the selected image…")
        lifecycleScope.launch {
            val analysis = CameraVisionAnalyzer.analyzeBitmap(this@NeuralLensActivity, bitmap)
            result.text = analysis.summary
            status.text = if (analysis.success) "Image analysis completed by Gemini Vision." else "Image analysis did not complete."
            setBusy(false)
        }
    }

    private fun scanCurrentScreen() {
        setBusy(true, "Scanning current screen for visible text…")
        lifecycleScope.launch {
            try {
                val scan = TacticalScannerManager.scanCurrentScreen(this@NeuralLensActivity)
                result.text = scan.summary
                status.text = if (scan.summary.isBlank()) "No readable text was found." else "Screen OCR scan finished."
            } catch (error: Exception) {
                status.text = "Screen OCR failed: ${error.localizedMessage ?: "unknown error"}"
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        analyzeButton.isEnabled = !busy && selectedBitmap != null
        if (message != null) status.text = message
    }

    private fun action(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
    }

    private fun color(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        preview.setImageDrawable(null)
        selectedBitmap?.recycle()
        selectedBitmap = null
        super.onDestroy()
    }
}
