package com.whispertranscriber.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder(private val context: Context) {

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
    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int? = null
    private var communicationDeviceActive = false
    private var bluetoothScoStarted = false

    fun getSampleRate(): Int = sampleRate

    @SuppressLint("MissingPermission")
    fun startRecording(quality: String = "medium", onPcmChunk: ((ByteArray) -> Unit)? = null) {
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
            val preferredInput = prepareAudioRouting()
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(audioFormat)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                restoreAudioRouting()
                return
            }

            preferredInput?.let { device ->
                val routed = audioRecord?.setPreferredDevice(device) == true
                Log.d(TAG, "Preferred input ${device.productName} (${device.type}) set: $routed")
            }

            audioBuffer.reset()
            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        synchronized(audioBuffer) {
                            audioBuffer.write(chunk)
                        }
                        onPcmChunk?.invoke(chunk)
                    }
                }
            }.apply {
                name = "AudioRecordThread"
                start()
            }

            Log.d(TAG, "Recording started at ${sampleRate}Hz")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", e)
            restoreAudioRouting()
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed to start", e)
            restoreAudioRouting()
        }
    }

    fun stopRecording(): ByteArray {
        isRecording = false
        recordingThread?.join(2000)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        restoreAudioRouting()

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
        restoreAudioRouting()
        audioBuffer.reset()
    }

    @SuppressLint("MissingPermission")
    private fun prepareAudioRouting(): AudioDeviceInfo? {
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        audioManager = manager
        previousAudioMode = manager.mode
        try {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enter communication audio mode", e)
        }

        val inputDevices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val preferred = AudioInputDeviceSelector.choosePreferredInput(
            inputDevices.map { device ->
                AudioInputDevice(
                    id = device.id,
                    type = device.type,
                    name = device.productName?.toString().orEmpty()
                )
            }
        )
        val preferredInput = preferred?.let { selected ->
            inputDevices.firstOrNull { it.id == selected.id }
        }
        if (preferred != null) {
            Log.d(TAG, "Selected input ${preferred.name.ifBlank { preferred.id.toString() }} (${preferred.type})")
            routeCommunicationDevice(manager, preferred.type)
        }
        return preferredInput
    }

    @SuppressLint("MissingPermission")
    private fun routeCommunicationDevice(manager: AudioManager, preferredInputType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val communicationDevice = manager.availableCommunicationDevices.firstOrNull { device ->
                device.type == preferredInputType
            } ?: manager.availableCommunicationDevices.firstOrNull { device ->
                isBluetoothType(preferredInputType) && isBluetoothType(device.type)
            }
            if (communicationDevice != null) {
                try {
                    communicationDeviceActive = manager.setCommunicationDevice(communicationDevice)
                    Log.d(TAG, "Communication device ${communicationDevice.productName} (${communicationDevice.type}) set: $communicationDeviceActive")
                } catch (e: SecurityException) {
                    Log.w(TAG, "Bluetooth routing permission denied", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to set communication device", e)
                }
            }
        } else if (preferredInputType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            try {
                @Suppress("DEPRECATION")
                manager.startBluetoothSco()
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = true
                bluetoothScoStarted = true
                Log.d(TAG, "Bluetooth SCO routing requested")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start Bluetooth SCO", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreAudioRouting() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceActive) {
            try {
                manager.clearCommunicationDevice()
            } catch (e: SecurityException) {
                Log.w(TAG, "Bluetooth routing permission denied while clearing route", e)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to clear communication device", e)
            }
        }
        if (bluetoothScoStarted) {
            try {
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
            } catch (e: Exception) {
                Log.w(TAG, "Unable to stop Bluetooth SCO", e)
            }
        }
        previousAudioMode?.let { mode ->
            try {
                manager.mode = mode
            } catch (e: Exception) {
                Log.w(TAG, "Unable to restore audio mode", e)
            }
        }
        communicationDeviceActive = false
        bluetoothScoStarted = false
        previousAudioMode = null
        audioManager = null
    }

    private fun isBluetoothType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET

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
