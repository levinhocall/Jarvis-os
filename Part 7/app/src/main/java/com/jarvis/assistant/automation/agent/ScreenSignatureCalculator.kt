package com.jarvis.assistant.automation.agent

import android.graphics.Rect
import android.os.Build
import com.jarvis.assistant.automation.accessibility.traverseBoundedTree
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Calculates lightweight, stable screen signatures to detect state transitions,
 * scrolling progress, end-of-list conditions, and verify UI mutations.
 */
object ScreenSignatureCalculator {

    fun computeSignature(root: AccessibilityNodeInfo?): ScreenSignature {
        if (root == null) {
            return ScreenSignature(
                packageName = "",
                className = "",
                elementCount = 0,
                contentHash = 0L,
                primaryTexts = emptyList()
            )
        }

        val pkg = try { root.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
        val cls = try { root.className?.toString().orEmpty() } catch (_: Exception) { "" }
        val texts = mutableListOf<String>()
        var count = 0
        var hash = 1125899906842597L
        val tempRect = Rect()
        val result = traverseBoundedTree(root, maxNodes = 300,
            childCount = { it.childCount }, child = { n, i -> n.getChild(i) },
            release = { if (Build.VERSION.SDK_INT < 33) { @Suppress("DEPRECATION") it.recycle() } }) { node ->
            count++
            val password = node.isPassword
            val text = if (password) "" else node.text?.take(512)?.toString()?.trim().orEmpty()
            val desc = if (password) "" else node.contentDescription?.take(512)?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val clickable = node.isClickable; val editable = node.isEditable
            node.getBoundsInScreen(tempRect)
            if (text.isNotEmpty() && texts.size < 15) texts += text
            else if (desc.isNotEmpty() && texts.size < 15) texts += desc
            hash = 31 * hash + text.hashCode(); hash = 31 * hash + desc.hashCode()
            hash = 31 * hash + viewId.hashCode()
            hash = 31 * hash + if (clickable) 1 else 0
            hash = 31 * hash + if (editable) 2 else 0
            hash = 31 * hash + tempRect.centerX(); hash = 31 * hash + tempRect.centerY()
        }
        if (result.failed) return ScreenSignature("", "", 0, 0, emptyList())

        return ScreenSignature(
            packageName = pkg,
            className = cls,
            elementCount = count,
            contentHash = hash,
            primaryTexts = texts
        )
    }

    /**
     * Generates a stable key for an individual interactive element (e.g. for bulk tracking).
     */
    fun computeElementKey(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        return "${id}_${text}_${desc}_(${rect.left},${rect.top})"
    }
}
