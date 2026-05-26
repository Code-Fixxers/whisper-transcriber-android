package com.whispertranscriber.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.whispertranscriber.MainActivity
import com.whispertranscriber.R
import com.whispertranscriber.audio.AudioRecorder
import com.whispertranscriber.audio.TtsAudioPlayer
import com.whispertranscriber.data.SettingsStore
import com.whispertranscriber.data.TranscriptionLog
import com.whispertranscriber.network.KokoroTtsClient
import com.whispertranscriber.network.WhisperApiClient
import com.whispertranscriber.network.WhisperLiveKitClient
import com.whispertranscriber.network.WhisperLiveKitSession
import com.whispertranscriber.network.WhisperServerDiscovery
import com.whispertranscriber.network.TranscriptionResult
import com.whispertranscriber.network.shouldRetryRestAfterLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlay"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.whispertranscriber.overlay.STOP"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var transcriptionLog: TranscriptionLog
    private lateinit var audioRecorder: AudioRecorder
    private val whisperClient = WhisperApiClient()
    private val liveKitClient = WhisperLiveKitClient()
    private val ttsClient = KokoroTtsClient()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var ttsAudioPlayer: TtsAudioPlayer

    private var bubbleView: View? = null
    private var expandedView: View? = null
    private var isRecording = false
    private var isExpanded = false
    private var transcriptionText = ""
    private var transcriptionJob: Job? = null
    private var liveKitSession: WhisperLiveKitSession? = null
    private var liveKitReady = false
    private var activeRecordIcon: ImageView? = null
    private var recordingCompletionStarted = false
    private var realtimeInsertionActive = false
    private var realtimeInsertionFailed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsStore = SettingsStore(this)
        transcriptionLog = TranscriptionLog(this)
        audioRecorder = AudioRecorder(this)
        ttsAudioPlayer = TtsAudioPlayer()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createBubbleView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        val bubbleSize = (56 * resources.displayMetrics.density).toInt()

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setBackgroundResource(R.drawable.bubble_background)
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(14, 14, 14, 14)
        }
        container.addView(icon)

        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val touchInterpreter = BubbleTouchInterpreter((touchSlop * touchSlop).toFloat())
        var longPressRunnable: Runnable? = null

        fun cancelLongPress() {
            longPressRunnable?.let { container.removeCallbacks(it) }
            longPressRunnable = null
        }

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchInterpreter.onDown(params.x, params.y, event.rawX, event.rawY)
                    cancelLongPress()
                    longPressRunnable = Runnable {
                        if (touchInterpreter.onLongPress() == BubbleTouchAction.TogglePanel) {
                            container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            toggleExpandedView()
                        }
                    }.also {
                        container.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val action = touchInterpreter.onMove(event.rawX, event.rawY)
                    if (action is BubbleTouchAction.DragTo) {
                        cancelLongPress()
                        params.x = action.x
                        params.y = action.y
                        windowManager.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    if (touchInterpreter.onUp() == BubbleTouchAction.TapRecord) {
                        onBubbleTapped(icon)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    touchInterpreter.onCancel()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(container, params)
        bubbleView = container
    }

    private fun onBubbleTapped(icon: ImageView) {
        if (isRecording) {
            stopRecording(icon)
        } else {
            startRecording(icon)
        }
    }

    private fun startRecording(icon: ImageView) {
        serviceScope.launch {
            val settings = settingsStore.settings.first()
            isRecording = true
            liveKitReady = false
            recordingCompletionStarted = false
            realtimeInsertionActive = false
            realtimeInsertionFailed = false
            activeRecordIcon = icon
            icon.setBackgroundResource(R.drawable.bubble_recording)
            icon.setImageResource(R.drawable.ic_stop)
            transcriptionText = "Discovering..."
            updateExpandedViewText()
            realtimeInsertionActive = TranscriberAccessibilityService.beginRealtimeText()
            val serverUrl = try {
                resolveServerUrl(settings.whisperServerUrl, settings.whisperServerPort)
            } catch (e: Exception) {
                isRecording = false
                activeRecordIcon = null
                realtimeInsertionActive = false
                TranscriberAccessibilityService.finishRealtimeText()
                icon.setBackgroundResource(R.drawable.bubble_background)
                icon.setImageResource(R.drawable.ic_mic)
                transcriptionText = "Error: ${e.message}"
                updateExpandedViewText()
                return@launch
            }
            liveKitSession = try {
                liveKitClient.connect(
                    serverUrl = serverUrl,
                    apiKey = settings.whisperApiKey,
                    onPartial = { partial ->
                        serviceScope.launch {
                            handleLivePartial(partial)
                        }
                    },
                    onReadyToStop = { result ->
                        serviceScope.launch {
                            finishRecording(result)
                        }
                    }
                ).also {
                    liveKitReady = true
                    transcriptionText = "Listening..."
                    updateExpandedViewText()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Live streaming unavailable, using REST fallback", e)
                realtimeInsertionActive = false
                TranscriberAccessibilityService.finishRealtimeText()
                null
            }
            if (!isRecording || recordingCompletionStarted) return@launch
            audioRecorder.startRecording(if (liveKitReady) "medium" else settings.audioQuality) { chunk ->
                liveKitSession?.sendPcm(chunk)
            }
            Log.d(TAG, "Recording started")
        }
    }

    private fun stopRecording(icon: ImageView) {
        activeRecordIcon = icon
        finishRecording()
    }

    private fun finishRecording(liveResult: TranscriptionResult? = null) {
        if (!isRecording || recordingCompletionStarted) return
        recordingCompletionStarted = true
        isRecording = false
        activeRecordIcon?.setBackgroundResource(R.drawable.bubble_background)
        activeRecordIcon?.setImageResource(R.drawable.ic_mic)

        val wavData = audioRecorder.stopRecording()
        Log.d(TAG, "Recording stopped, WAV size: ${wavData.size} bytes")

        transcriptionJob = serviceScope.launch {
            transcriptionText = "Transcribing..."
            updateExpandedViewText()

            val settings = settingsStore.settings.first()
            val startTime = System.currentTimeMillis()
            try {
                val serverUrl = resolveServerUrl(settings.whisperServerUrl, settings.whisperServerPort)
                val session = liveKitSession
                liveKitSession = null
                val result = liveResult?.let {
                    retryRestIfLiveResultIsBlank(serverUrl, wavData, settings.whisperApiKey, it)
                } ?: if (liveKitReady && session != null) {
                    try {
                        retryRestIfLiveResultIsBlank(serverUrl, wavData, settings.whisperApiKey, session.finish())
                    } catch (e: Exception) {
                        Log.w(TAG, "Live transcription finalization failed, retrying with REST", e)
                        whisperClient.transcribe(
                            serverUrl = serverUrl,
                            audioData = wavData,
                            apiKey = settings.whisperApiKey
                        )
                    }
                } else {
                    whisperClient.transcribe(
                        serverUrl = serverUrl,
                        audioData = wavData,
                        apiKey = settings.whisperApiKey
                    )
                }
                liveKitReady = false
                val elapsed = System.currentTimeMillis() - startTime
                if (result.success && result.text.isNotBlank()) {
                    transcriptionText = result.text
                    outputText(result.text)
                } else if (result.success) {
                    transcriptionText = "(No speech detected)"
                    finishRealtimeInsertion()
                } else {
                    transcriptionText = "Error: ${result.error}"
                    finishRealtimeInsertion()
                }
                transcriptionLog.addEntry(
                    durationMs = elapsed,
                    success = result.success,
                    text = result.text,
                    error = result.error
                )
            } catch (e: Exception) {
                liveKitReady = false
                liveKitSession?.cancel()
                liveKitSession = null
                finishRealtimeInsertion()
                val elapsed = System.currentTimeMillis() - startTime
                transcriptionText = "Error: ${e.message}"
                transcriptionLog.addEntry(
                    durationMs = elapsed,
                    success = false,
                    text = "",
                    error = e.message
                )
                Log.e(TAG, "Transcription failed", e)
            }
            activeRecordIcon = null
            recordingCompletionStarted = false
            updateExpandedViewText()
        }
    }

    private suspend fun retryRestIfLiveResultIsBlank(
        serverUrl: String,
        wavData: ByteArray,
        apiKey: String,
        liveResult: TranscriptionResult
    ): TranscriptionResult {
        if (!liveResult.shouldRetryRestAfterLive()) return liveResult
        Log.w(TAG, "Live transcription returned empty text, retrying with REST")
        return whisperClient.transcribe(
            serverUrl = serverUrl,
            audioData = wavData,
            apiKey = apiKey
        )
    }

    private fun handleLivePartial(partial: String) {
        transcriptionText = partial
        if (realtimeInsertionActive) {
            val updated = TranscriberAccessibilityService.updateRealtimeText(partial)
            if (!updated) {
                realtimeInsertionActive = false
                realtimeInsertionFailed = true
                TranscriberAccessibilityService.finishRealtimeText()
                Log.d(TAG, "Realtime field update failed; final transcript will use clipboard")
            }
        }
        updateExpandedViewText()
    }

    private suspend fun resolveServerUrl(configuredUrl: String, discoveryPort: Int): String {
        if (configuredUrl.isNotBlank()) return configuredUrl
        val discovered = WhisperServerDiscovery.discover(port = discoveryPort)
            ?: throw IllegalStateException("No WhisperLiveKit server found on local networks or Tailscale port $discoveryPort")
        settingsStore.updateServerUrl(discovered.url)
        return discovered.url
    }

    private suspend fun resolveTtsServerUrl(configuredUrl: String, discoveryPort: Int): String {
        if (configuredUrl.isNotBlank()) return configuredUrl
        val discovered = WhisperServerDiscovery.discover(port = discoveryPort)
            ?: throw IllegalStateException("No Kokoro TTS server found on local networks or Tailscale port $discoveryPort")
        settingsStore.updateTtsServerUrl(discovered.url)
        return discovered.url
    }

    private fun toggleExpandedView() {
        if (isExpanded) {
            removeExpandedView()
        } else {
            showExpandedView()
        }
        isExpanded = !isExpanded
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showExpandedView() {
        val density = resources.displayMetrics.density
        val width = (280 * density).toInt()
        val padding = (16 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(padding, padding, padding, padding)
            elevation = 8 * density
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "Transcription"
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleText)

        val copyButton = TextView(this).apply {
            text = "COPY"
            textSize = 12f
            setTextColor(0xFF6750A4.toInt())
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener { copyToClipboard() }
        }
        titleBar.addView(copyButton)

        val speakButton = TextView(this).apply {
            text = "SPEAK"
            textSize = 12f
            setTextColor(0xFF6750A4.toInt())
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener { speakClipboardText() }
        }
        titleBar.addView(speakButton)

        val closeButton = TextView(this).apply {
            text = "X"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                removeExpandedView()
                isExpanded = false
            }
        }
        titleBar.addView(closeButton)
        container.addView(titleBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }

        val contentText = TextView(this).apply {
            tag = "transcription_content"
            text = transcriptionText.ifBlank { "Tap the bubble to start recording.\nLong-press to show/hide this panel." }
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setTextIsSelectable(true)
        }
        scrollView.addView(contentText)
        container.addView(scrollView)

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(container, params)
        expandedView = container
    }

    private fun removeExpandedView() {
        expandedView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing expanded view", e)
            }
        }
        expandedView = null
    }

    private fun updateExpandedViewText() {
        expandedView?.findViewWithTag<TextView>("transcription_content")?.text = transcriptionText
    }

    private fun outputText(text: String) {
        if (realtimeInsertionActive) {
            val updated = TranscriberAccessibilityService.updateRealtimeText(text)
            finishRealtimeInsertion()
            if (updated) {
                Log.d(TAG, "Realtime text finalized in focused field")
                return
            }
            realtimeInsertionFailed = true
        } else {
            finishRealtimeInsertion()
        }

        if (!realtimeInsertionFailed && TranscriberAccessibilityService.pasteText(text)) {
            Log.d(TAG, "Text pasted into focused field")
            return
        }

        copyFinalTextToClipboard(text)
        Log.d(TAG, "No focused field, copied final transcript to clipboard")
    }

    private fun finishRealtimeInsertion() {
        realtimeInsertionActive = false
        TranscriberAccessibilityService.finishRealtimeText()
    }

    private fun copyFinalTextToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard() {
        if (transcriptionText.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", transcriptionText))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun speakClipboardText() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (text.isBlank()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }

        serviceScope.launch {
            try {
                val settings = settingsStore.settings.first()
                val serverUrl = resolveTtsServerUrl(settings.ttsServerUrl, settings.ttsServerPort)
                val audio = ttsClient.synthesizeWav(
                    serverUrl = serverUrl,
                    text = text,
                    voice = settings.ttsVoice,
                    speed = settings.ttsSpeed,
                    apiKey = settings.ttsApiKey,
                    model = settings.ttsModel
                )
                Log.d(TAG, "TTS synthesized ${audio.size} bytes for clipboard playback")
                Toast.makeText(this@FloatingOverlayService, "Playing clipboard", Toast.LENGTH_SHORT).show()
                ttsAudioPlayer.playWav(audio)
            } catch (e: Exception) {
                Toast.makeText(this@FloatingOverlayService, "TTS failed: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "TTS playback failed", e)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_overlay),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Floating overlay service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_overlay_title))
            .setContentText(getString(R.string.notification_overlay_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        transcriptionJob?.cancel()
        serviceScope.cancel()
        audioRecorder.release()
        ttsAudioPlayer.stop()
        whisperClient.shutdown()
        liveKitClient.shutdown()
        ttsClient.shutdown()
        removeExpandedView()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing bubble", e)
            }
        }
        bubbleView = null
        super.onDestroy()
    }
}
