package com.whispertranscriber

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.whispertranscriber.service.TranscriberAccessibilityService
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.whispertranscriber.data.SettingsStore
import com.whispertranscriber.data.TranscriptionLog
import com.whispertranscriber.service.FloatingOverlayService
import com.whispertranscriber.ui.LogScreen
import com.whispertranscriber.ui.SettingsScreen
import com.whispertranscriber.ui.theme.WhisperTranscriberTheme
import com.whispertranscriber.update.AppUpdateClient
import com.whispertranscriber.update.AppUpdateInstaller
import com.whispertranscriber.update.UpdateCheckResult
import com.whispertranscriber.update.UpdateManifest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var transcriptionLog: TranscriptionLog
    private lateinit var updateClient: AppUpdateClient
    private var overlayRunning by mutableStateOf(false)
    private var updateStatus by mutableStateOf("Current build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    private var availableUpdate by mutableStateOf<UpdateManifest?>(null)
    private var downloadedUpdate by mutableStateOf<File?>(null)
    private var updateBusy by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        transcriptionLog = TranscriptionLog(this)
        updateClient = AppUpdateClient(this, BuildConfig.UPDATE_MANIFEST_URL)
        requestPermissions()
        checkForUpdates()

        setContent {
            WhisperTranscriberTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onSettingsClick = { navController.navigate("settings") },
                            onLogClick = { navController.navigate("log") },
                            onToggleOverlay = { toggleOverlayService() },
                            onEnableAccessibility = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onCheckUpdate = { checkForUpdates() },
                            onDownloadUpdate = { downloadUpdate() },
                            onInstallUpdate = { installUpdate() },
                            overlayRunning = overlayRunning,
                            accessibilityEnabled = TranscriberAccessibilityService.isAvailable(),
                            updateStatus = updateStatus,
                            updateAvailable = availableUpdate != null,
                            updateDownloaded = downloadedUpdate != null,
                            updateBusy = updateBusy
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            settingsStore = settingsStore,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("log") {
                        LogScreen(
                            transcriptionLog = transcriptionLog,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun toggleOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (overlayRunning) {
            stopService(Intent(this, FloatingOverlayService::class.java))
            overlayRunning = false
        } else {
            val intent = Intent(this, FloatingOverlayService::class.java)
            startForegroundService(intent)
            overlayRunning = true
        }
    }

    private fun checkForUpdates() {
        if (updateBusy) return
        updateBusy = true
        updateStatus = "Checking for updates..."
        lifecycleScope.launch {
            try {
                when (val result = updateClient.check(BuildConfig.VERSION_CODE)) {
                    is UpdateCheckResult.Available -> {
                        availableUpdate = result.manifest
                        downloadedUpdate = null
                        updateStatus = "Update available: ${result.manifest.versionName} (${result.manifest.versionCode})"
                    }
                    UpdateCheckResult.UpToDate -> {
                        availableUpdate = null
                        downloadedUpdate = null
                        updateStatus = "Up to date: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    }
                    is UpdateCheckResult.Failed -> {
                        updateStatus = result.message
                    }
                }
            } catch (e: Exception) {
                updateStatus = e.message ?: "Update check failed"
            } finally {
                updateBusy = false
            }
        }
    }

    private fun downloadUpdate() {
        val manifest = availableUpdate ?: return
        if (updateBusy) return
        updateBusy = true
        updateStatus = "Downloading ${manifest.versionName}..."
        lifecycleScope.launch {
            try {
                downloadedUpdate = updateClient.download(manifest)
                updateStatus = "Downloaded ${manifest.versionName}. Ready to install."
            } catch (e: Exception) {
                updateStatus = e.message ?: "Download failed"
            } finally {
                updateBusy = false
            }
        }
    }

    private fun installUpdate() {
        val file = downloadedUpdate ?: return
        AppUpdateInstaller.install(this, file)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
    onLogClick: () -> Unit,
    onToggleOverlay: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    overlayRunning: Boolean,
    accessibilityEnabled: Boolean,
    updateStatus: String,
    updateAvailable: Boolean,
    updateDownloaded: Boolean,
    updateBusy: Boolean
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Whisper Transcriber") },
                actions = {
                    IconButton(onClick = onLogClick) {
                        Icon(Icons.Default.History, contentDescription = "Log")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Audio Transcription Overlay",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Floating Bubble", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Start the floating overlay to record and transcribe audio from anywhere. " +
                            "Audio is streamed to your self-hosted WhisperLiveKit server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onToggleOverlay,
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (overlayRunning) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Icon(
                            if (overlayRunning) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (overlayRunning) "Stop Overlay" else "Start Overlay")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("App Updates", style = MaterialTheme.typography.titleMedium)
                    Text(
                        updateStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onCheckUpdate,
                        enabled = !updateBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Check for Updates")
                    }
                    if (updateAvailable && !updateDownloaded) {
                        OutlinedButton(
                            onClick = onDownloadUpdate,
                            enabled = !updateBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Download Update")
                        }
                    }
                    if (updateDownloaded) {
                        OutlinedButton(
                            onClick = onInstallUpdate,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Install Update")
                        }
                    }
                }
            }

            if (!accessibilityEnabled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Type into Apps", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Enable the accessibility service to automatically paste transcriptions into the focused text field.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = onEnableAccessibility,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Accessibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Enable Accessibility")
                        }
                    }
                }
            }

            Text(
                "Configure your WhisperLiveKit server URL in Settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
