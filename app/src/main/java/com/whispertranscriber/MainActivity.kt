package com.whispertranscriber

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.whispertranscriber.service.TranscriberAccessibilityService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var transcriptionLog: TranscriptionLog
    private var overlayRunning by mutableStateOf(false)

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
        requestPermissions()

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
                            overlayRunning = overlayRunning,
                            accessibilityEnabled = TranscriberAccessibilityService.isAvailable()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
    onLogClick: () -> Unit,
    onToggleOverlay: () -> Unit,
    onEnableAccessibility: () -> Unit,
    overlayRunning: Boolean,
    accessibilityEnabled: Boolean
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
                .padding(24.dp),
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
                            "Audio is sent to your self-hosted Whisper server.",
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
                "Configure your Whisper server URL in Settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
