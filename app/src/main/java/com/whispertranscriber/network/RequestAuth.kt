package com.whispertranscriber.network

import okhttp3.Request

fun Request.Builder.withBearerAuth(apiKey: String): Request.Builder {
    val trimmed = apiKey.trim()
    if (trimmed.isNotEmpty()) {
        header("Authorization", "Bearer $trimmed")
    }
    return this
}
