package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.accessibility.AccessibilityState
import com.jarvis.assistant.databinding.ActivityHandsFreeBinding
import com.jarvis.assistant.ui.permission.PermissionActivity
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HandsFreeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHandsFreeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHandsFreeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupListeners()
        observeAccessibilityHealth()
    }

    override fun onResume() {
        super.onResume()
        AccessibilityHealthModel.refreshFromSystem(this)
        pollAccessibilityState()
    }

    private fun observeAccessibilityHealth() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AccessibilityHealthModel.state.collect { state ->
                    updateServiceStatus(state)
                }
            }
        }
    }

    /**
     * Rapid polling to reconcile 50-250ms Binder latency after user returns from Settings.
     */
    private fun pollAccessibilityState() {
        lifecycleScope.launch {
            val intervals = listOf(150L, 350L, 600L, 1000L, 1500L, 2000L)
            for (interval in intervals) {
                delay(interval)
                AccessibilityHealthModel.refreshFromSystem(this@HandsFreeActivity)
                if (AccessibilityHealthModel.isOperational()) break
            }
        }
    }

    private fun updateServiceStatus(state: AccessibilityState) {
        when (state) {
            AccessibilityState.CONNECTED -> {
                binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_green)
                )
                binding.tvAccessibilityStatus.text = "HANDS-FREE SERVICE: ACTIVE"
                binding.tvAccessibilityStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_green)
                )
                binding.btnToggleAccessibility.text = "Accessibility Service is Active"
                binding.btnToggleAccessibility.isEnabled = true
                binding.btnToggleAccessibility.alpha = 1.0f
            }
            AccessibilityState.ENABLED -> {
                binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_amber)
                )
                binding.tvAccessibilityStatus.text = "SERVICE CONNECTING… PLEASE WAIT"
                binding.tvAccessibilityStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_amber)
                )
                binding.btnToggleAccessibility.text = "Connecting… Tap to Recheck or Toggle"
                binding.btnToggleAccessibility.isEnabled = true
                binding.btnToggleAccessibility.alpha = 1.0f
            }
            AccessibilityState.DEGRADED -> {
                binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_amber)
                )
                binding.tvAccessibilityStatus.text = "SERVICE ACTIVE (DEGRADED)"
                binding.tvAccessibilityStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_amber)
                )
                binding.btnToggleAccessibility.text = "Service Degraded • Tap to Check"
                binding.btnToggleAccessibility.isEnabled = true
                binding.btnToggleAccessibility.alpha = 1.0f
            }
            AccessibilityState.DISCONNECTED -> {
                binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_red)
                )
                binding.tvAccessibilityStatus.text = "SERVICE DISCONNECTED • TAP TO RE-ENABLE"
                binding.tvAccessibilityStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_red)
                )
                binding.btnToggleAccessibility.text = "Reconnect Accessibility Service"
                binding.btnToggleAccessibility.isEnabled = true
                binding.btnToggleAccessibility.alpha = 1.0f
            }
            AccessibilityState.ERROR -> {
                binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_red)
                )
                binding.tvAccessibilityStatus.text = "SERVICE ERROR • TAP TO RESTART"
                binding.tvAccessibilityStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_red)
                )
                binding.btnToggleAccessibility.text = "Restart Accessibility Service"
                binding.btnToggleAccessibility.isEnabled = true
                binding.btnToggleAccessibility.alpha = 1.0f
            }
            AccessibilityState.DISABLED -> {
                binding.dotAccessibilityStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_red)
                )
                binding.tvAccessibilityStatus.text = "SERVICE DISABLED • TAP TO ENABLE"
                binding.tvAccessibilityStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.status_red)
                )
                binding.btnToggleAccessibility.text = "Enable Accessibility Service"
                binding.btnToggleAccessibility.isEnabled = true
                binding.btnToggleAccessibility.alpha = 1.0f
            }
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnToggleAccessibility.setOnClickListener {
            PermissionHelper.requestAccessibilityWithRestrictedGuidance(this)
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            PermissionHelper.requestAccessibilityWithRestrictedGuidance(this)
        }

        binding.btnOpenAllPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        binding.btnConfigureApi.setOnClickListener {
            startActivity(Intent(this, ApiConnectionActivity::class.java))
        }

        binding.btnHandsFreeOfficialTelegram.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }

        binding.btnHandsFreeDiscussion.setOnClickListener {
            openUrl("https://t.me/+DmyO7NFUia01YTVl")
        }

        binding.btnHandsFreeInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/techgptx.ai")
        }

        binding.tvFooterHandsFree.setOnClickListener {
            openUrl("https://t.me/+sYana61LvHdmZmE1")
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }
}
