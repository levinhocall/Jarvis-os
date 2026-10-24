package com.jarvis.assistant.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.model.Personality
import com.jarvis.assistant.databinding.ActivityPersonaBinding
import com.jarvis.assistant.util.ThemeManager

class PersonaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonaBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var currentSelectedPersona = GeminiConstants.PERSONALITY_ASSISTANT

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPersonaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadInitialState()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadInitialState() {
        val current = preferences.personality
        val initialPersona = Personality.fromId(current).id
        selectPersona(initialPersona, updateVoiceDefault = false)

        binding.etUserName.setText(preferences.userName.ifBlank { "NAZIB" })
        binding.etAssistantName.setText(preferences.assistantName.ifBlank { "Jarvis" })

        selectVoice(preferences.voice)
        updatePersonaPreview()
        binding.scrollPersona.post { binding.scrollPersona.scrollTo(0, 0) }
    }

    private fun setupListeners() {
        val previewWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePersonaPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        }

        binding.etUserName.addTextChangedListener(previewWatcher)
        binding.etAssistantName.addTextChangedListener(previewWatcher)

        // 1. Jarvis Pro
        binding.cardPersonaJarvis.setOnClickListener {
            selectPersona(Personality.JARVIS_PRO.id, updateVoiceDefault = true)
        }

        // 2. Girlfriend Mode
        binding.cardPersonaGirlfriend.setOnClickListener {
            selectPersona(Personality.GIRLFRIEND.id, updateVoiceDefault = true)
        }

        // 3. Boyfriend Mode
        binding.cardPersonaBoyfriend.setOnClickListener {
            selectPersona(Personality.BOYFRIEND.id, updateVoiceDefault = true)
        }

        // 4. NAZIB Mode
        binding.cardPersonaTechGpt.setOnClickListener {
            selectPersona(Personality.NAZIB.id, updateVoiceDefault = true)
        }

        // 5. Friendly Mode
        binding.cardPersonaFriendly.setOnClickListener {
            selectPersona(Personality.FRIENDLY.id, updateVoiceDefault = true)
        }

        // 6. Funny Mode
        binding.cardPersonaFunny.setOnClickListener {
            selectPersona(Personality.FUNNY.id, updateVoiceDefault = true)
        }

        // 7. Companion Mode
        binding.cardPersonaCompanion.setOnClickListener {
            selectPersona(Personality.COMPANION.id, updateVoiceDefault = true)
        }

        // 8. Teacher Mode
        binding.cardPersonaTeacher.setOnClickListener {
            selectPersona(Personality.TEACHER.id, updateVoiceDefault = true)
        }

        // 9. Developer Mode
        binding.cardPersonaDeveloper.setOnClickListener {
            selectPersona(Personality.DEVELOPER.id, updateVoiceDefault = true)
        }

        // 10. Foodie Mode
        binding.cardPersonaHungry.setOnClickListener {
            selectPersona(Personality.HUNGRY.id, updateVoiceDefault = true)
        }

        // 11. Nautanki Mode
        binding.cardPersonaNautanki.setOnClickListener {
            selectPersona(Personality.NAUTANKI.id, updateVoiceDefault = true)
        }

        // 12. Professional Mode
        binding.cardPersonaProfessional.setOnClickListener {
            selectPersona(Personality.PROFESSIONAL.id, updateVoiceDefault = true)
        }

        binding.btnActivatePersona.setOnClickListener {
            saveAndActivate()
        }
    }

    private fun selectPersona(personaKey: String, updateVoiceDefault: Boolean) {
        val persona = Personality.fromId(personaKey)
        currentSelectedPersona = persona.id

        binding.rbSelectJarvis.isChecked = (persona == Personality.JARVIS_PRO)
        binding.rbSelectGirlfriend.isChecked = (persona == Personality.GIRLFRIEND)
        binding.rbSelectBoyfriend.isChecked = (persona == Personality.BOYFRIEND)
        binding.rbSelectTechGpt.isChecked = (persona == Personality.NAZIB)
        binding.rbSelectFriendly.isChecked = (persona == Personality.FRIENDLY)
        binding.rbSelectFunny.isChecked = (persona == Personality.FUNNY)
        binding.rbSelectCompanion.isChecked = (persona == Personality.COMPANION)
        binding.rbSelectTeacher.isChecked = (persona == Personality.TEACHER)
        binding.rbSelectDeveloper.isChecked = (persona == Personality.DEVELOPER)
        binding.rbSelectHungry.isChecked = (persona == Personality.HUNGRY)
        binding.rbSelectNautanki.isChecked = (persona == Personality.NAUTANKI)
        binding.rbSelectProfessional.isChecked = (persona == Personality.PROFESSIONAL)

        val cardMappings = listOf(
            binding.cardPersonaJarvis to (persona == Personality.JARVIS_PRO),
            binding.cardPersonaGirlfriend to (persona == Personality.GIRLFRIEND),
            binding.cardPersonaBoyfriend to (persona == Personality.BOYFRIEND),
            binding.cardPersonaTechGpt to (persona == Personality.NAZIB),
            binding.cardPersonaFriendly to (persona == Personality.FRIENDLY),
            binding.cardPersonaFunny to (persona == Personality.FUNNY),
            binding.cardPersonaCompanion to (persona == Personality.COMPANION),
            binding.cardPersonaTeacher to (persona == Personality.TEACHER),
            binding.cardPersonaDeveloper to (persona == Personality.DEVELOPER),
            binding.cardPersonaHungry to (persona == Personality.HUNGRY),
            binding.cardPersonaNautanki to (persona == Personality.NAUTANKI),
            binding.cardPersonaProfessional to (persona == Personality.PROFESSIONAL)
        )

        cardMappings.forEach { (card, isSelected) ->
            card.alpha = if (isSelected) 1.0f else 0.85f
            card.elevation = if (isSelected) 6f else 0f
        }

        if (updateVoiceDefault) {
            selectVoice(persona.defaultVoice)
        }
        if (::binding.isInitialized) updatePersonaPreview()
    }

    private fun updatePersonaPreview() {
        if (!::binding.isInitialized) return
        val persona = Personality.fromId(currentSelectedPersona)
        val operator = binding.etUserName.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "NAZIB"
        val assistant = binding.etAssistantName.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "Jarvis"

        binding.tvPersonaPreview.text = "${persona.displayName} · ${getSelectedVoice()}"
        binding.tvPersonaGreeting.text = "Ready to help, $operator — from $assistant."
    }

    private fun selectVoice(voiceName: String) {
        when (voiceName.lowercase()) {
            "aoede" -> binding.rbVoiceAoede.isChecked = true
            "kore" -> binding.rbVoiceKore.isChecked = true
            "fenrir" -> binding.rbVoiceFenrir.isChecked = true
            "puck" -> binding.rbVoicePuck.isChecked = true
            "charon" -> binding.rbVoiceCharon.isChecked = true
            else -> binding.rbVoicePuck.isChecked = true
        }
    }

    private fun getSelectedVoice(): String {
        return when {
            binding.rbVoiceAoede.isChecked -> "Aoede"
            binding.rbVoiceKore.isChecked -> "Kore"
            binding.rbVoiceFenrir.isChecked -> "Fenrir"
            binding.rbVoiceCharon.isChecked -> "Charon"
            else -> "Puck"
        }
    }

    private fun saveAndActivate() {
        val userName = binding.etUserName.text?.toString()?.trim()
        preferences.userName = if (!userName.isNullOrBlank()) userName else "NAZIB"

        val assistantName = binding.etAssistantName.text?.toString()?.trim()
        preferences.assistantName = if (!assistantName.isNullOrBlank()) assistantName else "Jarvis"

        val voice = getSelectedVoice()
        preferences.voice = voice
        preferences.personality = currentSelectedPersona

        val persona = Personality.fromId(currentSelectedPersona)

        Toast.makeText(
            this,
            "Activated: ${persona.displayName} with $voice Voice",
            Toast.LENGTH_LONG
        ).show()

        finish()
    }
}
