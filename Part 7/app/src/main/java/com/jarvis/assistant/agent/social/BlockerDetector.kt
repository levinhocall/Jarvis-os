package com.jarvis.assistant.agent.social

import com.jarvis.assistant.agent.Blocker
import com.jarvis.assistant.agent.ScreenObservation

object BlockerDetector {

    private val LOGIN_KEYWORDS = listOf("log in", "sign in", "password", "forgot password", "username or email")
    private val CAPTCHA_KEYWORDS = listOf("captcha", "verify you are human", "select all images", "security check")
    private val TWO_FACTOR_KEYWORDS = listOf("two-factor", "2-step", "security code", "enter code sent to")
    private val PERMISSION_KEYWORDS = listOf("allow permission", "grant access", "while using the app", "allow jarvis")

    fun detectBlocker(observation: ScreenObservation): Blocker? {
        val allText = observation.elements.map { (it.text + " " + it.contentDescription).lowercase() }

        if (allText.any { text -> CAPTCHA_KEYWORDS.any { text.contains(it) } }) {
            return Blocker.CAPTCHA
        }
        if (allText.any { text -> TWO_FACTOR_KEYWORDS.any { text.contains(it) } }) {
            return Blocker.TWO_FACTOR
        }
        if (allText.any { text -> LOGIN_KEYWORDS.any { text.contains(it) } }) {
            return Blocker.LOGIN
        }
        if (allText.any { text -> PERMISSION_KEYWORDS.any { text.contains(it) } }) {
            return Blocker.PERMISSION_PROMPT
        }
        return null
    }
}
