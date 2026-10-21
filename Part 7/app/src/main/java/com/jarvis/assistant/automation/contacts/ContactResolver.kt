package com.jarvis.assistant.automation.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.min

class ContactResolver(private val context: Context) {

    companion object {
        private const val TAG = "ContactResolver"

        private val NICKNAME_MAP = mapOf(
            "mom" to listOf("mom", "mother", "mummy", "maa", "ammi"),
            "dad" to listOf("dad", "father", "papa", "daddy", "abbu"),
            "bro" to listOf("brother", "bro", "bhai"),
            "sis" to listOf("sister", "sis", "didi"),
            "wife" to listOf("wife", "biwi", "darling"),
            "husband" to listOf("husband", "hubby")
        )
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Resolves a contact query. Supports literal names with digits ("Gufran Khan 4", "Papa 2")
     * as well as trailing index disambiguation ("gufran khan 4" -> 4th Gufran Khan).
     */
    fun resolveContact(query: String, explicitIndex: Int? = null): ContactResolutionResult {
        if (!hasPermission()) {
            Log.w(TAG, "Cannot resolve contacts: READ_CONTACTS permission not granted")
            return ContactResolutionResult.PermissionDenied
        }

        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isBlank()) {
            return ContactResolutionResult.NoMatch
        }

        val allContacts = loadAllContacts()
        if (allContacts.isEmpty()) {
            return ContactResolutionResult.NoMatch
        }

        // PASS 1: Literal Exact Match First (handles literal "Gufran Khan 4", "Papa 2", "Home 1")
        val exactMatches = allContacts.filter { it.name.lowercase(Locale.ROOT) == cleanQuery }
        if (exactMatches.size == 1 && explicitIndex == null) {
            return ContactResolutionResult.SingleMatch(exactMatches.first())
        } else if (exactMatches.size > 1 && explicitIndex == null) {
            return ContactResolutionResult.MultipleMatches(exactMatches)
        }

        // PASS 2: Index Extraction Disambiguation
        val (baseQuery, parsedIndex) = extractIndexFromQuery(cleanQuery)
        val effectiveIndex = explicitIndex ?: parsedIndex

        if (effectiveIndex != null && baseQuery.isNotBlank()) {
            val baseCandidates = matchInternal(baseQuery, allContacts)
            if (baseCandidates.isNotEmpty()) {
                return if (effectiveIndex in 1..baseCandidates.size) {
                    ContactResolutionResult.SingleMatch(baseCandidates[effectiveIndex - 1])
                } else {
                    ContactResolutionResult.MultipleMatches(baseCandidates)
                }
            }
        }

        // PASS 3: Standard Resolution on Original Query
        val candidates = matchInternal(cleanQuery, allContacts)
        return when {
            candidates.size == 1 -> ContactResolutionResult.SingleMatch(candidates.first())
            candidates.size > 1 -> ContactResolutionResult.MultipleMatches(candidates)
            else -> ContactResolutionResult.NoMatch
        }
    }

    private fun matchInternal(cleanQuery: String, allContacts: List<ContactInfo>): List<ContactInfo> {
        // 1. Exact match
        val exact = allContacts.filter { it.name.lowercase(Locale.ROOT) == cleanQuery }
        if (exact.isNotEmpty()) return exact

        // 2. Nicknames
        val nicknameAliases = NICKNAME_MAP[cleanQuery]
        if (nicknameAliases != null) {
            val nickMatches = allContacts.filter { contact ->
                val lowerName = contact.name.lowercase(Locale.ROOT)
                nicknameAliases.any { alias -> lowerName.contains(alias) }
            }
            if (nickMatches.isNotEmpty()) return nickMatches
        }

        // 3. Word boundary / prefix match (preserving digits)
        val wordMatches = allContacts.filter { contact ->
            val lowerName = contact.name.lowercase(Locale.ROOT)
            val tokens = lowerName.split(" ", ".", "_", "-").filter { it.isNotBlank() }
            tokens.any { token -> token == cleanQuery || token.startsWith(cleanQuery) }
        }
        if (wordMatches.isNotEmpty()) return wordMatches

        // 4. Fuzzy Levenshtein match
        val scored = allContacts.map { contact ->
            val score = calculateSimilarity(cleanQuery, contact.name.lowercase(Locale.ROOT))
            Pair(contact, score)
        }.filter { it.second >= 0.70 }
            .sortedByDescending { it.second }

        if (scored.isNotEmpty()) {
            if (scored.size == 1 || (scored.first().second - (scored.getOrNull(1)?.second ?: 0f) > 0.15f)) {
                return listOf(scored.first().first)
            }
            return scored.take(5).map { it.first }
        }

        return emptyList()
    }

