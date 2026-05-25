package com.whispertranscriber.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroTtsClientTest {

    @Test
    fun parseVoicesFromKokoroResponse() {
        val voices = KokoroVoiceParser.parse("""{"voices":["af_bella","bm_daniel"]}""")

        assertEquals(listOf("af_bella", "bm_daniel"), voices)
    }

    @Test
    fun speechRequestUsesWavAndClampsSpeed() {
        val json = JsonParser.parseString(
            KokoroSpeechRequest.json("hello", "af_bella", 9.0f)
        ).asJsonObject

        assertEquals("kokoro", json.get("model").asString)
        assertEquals("hello", json.get("input").asString)
        assertEquals("af_bella", json.get("voice").asString)
        assertEquals("wav", json.get("response_format").asString)
        assertEquals(4.0f, json.get("speed").asFloat)
        assertEquals(false, json.get("stream").asBoolean)
    }
}
