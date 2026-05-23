# WhisperLiveKit Auto Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add WhisperLiveKit native streaming transcription and GitHub Release APK self-updates.

**Architecture:** Keep the overlay UX and add focused helper classes: one WebSocket streaming client, one update manifest/client/downloader, and small UI wiring. Preserve REST transcription as fallback.

**Tech Stack:** Kotlin, Android AudioRecord, OkHttp WebSocket/HTTP, Jetpack Compose, DataStore, GitHub Actions.

---

### Task 1: WhisperLiveKit Streaming

**Files:**
- Create: `app/src/main/java/com/whispertranscriber/network/WhisperLiveKitClient.kt`
- Modify: `app/src/main/java/com/whispertranscriber/audio/AudioRecorder.kt`
- Modify: `app/src/main/java/com/whispertranscriber/service/FloatingOverlayService.kt`
- Test: `app/src/test/java/com/whispertranscriber/network/WhisperLiveKitResultTest.kt`

- [ ] Add parser tests for committed lines, transcription buffer, and `ready_to_stop`.
- [ ] Implement result parser and WebSocket client using OkHttp.
- [ ] Add raw PCM streaming support to `AudioRecorder`.
- [ ] Wire overlay start/stop to stream PCM and finalize on `ready_to_stop`.
- [ ] Keep REST upload fallback for connection/startup failures.

### Task 2: Settings Defaults

**Files:**
- Modify: `app/src/main/java/com/whispertranscriber/data/SettingsStore.kt`
- Modify: `app/src/main/java/com/whispertranscriber/ui/SettingsScreen.kt`
- Modify: `README.md`

- [ ] Leave the default server URL blank and discover WhisperLiveKit on port `8090`.
- [ ] Update settings labels to mention WhisperLiveKit.
- [ ] Document WebSocket primary behavior and REST fallback.

### Task 3: App Updater

**Files:**
- Create: `app/src/main/java/com/whispertranscriber/update/AppUpdateClient.kt`
- Create: `app/src/main/java/com/whispertranscriber/update/AppUpdateInstaller.kt`
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/whispertranscriber/MainActivity.kt`
- Test: `app/src/test/java/com/whispertranscriber/update/AppUpdateClientTest.kt`

- [ ] Add manifest parsing and version comparison tests.
- [ ] Implement manifest fetch, APK download, size check, and SHA-256 verification.
- [ ] Add `FileProvider` install handoff with unknown-app-source settings fallback.
- [ ] Add home-screen update controls with explicit states and errors.

### Task 4: CI Publishing

**Files:**
- Modify: `.github/workflows/build-apk.yml`
- Modify: `app/build.gradle.kts`

- [ ] Generate monotonic `versionCode` from GitHub run number when available.
- [ ] Add `BuildConfig.UPDATE_MANIFEST_URL`.
- [ ] Publish `app.apk` and `app-manifest.json` to rolling `app-latest` release on `main`.

### Verification

- [ ] Run `nix develop -c ./gradlew test`.
- [ ] Run `nix develop -c ./gradlew assembleDebug`.
- [ ] Confirm manifest/provider XML is packaged by the build.
