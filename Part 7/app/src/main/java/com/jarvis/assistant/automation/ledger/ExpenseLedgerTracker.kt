package com.jarvis.assistant.automation.ledger

import android.content.Context
import android.util.Log
import com.jarvis.assistant.data.memory.RecallCoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class TransactionType {
    DEBIT,
    CREDIT,
    ORDER
}

data class LedgerEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: TransactionType,
    val amount: Double,
    val formattedAmount: String,
    val merchant: String,
    val source: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis()
)

object ExpenseLedgerTracker {
    private const val TAG = "ExpenseLedgerTracker"

    // In-memory ledger buffer for fast aggregations
    private val entries = CopyOnWriteArrayList<LedgerEntry>()

    private val FINANCIAL_PACKAGES = setOf(
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp",
        "com.sbi.upi",
        "com.msf.kbank.mobile",
        "com.icicibank.mobile",
        "com.axis.mobile",
        "com.snapwork.hdfc",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging"
    )

    fun isFinancialNotification(packageName: String, title: String, text: String): Boolean {
        if (FINANCIAL_PACKAGES.contains(packageName)) return true
        val combined = "$title $text".lowercase(Locale.ROOT)
        return combined.contains("debited") ||
                combined.contains("credited") ||
                combined.contains("spent") ||
                combined.contains("paid") ||
                combined.contains("sent rs") ||
                combined.contains("received rs") ||
                combined.contains("vpa") ||
                combined.contains("upi") ||
                combined.contains("order placed") ||
                combined.contains("wholesale") ||
                combined.contains("order confirmed")
    }

    /**
     * Parses notification text to extract monetary amounts and transaction metadata.
     */
    fun parseNotification(packageName: String, title: String, text: String): LedgerEntry? {
        val combined = "$title $text"
        val lower = combined.lowercase(Locale.ROOT)

        // Determine transaction type
        val type = when {
            lower.contains("credited") || lower.contains("received") || lower.contains("refund") -> TransactionType.CREDIT
            lower.contains("order placed") || lower.contains("order confirmed") || lower.contains("order delivered") -> TransactionType.ORDER
            lower.contains("debited") || lower.contains("paid") || lower.contains("spent") || lower.contains("sent") || lower.contains("txn of") -> TransactionType.DEBIT
            else -> TransactionType.DEBIT
        }

        // Extract monetary amount (₹, Rs, Rs., INR)
        val amountRegex = Regex("(?:₹|rs\\.?|inr)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
        val match = amountRegex.find(combined) ?: return null

        val rawAmountStr = match.groupValues[1].replace(",", "").trim()
        val amount = rawAmountStr.toDoubleOrNull() ?: return null
        val formattedAmount = "₹$rawAmountStr"

        // Extract merchant/recipient name
        val merchantRegex = Regex("(?:to|at|vpa|paid to|from|merchant)\\s+([a-zA-Z0-9\\s&]+?)(?:\\s+(?:on|using|ref|via|upi|date|bal|avl|account|a/c|card|with|\\.)|$)", RegexOption.IGNORE_CASE)
        val merchantMatch = merchantRegex.find(combined)
        val merchant = merchantMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: title.ifBlank { "Vendor" }

        val source = when {
            packageName.contains("phonepe") -> "PhonePe"
            packageName.contains("paytm") -> "Paytm"
            packageName.contains("paisa") -> "Google Pay"
            packageName.contains("messaging") -> "SMS"
            packageName.contains("amazon") -> "Amazon"
            packageName.contains("flipkart") -> "Flipkart"
            else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

        return LedgerEntry(
            type = type,
            amount = amount,
            formattedAmount = formattedAmount,
            merchant = merchant,
            source = source,
            rawText = combined
        )
    }

    /**
     * Processes an incoming notification and logs entry to Recall Core.
     */
    fun processNotification(
        packageName: String,
        title: String,
        text: String,
        context: Context
    ): LedgerEntry? {
        if (!isFinancialNotification(packageName, title, text)) return null

        val entry = parseNotification(packageName, title, text) ?: return null
        recordEntry(entry, context)
        return entry
    }

    /**
     * Records a ledger entry into in-memory store and persists to Recall Core.
     */
    fun recordEntry(entry: LedgerEntry, context: Context) {
        entries.add(entry)
        val timeStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
        val summary = "${entry.type.name}: ${entry.formattedAmount} to/from ${entry.merchant} via ${entry.source} at $timeStr"
        Log.i(TAG, "Recorded Ledger Entry: $summary")

        CoroutineScope(Dispatchers.IO).launch {
            RecallCoreManager.logProductResearch(
                context = context,
                productName = "[LEDGER] ${entry.merchant}",
                price = entry.formattedAmount,
                platform = entry.source,
                extraDetails = "${entry.type.name} | ${entry.rawText}"
            )
        }
    }

    /**
     * Generates a spoken summary of today's financial ledger transactions.
     * Responds to "Jarvis, aaj ka hisab batao" / "today's expenses".
     */
    fun getTodayLedgerSummary(context: Context): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis

        val todayEntries = entries.filter { it.timestamp >= startOfDay }

        if (todayEntries.isEmpty()) {
            return "Sir, aaj ke ledger mein abhi tak koi nayi UPI ya banking transactions detect nahi hui hain."
        }

        val totalDebits = todayEntries.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount }
        val debitCount = todayEntries.count { it.type == TransactionType.DEBIT }
        val totalCredits = todayEntries.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
        val creditCount = todayEntries.count { it.type == TransactionType.CREDIT }
        val orderCount = todayEntries.count { it.type == TransactionType.ORDER }

        val netSpend = totalDebits - totalCredits

        val parts = mutableListOf<String>()
        if (debitCount > 0) {
            parts.add("₹${"%.0f".format(totalDebits)} debit hua hai across $debitCount transactions")
        }
        if (creditCount > 0) {
            parts.add("₹${"%.0f".format(totalCredits)} credit receive hua hai")
        }
        if (orderCount > 0) {
            parts.add("$orderCount wholesale/e-commerce orders place hue hain")
        }

        val details = parts.joinToString(", aur ")
        return "Sir, aaj total $details. Net kharcha: ₹${"%.0f".format(netSpend)}."
    }

    fun clearLedger() {
        entries.clear()
    }
}
