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
        val raw = "Product: $productName | Price: $price | Platform: $platform ${if (extraDetails.isNotBlank()) "| $extraDetails" else ""}"
        
        app.memoryRepository.saveMemory(
            rawText = raw,
            category = MemoryEntity.CATEGORY_NOTES,
            key = productName.lowercase(Locale.ROOT),
            value = "$price ($platform)"
        )
        Log.i(TAG, "Logged to Recall Core: $raw")
        "Saved $productName at $price on $platform to Recall Core, Nazib Sir."
    }

    suspend fun queryRecall(
        context: Context,
        query: String
    ): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? JarvisApp ?: return@withContext "Memory unavailable"
        val cleanQuery = query.lowercase(Locale.ROOT)
            .replace(Regex("(?i)^(maine|kya|kaunsa|kaunsi|recall|jarvis|dekha tha|batao|search kiya tha|kya tha)\\s*"), "")
            .trim()

        val results = app.memoryRepository.searchMemories(if (cleanQuery.isBlank()) query else cleanQuery)
        if (results.isEmpty()) {
            return@withContext "Recall Core mein '$cleanQuery' ke regarding koi saved record nahi mila, Nazib Sir."
        }

        val best = results.first()
        "Recall Core record: ${best.rawText}, Sir."
    }

    suspend fun saveFact(
        context: Context,
        fact: String
    ): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? JarvisApp ?: return@withContext "Memory unavailable"
        val clean = fact
            .replace(Regex("(?i)^(hey jarvis|jarvis|please)?\\s*(yaad rakhna ki|yaad rakhna|remember that|remember to|remember)\\s*"), "")
            .trim()

        if (clean.isBlank()) {
            return@withContext "Sir, mujhe samajh nahi aaya kya yaad rakhna hai."
        }

        app.memoryRepository.saveMemory(
            rawText = clean,
            category = MemoryEntity.CATEGORY_NOTES,
            key = clean.take(30),
            value = clean
        )
        "Recall Core updated: '$clean' yaad rakh liya hai, Nazib Sir."
    }
}
