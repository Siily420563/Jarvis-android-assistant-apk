package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
        Log.i("JarvisAccessibility", "J.A.R.V.I.S. Accessibility Neural Link ONLINE")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event monitoring if required
    }

    override fun onInterrupt() {
        Log.w("JarvisAccessibility", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun scrollDown(): Boolean {
        val path = Path().apply {
            moveTo(500f, 1500f)
            lineTo(500f, 500f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun scrollUp(): Boolean {
        val path = Path().apply {
            moveTo(500f, 500f)
            lineTo(500f, 1500f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeTextInFocusedInput(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
