package com.jarvis.assistant.automation.routing

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** User-entered HTTPS API root only. Credentials belong in the encrypted key pool. */
object PlannerEndpoint {
    fun normalize(value: String): String {
        require(value.length in 1..2048) { "Enter an HTTPS API endpoint." }
        val url=value.trim().toHttpUrlOrNull() ?: error("Invalid API endpoint.")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null) { "Use HTTPS without credentials, query parameters or fragments." }
        return url.toString().trimEnd('/').removeSuffix("/chat/completions")
    }
}