    private fun extractIndexFromQuery(query: String): Pair<String, Int?> {
        val trailingRegex = Regex("^(.*?)\\s+(?:number\\s+|no\\.?\\s+)?([a-zA-Z0-9]+)$", RegexOption.IGNORE_CASE)
        val match = trailingRegex.find(query) ?: return Pair(query, null)
        val base = match.groupValues[1].trim()
        val token = match.groupValues[2].trim().lowercase(Locale.ROOT)

        val idx = token.toIntOrNull()
            ?: Regex("^(\\d{1,2})(?:st|nd|rd|th)$").find(token)?.groupValues?.get(1)?.toIntOrNull()
            ?: when (token) {
                "one", "first", "1st", "pehla", "ek" -> 1
                "two", "second", "2nd", "doosra", "dusra", "do" -> 2
                "three", "third", "3rd", "teesra", "teen" -> 3
                "four", "fourth", "4th", "chautha", "chaar", "char" -> 4
                "five", "fifth", "5th", "paanchva", "paanch" -> 5
                "six", "sixth", "6th", "chhattha", "chhe" -> 6
                "seven", "seventh", "7th", "saatva", "saat" -> 7
                "eight", "eighth", "8th", "aathva", "aath" -> 8
                "nine", "ninth", "9th", "nauva", "nau" -> 9
                "ten", "tenth", "10th", "dasva", "das" -> 10
                else -> null
            }

        return if (idx != null && base.isNotBlank()) Pair(base, idx) else Pair(query, null)
    }

    private fun loadAllContacts(): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
        )

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val keyIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val seenEntries = mutableSetOf<String>()

                while (it.moveToNext()) {
                    val id = if (idIdx >= 0) it.getString(idIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                    val number = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""
                    val lookupKey = if (keyIdx >= 0) it.getString(keyIdx) ?: "" else ""

                    val digitsOnly = number.replace(Regex("[^0-9]"), "")
                    val cleanNum = when {
                        digitsOnly.length == 10 -> "91$digitsOnly"
                        digitsOnly.length == 11 && digitsOnly.startsWith("0") -> "91${digitsOnly.substring(1)}"
                        else -> digitsOnly
                    }
                    val signature = "${name.lowercase()}|$cleanNum"
                    if (name.isNotBlank() && cleanNum.isNotBlank() && !seenEntries.contains(signature)) {
                        seenEntries.add(signature)
                        contacts.add(ContactInfo(id = id, name = name, phoneNumber = cleanNum, lookupKey = lookupKey))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts: ${e.message}", e)
        }

        return contacts
    }

    private fun calculateSimilarity(s1: String, s2: String): Float {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        if (longer.isEmpty()) return 1.0f

        val dist = levenshteinDistance(longer, shorter)
        return (longer.length - dist).toFloat() / longer.length.toFloat()
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        var cost = IntArray(lhs.length + 1) { it }
        var newCost = IntArray(lhs.length + 1) { 0 }

        for (i in 1..rhs.length) {
            newCost[0] = i
            for (j in 1..lhs.length) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhs.length]
    }
}
