package com.jarvis.assistant.ui.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.accessibility.AccessibilityDoctor
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.accessibility.TruthfulA11yStatus
import com.jarvis.assistant.util.PermissionHelper

/**
 * Step-by-step interactive guided recovery screen for Accessibility issues.
 * Re-runs live window probe dynamically after each user action.
 */
class AccessibilityFixActivity : AppCompatActivity() {

    private lateinit var tvVerdict: TextView
    private lateinit var tvDetail: TextView
    private lateinit var cardStatus: MaterialCardView
    private lateinit var btnRecheck: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accessibility_fix)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        tvVerdict = findViewById(R.id.tvLiveStatusVerdict)
        tvDetail = findViewById(R.id.tvLiveStatusDetail)
        cardStatus = findViewById(R.id.cardLiveStatus)
        btnRecheck = findViewById(R.id.btnRecheckProbe)

        val tvBuildStamp = findViewById<TextView>(R.id.tvBuildStamp)
        tvBuildStamp.text = "Build: ${BuildConfig.BUILD_GIT_SHA} | ${BuildConfig.BUILD_TIMESTAMP}"

        btnRecheck.setOnClickListener {
            refreshStatus()
        }

        findViewById<ImageButton>(R.id.btnCopyDoctorReport).setOnClickListener {
            val report = AccessibilityDoctor.generateCopyableReport(this)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Jarvis Accessibility Report", report))
            Toast.makeText(this, "Diagnostic report copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        // Step 1: Accessibility settings
        findViewById<Button>(R.id.btnStepToggleA11y).setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        // Step 2: Restricted settings
        findViewById<Button>(R.id.btnStepRestrictedSettings).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open App Info settings", Toast.LENGTH_SHORT).show()
            }
        }

        // Step 3: Battery optimization
        findViewById<Button>(R.id.btnStepBattery).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not open Battery settings", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Step 4: OEM Autostart
        findViewById<Button>(R.id.btnStepOemAutostart).setOnClickListener {
            PermissionHelper.openOemAutostartSettings(this)
        }
    }

    override fun onResume() {
        super.onResume()
        AccessibilityHealthModel.refreshFromSystem(this)
        refreshStatus()
    }

    private fun refreshStatus() {
        val report = AccessibilityDoctor.buildReport(this)
        when (report.truthfulStatus) {
            TruthfulA11yStatus.WORKING -> {
                tvVerdict.text = "ACCESSIBILITY IS WORKING ✓"
                tvVerdict.setTextColor(Color.parseColor("#10B981")) // Emerald Green
                cardStatus.setStrokeColor(Color.parseColor("#10B981"))
                tvDetail.text = report.liveProbeDetail
            }
            TruthfulA11yStatus.ENABLED_NOT_CONNECTED -> {
                tvVerdict.text = "ENABLED BUT NOT CONNECTED ⚠"
                tvVerdict.setTextColor(Color.parseColor("#F59E0B")) // Amber
                cardStatus.setStrokeColor(Color.parseColor("#F59E0B"))
                tvDetail.text = "Android lists the service as ON, but the system binder has not connected to the app. Please follow Step 1 below (turn OFF then ON)."
            }
            TruthfulA11yStatus.CONNECTED_NO_EVENTS -> {
                tvVerdict.text = "CONNECTED (NO RECENT EVENTS)"
                tvVerdict.setTextColor(Color.parseColor("#F59E0B"))
                cardStatus.setStrokeColor(Color.parseColor("#F59E0B"))
                tvDetail.text = "Service is bound but no window change events have been delivered recently."
            }
            TruthfulA11yStatus.DEGRADED -> {
                tvVerdict.text = "SERVICE DEGRADED ⚠"
                tvVerdict.setTextColor(Color.parseColor("#EF4444")) // Red
                cardStatus.setStrokeColor(Color.parseColor("#EF4444"))
                tvDetail.text = "Live window probe or recent actions failed: ${report.lastError ?: report.liveProbeDetail}"
            }
            TruthfulA11yStatus.CRASH_LOOP -> {
                tvVerdict.text = "CRASH LOOP DETECTED ⚠"
                tvVerdict.setTextColor(Color.parseColor("#EF4444"))
                cardStatus.setStrokeColor(Color.parseColor("#EF4444"))
                tvDetail.text = "Service process crashed repeatedly. Check Doctor Report for stack frames."
            }
            TruthfulA11yStatus.NOT_ENABLED -> {
                tvVerdict.text = "SERVICE DISABLED"
                tvVerdict.setTextColor(Color.parseColor("#64748B"))
                cardStatus.setStrokeColor(Color.parseColor("#CBD5E1"))
                tvDetail.text = "Jarvis Accessibility Service is currently OFF in Android Settings."
            }
        }
    }
}
