package com.jarvis.assistant.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import java.util.UUID

/**
 * Production implementation of ScreenDriver wrapping JarvisAccessibilityService.
 */
class ProductionScreenDriver : ScreenDriver {

    private fun getService(): JarvisAccessibilityService? = JarvisAccessibilityService.instance

    override fun capture(): ScreenObservation {
        val service = getService() ?: return ScreenObservation(
            observationId = UUID.randomUUID().toString(),
            packageName = "",
            windowClass = "",
            elements = emptyList(),
            screenSize = Pair(1080, 2400),
            windowStamp = System.currentTimeMillis()
        )

        val root = try { service.rootInActiveWindow } catch (_: Exception) { null }
        val elements = mutableListOf<UiElement>()
        val packageName = root?.packageName?.toString() ?: ""
        val className = root?.className?.toString() ?: ""

        if (root != null) {
            collectElements(root, elements)
        }

        val displayMetrics = service.resources.displayMetrics
        val screenSize = Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)

        return ScreenObservation(
            observationId = UUID.randomUUID().toString(),
            packageName = packageName,
            windowClass = className,
            elements = elements,
            screenSize = screenSize,
            windowStamp = System.currentTimeMillis()
        )
    }

    private fun collectElements(node: AccessibilityNodeInfo, list: MutableList<UiElement>) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val elementId = node.viewIdResourceName ?: "elem_${list.size}"

        list.add(
            UiElement(
                id = elementId,
                text = node.text?.toString() ?: "",
                contentDescription = node.contentDescription?.toString() ?: "",
                className = node.className?.toString() ?: "",
                bounds = RectF(rect),
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable
            )
        )

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                collectElements(child, list)
            }
        }
    }

    override fun clickElement(obs: ScreenObservation, elementId: String): DriverResult {
        if (obs.isStale(System.currentTimeMillis())) {
            return DriverResult.stale("Observation ${obs.observationId} is stale")
        }
        val service = getService() ?: return DriverResult.failed("Accessibility service unavailable")

        val target = obs.elements.find { it.id == elementId || it.text.equals(elementId, ignoreCase = true) }
            ?: return DriverResult.notFound("Element $elementId not in observation")

        val cx = target.bounds.centerX()
        val cy = target.bounds.centerY()
        val clicked = service.clickAt(cx, cy)
        return if (clicked) DriverResult.ok("Clicked element $elementId at ($cx, $cy)")
        else DriverResult.failed("Failed to dispatch click at ($cx, $cy)")
    }

    override fun gestureTap(x: Float, y: Float): DriverResult {
        val service = getService() ?: return DriverResult.failed("Accessibility service unavailable")
        val tapped = service.clickAt(x, y)
        return if (tapped) DriverResult.ok("Tapped at ($x, $y)")
        else DriverResult.failed("Failed to tap at ($x, $y)")
    }

    override fun setText(elementId: String, text: String): DriverResult {
        val service = getService() ?: return DriverResult.failed("Accessibility service unavailable")
        val root = try { service.rootInActiveWindow } catch (_: Exception) { null }
            ?: return DriverResult.failed("No active window to set text")

        val nodes = root.findAccessibilityNodeInfosByViewId(elementId)
        val target = nodes.firstOrNull() ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null) return DriverResult.notFound("Editable target not found for $elementId")

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val set = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return if (set) DriverResult.ok("Set text on $elementId")
        else DriverResult.failed("ACTION_SET_TEXT returned false")
    }

    override fun scroll(direction: String): DriverResult {
        val service = getService() ?: return DriverResult.failed("Accessibility service unavailable")
        val scrolled = service.performSmartScroll(direction, "medium")
        return if (scrolled) DriverResult.ok("Scrolled $direction")
        else DriverResult.failed("Scroll $direction failed")
    }

    override fun back(): DriverResult {
        val service = getService() ?: return DriverResult.failed("Accessibility service unavailable")
        val done = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return if (done) DriverResult.ok("Dispatched BACK")
        else DriverResult.failed("GLOBAL_ACTION_BACK failed")
    }

    override fun home(): DriverResult {
        val service = getService() ?: return DriverResult.failed("Accessibility service unavailable")
        val done = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        return if (done) DriverResult.ok("Dispatched HOME")
        else DriverResult.failed("GLOBAL_ACTION_HOME failed")
    }
}
