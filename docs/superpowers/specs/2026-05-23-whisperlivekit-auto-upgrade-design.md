# WhisperLiveKit and Auto Upgrade Design

## Goal

Make the Android overlay work well with WhisperLiveKit servers discovered on local networks or Tailscale port `8090`, and add a pull-based APK updater backed by GitHub Releases.

## WhisperLiveKit Integration

WhisperLiveKit remains compatible with the existing OpenAI-style REST endpoint, but the app should use its native streaming endpoint as the primary path. The app will discover a server on port `8090`, open its `/asr` WebSocket, send raw little-endian signed 16-bit mono PCM at 16 kHz while the user is recording, and send a final empty binary message when recording stops. It will parse full-state JSON messages containing `lines`, `buffer_transcription`, and `ready_to_stop`.

The existing tap-to-start/tap-to-stop overlay interaction stays unchanged. While recording, the expanded overlay can show partial text from WhisperLiveKit. On finalization, the app combines committed `lines` plus any buffer text, inserts the final text into the focused field, and logs the transcription. If WebSocket streaming fails before any result, the app falls back to the existing REST upload path.

## Update Flow

GitHub Actions will build the APK, publish `app.apk` and `app-manifest.json` to a rolling `app-latest` GitHub Release on pushes to `main`, and keep artifact uploads for CI debugging. The manifest includes `versionCode`, `versionName`, `commit`, `apkUrl`, `sizeBytes`, and `sha256`.

The app will expose update controls on the home screen: check, download, and install. The app compares only numeric `versionCode`, verifies downloaded APK size and SHA-256, stores the APK in cache, then launches Android's package installer through a `FileProvider`. User approval is still required by Android; the app will not silently install updates.

## Boundaries

This design does not add forced updates, background polling, release signing changes, or Play Store integration. It assumes APK signing remains consistent between builds; otherwise Android will reject updates.

## Testing

Unit tests cover update manifest parsing/version comparison, SHA-256 verification behavior, and WhisperLiveKit JSON result aggregation. A Gradle build verifies Android integration and manifest/provider configuration.
