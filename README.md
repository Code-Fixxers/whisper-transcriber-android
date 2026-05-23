# Whisper Transcriber

Android floating overlay app for voice-to-text using a self-hosted [WhisperLiveKit](https://github.com/QuentinFuxa/WhisperLiveKit) server. Tap the bubble, speak, and the transcription is typed directly into whatever text field you're using.

Works over Tailscale / ZeroTier — just point it at your server's VPN IP.

## How it works

1. A floating bubble sits over all apps (like Messenger chat heads)
2. Tap to start recording, tap again to stop
3. Audio is streamed to WhisperLiveKit via native WebSocket (`/asr`) when PCM input is enabled
4. If live streaming is unavailable, the app falls back to the OpenAI-compatible REST API (`/v1/audio/transcriptions`)
5. Transcribed text is automatically typed into the focused input field (or copied to clipboard)

## Setup

### Server

Run [WhisperLiveKit](https://github.com/QuentinFuxa/WhisperLiveKit) on your machine. For native Android streaming, start it with PCM input enabled:

```bash
whisperlivekit-server --host 0.0.0.0 --port 8090 --pcm-input
```

### App

1. Install the APK (grab from [Actions artifacts](../../actions) or build yourself)
2. Open the app. Leave the server URL blank to auto-discover WhisperLiveKit on local networks and Tailscale port `8090`, or set a URL manually in **Settings**.
3. Grant permissions when prompted:
   - **Microphone** — for recording audio
   - **Display over other apps** — for the floating bubble
   - **Notifications** — for the foreground service
4. Enable the **Whisper Transcriber** accessibility service in Android Settings → Accessibility (needed to type into other apps' text fields)
5. Tap **Start Overlay** — the floating bubble appears

### Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Capture voice from microphone |
| `SYSTEM_ALERT_WINDOW` | Floating bubble overlay |
| `FOREGROUND_SERVICE` | Keep the overlay alive |
| `INTERNET` | Send audio to whisper server |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| Accessibility Service | Type transcription into focused text fields |

## Building

### With Nix (CI uses this)

```bash
nix develop --command ./gradlew assembleDebug
```

The `flake.nix` provides JDK 17 + Android SDK (platform 34, build-tools 34.0.0).

### Without Nix

Requires JDK 17 and Android SDK with platform 34:

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleDebug
```

APKs end up in `app/build/outputs/apk/`.

## CI

GitHub Actions builds debug + release APKs on every push using a self-hosted NixOS runner. Artifacts are retained for 7 days, older ones are cleaned up automatically.

## Project structure

```
app/src/main/java/com/whispertranscriber/
├── MainActivity.kt              # Home screen, nav, permissions
├── audio/
│   └── AudioRecorder.kt         # Mic recording → WAV conversion
├── data/
│   ├── SettingsStore.kt          # DataStore-backed preferences
│   └── TranscriptionLog.kt      # Transcription history (last 100)
├── network/
│   ├── WhisperApiClient.kt      # REST fallback via OpenAI-compatible API
│   └── WhisperLiveKitClient.kt  # Native WebSocket streaming client
├── update/                      # GitHub Release update checker/downloader/installer
├── service/
│   ├── FloatingOverlayService.kt          # Bubble UI + record/transcribe flow
│   └── TranscriberAccessibilityService.kt # Types text into focused fields
└── ui/
    ├── LogScreen.kt              # Transcription history viewer
    ├── SettingsScreen.kt         # Server URL + audio quality config
    └── theme/Theme.kt            # Material 3 theme
```

### whisper-client (Rust crate)

`whisper-client/` contains an async Rust library for calling a Whisper API with either API key auth or [Cashu](https://cashu.space) ecash payment (using [cdk](https://github.com/cashubtc/cdk) 0.8). This is a standalone library, not used by the Android app.

```rust
let client = WhisperClient::new("https://whisper.example.com".into());

// With API key
let result = client.transcribe_with_key(
    "sk-...", audio_bytes, "recording.wav", TranscribeOptions::default()
).await?;

// With Cashu payment (10 sats/minute)
let result = client.transcribe_with_cashu(
    &wallet, 10, audio_bytes, "recording.wav", TranscribeOptions::default()
).await?;

println!("{}", result.text);
```

## Updates

The app checks a rolling GitHub Release manifest at `app-latest`. When a newer `versionCode` is available, it downloads `app.apk`, verifies size and SHA-256, then hands off to Android's package installer. Android still requires user approval, and APK signing must stay consistent between builds. CI currently publishes the debug-signed APK because no release signing key is configured in this repo.

## Network notes

- **HTTP / ws://** works out of the box to any IP (cleartext traffic is allowed via network security config)
- **HTTPS with self-signed certs** works — the client trusts all certificates (this is a private VPN tool, not a public app)
- Works over **Tailscale**, **ZeroTier**, or any VPN — just use the VPN IP as the server URL

## Tech stack

- Kotlin + Jetpack Compose + Material 3
- OkHttp for network
- DataStore for preferences
- Target SDK 34, min SDK 26
- Gradle 8.5, AGP 8.2.2
