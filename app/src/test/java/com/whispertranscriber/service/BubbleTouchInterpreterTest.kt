package com.whispertranscriber.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubbleTouchInterpreterTest {

    @Test
    fun longPressThenReleaseDoesNotTriggerRecordingTap() {
        val interpreter = BubbleTouchInterpreter(moveSlopSquared = 100f)

        interpreter.onDown(viewX = 10, viewY = 20, rawX = 100f, rawY = 200f)

        assertEquals(BubbleTouchAction.TogglePanel, interpreter.onLongPress())
        assertNull(interpreter.onUp())
    }

    @Test
    fun tapWithoutLongPressTriggersRecordingTap() {
        val interpreter = BubbleTouchInterpreter(moveSlopSquared = 100f)

        interpreter.onDown(viewX = 10, viewY = 20, rawX = 100f, rawY = 200f)

        assertEquals(BubbleTouchAction.TapRecord, interpreter.onUp())
    }

    @Test
    fun dragCancelsLongPressAndReturnsNewPosition() {
        val interpreter = BubbleTouchInterpreter(moveSlopSquared = 100f)

        interpreter.onDown(viewX = 10, viewY = 20, rawX = 100f, rawY = 200f)

        assertEquals(BubbleTouchAction.DragTo(x = 30, y = 45), interpreter.onMove(rawX = 120f, rawY = 225f))
        assertNull(interpreter.onLongPress())
        assertNull(interpreter.onUp())
    }
}
