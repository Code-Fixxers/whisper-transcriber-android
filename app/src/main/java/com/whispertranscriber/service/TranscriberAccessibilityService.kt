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

        fun beginRealtimeText(): Boolean {
            return instance?.beginRealtimeTextSession() ?: false
        }

        fun updateRealtimeText(text: String): Boolean {
            return instance?.updateRealtimeTextSession(text) ?: false
        }

        fun finishRealtimeText() {
            instance?.finishRealtimeTextSession()
        }

        fun isAvailable(): Boolean = instance != null
    }

    private var realtimeState: RealtimeTextEdit.State? = null

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
        val focusedNode = focusedEditableNode() ?: return false
        val edit = RealtimeTextEdit.apply(
            fieldText = focusedNode.text?.toString() ?: "",
            selectionStart = focusedNode.textSelectionStart,
            selectionEnd = focusedNode.textSelectionEnd,
            state = null,
            transcript = text
        ) ?: return false
        val result = applyTextEdit(focusedNode, edit)
        Log.d(TAG, "Text committed: $result")
        return result
    }

    private fun beginRealtimeTextSession(): Boolean {
        realtimeState = null
        return focusedEditableNode() != null
    }

    private fun updateRealtimeTextSession(text: String): Boolean {
        if (text.isBlank()) return true

        val focusedNode = focusedEditableNode() ?: return false
        val edit = RealtimeTextEdit.apply(
            fieldText = focusedNode.text?.toString() ?: "",
            selectionStart = focusedNode.textSelectionStart,
            selectionEnd = focusedNode.textSelectionEnd,
            state = realtimeState,
            transcript = text
        ) ?: run {
            realtimeState = null
            return false
        }

        val result = applyTextEdit(focusedNode, edit)
        if (result) {
            realtimeState = edit.state
        } else {
            realtimeState = null
        }
        return result
    }

    private fun finishRealtimeTextSession() {
        realtimeState = null
    }

    private fun focusedEditableNode(): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null || !focusedNode.isEditable) {
            Log.d(TAG, "No focused editable field found")
            return null
        }
        return focusedNode
    }

    private fun applyTextEdit(
        focusedNode: AccessibilityNodeInfo,
        edit: RealtimeTextEdit.Result
    ): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.fieldText)
        }
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (result) {
            val cursorArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, edit.selectionStart)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, edit.selectionEnd)
            }
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
        }

        return result
    }
}
