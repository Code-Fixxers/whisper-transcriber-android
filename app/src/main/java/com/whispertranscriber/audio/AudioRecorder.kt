package com.whispertranscriber.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE_HIGH = 44100
        private const val SAMPLE_RATE_MEDIUM = 16000
        private const val SAMPLE_RATE_LOW = 8000
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private val audioBuffer = ByteArrayOutputStream()
    private var sampleRate = SAMPLE_RATE_MEDIUM

    fun getSampleRate(): Int = sampleRate

    fun startRecording(quality: String = "medium") {
        if (isRecording) return

        sampleRate = when (quality) {
            "high" -> SAMPLE_RATE_HIGH
            "low" -> SAMPLE_RATE_LOW
            else -> SAMPLE_RATE_MEDIUM
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioBuffer.reset()
            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        synchronized(audioBuffer) {
                            audioBuffer.write(buffer, 0, read)
                        }
                    }
                }
            }.apply {
                name = "AudioRecordThread"
                start()
            }

            Log.d(TAG, "Recording started at ${sampleRate}Hz")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", e)
        }
    }

    fun stopRecording(): ByteArray {
        isRecording = false
        recordingThread?.join(2000)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcmData = synchronized(audioBuffer) {
            audioBuffer.toByteArray()
        }

        return createWavFile(pcmData, sampleRate)
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    fun release() {
        isRecording = false
        recordingThread?.join(1000)
        audioRecord?.release()
        audioRecord = null
        audioBuffer.reset()
    }

    private fun createWavFile(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val buffer = ByteBuffer.allocate(44 + dataSize).apply {
            order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            put("RIFF".toByteArray())
            putInt(totalSize)
            put("WAVE".toByteArray())

            // fmt chunk
            put("fmt ".toByteArray())
            putInt(16) // chunk size
            putShort(1) // PCM format
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())

            // data chunk
            put("data".toByteArray())
            putInt(dataSize)
            put(pcmData)
        }

        return buffer.array()
    }
}
