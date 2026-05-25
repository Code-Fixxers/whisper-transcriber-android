package com.whispertranscriber.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class TtsAudioPlayer {
    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var playbackId = 0

    suspend fun playWav(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val wav = WavAudioParser.parse(bytes)
        val channelMask = when (wav.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw IOException("Unsupported channel count: ${wav.channelCount}")
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            wav.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) throw IOException("AudioTrack buffer unavailable: $minBufferSize")

        val bufferSize = maxOf(minBufferSize, minOf(wav.pcm.size, minBufferSize * 4))
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(wav.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()

        val currentPlaybackId = synchronized(lock) {
            stopLocked()
            playbackId += 1
            audioTrack = track
            playbackId
        }

        Log.d(TAG, "Playing TTS WAV: ${wav.sampleRate} Hz, ${wav.channelCount} channel(s), ${wav.pcm.size} PCM bytes")
        try {
            track.play()
            var offset = 0
            while (offset < wav.pcm.size && isCurrentPlayback(track, currentPlaybackId)) {
                val written = track.write(wav.pcm, offset, minOf(16_384, wav.pcm.size - offset))
                if (written < 0) throw IOException("AudioTrack write failed: $written")
                if (written == 0) Thread.sleep(10) else offset += written
            }
            val totalFrames = wav.pcm.size / (wav.channelCount * 2)
            while (
                totalFrames > 0 &&
                track.playbackHeadPosition < totalFrames &&
                isCurrentPlayback(track, currentPlaybackId)
            ) {
                Thread.sleep(20)
            }
            if (isCurrentPlayback(track, currentPlaybackId)) {
                track.stop()
            }
        } finally {
            val releaseTrack = synchronized(lock) {
                if (audioTrack == track) {
                    audioTrack = null
                    true
                } else {
                    false
                }
            }
            if (releaseTrack) track.release()
        }
    }

    fun stop() {
        synchronized(lock) {
            playbackId += 1
            stopLocked()
        }
    }

    private fun stopLocked() {
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping TTS playback", e)
            } finally {
                track.release()
            }
        }
        audioTrack = null
    }

    private fun isCurrentPlayback(track: AudioTrack, id: Int): Boolean {
        return synchronized(lock) { audioTrack == track && playbackId == id }
    }

    private companion object {
        const val TAG = "TtsAudioPlayer"
    }
}
