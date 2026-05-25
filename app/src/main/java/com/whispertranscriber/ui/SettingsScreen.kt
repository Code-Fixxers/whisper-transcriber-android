package com.whispertranscriber.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.whispertranscriber.data.AppSettings
import com.whispertranscriber.data.SettingsStore
import com.whispertranscriber.network.WhisperServerDiscovery
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit
) {
    val settings by settingsStore.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    // Local state for the text field, seeded once from persisted value
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var serverPortText by remember { mutableStateOf<String?>(null) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    var portDebounceJob by remember { mutableStateOf<Job?>(null) }
    var discoveryStatus by remember { mutableStateOf("Leave blank to auto-discover healthy WhisperLiveKit servers.") }
    var discovering by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf<List<String>>(emptyList()) }
    var serverDropdownExpanded by remember { mutableStateOf(false) }

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

    val displayUrl = serverUrl ?: settings.whisperServerUrl
    val displayPort = serverPortText ?: settings.whisperServerPort.toString()
    val selectedServerOption = if (displayUrl.isNotBlank() && displayUrl in discoveredServers) {
        displayUrl
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
