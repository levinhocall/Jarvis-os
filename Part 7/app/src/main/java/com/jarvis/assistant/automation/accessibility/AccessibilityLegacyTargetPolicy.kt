package com.jarvis.assistant.automation.accessibility

import java.util.Locale

internal object AccessibilityLegacyTargetPolicy {
    fun whatsapp(packageName: String?) = packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b"
    fun youtube(packageName: String?) = packageName == "com.google.android.youtube" || packageName == "app.revanced.android.youtube"
    fun draftMatches(expected: String?, observed: String?): Boolean = expected.isNullOrBlank() ||
        expected.replace("\r\n", "\n").trim() == observed?.replace("\r\n", "\n")?.trim()
    fun callConfirmation(text: String, description: String): Boolean {
        val labels = setOf("call", "video call", "start call", "कॉल करें", "कॉल")
        return text.trim().lowercase(Locale.ROOT) in labels || description.trim().lowercase(Locale.ROOT) in labels
    }
    fun mayTapCall(acceptedTaps: Int) = acceptedTaps == 0
    fun mayConfirmCall(acceptedTaps: Int) = acceptedTaps == 1
}
