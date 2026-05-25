package com.whispertranscriber.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeTextEditTest {

    @Test
    fun firstUpdateInsertsTranscriptAtCursor() {
        val result = RealtimeTextEdit.apply(
            fieldText = "hello  world",
            selectionStart = 6,
            selectionEnd = 6,
            state = null,
            transcript = "brave"
        )

        assertEquals("hello brave world", result?.fieldText)
        assertEquals(11, result?.selectionStart)
        assertEquals(RealtimeTextEdit.State(start = 6, text = "brave"), result?.state)
    }

    @Test
    fun laterUpdateReplacesPreviousTranscriptInsteadOfAppending() {
        val first = RealtimeTextEdit.apply(
            fieldText = "",
            selectionStart = 0,
            selectionEnd = 0,
            state = null,
            transcript = "hello wor"
        )!!

        val second = RealtimeTextEdit.apply(
            fieldText = first.fieldText,
            selectionStart = first.selectionStart,
            selectionEnd = first.selectionEnd,
            state = first.state,
            transcript = "hello world"
        )

        assertEquals("hello world", second?.fieldText)
        assertEquals(11, second?.selectionStart)
        assertEquals(RealtimeTextEdit.State(start = 0, text = "hello world"), second?.state)
    }

    @Test
    fun firstUpdateReplacesCurrentSelection() {
        val result = RealtimeTextEdit.apply(
            fieldText = "hello old world",
            selectionStart = 6,
            selectionEnd = 9,
            state = null,
            transcript = "new"
        )

        assertEquals("hello new world", result?.fieldText)
        assertEquals(9, result?.selectionStart)
        assertEquals(RealtimeTextEdit.State(start = 6, text = "new"), result?.state)
    }

    @Test
    fun updateFailsWhenPreviousTranscriptNoLongerMatchesFieldText() {
        val result = RealtimeTextEdit.apply(
            fieldText = "user changed the field",
            selectionStart = 22,
            selectionEnd = 22,
            state = RealtimeTextEdit.State(start = 0, text = "hello wor"),
            transcript = "hello world"
        )

        assertNull(result)
    }
}
