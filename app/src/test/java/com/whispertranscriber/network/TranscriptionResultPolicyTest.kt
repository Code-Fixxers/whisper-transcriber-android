package com.whispertranscriber.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionResultPolicyTest {

    @Test
    fun blankSuccessfulLiveResultShouldRetryWithRest() {
        val result = TranscriptionResult(success = true, text = "")

        assertTrue(result.shouldRetryRestAfterLive())
    }

    @Test
    fun nonBlankSuccessfulLiveResultShouldNotRetryWithRest() {
        val result = TranscriptionResult(success = true, text = "hello")

        assertFalse(result.shouldRetryRestAfterLive())
    }

    @Test
    fun failedLiveResultShouldNotBeTreatedAsNoSpeech() {
        val result = TranscriptionResult(success = false, text = "", error = "socket failed")

        assertFalse(result.shouldRetryRestAfterLive())
    }
}
