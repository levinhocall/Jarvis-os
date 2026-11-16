package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.net.toUri
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
        binding.switchIndependentReviews.isChecked = preferences.independentModelReviewsEnabled
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
                text = "No secondary Gemini keys in pool. Primary key above is used."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
            }
            binding.layoutKeyPoolContainer.addView(emptyTv)
            loadExternalVoiceProviderRow()
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
        loadExternalVoiceProviderRow()
    }

    private fun loadExternalVoiceProviderRow() {
        val pool = preferences.getApiKeyPool("elevenlabs")
        val row = TextView(this).apply {
            text = if (pool.isEmpty()) {
                "ELEVENLABS VOICE • NOT CONFIGURED • TAP TO ADD KEY"
            } else {
                "ELEVENLABS VOICE • ${pool.size} KEY(S) CONFIGURED • TAP TO MANAGE"
            }
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 12f
            setPadding(0, 18, 0, 12)
            setOnClickListener {
                val keys = preferences.getApiKeyPool("elevenlabs")
                val actions = if (keys.isEmpty()) arrayOf("Add ElevenLabs key") else arrayOf("Add another key", "Remove configured keys")
                MaterialAlertDialogBuilder(this@ApiConnectionActivity)
                    .setTitle("External Voice Provider")
                    .setItems(actions) { _, which ->
                        if (keys.isEmpty() || which == 0) {
                            showAddPoolKeyDialog("elevenlabs")
                        } else {
                            MaterialAlertDialogBuilder(this@ApiConnectionActivity)
                                .setTitle("Remove ElevenLabs key")
                                .setItems(keys.map { item ->
                                    if (item.key.length > 8) "${item.key.take(4)}...****...${item.key.takeLast(4)}" else "****"
                                }.toTypedArray()) { _, index ->
                                    preferences.removeApiKeyFromPool("elevenlabs", keys[index].key)
                                    loadKeyPool()
                                }
                                .show()
                        }
                    }
                    .show()
            }
        }
        binding.layoutKeyPoolContainer.addView(row)
        loadPlannerProviderRow()
    }

    private fun loadSystemVoiceEngineRow() {
        binding.layoutKeyPoolContainer.addView(TextView(this).apply {
            text=getString(R.string.local_engine_requested,preferences.localTtsEngine.ifBlank { "Android default" })
            minHeight=(48*resources.displayMetrics.density).toInt()
            setOnClickListener {
                if(com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value) {
                    Toast.makeText(this@ApiConnectionActivity,R.string.local_engine_end_session,Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val engines=withContext(Dispatchers.IO) {
                        com.jarvis.assistant.audio.SystemTtsEngineSelection.installed(this@ApiConnectionActivity)
                    }
                    if(isFinishing) return@launch
                    val labels=arrayOf(getString(R.string.local_engine_default)) + engines.map { it.label+" ("+it.packageName+")" }
                    MaterialAlertDialogBuilder(this@ApiConnectionActivity).setTitle(R.string.local_engine_title)
                        .setItems(labels) { _, index ->
                            if(com.jarvis.assistant.background.JarvisConversationService.isSessionOn.value) return@setItems
                            com.jarvis.assistant.audio.SystemTtsEngineSelection.save(if(index == 0) "" else engines[index-1].packageName)
                            com.jarvis.assistant.audio.VoiceSynthesisRouter.release()
                            com.jarvis.assistant.audio.VoiceSynthesisRouter.init(applicationContext)
                            com.jarvis.assistant.background.JarvisConversationService.refreshLocalTtsEngine()
                            Toast.makeText(this@ApiConnectionActivity,R.string.local_engine_requested_note,Toast.LENGTH_LONG).show()
                            loadKeyPool()
                        }.setNeutralButton(R.string.local_engine_pocket_source) { _, _ ->
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                com.jarvis.assistant.audio.SystemTtsEngineSelection.POCKET_SOURCE.toUri()))
                        }.setNegativeButton("Cancel",null).show()
                }
            }
        })
    }

    private fun loadPlannerProviderRow() {
        loadSystemVoiceEngineRow()
        binding.layoutKeyPoolContainer.addView(TextView(this).apply {
            text = getString(R.string.planner_provider_status, preferences.plannerProvider, preferences.plannerModel.ifBlank { "model not selected" })
            minHeight = (48 * resources.displayMetrics.density).toInt()
            textSize = 13f; setPadding(0,16,0,16)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@ApiConnectionActivity).setTitle("Mission planner provider")
                    .setItems(arrayOf("Choose provider", "Set provider model identifier", "Add provider API key", "Remove provider keys", "Set custom HTTPS endpoint")) { _, action ->
                        when(action) {
                            0 -> {
                                val ids = arrayOf("gemini","openai_compatible","openrouter","deepseek","custom")
                                MaterialAlertDialogBuilder(this@ApiConnectionActivity).setTitle("Choose planner provider")
                                    .setSingleChoiceItems(arrayOf("Gemini (default)","OpenAI","OpenRouter","DeepSeek","Custom HTTPS API"),ids.indexOf(preferences.plannerProvider)) { dialog, selected ->
                                        preferences.plannerProvider=ids[selected]; dialog.dismiss(); loadKeyPool()
                                    }.setNegativeButton("Cancel",null).show()
                            }
                            1 -> {
                                if(preferences.plannerProvider == "gemini") { Toast.makeText(this@ApiConnectionActivity,"Gemini uses the existing model catalog.",Toast.LENGTH_SHORT).show(); return@setItems }
                                val model = EditText(this@ApiConnectionActivity).apply { setSingleLine(true); setText(preferences.plannerModel); hint="Exact model identifier from your provider" }
                                MaterialAlertDialogBuilder(this@ApiConnectionActivity).setTitle("Planner model")
                                    .setView(model).setNegativeButton("Cancel",null).setPositiveButton("Save") { _, _ ->
                                        val value=model.text.toString().trim()
                                        if(value.length in 1..160) { preferences.plannerModel=value; loadKeyPool() }
                                    }.show()
                            }
                            2 -> showAddPoolKeyDialog(preferences.plannerProvider)
                            4 -> {
                                val endpoint=EditText(this@ApiConnectionActivity).apply {
                                    setSingleLine(true);setText(preferences.plannerCustomEndpoint)
                                    hint="https://your-provider.example/v1";isSaveEnabled=false
                                }
                                MaterialAlertDialogBuilder(this@ApiConnectionActivity).setTitle("Custom HTTPS API root")
                                    .setView(endpoint).setNegativeButton("Cancel",null).setPositiveButton("Save") { _, _ ->
                                        runCatching { preferences.plannerCustomEndpoint=endpoint.text.toString() }
                                            .onFailure { Toast.makeText(this@ApiConnectionActivity,"Use HTTPS without credentials or query parameters.",Toast.LENGTH_LONG).show() }
                                        loadKeyPool()
                                    }.show()
                            }
                            3 -> {
                                val id = preferences.plannerProvider
                                MaterialAlertDialogBuilder(this@ApiConnectionActivity).setTitle("Remove configured planner keys?")
                                    .setNegativeButton("Cancel",null).setPositiveButton("Remove") { _, _ ->
                                        preferences.getApiKeyPool(id).forEach { preferences.removeApiKeyFromPool(id,it.key) }; loadKeyPool()
                                    }.show()
                            }
                        }
                    }.setNegativeButton("Close",null).show()
            }
        })
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

    private fun showAddPoolKeyDialog(providerId: String = "gemini") {
        val isElevenLabs = providerId == "elevenlabs"
        val providerLabel = when(providerId) { "elevenlabs" -> "ElevenLabs"; "openai_compatible" -> "OpenAI"; "openrouter" -> "OpenRouter"; "deepseek" -> "DeepSeek"; else -> "Gemini" }
        val input = EditText(this).apply {
            hint = getString(R.string.provider_key_hint,providerLabel)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            setSingleLine(true)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.provider_key_title,providerLabel))
            .setMessage(if (isElevenLabs) "The external voice router will use ElevenLabs when a key is available and fall back to Android TextToSpeech otherwise." else "Only the selected provider receives this key. It stays in the existing encrypted key pool.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val newKey = input.text.toString().trim()
                if (newKey.isNotBlank()) {
                    if (!preferences.canStoreApiKeySecurely) {
                        Snackbar.make(binding.root, "Encrypted storage is unavailable. The key was not added.", Snackbar.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    preferences.addApiKeyToPool(providerId, newKey)
                    loadKeyPool()
                    Toast.makeText(this, getString(R.string.provider_key_saved,providerLabel), Toast.LENGTH_SHORT).show()
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
            preferences.independentModelReviewsEnabled = binding.switchIndependentReviews.isChecked
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
                    val url = "https://generativelanguage.googleapis.com/v1beta/models"
                    val request = Request.Builder()
                        .url(url)
                        .header("x-goog-api-key", apiKey)
                        .build()
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
                binding.tvLiveStatus.text = "API KEY ACCEPTED • LIVE NOT TESTED"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                Snackbar.make(binding.root, "Model listing accepted the key (${ping}ms). Live voice has not been tested.", Snackbar.LENGTH_LONG).show()
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

        if (key.isNotEmpty() && !preferences.canStoreApiKeySecurely) {
            Snackbar.make(binding.root, "Encrypted API-key storage is unavailable. The key was not saved.", Snackbar.LENGTH_LONG).show()
            return
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
