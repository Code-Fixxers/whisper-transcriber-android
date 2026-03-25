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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed — we only use this service to commit text
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun commitText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    getExistingText(focusedNode) + text
                )
            }
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Text committed: $result")
            return result
        }
        Log.d(TAG, "No focused editable field found")
        return false
    }

    private fun getExistingText(node: AccessibilityNodeInfo): String {
        return node.text?.toString() ?: ""
    }
}
