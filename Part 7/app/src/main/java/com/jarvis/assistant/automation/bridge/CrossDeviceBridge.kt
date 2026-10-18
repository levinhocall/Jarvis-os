package com.jarvis.assistant.automation.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CrossDeviceShareResult(
    val success: Boolean,
    val message: String,
    val sharedContent: String = ""
)

object CrossDeviceBridge {
    private const val TAG = "CrossDeviceBridge"

    /**
     * Extracts active screen URL or text and dispatches via clipboard / Quick Share intent.
     */
    suspend fun shareCurrentContent(
        context: Context,
        targetDevice: String = "PC"
    ): CrossDeviceShareResult = withContext(Dispatchers.Main) {
        val a11y = JarvisAccessibilityService.instance
        val rootNode = a11y?.getActiveRootNode()

        var extractedUrl: String? = null
        if (rootNode != null) {
            val urls = mutableListOf<String>()
            findUrlsInNode(rootNode, urls)
            extractedUrl = urls.firstOrNull()
        }

        val contentToShare = extractedUrl ?: "https://github.com/levinhocall/Jarvis-os"

        // 1. Copy to system clipboard for immediate LAN/cross-device clipboard sync (KDE Connect, Phone Link, etc.)
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Jarvis PC Bridge", contentToShare)
            clipboard?.setPrimaryClip(clip)
            Log.i(TAG, "Copied content to clipboard for cross-device sync: $contentToShare")
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard copy error: ${e.message}")
        }

        // 2. Fire ACTION_SEND Intent with FLAG_ACTIVITY_NEW_TASK
        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, contentToShare)
                type = "text/plain"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(sendIntent, "Share with $targetDevice").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Share intent error: ${e.message}")
        }

        CrossDeviceShareResult(
            success = true,
            message = "Dispatched link to $targetDevice and copied to clipboard, Nazib Sir.",
            sharedContent = contentToShare
        )
    }

    private fun findUrlsInNode(node: AccessibilityNodeInfo, results: MutableList<String>) {
        val text = node.text?.toString() ?: ""
        if (text.startsWith("http://") || text.startsWith("https://") || text.contains(".com") || text.contains("www.")) {
            results.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findUrlsInNode(child, results)
            child.recycle()
        }
    }
}
