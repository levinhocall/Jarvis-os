package com.jarvis.assistant.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Lets JARVIS place phone calls by spoken contact name, raw phone number,
 * or 1-based index disambiguation (e.g. "Gufran Khan 4", "call the 2nd one").
 */
object ContactCaller {

    data class Contact(val name: String, val number: String, val type: String = "Mobile")

    fun hasContactsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    sealed class CallResult {
        data class Success(
            val contact: Contact,
            val indexUsed: Int? = null,
            val totalCandidates: Int? = null
        ) : CallResult()

        data class NoMatch(val query: String) : CallResult()

        data class MultipleMatches(
            val query: String,
            val matches: List<Contact>,
            val formattedCandidateList: String = ""
        ) : CallResult()

        data class IndexOutOfBounds(
            val query: String,
            val requestedIndex: Int,
            val matches: List<Contact>
        ) : CallResult()

        object MissingContactsPermission : CallResult()
        object MissingCallPermission : CallResult()
        object CallFailed : CallResult()
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Cleans common spoken prefixes and suffixes in English and Hinglish/Hindi.
     */
    fun cleanQueryString(raw: String): String {
        var q = raw.trim().lowercase(Locale.ROOT)
        val prefixes = listOf(
            "message ", "send message to ", "send whatsapp to ", "whatsapp ", "open contact ", "open ",
            "call ", "phone ", "dial ", "contact ", "to ", "my ", "please ", "karo ", "lagao ", "bhejo "
        )
        val suffixes = listOf(
            " ko message bhejo", " ko whatsapp karo", " ko whatsapp kro",
            " ko call karo", " ko call kro", " ko call lagao", " ko call laga do",
            " ko phone karo", " ko phone kro", " ko phone lagao", " ko phone laga do",
            " ko message karo", " ko message kro", " ko message", " ko whatsapp",
            " ko dial karo", " ko dial kro", " ko call", " ko phone",
            " phone karo", " phone kro", " call karo", " call kro", " phone lagao", " call lagao",
            " dial karo", " dial kro", " ke paas", " ko", " ji", " bhai", " bhaiya", " sir",
            " beta", " madam", " didi", " uncle", " aunty"
        )
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (q.startsWith(prefix)) {
                    q = q.removePrefix(prefix).trim()
                    changed = true
                }
            }
            for (suffix in suffixes) {
                if (q.endsWith(suffix)) {
                    q = q.removeSuffix(suffix).trim()
                    changed = true
                }
            }
        }
        return q
    }

    /**
     * Parses numeric indices, English words, ordinals, and Hinglish counting tokens.
     */
    fun parseIndexToken(token: String): Int? {
        val clean = token.lowercase(Locale.ROOT).trim()
        clean.toIntOrNull()?.let { if (it in 1..99) return it }

        val ordinalDigitMatch = Regex("^(\\d{1,2})(?:st|nd|rd|th)$").find(clean)
        if (ordinalDigitMatch != null) {
            val n = ordinalDigitMatch.groupValues[1].toIntOrNull()
            if (n != null && n in 1..99) return n
        }

        return when (clean) {
            // English Words & Ordinals
            "one", "first", "1st" -> 1
            "two", "second", "2nd" -> 2
            "three", "third", "3rd" -> 3
            "four", "fourth", "4th" -> 4
            "five", "fifth", "5th" -> 5
            "six", "sixth", "6th" -> 6
            "seven", "seventh", "7th" -> 7
            "eight", "eighth", "8th" -> 8
            "nine", "ninth", "9th" -> 9
            "ten", "tenth", "10th" -> 10

            // Hindi / Hinglish Ordinals & Words
            "pehla", "pehli", "first wala" -> 1
            "doosra", "dusra", "doosri", "dusri", "second wala" -> 2
            "teesra", "tisra", "teesri", "tisri", "third wala" -> 3
            "chautha", "chauthi", "fourth wala" -> 4
            "paanchva", "panchva", "paanchvi", "fifth wala" -> 5
            "chhattha", "chatha", "sixth wala" -> 6
            "saatva", "satva", "seventh wala" -> 7
            "aathva", "athva", "eighth wala" -> 8
            "nauva", "ninth wala" -> 9
            "dasva", "tenth wala" -> 10

            // Hindi Cardinal Numbers
            "ek" -> 1
            "do" -> 2
            "teen" -> 3
            "chaar", "char" -> 4
            "paanch", "panch" -> 5
            "chhe", "che" -> 6
            "saat" -> 7
            "aath" -> 8
            "nau" -> 9
            "das" -> 10
            else -> null
        }
    }

    /**
     * Extracts trailing or leading index from query.
     * Examples:
     *   "gufran khan 4" -> ("gufran khan", 4)
     *   "gufran khan 4th" -> ("gufran khan", 4)
     *   "gufran khan number 4" -> ("gufran khan", 4)
     *   "gufran khan fourth" -> ("gufran khan", 4)
     *   "gufran khan chautha" -> ("gufran khan", 4)
     *   "4th gufran khan" -> ("gufran khan", 4)
     */
    fun extractTrailingIndex(rawQuery: String): Pair<String, Int?> {
        val q = cleanQueryString(rawQuery)

        // 1. Phrased index: "gufran khan number 4", "gufran khan no. 4", "gufran khan contact 4"
        val phrasedRegex = Regex("^(.*?)\\s+(?:number|no\\.?|contact|call)\\s+([a-zA-Z0-9]+)$", RegexOption.IGNORE_CASE)
        val phrasedMatch = phrasedRegex.find(q)
        if (phrasedMatch != null) {
            val base = phrasedMatch.groupValues[1].trim()
            val token = phrasedMatch.groupValues[2].trim()
            val idx = parseIndexToken(token)
            if (idx != null && base.isNotBlank()) {
                return Pair(base, idx)
            }
        }

        // 2. Hinglish reverse phrased: "gufran khan 4 number", "gufran khan 4 wala"
        val hinglishSuffixRegex = Regex("^(.*?)\\s+([a-zA-Z0-9]+)\\s+(?:number|no\\.?|wala|waala)$", RegexOption.IGNORE_CASE)
        val hinglishSuffixMatch = hinglishSuffixRegex.find(q)
        if (hinglishSuffixMatch != null) {
            val base = hinglishSuffixMatch.groupValues[1].trim()
            val token = hinglishSuffixMatch.groupValues[2].trim()
            val idx = parseIndexToken(token)
            if (idx != null && base.isNotBlank()) {
                return Pair(base, idx)
            }
        }

        // 3. Trailing token index: "gufran khan 4", "gufran khan 4th", "gufran khan fourth"
        val trailingTokenRegex = Regex("^(.*?)\\s+([a-zA-Z0-9]+)$", RegexOption.IGNORE_CASE)
        val trailingMatch = trailingTokenRegex.find(q)
        if (trailingMatch != null) {
            val base = trailingMatch.groupValues[1].trim()
            val token = trailingMatch.groupValues[2].trim()
            val idx = parseIndexToken(token)
            if (idx != null && base.isNotBlank()) {
                return Pair(base, idx)
            }
        }

        // 4. Leading ordinal: "4th gufran khan", "fourth gufran khan", "chautha gufran khan"
        val leadingOrdinalRegex = Regex("^(?:the\\s+)?([a-zA-Z0-9]+)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val leadingMatch = leadingOrdinalRegex.find(q)
        if (leadingMatch != null) {
            val token = leadingMatch.groupValues[1].trim()
            val base = leadingMatch.groupValues[2].trim()
            val idx = parseIndexToken(token)
            if (idx != null && base.isNotBlank()) {
                return Pair(base, idx)
            }
        }

        return Pair(q, null)
    }

    /**
     * Looks up contacts stored on the device with case-insensitivity, digit preservation,
     * and fallback to index extraction.
     */
    fun findMatches(context: Context, spokenName: String): List<Contact> {
        val cleanQuery = cleanQueryString(spokenName)
        if (cleanQuery.isEmpty()) return emptyList()

        // 1. Literal search first
        val literalMatches = findMatchesInternal(context, cleanQuery)
        val exactLiteral = literalMatches.firstOrNull { it.name.equals(cleanQuery, ignoreCase = true) }
        if (exactLiteral != null) {
            return listOf(exactLiteral)
        }
        if (literalMatches.isNotEmpty()) {
            return literalMatches
        }

        // 2. Trailing index search if literal search produced nothing
        val (baseQuery, index) = extractTrailingIndex(spokenName)
        if (index != null && baseQuery.isNotBlank() && baseQuery != cleanQuery) {
            val baseMatches = findMatchesInternal(context, baseQuery)
            if (baseMatches.isNotEmpty()) {
                return if (index in 1..baseMatches.size) {
                    listOf(baseMatches[index - 1])
                } else {
                    baseMatches
                }
            }
        }

        return emptyList()
    }

    /**
     * Internal contact search query supporting literal numbers in contact names.
     */
    fun findMatchesInternal(context: Context, cleanQuery: String): List<Contact> {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        if (cleanQuery.isBlank()) return emptyList()

        val rawCleanQuery = cleanQuery.replace(Regex("[^a-z0-9]"), "")
        val allContacts = mutableListOf<Contact>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        try {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    val typeVal = if (typeIdx >= 0) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    val typeLabel = when (typeVal) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                        else -> "Mobile"
                    }
                    if (name.isNotBlank() && number.isNotBlank()) {
                        allContacts.add(Contact(name.trim(), number.trim(), typeLabel))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactCaller", "Contact query failed", e)
        }

        if (allContacts.isEmpty()) return emptyList()

        val distinctContacts = allContacts.distinctBy {
            val digits = it.number.filter(Char::isDigit)
            "${it.name.lowercase()}|$digits"
        }

        // Nickname / relationship aliases
        val aliases = when (cleanQuery) {
            "mom", "mum", "mummy", "mother", "maa", "ammi" -> setOf("mom", "mum", "mummy", "mother", "maa", "ammi", "mommy")
            "dad", "daddy", "father", "papa", "abbu" -> setOf("dad", "daddy", "father", "papa", "abbu", "baba")
            else -> emptySet()
        }
        if (aliases.isNotEmpty()) {
            val aliasMatches = distinctContacts.filter { contact ->
                contact.name.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                    .any { token -> token in aliases }
            }
            if (aliasMatches.isNotEmpty()) return aliasMatches
        }

        // 1. Exact match (case-insensitive) - handles "Gufran Khan 4", "Papa 2", "Home 1"
        val exactMatches = distinctContacts.filter { it.name.lowercase() == cleanQuery }
        if (exactMatches.isNotEmpty()) return exactMatches

        // 2. Exact alphanumeric match (ignoring spaces & punctuation)
        val alphaMatches = distinctContacts.filter {
            it.name.lowercase().replace(Regex("[^a-z0-9]"), "") == rawCleanQuery
        }
        if (alphaMatches.isNotEmpty()) return alphaMatches

        // 3. Contact full name starts with cleanQuery
        if (cleanQuery.length >= 2) {
            val startsWithMatches = distinctContacts.filter {
                it.name.lowercase().startsWith(cleanQuery)
            }
            if (startsWithMatches.isNotEmpty()) return startsWithMatches
        }

        // 4. Any word in contact name exactly equals cleanQuery (including single digits)
        val exactWordMatches = distinctContacts.filter { c ->
            val words = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            words.any { word -> word == cleanQuery }
        }
        if (exactWordMatches.isNotEmpty()) return exactWordMatches

        // 5. Any word in contact name starts with cleanQuery
        if (cleanQuery.length >= 3) {
            val wordStartsWithMatches = distinctContacts.filter { c ->
                val words = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
                words.any { word -> word.startsWith(cleanQuery) }
            }
            if (wordStartsWithMatches.isNotEmpty()) return wordStartsWithMatches
        }

        // 6. Query contains exact word matching a contact name word
        // CRITICAL FIX: Allow tokens of length >= 2 OR single digits (never drop '4', '2', '1')!
        val queryWords = cleanQuery.split(Regex("\\s+")).filter { it.length >= 2 || it.all(Char::isDigit) }
        if (queryWords.isNotEmpty()) {
            val queryWordMatches = distinctContacts.filter { c ->
                val contactWords = c.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 || it.all(Char::isDigit) }
                contactWords.any { cWord -> queryWords.contains(cWord) }
            }
            if (queryWordMatches.isNotEmpty()) return queryWordMatches
        }

        return emptyList()
    }

    /**
     * Places a phone call to the contact. Handles literal names ending in digits,
     * index extraction (e.g. "Gufran Khan 4"), explicit indices, and formatted disambiguation.
     */
    fun callContact(context: Context, spokenName: String, explicitIndex: Int? = null): CallResult {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return CallResult.MissingContactsPermission
        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) return CallResult.MissingCallPermission

        val rawInput = spokenName.trim()
        if (rawInput.isEmpty()) return CallResult.NoMatch(spokenName)

        // Raw telephone number direct dial
        val digitsOnly = rawInput.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 3 && rawInput.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
            return placeCall(context, Contact(rawInput, digitsOnly))
        }

        val cleanQuery = cleanQueryString(spokenName)

        // PASS 1: Literal Search First (e.g. check if contact is literally "Gufran Khan 4" or "Papa 2")
        val literalMatches = findMatchesInternal(context, cleanQuery)
        val singleExactLiteral = literalMatches.firstOrNull { it.name.equals(cleanQuery, ignoreCase = true) }
        if (singleExactLiteral != null && explicitIndex == null) {
            val sameNameDistinctNumbers = literalMatches.filter { it.name.equals(cleanQuery, ignoreCase = true) }
                .distinctBy { it.number.filter(Char::isDigit) }
            if (sameNameDistinctNumbers.size == 1) {
                return placeCall(context, singleExactLiteral)
            } else if (sameNameDistinctNumbers.size > 1) {
                return CallResult.MultipleMatches(spokenName, sameNameDistinctNumbers, formatCandidateList(sameNameDistinctNumbers))
            }
        }

        // PASS 2: Trailing Index Disambiguation
        val (baseQuery, parsedIndex) = extractTrailingIndex(spokenName)
        val effectiveIndex = explicitIndex ?: parsedIndex

        if (effectiveIndex != null && baseQuery.isNotBlank()) {
            val baseMatches = findMatchesInternal(context, baseQuery)
                .distinctBy { "${it.name.lowercase()}|${it.number.filter(Char::isDigit)}" }

            if (baseMatches.isNotEmpty()) {
                return if (effectiveIndex in 1..baseMatches.size) {
                    val targetContact = baseMatches[effectiveIndex - 1]
                    placeCall(context, targetContact, indexUsed = effectiveIndex, totalCandidates = baseMatches.size)
                } else {
                    CallResult.IndexOutOfBounds(baseQuery, effectiveIndex, baseMatches)
                }
            }
        }

        // PASS 3: Fallback on literal matches if no index was specified or index base produced no result
        if (literalMatches.isEmpty()) {
            return CallResult.NoMatch(spokenName)
        }

        val distinctByNumber = literalMatches.distinctBy { "${it.name.lowercase()}|${it.number.filter(Char::isDigit)}" }
        if (distinctByNumber.size == 1) {
            return placeCall(context, distinctByNumber.first())
        }

        return CallResult.MultipleMatches(spokenName, distinctByNumber, formatCandidateList(distinctByNumber))
    }

    private fun formatCandidateList(matches: List<Contact>): String {
        return matches.mapIndexed { idx, c ->
            val numDisplay = if (c.number.length >= 4) "...${c.number.takeLast(4)}" else c.number
            "${idx + 1}. ${c.name} (${c.type}: $numDisplay)"
        }.joinToString("\n")
    }

    private fun placeCall(
        context: Context,
        target: Contact,
        indexUsed: Int? = null,
        totalCandidates: Int? = null
    ): CallResult {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(target.number)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            CallResult.Success(target, indexUsed, totalCandidates)
        } catch (e: Exception) {
            android.util.Log.e("ContactCaller", "Failed to place call to ${target.name}", e)
            CallResult.CallFailed
        }
    }
}
