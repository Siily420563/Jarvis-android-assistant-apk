package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor

data class ScreenNode(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect
)

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        val isOnline: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("SaraAccessibility", "SARA Accessibility Automation Engine ONLINE")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track active window events if needed
    }

    override fun onInterrupt() {
        Log.w("SaraAccessibility", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    // --- Screen Node Hierarchy Dumper ---
    fun dumpScreenHierarchy(): List<ScreenNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<ScreenNode>()
        collectNodes(root, nodes)
        return nodes
    }

    fun getScreenHierarchySummary(): String {
        val nodes = dumpScreenHierarchy()
        if (nodes.isEmpty()) return "Screen node hierarchy: Empty or Protected (FLAG_SECURE/Canvas)"
        val sb = StringBuilder()
        sb.append("Current Screen Nodes (${nodes.size} elements):\n")
        nodes.take(40).forEachIndexed { idx, n ->
            val desc = if (n.contentDescription.isNotBlank()) " desc='${n.contentDescription}'" else ""
            val txt = if (n.text.isNotBlank()) " text='${n.text}'" else ""
            val id = if (n.viewId.isNotBlank()) " id='${n.viewId}'" else ""
            val flags = (if (n.isClickable) "[clickable]" else "") + (if (n.isEditable) "[input]" else "")
            sb.append("${idx + 1}. $flags$txt$desc$id bounds=(${n.bounds.centerX()},${n.bounds.centerY()})\n")
        }
        return sb.toString()
    }

    private fun collectNodes(node: AccessibilityNodeInfo, list: MutableList<ScreenNode>) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable

        if (text.isNotBlank() || contentDesc.isNotBlank() || isClickable || isEditable) {
            list.add(
                ScreenNode(
                    text = text,
                    contentDescription = contentDesc,
                    viewId = viewId,
                    className = className,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    bounds = rect
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, list)
        }
    }

    // --- Interaction Dispatchers ---

    fun clickNodeByText(targetText: String, ignoreCase: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleanTarget = targetText.trim()
        val matchedNodes = root.findAccessibilityNodeInfosByText(cleanTarget)

        for (node in matchedNodes) {
            if (node.isClickable) {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return true
            }
            // If parent is clickable (common pattern in Android button containers)
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                parent = parent.parent
            }

            // If ACTION_CLICK failed on node, try clicking center coordinate via gesture
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                return clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }

        // Fallback: search through all nodes
        val allNodes = dumpScreenHierarchy()
        for (n in allNodes) {
            if ((n.text.contains(cleanTarget, ignoreCase = ignoreCase) || n.contentDescription.contains(cleanTarget, ignoreCase = ignoreCase)) && !n.bounds.isEmpty) {
                return clickCoordinates(n.bounds.centerX().toFloat(), n.bounds.centerY().toFloat())
            }
        }
        return false
    }

    fun clickCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun inputText(text: String, targetHintOrId: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false

        // 1. Try finding input by hint / viewId if provided
        if (!targetHintOrId.isNullOrBlank()) {
            val nodes = root.findAccessibilityNodeInfosByText(targetHintOrId)
            for (node in nodes) {
                if (node.isEditable || node.isFocusable) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        return true
                    }
                }
            }
        }

        // 2. Try currently focused input
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true
            }
        }

        // 3. Search for any editable node in active window
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        fun findEditables(n: AccessibilityNodeInfo) {
            if (n.isEditable) allNodes.add(n)
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                findEditables(c)
            }
        }
        findEditables(root)
        if (allNodes.isNotEmpty()) {
            val target = allNodes.first()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        return false
    }

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun performQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun performScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else false
    }

    fun scrollDown(): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels * 0.75f
        val endY = displayMetrics.heightPixels * 0.25f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scrollUp(): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels * 0.25f
        val endY = displayMetrics.heightPixels * 0.75f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // --- Vision Screenshot Callback (API 30+) ---
    fun captureScreenBitmap(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            onResult(copy)
                        } catch (e: Exception) {
                            Log.e("SaraAccessibility", "Bitmap conversion error", e)
                            onResult(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("SaraAccessibility", "takeScreenshot failed code: $errorCode")
                        onResult(null)
                    }
                }
            )
        } else {
            onResult(null)
        }
    }
}
