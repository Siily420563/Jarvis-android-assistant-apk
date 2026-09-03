package com.example.perception

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.atomic.AtomicInteger

/**
 * Clean, compact representation of a single UI element on screen.
 * Short integer IDs (e.g., [1], [2]) allow the LLM to refer to elements cheaply and precisely.
 */
data class UiElement(
    val id: Int,
    val role: String,
    val text: String,
    val contentDescription: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean,
    val isPassword: Boolean,
    val viewId: String = "",
    val className: String = ""
) {
    fun toCompactLine(): String {
        val flags = buildList {
            if (isClickable) add("clickable")
            if (isEditable) add("editable")
            if (isScrollable) add("scrollable")
            if (isChecked) add("checked")
            if (isFocused) add("focused")
        }.joinToString(",")

        val displayedText = when {
            isPassword -> "[PASSWORD_PROTECTED]"
            text.isNotBlank() -> "\"$text\""
            else -> ""
        }

        val displayedDesc = if (contentDescription.isNotBlank() && contentDescription != text) {
            "desc=\"$contentDescription\""
        } else ""

        val idStr = if (viewId.isNotBlank() && viewId.contains(":id/")) {
            "id=${viewId.substringAfter(":id/")}"
        } else ""

        val flagStr = if (flags.isNotBlank()) "[$flags]" else ""
        val textStr = listOf(displayedText, displayedDesc, idStr).filter { it.isNotBlank() }.joinToString(" ")

        return "[$id] $role $flagStr $textStr bounds=(${bounds.centerX()},${bounds.centerY()})"
    }
}

/**
 * Snapshot of the current foreground screen state.
 */
data class ScreenState(
    val packageName: String = "",
    val activityName: String = "",
    val isKeyboardOpen: Boolean = false,
    val hasDialogOrPopup: Boolean = false,
    val isLoadingState: Boolean = false,
    val elements: List<UiElement> = emptyList(),
    val elementLookup: Map<Int, UiElement> = elements.associateBy { it.id }
) {
    fun toCompactRepresentation(maxElements: Int = 150): String {
        val sb = StringBuilder()
        sb.append("Current App: ").append(packageName.ifBlank { "Unknown" })
        if (activityName.isNotBlank()) {
            sb.append(" (").append(activityName.substringAfterLast(".")).append(")")
        }
        if (isKeyboardOpen) sb.append(" | Keyboard: OPEN")
        if (hasDialogOrPopup) sb.append(" | [DIALOG/POPUP DETECTED]")
        if (isLoadingState) sb.append(" | [LOADING INDICATOR VISIBLE]")
        sb.append("\n")

        if (elements.isEmpty()) {
            sb.append("[No interactive elements detected - screen may be empty, secure, or canvas-based]\n")
            return sb.toString()
        }

        sb.append("Visible UI Elements (${elements.size} total, showing top ${minOf(elements.size, maxElements)}):\n")
        elements.take(maxElements).forEach { el ->
            sb.append(el.toCompactLine()).append("\n")
        }
        return sb.toString()
    }
}

object ScreenSerializer {

    /**
     * Serializes one or more root AccessibilityNodeInfo trees into a clean ScreenState.
     */
    fun serialize(
        roots: List<AccessibilityNodeInfo>,
        packageName: String = "",
        activityName: String = "",
        isKeyboardOpen: Boolean = false
    ): ScreenState {
        val counter = AtomicInteger(1)
        val rawElements = mutableListOf<UiElement>()
        var dialogDetected = false
        var loadingDetected = false

        for (root in roots) {
            collectNodes(root, counter, rawElements)
        }

        // Deduplicate elements that have identical text/desc and overlapping bounds
        val deduplicated = deduplicateElements(rawElements)

        // Prioritize elements: actionable (clickable/editable) and readable text first
        val prioritized = deduplicated.sortedWith(
            compareByDescending<UiElement> { it.isEditable }
                .thenByDescending { it.isClickable }
                .thenByDescending { it.isFocused }
                .thenByDescending { it.text.isNotBlank() }
                .thenBy { it.bounds.top }
        )

        for (el in prioritized) {
            val lowerText = (el.text + " " + el.contentDescription).lowercase()
            if (lowerText.contains("loading") || lowerText.contains("please wait") || el.className.contains("ProgressBar")) {
                loadingDetected = true
            }
            if (el.className.contains("Dialog") || el.className.contains("AlertDialog") || lowerText.contains("allow ") || lowerText.contains("permission")) {
                dialogDetected = true
            }
        }

        return ScreenState(
            packageName = packageName,
            activityName = activityName,
            isKeyboardOpen = isKeyboardOpen,
            hasDialogOrPopup = dialogDetected,
            isLoadingState = loadingDetected,
            elements = prioritized
        )
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        counter: AtomicInteger,
        list: MutableList<UiElement>
    ) {
        if (node == null) return
        if (!node.isVisibleToUser) return

        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Filter out zero-size or off-screen invisible elements
        if (rect.width() <= 0 || rect.height() <= 0) return

        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isChecked = node.isChecked
        val isFocused = node.isFocused
        val isPassword = node.isPassword
        val viewId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""

        val isMeaningful = text.isNotBlank() || contentDesc.isNotBlank() ||
                isClickable || isEditable || isScrollable || isChecked || isFocused

        if (isMeaningful) {
            val role = determineRole(className, isClickable, isEditable, isScrollable, isChecked)
            val element = UiElement(
                id = counter.getAndIncrement(),
                role = role,
                text = text,
                contentDescription = contentDesc,
                bounds = rect,
                isClickable = isClickable,
                isEditable = isEditable,
                isScrollable = isScrollable,
                isChecked = isChecked,
                isFocused = isFocused,
                isPassword = isPassword,
                viewId = viewId,
                className = className
            )
            list.add(element)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectNodes(child, counter, list)
        }
    }

    private fun determineRole(
        className: String,
        isClickable: Boolean,
        isEditable: Boolean,
        isScrollable: Boolean,
        isChecked: Boolean
    ): String {
        return when {
            isEditable -> "input"
            className.contains("EditText", ignoreCase = true) -> "input"
            className.contains("Button", ignoreCase = true) -> "button"
            className.contains("CheckBox", ignoreCase = true) || className.contains("RadioButton", ignoreCase = true) -> "checkbox"
            className.contains("Switch", ignoreCase = true) || className.contains("ToggleButton", ignoreCase = true) -> "switch"
            className.contains("ImageView", ignoreCase = true) && isClickable -> "image_button"
            className.contains("ImageView", ignoreCase = true) -> "image"
            isScrollable -> "list"
            isClickable -> "button"
            else -> "text"
        }
    }

    private fun deduplicateElements(elements: List<UiElement>): List<UiElement> {
        val result = mutableListOf<UiElement>()
        for (el in elements) {
            val isDuplicate = result.any { existing ->
                existing.text == el.text &&
                        existing.contentDescription == el.contentDescription &&
                        existing.bounds == el.bounds
            }
            if (!isDuplicate) {
                result.add(el)
            }
        }
        return result
    }
}
