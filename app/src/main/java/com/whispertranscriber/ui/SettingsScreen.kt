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
import androidx.compose.ui.unit.dp
import com.whispertranscriber.data.AppSettings
import com.whispertranscriber.data.SettingsStore
import com.whispertranscriber.network.WhisperServerDiscovery
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
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    var discoveryStatus by remember { mutableStateOf("Leave blank to auto-discover port 8090.") }
    var discovering by remember { mutableStateOf(false) }

    // Seed local state from DataStore only on first real emission
    LaunchedEffect(settings.whisperServerUrl) {
        if (serverUrl == null) {
            serverUrl = settings.whisperServerUrl
        }
    }

    val displayUrl = serverUrl ?: settings.whisperServerUrl

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
                label = { Text("Server URL") },
                placeholder = { Text("Auto-discover http://*:8090") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    discovering = true
                    discoveryStatus = "Scanning local networks and Tailscale..."
                    scope.launch {
                        val discovered = WhisperServerDiscovery.discover()
                        if (discovered == null) {
                            discoveryStatus = "No WhisperLiveKit server found on port 8090."
                        } else {
                            serverUrl = discovered.url
                            settingsStore.updateServerUrl(discovered.url)
                            discoveryStatus = "Found ${discovered.url}"
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
