package com.whispertranscriber.audio

import android.content.Context
import android.media.MediaPlayer
import java.io.File

class TtsAudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    fun playWav(bytes: ByteArray) {
        stop()
        val file = File(context.cacheDir, "tts/kokoro-test.wav").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                if (mediaPlayer == it) mediaPlayer = null
            }
            setOnErrorListener { player, _, _ ->
                player.release()
                if (mediaPlayer == player) mediaPlayer = null
                true
            }
            prepare()
            start()
        }
    }

    fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }
}
