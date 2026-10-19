package com.jarvis.assistant.data.memory

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object RecallCoreManager {
    private const val TAG = "RecallCoreManager"

    suspend fun logProductResearch(
        context: Context,
        productName: String,
        price: String,
        platform: String,
        extraDetails: String = ""
    ): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? JarvisApp ?: return@withContext "Memory unavailable"
        val user = app.preferences.userName.ifBlank { "Sir" }
        val raw = EpisodicVectorMemory.sanitizeForStorage(
            "Product: $productName | Price: $price | Platform: $platform ${if (extraDetails.isNotBlank()) "| $extraDetails" else ""}"
        )
        
        app.memoryRepository.saveMemory(
            rawText = raw,
            category = MemoryEntity.CATEGORY_NOTES,
            key = productName.lowercase(Locale.ROOT),
            value = "$price ($platform)"
        )
        Log.i(TAG, "Logged to Recall Core: $raw")
        "Saved $productName at $price on $platform to Recall Core, $user."
    }

    suspend fun queryRecall(
        context: Context,
        query: String
    ): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? JarvisApp ?: return@withContext "Memory unavailable"
        val user = app.preferences.userName.ifBlank { "Sir" }
        val cleanQuery = query.lowercase(Locale.ROOT)
            .replace(Regex("(?i)\\b(hey jarvis|jarvis|please|maine|kya|kaunsa|kaunsi|recall|dekha tha|batao|search kiya tha|kya tha)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        val vectorMatches = EpisodicVectorMemory.queryVectorRecall(context, if (cleanQuery.isBlank()) query else cleanQuery)
        if (vectorMatches.isNotEmpty()) {
            val top = vectorMatches.first()
            return@withContext "Recall Core record: ${top.record.text}, $user."
        }

        val results = app.memoryRepository.searchMemories(if (cleanQuery.isBlank()) query else cleanQuery)
        if (results.isEmpty()) {
            return@withContext "Recall Core mein '$cleanQuery' ke regarding koi saved record nahi mila, $user."
        }

        val best = results.first()
        "Recall Core record: ${best.rawText}, $user."
    }

    suspend fun saveFact(
        context: Context,
        fact: String
    ): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? JarvisApp ?: return@withContext "Memory unavailable"
        val user = app.preferences.userName.ifBlank { "Sir" }
        val clean = fact
            .replace(Regex("(?i)^(hey jarvis|jarvis|please)?\\s*(yaad rakhna ki|yaad rakhna|remember that|remember to|remember)\\s*"), "")
            .trim()

        if (clean.isBlank()) {
            return@withContext "Sir, mujhe samajh nahi aaya kya yaad rakhna hai."
        }

        val sanitized = EpisodicVectorMemory.sanitizeForStorage(clean)
        app.memoryRepository.saveMemory(
            rawText = sanitized,
            category = MemoryEntity.CATEGORY_NOTES,
            key = sanitized.take(30),
            value = sanitized
        )
        "Recall Core updated: '$sanitized' yaad rakh liya hai, $user."
    }
}
