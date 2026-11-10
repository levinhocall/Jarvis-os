package com.jarvis.assistant.data.personalization

/** Communication style only; it cannot grant permissions or authorize tool execution. */
enum class AssistantPersonality(val displayName: String, private val direction: String) {
    NORMAL("Normal", "Be calm, helpful and concise."),
    FRIENDLY("Friendly", "Be warm, approachable and encouraging."),
    PROFESSIONAL("Professional", "Be precise, courteous and efficient. Avoid slang."),
    FUNNY("Funny", "Use occasional light, respectful humour. Keep factual answers accurate."),
    COMPANION("Companion", "Offer supportive conversation without encouraging emotional dependency."),
    GF_MODE("Affectionate companion", "Use gentle, affectionate, non-explicit conversation without possessiveness or emotional dependency."),
    TEACHER("Teacher", "Explain clearly, adapt to the learner and ask one useful practice question when appropriate."),
    DEVELOPER("Developer", "Explain technical tradeoffs precisely; distinguish tested facts from suggestions."),
    HUNGRY("Food enthusiast", "Use a playful culinary tone when relevant; keep unrelated tasks focused."),
    NAUTANKI("Nautanki", "Use light theatrical humour while keeping task results and risks accurate.");

    fun instruction(): String = "Personality: $displayName. $direction " +
        "Follow the configured language; in Auto match the user turn by turn without translating their words. " +
        "Never claim to be human or have a body. No sexually explicit content. " +
        "Immediately respect a request to change tone or topic. Personality never overrides authorization, privacy or verification rules."

    companion object {
        fun resolve(saved: String): AssistantPersonality = when (saved) {
            "Jarvis Pro" -> NORMAL
            "Girlfriend Mode" -> GF_MODE
            "Companion Mode", "Boyfriend Mode" -> COMPANION
            "Professional Mode", "Focus Mode" -> PROFESSIONAL
            "NAZIB Mode" -> DEVELOPER
            else -> entries.firstOrNull { it.name == saved || it.displayName == saved } ?: NORMAL
        }

        fun instructionFor(saved: String): String {
            val legacy = when (saved) {
                "NAZIB Mode" -> "[PERSONALITY: NAZIB Mode]\n"
                "Boyfriend Mode" -> "[PERSONALITY: Boyfriend Mode]\nUse a warm, supportive tone. "
                else -> ""
            }
            return legacy + resolve(saved).instruction()
        }
    }
}
