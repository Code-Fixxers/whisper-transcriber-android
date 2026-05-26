package com.whispertranscriber.ui

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.whispertranscriber.audio.TtsAudioPlayer
import com.whispertranscriber.data.AppSettings
import com.whispertranscriber.data.SettingsStore
import com.whispertranscriber.network.KokoroTtsClient
import com.whispertranscriber.network.WhisperServerDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "SettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit
) {
    val settings by settingsStore.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ttsClient = remember { KokoroTtsClient() }
    val ttsAudioPlayer = remember(context) { TtsAudioPlayer() }
    DisposableEffect(ttsClient, ttsAudioPlayer) {
        onDispose {
            ttsAudioPlayer.stop()
            ttsClient.shutdown()
        }
    }

    // Local state for the text field, seeded once from persisted value
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var serverPortText by remember { mutableStateOf<String?>(null) }
    var ttsServerUrl by remember { mutableStateOf<String?>(null) }
    var ttsServerPortText by remember { mutableStateOf<String?>(null) }
    var ttsSpeed by remember { mutableStateOf<Float?>(null) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    var portDebounceJob by remember { mutableStateOf<Job?>(null) }
    var ttsUrlDebounceJob by remember { mutableStateOf<Job?>(null) }
    var ttsPortDebounceJob by remember { mutableStateOf<Job?>(null) }
    var discoveryStatus by remember { mutableStateOf("Leave blank to auto-discover healthy WhisperLiveKit servers.") }
    var discovering by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf<List<String>>(emptyList()) }
    var serverDropdownExpanded by remember { mutableStateOf(false) }
    var ttsDiscoveryStatus by remember { mutableStateOf("Discover Kokoro TTS on port 8880, or enter a custom URL.") }
    var discoveringTts by remember { mutableStateOf(false) }
    var ttsTesting by remember { mutableStateOf(false) }
    var discoveredTtsServers by remember { mutableStateOf<List<String>>(emptyList()) }
    var ttsServerDropdownExpanded by remember { mutableStateOf(false) }
    var ttsVoiceDropdownExpanded by remember { mutableStateOf(false) }
    var ttsVoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var ttsSampleText by remember {
        mutableStateOf("This is a Kokoro voice test. The quick brown fox jumps over the lazy dog.")
    }

    // Seed local state from DataStore only on first real emission
    LaunchedEffect(settings.whisperServerUrl) {
        if (serverUrl == null) {
            serverUrl = settings.whisperServerUrl
        }
    }
    LaunchedEffect(settings.whisperServerPort) {
        if (serverPortText == null) {
            serverPortText = settings.whisperServerPort.toString()
        }
    }
    LaunchedEffect(settings.ttsServerUrl) {
        if (ttsServerUrl == null) {
            ttsServerUrl = settings.ttsServerUrl
        }
    }
    LaunchedEffect(settings.ttsServerPort) {
        if (ttsServerPortText == null) {
            ttsServerPortText = settings.ttsServerPort.toString()
        }
    }
    LaunchedEffect(settings.ttsSpeed) {
        if (ttsSpeed == null) {
            ttsSpeed = settings.ttsSpeed
        }
    }

    val displayUrl = serverUrl ?: settings.whisperServerUrl
    val displayPort = serverPortText ?: settings.whisperServerPort.toString()
    val displayTtsUrl = ttsServerUrl ?: settings.ttsServerUrl
    val displayTtsPort = ttsServerPortText ?: settings.ttsServerPort.toString()
    val displayTtsSpeed = (ttsSpeed ?: settings.ttsSpeed).coerceIn(0.25f, 4.0f)
    val selectedServerOption = if (displayUrl.isNotBlank() && displayUrl in discoveredServers) {
        displayUrl
    } else {
        "Custom"
    }
    val selectedTtsServerOption = if (displayTtsUrl.isNotBlank() && displayTtsUrl in discoveredTtsServers) {
        displayTtsUrl
    } else {
        "Custom"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            Text("WhisperLiveKit Server", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = serverDropdownExpanded,
                onExpandedChange = { serverDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedServerOption,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Discovered Servers") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = serverDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = serverDropdownExpanded,
                    onDismissRequest = { serverDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Custom") },
                        onClick = {
                            if (selectedServerOption != "Custom") {
                                serverUrl = ""
                                scope.launch { settingsStore.updateServerUrl("") }
                            }
                            serverDropdownExpanded = false
                        }
                    )
                    discoveredServers.forEach { discoveredUrl ->
                        DropdownMenuItem(
                            text = { Text(discoveredUrl) },
                            onClick = {
                                serverUrl = discoveredUrl
                                scope.launch { settingsStore.updateServerUrl(discoveredUrl) }
                                serverDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = displayUrl,
                onValueChange = { newValue ->
                    serverUrl = newValue
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        delay(500)
                        settingsStore.updateServerUrl(newValue)
                    }
                },
                label = { Text("Custom Server URL") },
                placeholder = { Text("Leave blank to auto-discover") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.whisperApiKey,
                onValueChange = { newValue ->
                    scope.launch { settingsStore.updateWhisperApiKey(newValue) }
                },
                label = { Text("STT API Key") },
                placeholder = { Text("Optional Bearer token") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = displayPort,
                onValueChange = { rawValue ->
                    val newValue = rawValue.filter { it.isDigit() }.take(5)
                    serverPortText = newValue
                    portDebounceJob?.cancel()
                    val port = newValue.toIntOrNull()
                    if (port != null && port in 1..65535) {
                        portDebounceJob = scope.launch {
                            delay(500)
                            settingsStore.updateServerPort(port)
                        }
                    }
                },
                label = { Text("Discovery Port") },
                placeholder = { Text(WhisperServerDiscovery.DEFAULT_PORT.toString()) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val port = displayPort.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: WhisperServerDiscovery.DEFAULT_PORT
                    discovering = true
                    discoveryStatus = "Scanning local networks and Tailscale on port $port..."
                    scope.launch {
                        settingsStore.updateServerPort(port)
                        val discovered = WhisperServerDiscovery.discoverAll(port = port)
                        discoveredServers = discovered.map { it.url }
                        if (discoveredServers.isEmpty()) {
                            discoveryStatus = "No healthy WhisperLiveKit server found on port $port."
                        } else {
                            val selectedUrl = discoveredServers.first()
                            serverUrl = selectedUrl
                            settingsStore.updateServerUrl(selectedUrl)
                            discoveryStatus = "Found ${discoveredServers.size} server(s)."
                        }
                        discovering = false
                    }
                },
                enabled = !discovering,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Text(if (discovering) "Scanning..." else "Discover Server")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                discoveryStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            Text("Text To Speech", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = ttsServerDropdownExpanded,
                onExpandedChange = { ttsServerDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedTtsServerOption,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Discovered TTS Servers") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsServerDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = ttsServerDropdownExpanded,
                    onDismissRequest = { ttsServerDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Custom") },
                        onClick = {
                            if (selectedTtsServerOption != "Custom") {
                                ttsServerUrl = ""
                                scope.launch { settingsStore.updateTtsServerUrl("") }
                            }
                            ttsServerDropdownExpanded = false
                        }
                    )
                    discoveredTtsServers.forEach { discoveredUrl ->
                        DropdownMenuItem(
                            text = { Text(discoveredUrl) },
                            onClick = {
                                ttsServerUrl = discoveredUrl
                                scope.launch { settingsStore.updateTtsServerUrl(discoveredUrl) }
                                ttsServerDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = displayTtsUrl,
                onValueChange = { newValue ->
                    ttsServerUrl = newValue
                    ttsUrlDebounceJob?.cancel()
                    ttsUrlDebounceJob = scope.launch {
                        delay(500)
                        settingsStore.updateTtsServerUrl(newValue)
                    }
                },
                label = { Text("Custom TTS URL") },
                placeholder = { Text("Leave blank to auto-discover") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.ttsApiKey,
                onValueChange = { newValue ->
                    scope.launch { settingsStore.updateTtsApiKey(newValue) }
                },
                label = { Text("TTS API Key") },
                placeholder = { Text("Optional Bearer token") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = settings.ttsModel,
                onValueChange = { newValue ->
                    scope.launch { settingsStore.updateTtsModel(newValue) }
                },
                label = { Text("TTS Model") },
                placeholder = { Text("kokoro or kokoro-tts") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = displayTtsPort,
                onValueChange = { rawValue ->
                    val newValue = rawValue.filter { it.isDigit() }.take(5)
                    ttsServerPortText = newValue
                    ttsPortDebounceJob?.cancel()
                    val port = newValue.toIntOrNull()
                    if (port != null && port in 1..65535) {
                        ttsPortDebounceJob = scope.launch {
                            delay(500)
                            settingsStore.updateTtsServerPort(port)
                        }
                    }
                },
                label = { Text("TTS Discovery Port") },
                placeholder = { Text("8880") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val port = displayTtsPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8880
                        discoveringTts = true
                        ttsDiscoveryStatus = "Scanning for Kokoro TTS on port $port..."
                        scope.launch {
                            settingsStore.updateTtsServerPort(port)
                            val discovered = WhisperServerDiscovery.discoverAll(port = port)
                            discoveredTtsServers = discovered.map { it.url }
                            if (discoveredTtsServers.isEmpty()) {
                                ttsDiscoveryStatus = "No healthy TTS server found on port $port."
                            } else {
                                val selectedUrl = discoveredTtsServers.first()
                                ttsServerUrl = selectedUrl
                                settingsStore.updateTtsServerUrl(selectedUrl)
                                ttsDiscoveryStatus = "Found ${discoveredTtsServers.size} TTS server(s)."
                            }
                            discoveringTts = false
                        }
                    },
                    enabled = !discoveringTts && !ttsTesting,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Text(if (discoveringTts) "Scanning..." else "Discover TTS")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val url = displayTtsUrl.trim()
                        if (url.isBlank()) {
                            ttsDiscoveryStatus = "Select or enter a TTS server URL first."
                            return@Button
                        }
                        ttsTesting = true
                        ttsDiscoveryStatus = "Fetching voices..."
                        scope.launch {
                            try {
                                val voices = ttsClient.voices(url, settings.ttsApiKey)
                                ttsVoices = voices
                                val selectedVoice = when {
                                    settings.ttsVoice in voices -> settings.ttsVoice
                                    voices.isNotEmpty() -> voices.first()
                                    else -> settings.ttsVoice
                                }
                                if (selectedVoice != settings.ttsVoice) {
                                    settingsStore.updateTtsVoice(selectedVoice)
                                }
                                Log.d(TAG, "Loaded ${voices.size} Kokoro TTS voice(s) from $url")
                                ttsDiscoveryStatus = "Loaded ${voices.size} voice(s)."
                            } catch (e: Exception) {
                                Log.e(TAG, "TTS voice fetch failed", e)
                                ttsDiscoveryStatus = if (e.message?.contains("404") == true) {
                                    try {
                                        val models = ttsClient.models(url, settings.ttsApiKey)
                                        val modelHint = models.firstOrNull { it.contains("tts", ignoreCase = true) }
                                        if (modelHint != null && settings.ttsModel.isBlank()) {
                                            settingsStore.updateTtsModel(modelHint)
                                        }
                                        "Connected. Voice list unavailable; enter voice manually."
                                    } catch (modelsError: Exception) {
                                        Log.e(TAG, "TTS model fetch failed", modelsError)
                                        "Voice list unavailable, and model check failed: ${modelsError.message}"
                                    }
                                } else {
                                    "TTS connection failed: ${e.message}"
                                }
                            }
                            ttsTesting = false
                        }
                    },
                    enabled = !discoveringTts && !ttsTesting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (ttsTesting) "Testing..." else "Test Connection")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                ttsDiscoveryStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = ttsVoiceDropdownExpanded,
                onExpandedChange = { ttsVoiceDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = settings.ttsVoice,
                    onValueChange = { newValue ->
                        scope.launch { settingsStore.updateTtsVoice(newValue) }
                    },
                    label = { Text("Voice") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ttsVoiceDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = ttsVoiceDropdownExpanded,
                    onDismissRequest = { ttsVoiceDropdownExpanded = false }
                ) {
                    val voices = if (ttsVoices.isNotEmpty()) ttsVoices else listOf(settings.ttsVoice)
                    voices.distinct().forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice) },
                            onClick = {
                                scope.launch { settingsStore.updateTtsVoice(voice) }
                                ttsVoiceDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Playback Speed ${"%.2f".format(displayTtsSpeed)}x", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = displayTtsSpeed,
                onValueChange = { ttsSpeed = it },
                onValueChangeFinished = {
                    scope.launch { settingsStore.updateTtsSpeed((ttsSpeed ?: settings.ttsSpeed).coerceIn(0.25f, 4.0f)) }
                },
                valueRange = 0.25f..4.0f,
                steps = 14
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ttsSampleText,
                onValueChange = { ttsSampleText = it },
                label = { Text("Test Text") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val url = displayTtsUrl.trim()
                    val text = ttsSampleText.trim()
                    if (url.isBlank()) {
                        ttsDiscoveryStatus = "Select or enter a TTS server URL first."
                        return@Button
                    }
                    if (text.isBlank()) {
                        ttsDiscoveryStatus = "Enter test text first."
                        return@Button
                    }
                    ttsTesting = true
                    ttsDiscoveryStatus = "Generating test audio..."
                    scope.launch {
                        try {
                            val audio = ttsClient.synthesizeWav(
                                serverUrl = url,
                                text = text,
                                voice = settings.ttsVoice,
                                speed = displayTtsSpeed,
                                apiKey = settings.ttsApiKey,
                                model = settings.ttsModel
                            )
                            Log.d(TAG, "TTS synthesized ${audio.size} bytes for test playback")
                            settingsStore.updateTtsSpeed(displayTtsSpeed)
                            ttsDiscoveryStatus = "Playing ${settings.ttsVoice}."
                            ttsAudioPlayer.playWav(audio)
                            ttsDiscoveryStatus = "Played ${settings.ttsVoice}."
                        } catch (e: Exception) {
                            Log.e(TAG, "TTS test playback failed", e)
                            ttsDiscoveryStatus = "TTS playback failed: ${e.message}"
                        }
                        ttsTesting = false
                    }
                },
                enabled = !discoveringTts && !ttsTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (ttsTesting) "Working..." else "Play Test Voice")
            }

            Spacer(Modifier.height(24.dp))
            Text("Recording", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            var qualityExpanded by remember { mutableStateOf(false) }
            val qualityOptions = listOf("low" to "Low (8kHz)", "medium" to "Medium (16kHz)", "high" to "High (44.1kHz)")

            ExposedDropdownMenuBox(
                expanded = qualityExpanded,
                onExpandedChange = { qualityExpanded = it }
            ) {
                OutlinedTextField(
                    value = qualityOptions.firstOrNull { it.first == settings.audioQuality }?.second ?: "Medium (16kHz)",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Audio Quality") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = qualityExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = qualityExpanded,
                    onDismissRequest = { qualityExpanded = false }
                ) {
                    qualityOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                scope.launch { settingsStore.updateAudioQuality(value) }
                                qualityExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
