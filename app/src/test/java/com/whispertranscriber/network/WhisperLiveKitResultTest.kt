package com.whispertranscriber.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLiveKitResultTest {

    @Test
    fun parseMessageCombinesCommittedLinesAndBuffer() {
        val message = """
            {
              "lines": [
                {"speaker": "SPEAKER_00", "text": "hello", "start": 0.0, "end": 1.2},
                {"speaker": "SPEAKER_00", "text": "world", "start": 1.2, "end": 2.4}
              ],
              "buffer_transcription": "from android"
            }
        """.trimIndent()

        val result = WhisperLiveKitResultParser.parse(message)

        assertFalse(result.readyToStop)
        assertEquals("hello world from android", result.text)
    }

    @Test
    fun parseReadyToStopMarksFinalSignal() {
        val result = WhisperLiveKitResultParser.parse("""{"type":"ready_to_stop"}""")

        assertTrue(result.readyToStop)
        assertEquals("", result.text)
    }
}
