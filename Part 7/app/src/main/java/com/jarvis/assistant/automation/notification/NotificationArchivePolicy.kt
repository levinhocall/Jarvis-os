package com.jarvis.assistant.automation.notification

/** Archive policy only; live notification tools retain their existing behavior. */
object NotificationArchivePolicy {
    fun keep(packageName: String, ownPackage: String, ongoing: Boolean, groupSummary: Boolean, hasText: Boolean): Boolean =
        packageName.isNotBlank() && packageName != ownPackage && !ongoing && !groupSummary && hasText
}
