package com.jarvis.assistant.automation.mission

import java.util.Locale

/** A direct user instruction authorizes only this recipient and this message. */
class ExplicitMessageAuthorization private constructor(val recipient: String, val message: String) {
    fun matches(contact: String?, text: String?): Boolean =
        normalize(contact.orEmpty()) == recipient && normalize(text.orEmpty()) == message

    fun applyTo(mission: MissionDefinition): MissionDefinition {
        if(mission.status != MissionOverallStatus.WAITING_FOR_APPROVAL || mission.clarificationRequest != null) return mission
        if(mission.steps.any {it.status!=MissionStepStatus.PENDING}) return mission
        val send=mission.steps.singleOrNull {it.toolName=="whatsapp_send"} ?: return mission
        if(!matches(send.parameters["contact"] as? String,send.parameters["message"] as? String) ||
            mission.steps.any {it.toolName !in setOf("whatsapp_prepare","whatsapp_send")}) return mission
        return mission.copy(status=MissionOverallStatus.PLANNING,approvalState=MissionApprovalState.APPROVED,approvalPrompt=null)
    }

    companion object {
        private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        fun fromUtterance(utterance: String, recipient: String, message: String): ExplicitMessageAuthorization? {
            val who = normalize(recipient)
            val what = normalize(message)
            if (who.isBlank() || what.isBlank()) return null
            val command = normalize(utterance)
            val target = Regex.escape(who)
            val body = Regex.escape(what)
            val english = Regex("^(?:please )?(?:open whatsapp (?:and )?)?send (?:a |the )?(?:whatsapp )?(?:message )?(?:to )?$target (?:saying |that |: )?$body[.!?]?$")
            val hinglish = Regex("^(?:whatsapp (?:pe |par )?)?$target ko $body (?:bhejo|bhej do|send karo)[.!?]?$")
            return if (english.matches(command) || hinglish.matches(command)) ExplicitMessageAuthorization(who,what) else null
        }
    }
}
