package com.whispertranscriber.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavAudioParserTest {

    @Test
    fun parsesPcm16MonoWav() {
        val pcm = byteArrayOf(1, 0, 2, 0)
        val audio = WavAudioParser.parse(wavBytes(sampleRate = 24_000, channels = 1, pcm = pcm))

        assertEquals(24_000, audio.sampleRate)
        assertEquals(1, audio.channelCount)
        assertEquals(16, audio.bitsPerSample)
        assertArrayEquals(pcm, audio.pcm)
    }

    private fun wavBytes(sampleRate: Int, channels: Short, pcm: ByteArray): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = (channels * 2).toShort()
        return ByteBuffer.allocate(44 + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray())
            .putInt(36 + pcm.size)
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(channels)
            .putInt(sampleRate)
            .putInt(byteRate)
            .putShort(blockAlign)
            .putShort(16)
            .put("data".toByteArray())
            .putInt(pcm.size)
            .put(pcm)
            .array()
    }
}
