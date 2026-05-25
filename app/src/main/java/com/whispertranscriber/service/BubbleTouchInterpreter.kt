package com.whispertranscriber.service

sealed class BubbleTouchAction {
    data object TapRecord : BubbleTouchAction()
    data object TogglePanel : BubbleTouchAction()
    data class DragTo(val x: Int, val y: Int) : BubbleTouchAction()
}

class BubbleTouchInterpreter(
    private val moveSlopSquared: Float
) {
    private var down = false
    private var moved = false
    private var longPressTriggered = false
    private var initialViewX = 0
    private var initialViewY = 0
    private var initialRawX = 0f
    private var initialRawY = 0f

    fun onDown(viewX: Int, viewY: Int, rawX: Float, rawY: Float) {
        down = true
        moved = false
        longPressTriggered = false
        initialViewX = viewX
        initialViewY = viewY
        initialRawX = rawX
        initialRawY = rawY
    }

    fun onMove(rawX: Float, rawY: Float): BubbleTouchAction.DragTo? {
        if (!down || longPressTriggered) return null
        val dx = rawX - initialRawX
        val dy = rawY - initialRawY
        if (!moved && dx * dx + dy * dy <= moveSlopSquared) return null
        moved = true
        return BubbleTouchAction.DragTo(
            x = initialViewX + dx.toInt(),
            y = initialViewY + dy.toInt()
        )
    }

    fun onLongPress(): BubbleTouchAction.TogglePanel? {
        if (!down || moved || longPressTriggered) return null
        longPressTriggered = true
        return BubbleTouchAction.TogglePanel
    }

    fun onUp(): BubbleTouchAction.TapRecord? {
        if (!down) return null
        val shouldTap = !moved && !longPressTriggered
        reset()
        return if (shouldTap) BubbleTouchAction.TapRecord else null
    }

    fun onCancel() {
        reset()
    }

    private fun reset() {
        down = false
        moved = false
        longPressTriggered = false
    }
}
