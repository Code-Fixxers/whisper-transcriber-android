package com.whispertranscriber.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestAuthTest {

    @Test
    fun addsBearerAuthorizationWhenApiKeyIsPresent() {
        val request = Request.Builder()
            .url("http://example.test")
            .withBearerAuth("  test-key  ")
            .build()

        assertEquals("Bearer test-key", request.header("Authorization"))
    }

    @Test
    fun omitsAuthorizationWhenApiKeyIsBlank() {
        val request = Request.Builder()
            .url("http://example.test")
            .withBearerAuth(" ")
            .build()

        assertNull(request.header("Authorization"))
    }
}
