package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.aura.AuraControls
import com.jarvis.assistant.ui.compose.AuraStudioComposeScreen
import com.jarvis.assistant.ui.compose.EngineDiagnosticsActivity
import com.jarvis.assistant.util.ThemeManager

/**
 * Interactive Orb & Aura Studio backed by Jetpack Compose + OpenGL ES 3.0 preview.
 * Preserves full access to legacy Aura autonomy controls and power/theme configuration.
 */
class AuraControlActivity : AppCompatActivity() {
    private val preferences by lazy { JarvisApp.instance.preferences }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AuraStudioComposeScreen(
                    preferences = preferences,
                    onBack = { finish() },
                    onOpenDiagnostics = {
                        startActivity(Intent(this@AuraControlActivity, EngineDiagnosticsActivity::class.java))
                    },
                    onOpenLegacyAuraControls = { showLegacyControlsDialog() },
                    onAppThemeChanged = {
                        ThemeManager.cycleHomeTheme(this@AuraControlActivity)
                        recreate()
                    }
                )
            }
        })
    }

    private fun showLegacyControlsDialog() {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        AuraControls.add(this, container)
        val scroll = ScrollView(this).apply { addView(container) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Aura Autonomy & Power Controls")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }
}
