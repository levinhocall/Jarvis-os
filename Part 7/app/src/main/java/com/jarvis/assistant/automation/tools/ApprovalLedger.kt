package com.jarvis.assistant.automation.tools

/**
 * App-side, user-owned approval for sensitive tool calls (messages, calls).
 *
 * The model must never be able to approve its own action. In particular a `confirmed=true` argument in
 * a tool call is model-authored and is therefore ignored. Approval is granted only when ALL hold:
 *  1. the same action (tool + identifying arguments) was already requested and left pending, and
 *  2. the user has since spoken a NEW turn (different from the turn that triggered the request), and
 *  3. that new turn is an affirmative reply and not a negative one, and
 *  4. the request has not expired.
 * The first sighting of an action never approves, even if the triggering utterance sounds affirmative
 * ("haan, bhej do hello") because that utterance is the instruction itself, not a confirmation of it.
 */
class ApprovalLedger(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    enum class Verdict { APPROVED, PENDING, DENIED }

    private class Pending(val createdAt: Long, val requestUtterance: String)

    private val pending = LinkedHashMap<String, Pending>()

    @Synchronized
    fun check(toolName: String, identity: List<String>, userUtterance: String): Verdict {
        val now = clock()
        purgeExpired(now)
        val key = keyOf(toolName, identity)
        val utterance = normalize(userUtterance)
        val existing = pending[key]

        if (existing == null) {
            if (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
            pending[key] = Pending(now, utterance)
            return Verdict.PENDING
        }

        // Same turn that created the request (or no new speech yet): cannot confirm yet.
        if (utterance.isEmpty() || utterance == existing.requestUtterance) return Verdict.PENDING

        return when {
            isNegative(utterance) -> {
                pending.remove(key)
                Verdict.DENIED
            }
            isAffirmative(utterance) -> {
                pending.remove(key)
                Verdict.APPROVED
            }
            else -> Verdict.PENDING
        }
    }

    @Synchronized
    fun clear() = pending.clear()

    @Synchronized
    fun pendingCount(): Int {
        purgeExpired(clock())
        return pending.size
    }

    private fun purgeExpired(now: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.createdAt > ttlMs) iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 120_000L
        private const val MAX_PENDING = 16

        private val nonWord = Regex("[^\\p{L}\\p{M}\\p{N}]+")

        // English, Hindi (Devanagari) and Hinglish. Matched as whole words / phrases.
        private val affirmative = listOf(
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "confirmed", "go ahead",
            "do it", "send it", "call him", "call her", "please do",
            "haan", "han", "haa", "ha ji", "haan ji", "ji haan", "theek hai", "thik hai", "kar do", "kardo",
            "bhej do", "bhejdo", "laga do", "lagao", "kar de", "bilkul",
            "हाँ", "हां", "जी", "ठीक है", "कर दो", "भेज दो", "लगा दो", "बिल्कुल"
        )
        private val negative = listOf(
            "no", "nope", "nah", "cancel", "stop", "don't", "dont", "do not", "never mind", "nevermind", "wait",
            "nahi", "nahin", "mat", "ruko", "rehne do", "rahne do", "chhodo", "chodo",
            "नहीं", "मत", "रुको", "रहने दो", "छोड़ो", "रद्द"
        )

        internal fun normalize(text: String): String =
            nonWord.replace(text.lowercase().replace("'", ""), " ").trim()

        private fun containsPhrase(normalized: String, phrase: String): Boolean {
            val target = nonWord.replace(phrase.lowercase().replace("'", ""), " ").trim()
            return " $normalized ".contains(" $target ")
        }

        internal fun isNegative(normalized: String): Boolean = negative.any { containsPhrase(normalized, it) }

        internal fun isAffirmative(normalized: String): Boolean = affirmative.any { containsPhrase(normalized, it) }

        internal fun keyOf(toolName: String, identity: List<String>): String =
            toolName + "|" + identity.joinToString("|") { normalize(it) }
    }
}

/** Process-wide ledger used by the conversation service. The model has no way to write to it. */
object ToolApprovalGate {
    private val ledger = ApprovalLedger()

    fun evaluate(toolName: String, identity: List<String>, userUtterance: String): ApprovalLedger.Verdict =
        ledger.check(toolName, identity, userUtterance)

    /** Text returned to the model when an action is not (yet) approved by the user. */
    fun instruction(verdict: ApprovalLedger.Verdict, action: String): String = when (verdict) {
        ApprovalLedger.Verdict.DENIED ->
            "The user declined, so $action was NOT performed. Do not retry unless the user asks again."
        else ->
            "User confirmation is required before $action. Ask the user to confirm out loud, wait for their " +
                "next reply, then call this tool again with identical arguments. The 'confirmed' argument is ignored."
    }

    fun reset() = ledger.clear()
}
