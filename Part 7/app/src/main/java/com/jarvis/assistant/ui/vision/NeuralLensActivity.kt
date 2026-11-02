package com.jarvis.assistant.ui.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import java.io.File
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
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
    private lateinit var livePreview: PreviewView
    private lateinit var status: TextView
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var resumeCameraOnForeground = false
    private var pendingCaptureFile: File? = null
    private lateinit var result: TextView
    private lateinit var progress: ProgressBar
    private lateinit var analyzeButton: Button

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else if (::status.isInitialized) status.text = "Camera permission denied. Gallery image analysis remains available."
    }

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            status.text = "Image selection cancelled."
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            status.text = "Loading selected image…"
            val bitmap = withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = ImageDecodeSizing.sampleSize(bounds.outWidth, bounds.outHeight)
                }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
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
        livePreview = PreviewView(this).apply {
            contentDescription = "Live CameraX camera preview"
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(0xFF080B12.toInt())
        }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Captured or selected image preview"
            setBackgroundColor(0xFF080B12.toInt())
            visibility = View.GONE
        }
        val previewFrame = android.widget.FrameLayout(this).apply {
            addView(livePreview, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(preview, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(280)))
        root.addView(action("Start live camera") { requestCameraAndStart() })
        root.addView(action("Capture photo") { capturePhoto() })
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
            setTextIsSelectable(true)
        }
        root.addView(result)
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Camera permission is required. Gallery image analysis remains available."
            return
        }
        status.text = "Starting live camera…"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                resumeCameraOnForeground = true
                return@addListener
            }
            try {
                val provider = providerFuture.get()
                val previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(livePreview.surfaceProvider)
                }
                val captureUseCase = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    captureUseCase
                )
                cameraProvider = provider
                imageCapture = captureUseCase
                livePreview.visibility = View.VISIBLE
                preview.visibility = View.GONE
                status.text = "Live camera ready. Capture a photo when you are ready."
            } catch (error: Exception) {
                imageCapture = null
                status.text = "Could not start camera: ${error.localizedMessage ?: "camera unavailable"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture
        if (capture == null) {
            status.text = "Camera is not ready. Starting the live preview…"
            requestCameraAndStart()
            return
        }
        val file = File(cacheDir, "neural-lens-${System.currentTimeMillis()}.jpg")
        pendingCaptureFile = file
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        status.text = "Capturing photo…"
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (isFinishing || isDestroyed) {
                        file.delete()
                        if (pendingCaptureFile == file) pendingCaptureFile = null
                        return
                    }
                    lifecycleScope.launch {
                        try {
                            val bitmap = withContext(Dispatchers.IO) {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(file.absolutePath, bounds)
                                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                                BitmapFactory.decodeFile(
                                    file.absolutePath,
                                    BitmapFactory.Options().apply {
                                        inSampleSize = ImageDecodeSizing.sampleSize(bounds.outWidth, bounds.outHeight)
                                    }
                                )
                            }
                            if (bitmap == null) {
                                status.text = "Camera photo was saved but could not be decoded."
                            } else {
                                cameraProvider?.unbindAll()
                                imageCapture = null
                                showImage(bitmap, "CameraX photo captured. Review it, then analyze.")
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (!isFinishing && !isDestroyed) {
                                status.text = "Could not load captured photo: ${error.localizedMessage ?: "unknown error"}"
                            }
                        } finally {
                            file.delete()
                            if (pendingCaptureFile == file) pendingCaptureFile = null
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    if (pendingCaptureFile == file) pendingCaptureFile = null
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Camera capture failed: ${exception.message ?: "unknown camera error"}"
                    }
                }
            }
        )
    }

    private fun showImage(bitmap: Bitmap, message: String) {
        // Stop camera streaming once the user switches to a still image; this releases
        // the camera promptly for other apps and avoids hidden preview battery use.
        cameraProvider?.unbindAll()
        imageCapture = null
        selectedBitmap?.takeIf { it !== bitmap }?.recycle()
        selectedBitmap = bitmap
        livePreview.visibility = View.GONE
        preview.visibility = View.VISIBLE
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
        // Analyze an owned snapshot. The activity may be destroyed and recycle its preview
        // bitmap while the provider request is still running.
        val analysisBitmap = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: run {
                    status.text = "Could not prepare the selected image for analysis."
                    return
                }
        } catch (error: Exception) {
            status.text = "Could not prepare the selected image for analysis."
            return
        }
        setBusy(true, "Analyzing the selected image…")
        lifecycleScope.launch {
            try {
                val analysis = CameraVisionAnalyzer.analyzeBitmap(this@NeuralLensActivity, analysisBitmap)
                if (!isFinishing && !isDestroyed) {
                    result.text = analysis.summary
                    status.text = if (analysis.success) "Image analysis completed by Gemini Vision." else "Image analysis did not complete."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isFinishing && !isDestroyed) {
                    status.text = "Image analysis failed: ${error.localizedMessage ?: "unknown error"}"
                }
            } finally {
                if (!analysisBitmap.isRecycled) analysisBitmap.recycle()
                if (!isFinishing && !isDestroyed) setBusy(false)
            }
        }
    }

    private fun scanCurrentScreen() {
        setBusy(true, "Scanning current screen for visible text…")
        lifecycleScope.launch {
            try {
                val scan = TacticalScannerManager.scanCurrentScreen(this@NeuralLensActivity)
                result.text = scan.summary
                status.text = if (scan.summary.isBlank()) "No readable text was found." else "Screen OCR scan finished."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isFinishing && !isDestroyed) {
                    status.text = "Screen OCR failed: ${error.localizedMessage ?: "unknown error"}"
                }
            } finally {
                if (!isFinishing && !isDestroyed) setBusy(false)
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

    override fun onResume() {
        super.onResume()
        if (resumeCameraOnForeground && selectedBitmap == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            resumeCameraOnForeground = false
            startCamera()
        }
    }

    override fun onStop() {
        resumeCameraOnForeground = imageCapture != null
        cameraProvider?.unbindAll()
        imageCapture = null
        super.onStop()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        resumeCameraOnForeground = false
        pendingCaptureFile?.delete()
        pendingCaptureFile = null
        if (::preview.isInitialized) preview.setImageDrawable(null)
        selectedBitmap?.takeUnless { it.isRecycled }?.recycle()
        selectedBitmap = null
        super.onDestroy()
    }
}
