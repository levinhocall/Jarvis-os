package com.jarvis.assistant.vision

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.data.memory.RecallCoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class TacticalScanResult(
    val success: Boolean,
    val summary: String,
    val detectedProduct: String? = null,
    val detectedPrice: String? = null,
    val detectedRating: String? = null,
    val platform: String = "App"
)

object TacticalScannerManager {
    private const val TAG = "TacticalScannerManager"

    suspend fun scanCurrentScreen(context: Context): TacticalScanResult = withContext(Dispatchers.Default) {
        val a11y = JarvisAccessibilityService.instance
        val rootNode = a11y?.getActiveRootNode()
        if (rootNode == null) {
            return@withContext TacticalScanResult(
                success = false,
                summary = "Screen analysis failed: accessibility tree is currently unavailable."
            )
        }

        val allTexts = mutableListOf<String>()
        collectAllText(rootNode, allTexts)

        val pkgName = rootNode.packageName?.toString()?.lowercase(Locale.ROOT) ?: ""
        val platform = when {
            pkgName.contains("flipkart") -> "Flipkart"
            pkgName.contains("amazon") -> "Amazon"
            pkgName.contains("chrome") || pkgName.contains("browser") -> "Web"
            pkgName.contains("whatsapp") -> "WhatsApp"
            pkgName.contains("youtube") -> "YouTube"
            else -> "Screen"
        }

        // Regex patterns
        val priceRegex = Regex("(?:₹|rs\\.?|inr)\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val ratingRegex = Regex("([1-5]\\.\\d)\\s*(?:★|stars?|out of 5)?", RegexOption.IGNORE_CASE)

        var detectedPrice: String? = null
        var detectedRating: String? = null
        val candidateTitles = mutableListOf<String>()

        for (text in allTexts) {
            val trimmed = text.trim()
            if (trimmed.length in 8..80 && !trimmed.contains("₹") && !trimmed.startsWith("http")) {
                candidateTitles.add(trimmed)
            }
            if (detectedPrice == null) {
                val match = priceRegex.find(trimmed)
                if (match != null) {
                    detectedPrice = "₹" + match.groupValues[1]
                }
            }
            if (detectedRating == null) {
                val match = ratingRegex.find(trimmed)
                if (match != null) {
                    detectedRating = match.groupValues[1] + "★"
                }
            }
        }

        val detectedProduct = candidateTitles.firstOrNull() ?: "Item on $platform"

        if (detectedPrice != null) {
            // Save to Recall Core asynchronously
            RecallCoreManager.logProductResearch(
                context = context,
                productName = detectedProduct,
                price = detectedPrice,
                platform = platform,
                extraDetails = if (detectedRating != null) "Rating: $detectedRating" else ""
            )

            val ratingText = if (detectedRating != null) " with a $detectedRating rating" else ""
            val verdict = "Tactical scan complete: detected $detectedProduct at $detectedPrice on $platform$ratingText. Logged to Recall Core, Nazib Sir."
            Log.i(TAG, verdict)
            return@withContext TacticalScanResult(
                success = true,
                summary = verdict,
                detectedProduct = detectedProduct,
                detectedPrice = detectedPrice,
                detectedRating = detectedRating,
                platform = platform
            )
        } else {
            val textPreview = candidateTitles.take(2).joinToString(", ")
            val verdict = "Screen scanned on $platform. Content includes: $textPreview. No immediate price tag detected, Sir."
            return@withContext TacticalScanResult(
                success = true,
                summary = verdict,
                detectedProduct = detectedProduct,
                platform = platform
            )
        }
    }

    private fun collectAllText(node: AccessibilityNodeInfo, collector: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) collector.add(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc != text) collector.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, collector)
            child.recycle()
        }
    }
}
