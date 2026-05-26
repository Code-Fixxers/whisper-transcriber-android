# Whisper Transcriber

Android floating overlay app for voice-to-text using a self-hosted [WhisperLiveKit](https://github.com/QuentinFuxa/WhisperLiveKit) server. Tap the bubble, speak, and the transcription streams directly into the active text field.

Works over Tailscale / ZeroTier — just point it at your server's VPN IP.

## How it works

1. A floating bubble sits over all apps (like Messenger chat heads)
2. Tap to start recording; WhisperLiveKit silence detection stops the capture, or tap again to stop manually
3. Audio is streamed to WhisperLiveKit via native WebSocket (`/asr`) when PCM input is enabled
4. Partial transcripts replace the in-progress text in the focused input field in real time
5. If no editable field is focused, the final transcript is copied to the clipboard once the utterance is silent
6. Optional Kokoro TTS can read clipboard text aloud through the overlay
7. If live streaming is unavailable, the app falls back to the OpenAI-compatible REST API (`/v1/audio/transcriptions`)

## Setup

### Server

Run [WhisperLiveKit](https://github.com/QuentinFuxa/WhisperLiveKit) on your machine. For native Android streaming, start it with PCM input enabled:

```bash
whisperlivekit-server --host 0.0.0.0 --port 8090 --pcm-input
```

For TTS, run Kokoro-FastAPI on your machine or tailnet. The app discovers healthy TTS servers on port `8880` by default and uses the OpenAI-compatible `/v1/audio/speech` endpoint.

LiteLLM/OpenAI-compatible proxies are supported for REST STT and TTS. Set the proxy URL manually, add the matching STT/TTS API key in Settings, and use the proxy model name for TTS, for example `kokoro-tts`. Some proxies do not expose `/v1/audio/voices`; in that case enter the voice name manually.

### App

1. Install the APK (grab from [Actions artifacts](../../actions) or build yourself)
2. Open the app. Leave the server URL blank to auto-discover WhisperLiveKit on local networks and Tailscale port `8090`, or set a URL manually in **Settings**. If the endpoint requires auth, fill **STT API Key**; the app sends it as `Authorization: Bearer ...`.
3. Grant permissions when prompted:
   - **Microphone** — for recording audio
   - **Display over other apps** — for the floating bubble
   - **Notifications** — for the foreground service
4. Enable the **Whisper Transcriber** accessibility service in Android Settings → Accessibility (needed to type into other apps' text fields)
5. Tap **Start Overlay** — the floating bubble appears
6. Optional: in **Settings → Text To Speech**, discover your Kokoro server, test the connection to load voices, pick a model/voice/speed, and play sample text. If the endpoint requires auth, fill **TTS API Key**.

### Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Capture voice from microphone |
| `BLUETOOTH_CONNECT` | Use a connected headset microphone on Android 12+ |
| `MODIFY_AUDIO_SETTINGS` | Route recording through the active communication device |
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

The app checks a rolling GitHub Release manifest at `app-latest`. When a newer `versionCode` is available, it downloads `app.apk`, verifies size and SHA-256, then hands off to Android's package installer. Android still requires user approval, and APK signing must stay consistent between builds. CI publishes the release-signed APK directly as `app.apk`.

## Network notes

- **HTTP / ws://** works out of the box to any IP (cleartext traffic is allowed via network security config)
- **HTTPS with self-signed certs** works — the client trusts all certificates (this is a private VPN tool, not a public app)
- Works over **Tailscale**, **ZeroTier**, or any VPN — just use the VPN IP as the server URL
- Recording prefers the active headset microphone when Android exposes one, then wired/USB headsets, then the built-in mic
- Long-press the overlay to open the panel, then tap **SPEAK** to read the current clipboard with the selected Kokoro voice

## Live endpoint probe

Use the probe script to verify a deployed WhisperLiveKit server with a known 16 kHz mono WAV:

```bash
python3 scripts/whisperlivekit_live_probe.py \
  --url http://100.101.157.56:8090 \
  --wav /tmp/jfk.wav \
  --expect country
```

The app needs the server WebSocket config to report `"useAudioWorklet": true` for real-time Android PCM streaming. Start WhisperLiveKit with `--pcm-input` for that mode.

## Tech stack

- Kotlin + Jetpack Compose + Material 3
- OkHttp for network
- DataStore for preferences
- Target SDK 34, min SDK 26
- Gradle 8.5, AGP 8.2.2
