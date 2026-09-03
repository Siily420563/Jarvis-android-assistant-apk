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
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executor

import com.example.perception.ScreenSerializer
import com.example.perception.ScreenState
import com.example.perception.UiElement

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

        // Visual overlay highlight listener (used by floating HUD)
        var onElementHighlighted: ((Rect?) -> Unit)? = null
    }

    @Volatile
    var currentForegroundPackage: String = ""
        private set

    @Volatile
    var currentForegroundActivity: String = ""
        private set

    private var latestScreenState: ScreenState = ScreenState()
    private val activeNodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("SaraAccessibility", "Jarvis Accessibility Automation Engine ONLINE")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        if (!pkg.isNullOrBlank() && pkg != "com.example") {
            currentForegroundPackage = pkg
        }
        if (!cls.isNullOrBlank() && cls.contains("Activity")) {
            currentForegroundActivity = cls
        }
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

    // --- Enhanced Screen State Capture via ScreenSerializer ---

    @Synchronized
    fun captureScreenState(): ScreenState {
        val rootNodes = mutableListOf<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                for (w in winList) {
                    val root = w.root ?: continue
                    rootNodes.add(root)
                }
            }
        }
        if (rootNodes.isEmpty()) {
            rootInActiveWindow?.let { rootNodes.add(it) }
        }

        val state = ScreenSerializer.serialize(
            roots = rootNodes,
            packageName = currentForegroundPackage,
            activityName = currentForegroundActivity,
            isKeyboardOpen = isSoftKeyboardOpen()
        )
        latestScreenState = state

        // Re-index nodes for fast lookup
        activeNodeMap.clear()
        indexNodes(rootNodes, state)

        return state
    }

    private fun isSoftKeyboardOpen(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val wins = windows ?: return false
            return wins.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        return false
    }

    private fun indexNodes(roots: List<AccessibilityNodeInfo>, state: ScreenState) {
        // Map element IDs to live AccessibilityNodeInfo instances matching bounds & text
        for (root in roots) {
            indexRecursive(root, state)
        }
    }

    private fun indexRecursive(node: AccessibilityNodeInfo, state: ScreenState) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        val match = state.elements.firstOrNull { el ->
            el.bounds == rect && (text.isBlank() || el.text == text) && (desc.isBlank() || el.contentDescription == desc)
        }
        if (match != null && !activeNodeMap.containsKey(match.id)) {
            activeNodeMap[match.id] = node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            indexRecursive(child, state)
        }
    }

    fun getElementById(id: Int): UiElement? {
        return latestScreenState.elementLookup[id]
    }

    fun clickElementById(id: Int): Boolean {
        val element = getElementById(id)
        val rect = element?.bounds
        if (rect != null) {
            highlightElement(rect)
        }

        val liveNode = activeNodeMap[id]
        if (liveNode != null) {
            if (liveNode.isClickable && liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            var p = liveNode.parent
            while (p != null) {
                if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                p = p.parent
            }
        }

        // Coordinate click fallback
        if (rect != null && !rect.isEmpty) {
            return clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    fun longPressElementById(id: Int): Boolean {
        val element = getElementById(id) ?: return false
        val rect = element.bounds
        highlightElement(rect)
        return longPressCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    fun doubleTapElementById(id: Int): Boolean {
        val element = getElementById(id) ?: return false
        val rect = element.bounds
        highlightElement(rect)
        val ok1 = clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        android.os.SystemClock.sleep(120)
        val ok2 = clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        return ok1 || ok2
    }

    fun typeIntoElementById(id: Int, text: String, submit: Boolean = false): Boolean {
        val element = getElementById(id)
        val rect = element?.bounds
        if (rect != null) {
            highlightElement(rect)
        }

        val liveNode = activeNodeMap[id]
        var typed = false

        if (liveNode != null && (liveNode.isEditable || liveNode.isFocusable)) {
            liveNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            typed = liveNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        if (!typed && rect != null) {
            // Tap to focus first, then set text
            clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            android.os.SystemClock.sleep(200)
            typed = inputText(text)
        }

        if (submit) {
            android.os.SystemClock.sleep(250)
            // Look for send or search button
            clickNodeByText("Send") || clickNodeByText("Search") || clickNodeByText("Go") || clickNodeByText("खोजें")
        }

        return typed
    }

    fun highlightElement(rect: Rect) {
        onElementHighlighted?.invoke(rect)
        Handler(Looper.getMainLooper()).postDelayed({
            onElementHighlighted?.invoke(null)
        }, 800)
    }

    fun longPressCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 750))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipeCoordinates(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // --- Screen Node Hierarchy Dumper ---
    fun dumpScreenHierarchy(): List<ScreenNode> {
        val nodes = mutableListOf<ScreenNode>()
        
        // 1. Check all windows if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                for (w in winList) {
                    val root = w.root ?: continue
                    collectNodes(root, nodes)
                }
                if (nodes.isNotEmpty()) return nodes
            }
        }

        // 2. Fallback to active window
        val root = rootInActiveWindow
        if (root != null) {
            collectNodes(root, nodes)
        }
        return nodes
    }

    fun getScreenHierarchySummary(): String {
        val nodes = dumpScreenHierarchy()
        if (nodes.isEmpty()) return "Screen node hierarchy: Empty or Protected (FLAG_SECURE/Canvas)"
        val sb = StringBuilder()
        sb.append("Current Screen Nodes (${nodes.size} elements):\n")
        nodes.take(50).forEachIndexed { idx, n ->
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
        val cleanTarget = targetText.trim()
        if (cleanTarget.isBlank()) return false

        // 1. Try finding by text in root window
        val root = rootInActiveWindow
        if (root != null) {
            val matchedNodes = root.findAccessibilityNodeInfosByText(cleanTarget)
            for (node in matchedNodes) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    parent = parent.parent
                }
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
                    return clickCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
            }
        }

        // 2. Comprehensive search across all nodes dumped from all windows
        val allNodes = dumpScreenHierarchy()
        for (n in allNodes) {
            val matchText = n.text.contains(cleanTarget, ignoreCase = ignoreCase) ||
                    n.contentDescription.contains(cleanTarget, ignoreCase = ignoreCase) ||
                    n.viewId.contains(cleanTarget, ignoreCase = ignoreCase)
            if (matchText && !n.bounds.isEmpty && n.bounds.centerX() > 0 && n.bounds.centerY() > 0) {
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
