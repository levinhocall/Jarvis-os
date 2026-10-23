package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.databinding.ActivityApiConnectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }
    private val testHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadCurrentPreferences()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadCurrentPreferences() {
        binding.etApiKey.setText(preferences.apiKey)
        binding.etCustomEndpoint.setText(preferences.customEndpoint)

        if (!preferences.canStoreApiKeySecurely) {
            binding.tvLiveStatus.text = "SECURE STORAGE UNAVAILABLE • API KEY DISABLED"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        }

        when (preferences.aiModel) {
            GeminiConstants.MODEL_3_8_LIVE -> binding.rbModelFlash20.isChecked = true
            GeminiConstants.MODEL_3_1_FLASH_AGENT -> binding.rbModelFlash15.isChecked = true
            else -> binding.rbModelFlashThinking.isChecked = true
        }

        if (!preferences.canStoreApiKeySecurely) {
            // Keep the secure-storage warning set above.
        } else if (preferences.apiKey.isNotBlank()) {
            binding.tvLiveStatus.text = "KEY CONFIGURED • READY"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        } else {
            binding.tvLiveStatus.text = "NO API KEY • ENTER KEY BELOW"
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EAB308"))
        }

        if (preferences.customEndpoint.isNotBlank()) {
            binding.tvEndpointSummary.text = "Active Endpoint: ${preferences.customEndpoint}"
        }

        loadKeyPool()
        loadUsageStats()
    }

    private fun loadKeyPool() {
        binding.layoutKeyPoolContainer.removeAllViews()
        val pool = preferences.getApiKeyPool("gemini")

        if (pool.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No secondary keys in pool. Primary key above is used."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
            }
            binding.layoutKeyPoolContainer.addView(emptyTv)
            return
        }

        for (item in pool) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10, 0, 10)
            }

            val maskedKey = if (item.key.length > 8) {
                "${item.key.take(4)}...****...${item.key.takeLast(4)}"
            } else {
                "****"
            }

            val keyTv = TextView(this).apply {
                text = maskedKey
                setTextColor(Color.WHITE)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val (badgeText, badgeColor) = when (item.state) {
                com.jarvis.assistant.data.preferences.ApiKeyState.ACTIVE -> "ACTIVE" to "#22C55E"
                com.jarvis.assistant.data.preferences.ApiKeyState.COOLDOWN -> "COOLDOWN" to "#F59E0B"
                com.jarvis.assistant.data.preferences.ApiKeyState.INVALID -> "INVALID" to "#EF4444"
            }

            val statusTv = TextView(this).apply {
                text = badgeText
                setTextColor(Color.parseColor(badgeColor))
                textSize = 11f
                setPadding(12, 4, 12, 4)
                setBackgroundResource(R.drawable.bg_jarvis_status_badge)
            }

            val deleteBtn = ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                setPadding(16, 8, 8, 8)
                setOnClickListener {
                    preferences.removeApiKeyFromPool("gemini", item.key)
                    loadKeyPool()
                    Toast.makeText(this@ApiConnectionActivity, "Key removed from pool", Toast.LENGTH_SHORT).show()
                }
            }

            row.addView(keyTv)
            row.addView(statusTv)
            row.addView(deleteBtn)
            binding.layoutKeyPoolContainer.addView(row)
        }
    }

    private fun loadUsageStats() {
        val geminiStats = preferences.getUsageStats("gemini")
        val openAiStats = preferences.getUsageStats("openai_compatible")

        val geminiCostFormatted = String.format(java.util.Locale.US, "%.4f", geminiStats.totalCostUsd)
        val openAiCostFormatted = String.format(java.util.Locale.US, "%.4f", openAiStats.totalCostUsd)

        binding.tvGeminiUsageStats.text =
            "Gemini: ${geminiStats.totalPromptTokens} prompt / ${geminiStats.totalCompletionTokens} comp tokens ($$geminiCostFormatted)"
        binding.tvOpenAiUsageStats.text =
            "OpenAI Compatible: ${openAiStats.totalPromptTokens} prompt / ${openAiStats.totalCompletionTokens} comp tokens ($$openAiCostFormatted)"
    }

    private fun showAddPoolKeyDialog() {
        val input = EditText(this).apply {
            hint = "Enter Gemini API key (AIzaSy...)"
            setSingleLine(true)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Key to Rotation Pool")
            .setMessage("Add an additional Gemini API key. Calls will rotate round-robin across active keys.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val newKey = input.text.toString().trim()
                if (newKey.isNotBlank()) {
                    preferences.addApiKeyToPool("gemini", newKey)
                    loadKeyPool()
                    Toast.makeText(this, "Key added to rotation pool", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupListeners() {
        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasted = item?.text?.toString()?.trim() ?: ""
            if (pasted.isNotBlank()) {
                binding.etApiKey.setText(pasted)
                Toast.makeText(this, "API Key pasted from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearKey.setOnClickListener {
            binding.etApiKey.setText("")
        }

        binding.btnTestConnection.setOnClickListener {
            val enteredKey = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (enteredKey.isBlank()) {
                Snackbar.make(binding.root, "Please enter an API key first", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            testConnection(enteredKey)
        }

        binding.btnAddPoolKey.setOnClickListener {
            showAddPoolKeyDialog()
        }

        binding.btnSaveConnection.setOnClickListener {
            saveAndApply()
        }
    }

    private fun testConnection(apiKey: String) {
        binding.btnTestConnection.isEnabled = false
        binding.tvPingResult.text = "Testing ping..."
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                    val request = Request.Builder().url(url).build()
                    testHttpClient.newCall(request).execute().use { response ->
                        val duration = System.currentTimeMillis() - startTime
                        if (response.isSuccessful) {
                            Pair(true, duration)
                        } else {
                            Pair(false, response.code)
                        }
                    }
                } catch (e: Exception) {
                    Pair(false, -1)
                }
            }

            binding.btnTestConnection.isEnabled = true
            if (result.first) {
                val ping = result.second
                binding.tvPingResult.text = "Ping: ${ping}ms • Valid"
                binding.tvLiveStatus.text = "CONNECTED • HANDSHAKE OK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "Connection verified! Ping: ${ping}ms", Snackbar.LENGTH_LONG).show()
            } else {
                val code = result.second
                binding.tvPingResult.text = if (code == -1) "Network Timeout" else "HTTP $code Error"
                binding.tvLiveStatus.text = "FAILED • INVALID KEY OR NETWORK"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                Snackbar.make(binding.root, "Connection failed. Please verify API key.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndApply() {
        val key = binding.etApiKey.text?.toString()?.trim() ?: ""
        val customEp = binding.etCustomEndpoint.text?.toString()?.trim() ?: ""

        val selectedModel = when (binding.rgModelGroup.checkedRadioButtonId) {
            binding.rbModelFlash20.id -> GeminiConstants.MODEL_3_8_LIVE
            binding.rbModelFlash15.id -> GeminiConstants.MODEL_3_1_FLASH_AGENT
            else -> GeminiConstants.MODEL_3_1_FLASH
        }

        try {
            preferences.apiKey = key
        } catch (e: IllegalStateException) {
            Snackbar.make(binding.root, e.message ?: "Secure key storage is unavailable.", Snackbar.LENGTH_LONG).show()
            return
        }
        preferences.aiModel = selectedModel
        preferences.customEndpoint = customEp
        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
        com.jarvis.assistant.background.JarvisConversationService.restartSession()

        Toast.makeText(this, "API & Connection settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
