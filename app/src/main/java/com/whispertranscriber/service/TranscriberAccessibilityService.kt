package com.whispertranscriber.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TranscriberAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TranscriberA11y"
        var instance: TranscriberAccessibilityService? = null
            private set

        fun pasteText(text: String): Boolean {
            return instance?.commitText(text) ?: false
        }

        fun isAvailable(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun commitText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null || !focusedNode.isEditable) {
            Log.d(TAG, "No focused editable field found")
            return false
        }

        // Get existing text and cursor position
        val existing = focusedNode.text?.toString() ?: ""
        val selStart = focusedNode.textSelectionStart
        val selEnd = focusedNode.textSelectionEnd

        // Insert at cursor position, or append if no valid cursor
        val newText = if (selStart >= 0 && selEnd >= 0 && selStart <= existing.length) {
            existing.substring(0, selStart) + text + existing.substring(selEnd.coerceAtMost(existing.length))
        } else {
            existing + text
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Move cursor to end of inserted text
        if (result) {
            val newCursorPos = if (selStart >= 0) selStart + text.length else newText.length
            val cursorArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
        }

        Log.d(TAG, "Text committed: $result")
        return result
    }
}
