package com.whispertranscriber.audio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class WavAudio(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val pcm: ByteArray
)

object WavAudioParser {
    fun parse(bytes: ByteArray): WavAudio {
        if (bytes.size < 44) throw IOException("WAV is too small")
        if (bytes.asciiAt(0) != "RIFF" || bytes.asciiAt(8) != "WAVE") {
            throw IOException("Unsupported WAV header")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var audioFormat = -1
        var channelCount = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataSize = -1

        while (offset + 8 <= bytes.size) {
            val chunkId = bytes.asciiAt(offset)
            val chunkSize = buffer.getInt(offset + 4)
            val chunkDataOffset = offset + 8
            if (chunkSize < 0 || chunkDataOffset + chunkSize > bytes.size) {
                throw IOException("Invalid WAV chunk size")
            }

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16) throw IOException("Invalid WAV fmt chunk")
                    audioFormat = buffer.getShort(chunkDataOffset).toInt()
                    channelCount = buffer.getShort(chunkDataOffset + 2).toInt()
                    sampleRate = buffer.getInt(chunkDataOffset + 4)
                    bitsPerSample = buffer.getShort(chunkDataOffset + 14).toInt()
                }
                "data" -> {
                    dataOffset = chunkDataOffset
                    dataSize = chunkSize
                }
            }

            offset = chunkDataOffset + chunkSize + (chunkSize % 2)
        }

        if (audioFormat != 1) throw IOException("Only PCM WAV is supported")
        if (channelCount !in 1..2) throw IOException("Unsupported WAV channel count: $channelCount")
        if (sampleRate <= 0) throw IOException("Invalid WAV sample rate")
        if (bitsPerSample != 16) throw IOException("Only 16-bit WAV is supported")
        if (dataOffset < 0 || dataSize < 0) throw IOException("WAV data chunk not found")

        return WavAudio(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            pcm = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        )
    }

    private fun ByteArray.asciiAt(offset: Int): String {
        return String(this, offset, 4, StandardCharsets.US_ASCII)
    }
}
