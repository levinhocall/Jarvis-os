package com.jarvis.assistant.ui.forge

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.forge.ForgeController
import com.jarvis.assistant.forge.ForgePhase
import com.jarvis.assistant.forge.ForgeStage
import com.jarvis.assistant.forge.ForgeUiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Immersive activity hosting the Website Forge live 3D Three.js builder and generated site viewer.
 */
class ForgeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_FORGE = "extra_open_forge"
        const val EXTRA_SAVED_FILE = "extra_saved_file"
        const val EXTRA_PROMPT = "extra_prompt"
        private const val TOOLBAR_HIDE_DELAY_MS = 4000L
    }

    private lateinit var forgeWebGlScene: ForgeWebGlScene
    private lateinit var forgeFallbackScene: ForgeFallbackScene
    private lateinit var generatedSiteView: GeneratedSiteView
    private lateinit var forgeHudContainer: View
    private lateinit var forgeToolbar: View
    private lateinit var btnToolbarHandle: ImageButton

    private lateinit var tvPromptChip: TextView
    private lateinit var tvStageLabel: TextView
    private lateinit var tvStageDesc: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvStatKb: TextView
    private lateinit var tvStatSections: TextView
    private lateinit var tvStatSpeed: TextView
    private lateinit var tvStatElapsed: TextView
    private lateinit var btnCancel: Button

    private lateinit var trackSegs: List<View>

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastObservedStage = ForgeStage.BLUEPRINT
    private var isUsingFallback = false
    private var autoHideRunnable: Runnable? = null
    private var isLowRam = false

    private val saveDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    val file = ForgeController.uiState.value.file
                    if (file != null && file.exists()) {
                        os.write(file.readBytes())
                    } else {
                        os.write(ForgeController.partialHtml().toByteArray(Charsets.UTF_8))
                    }
                }
                Toast.makeText(this, "Website saved successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to save file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forge)

        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        isLowRam = am?.isLowRamDevice == true

        ForgeController.init(this)
        ForgeController.consumePendingOpen()

        bindViews()
        setupListeners()
        setupScenes()

        handleIntent(intent)

        lifecycleScope.launch {
            ForgeController.uiState.collectLatest { state ->
                renderState(state)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ForgeController.setScreenVisible(true)
    }

    override fun onPause() {
        super.onPause()
        ForgeController.setScreenVisible(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        forgeWebGlScene.disposeScene()
    }

    private fun bindViews() {
        forgeWebGlScene = findViewById(R.id.forgeWebGlScene)
        forgeFallbackScene = findViewById(R.id.forgeFallbackScene)
        generatedSiteView = findViewById(R.id.generatedSiteView)
        forgeHudContainer = findViewById(R.id.forgeHudContainer)
        forgeToolbar = findViewById(R.id.forgeToolbar)
        btnToolbarHandle = findViewById(R.id.btnToolbarHandle)

        tvPromptChip = findViewById(R.id.tvPromptChip)
        tvStageLabel = findViewById(R.id.tvStageLabel)
        tvStageDesc = findViewById(R.id.tvStageDesc)
        tvPercent = findViewById(R.id.tvPercent)
        tvStatKb = findViewById(R.id.tvStatKb)
        tvStatSections = findViewById(R.id.tvStatSections)
        tvStatSpeed = findViewById(R.id.tvStatSpeed)
        tvStatElapsed = findViewById(R.id.tvStatElapsed)
        btnCancel = findViewById(R.id.btnCancel)

        trackSegs = listOf(
            findViewById(R.id.trackSeg1),
            findViewById(R.id.trackSeg2),
            findViewById(R.id.trackSeg3),
            findViewById(R.id.trackSeg4),
            findViewById(R.id.trackSeg5),
            findViewById(R.id.trackSeg6)
        )
    }

    private fun setupScenes() {
        forgeWebGlScene.callback = object : ForgeWebGlScene.Callback {
            override fun onReady() {
                val prefs = AppPreferences(this@ForgeActivity)
                forgeWebGlScene.initTheme(
                    bgPrimary = "#070709",
                    bgSecondary = "#121218",
                    accentPurple = "#8B5CF6",
                    accentGold = "#F59E0B",
                    accentCyan = "#06B6D4",
                    textPrimary = "#F8FAFC",
                    textSecondary = "#94A3B8",
                    reduceMotion = prefs.isReduceMotionEnabled,
                    isLowRam = isLowRam
                )
            }

            override fun onRevealFinished() {
                // Slabs fly-in finished; reveal generated site
                showSiteAndToolbar()
            }

            override fun onWebGLFailed() {
                isUsingFallback = true
                forgeWebGlScene.visibility = View.GONE
                forgeFallbackScene.visibility = View.VISIBLE
            }
        }

        generatedSiteView.renderListener = object : GeneratedSiteView.OnSiteRenderedListener {
            override fun onSitePainted() {
                // Final site loaded and painted, run 3D reveal sequence
                if (!isUsingFallback) {
                    forgeWebGlScene.triggerReveal()
                } else {
                    showSiteAndToolbar()
                }
            }
        }
    }

    private fun setupListeners() {
        btnCancel.setOnClickListener {
            ForgeController.cancel()
        }

        findViewById<View>(R.id.btnToolbarBack).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btnToolbarShare).setOnClickListener {
            shareWebsite()
        }

        findViewById<View>(R.id.btnToolbarSave).setOnClickListener {
            saveDocumentLauncher.launch("website_${System.currentTimeMillis()}.html")
        }

        findViewById<View>(R.id.btnToolbarBrowser).setOnClickListener {
            openInSystemBrowser()
        }

        findViewById<View>(R.id.btnToolbarEdit).setOnClickListener {
            showEditDialog()
        }

        findViewById<View>(R.id.btnToolbarRebuild).setOnClickListener {
            ForgeController.retry()
        }

        findViewById<View>(R.id.btnToolbarDelete).setOnClickListener {
            confirmDelete()
        }

        btnToolbarHandle.setOnClickListener {
            showToolbarWithAutoHide()
        }
    }

    private fun handleIntent(intent: Intent?) {
        val savedPath = intent?.getStringExtra(EXTRA_SAVED_FILE)
        if (!savedPath.isNullOrBlank()) {
            val file = File(savedPath)
            if (file.exists()) {
                ForgeController.openSaved(file)
                return
            }
        }

        val prompt = intent?.getStringExtra(EXTRA_PROMPT)
        if (!prompt.isNullOrBlank() && ForgeController.uiState.value.phase == ForgePhase.IDLE) {
            ForgeController.start(prompt)
        }
    }

    private fun renderState(state: ForgeUiState) {
        when (state.phase) {
            ForgePhase.IDLE -> {
                forgeHudContainer.visibility = View.VISIBLE
                forgeToolbar.visibility = View.GONE
                btnToolbarHandle.visibility = View.GONE
            }
            ForgePhase.BUILDING -> {
                setImmersiveMode(true)
                forgeHudContainer.visibility = View.VISIBLE
                generatedSiteView.visibility = View.GONE
                forgeToolbar.visibility = View.GONE
                btnToolbarHandle.visibility = View.GONE

                tvPromptChip.text = state.prompt.ifBlank { "Website Builder" }
                tvStageLabel.text = "Stage ${state.stage.index}: ${state.stage.label}"
                tvStageDesc.text = state.stage.description

                val percent = (state.progress * 100).toInt()
                tvPercent.text = "$percent%"

                // Update 6 segments
                trackSegs.forEachIndexed { i, seg ->
                    if (i < state.stage.index) {
                        seg.setBackgroundColor(Color.parseColor("#8B5CF6"))
                    } else {
                        seg.setBackgroundColor(Color.parseColor("#121218"))
                    }
                }

                // Stats
                val kb = state.metrics.chars / 1024
                tvStatKb.text = "$kb KB"
                tvStatSections.text = "${state.sections.size} sections"
                tvStatSpeed.text = "${state.metrics.tokensPerSec.toInt()} tok/s"
                tvStatElapsed.text = "${state.metrics.elapsedSec}s"

                // Haptic feedback on stage change
                if (state.stage != lastObservedStage) {
                    performStageHaptic()
                    lastObservedStage = state.stage
                }

                // Push telemetry to 3D scene
                if (!isUsingFallback) {
                    forgeWebGlScene.pushState(state)
                } else {
                    forgeFallbackScene.updateState(state)
                }

                // Intermediate preview on sandboxed webview
                generatedSiteView.updateStreamingPreview(ForgeController.partialHtml(), isLowRam)
            }
            ForgePhase.DONE -> {
                setImmersiveMode(false)
                // If the generated site is not yet loaded, load it now
                val html = ForgeController.partialHtml()
                if (html.isNotBlank() && generatedSiteView.alpha == 0.0f) {
                    generatedSiteView.loadFinalSite(html)
                }
            }
            ForgePhase.FAILED, ForgePhase.CANCELLED -> {
                setImmersiveMode(false)
                forgeHudContainer.visibility = View.VISIBLE
                tvStageLabel.text = if (state.phase == ForgePhase.CANCELLED) "Build Cancelled" else "Build Failed"
                tvStageDesc.text = state.error ?: "Unable to complete website build."
                btnCancel.text = "Close"
                btnCancel.setOnClickListener { finish() }
                if (!isUsingFallback) {
                    forgeWebGlScene.triggerFail()
                }
            }
        }
    }

    private fun showSiteAndToolbar() {
        forgeHudContainer.animate().alpha(0.0f).setDuration(400).withEndAction {
            forgeHudContainer.visibility = View.GONE
        }.start()

        generatedSiteView.visibility = View.VISIBLE
        generatedSiteView.animate().alpha(1.0f).setDuration(500).start()

        showToolbarWithAutoHide()
    }

    private fun showToolbarWithAutoHide() {
        btnToolbarHandle.visibility = View.GONE
        forgeToolbar.visibility = View.VISIBLE
        forgeToolbar.alpha = 1.0f

        autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        autoHideRunnable = Runnable {
            forgeToolbar.animate().alpha(0.0f).setDuration(300).withEndAction {
                forgeToolbar.visibility = View.GONE
                btnToolbarHandle.visibility = View.VISIBLE
            }.start()
        }
        mainHandler.postDelayed(autoHideRunnable!!, TOOLBAR_HIDE_DELAY_MS)
    }

    private fun shareWebsite() {
        val file = ForgeController.uiState.value.file ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.forge.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Generated Website"))
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInSystemBrowser() {
        val file = ForgeController.uiState.value.file ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.forge.fileprovider", file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(viewIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found to open HTML: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditDialog() {
        val input = EditText(this).apply {
            hint = "e.g. Change color palette to emerald green, add contact form"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Website")
            .setMessage("Describe what changes to make to the generated page:")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val editChange = input.text.toString().trim()
                if (editChange.isNotBlank()) {
                    ForgeController.edit(editChange)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Website")
            .setMessage("Are you sure you want to delete this generated website?")
            .setPositiveButton("Delete") { _, _ ->
                val file = ForgeController.uiState.value.file
                file?.delete()
                ForgeController.reset()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performStageHaptic() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    private fun setImmersiveMode(enable: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enable) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
