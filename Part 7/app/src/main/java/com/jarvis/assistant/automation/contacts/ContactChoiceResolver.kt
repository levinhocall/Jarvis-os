package com.jarvis.assistant.automation.contacts

import com.jarvis.assistant.util.ContactCaller
import java.util.Locale

/** Resolve only a unique explicit choice; arbitrary text must never select the first contact. */
object ContactChoiceResolver {
    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun words(value: String) = Regex("[\\p{L}\\p{M}\\p{N}]+")
        .findAll(normalize(value)).map { it.value }.toSet()

    fun choose(candidates: List<ContactInfo>, choice: String): ContactInfo? {
        val normalized = normalize(choice)
        if (normalized.isBlank()) return null
        val ordinal = normalized.removePrefix("the ").removePrefix("number ").removePrefix("option ")
            .removeSuffix(" one").trim()
        val index = ContactCaller.parseIndexToken(ordinal) ?: when (ordinal) {
            "पहला", "पहले" -> 1
            "दूसरा", "दूसरे" -> 2
            "तीसरा", "तीसरे" -> 3
            "चौथा" -> 4
            "पांचवां", "पाँचवां" -> 5
            else -> null
        }
        if (index != null) return candidates.getOrNull(index - 1)
        val exact = candidates.filter { normalize(it.name) == normalized }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        val requested = words(normalized)
        if (requested.isEmpty()) return null
        return candidates.filter { requested.all(words(it.name)::contains) }.singleOrNull()
    }
}
