# SendSpinDroid Project Memory

## Overview

SendSpinDroid is a native Kotlin Android client for SendSpin. It acts as a **Player**-role client: it connects to a SendSpin server over WebSocket, synchronizes its local clock to the server, and renders timestamped PCM audio with continuous sync correction.

## Fork Context

This is a personal fork of `chrisuthe/SendspinDroid`, used on a single device for a single use case:

- **Device**: NVIDIA Shield Pro 2019
- **OS**: Android TV 11 (API 30)
- **Display**: 77" TV, 10-foot viewing distance
- **Input**: D-pad remote only
- **Mode**: SendSpin Player only — Music Assistant features are not used

The `upstream` git remote points at `https://github.com/chrisuthe/SendspinDroid.git`. `origin` is this fork.

### Fork Policy: Music Assistant code is inert

Music Assistant (MA) code sits in the tree but is unused on this fork. It compiles, it ships in the APK, we ignore it.

- Do not enhance MA features, screens, or protocols.
- Do not clean up or refactor MA code.
- Do not touch MA strings in `strings.xml`.
- When merging from upstream, accept MA-touching changes as-is.
- Pure MA code lives under `android/app/src/main/java/com/sendspindroid/musicassistant/` and `android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/`.
- Files that mix SendSpin + MA logic (review carefully on upstream merges): `MainActivity.kt`, `AppShell.kt`, `NowPlayingScreen.kt`, `NowPlayingHeadUnit.kt`, `UserSettings.kt`.
- `AppShell.kt` has **two Scaffolds** branched on `isMaConnected`: the `if (!isMaConnected)` branch is the LIVE path on this fork; the `else` branch (MA-connected NavigationSuiteScaffold) is effectively dead code. Any shell-level UI work (topBar, padding, rail) must land on the non-MA path first. Mirror into the MA branch only when required to keep both compiling.

### Fork Policy: Android TV is the only target

`FormFactor.TV` in `ui/adaptive/FormFactor.kt` is the only layout that matters for correctness. Phone, tablet, and head-unit layouts are kept in the tree (deleting them would churn upstream merges) but do not need to be hand-tested or enhanced.

Any new or modified UI must:

- Use `.tvFocusable()` from `ui/adaptive/TvFocusHelpers.kt` on every interactive element.
- Apply `.overscanSafe()` at screen roots so nothing critical sits within ~48dp of the edge.
- Scale typography and sizing via `ui/adaptive/AdaptiveDefaults.kt` rather than hardcoded dp/sp values.
- Land `TvInitialFocus` on a sensible default element on first composition.
- Keep hit targets >=~48dp and TV-layout text >=~18sp.

## Application Architecture

SendSpinDroid is a **synchronized audio player** that connects to SendSpin servers:

```
SendSpin Server ──WebSocket──► SendSpinClient ──► SyncAudioPlayer (AudioTrack) ──► Audio Output
                    │
                    ├── JSON messages (metadata, state)
                    └── Binary messages (timestamped audio PCM)
```

## Key Components

### SendSpinClient (`sendspin/SendSpinClient.kt`)
- WebSocket connection via OkHttp
- Protocol parsing (JSON + binary)
- Clock synchronization
- Audio buffering with timestamps

### PlaybackService (`playback/PlaybackService.kt`)
- Android MediaLibraryService for background playback
- MediaSession for lock screen/notification controls
- Android Auto browse tree support

## SendSpin Protocol

### Text Messages (JSON)
- `server/state` - Server state and track metadata
- `group/update` - Group playback state changes
- `stream/start` - Audio stream beginning
- `stream/end` - Audio stream ending

### Binary Messages
```
Header format: struct ">Bq" (big-endian: 1 byte type + 8 byte int64 timestamp)
Byte 0:     Message type
Bytes 1-8:  Timestamp (big-endian int64, microseconds since server start)
Bytes 9+:   Payload (PCM audio or image data)

Types:
  4:     Audio data
  8-11:  Artwork channels 0-3 (empty payload = clear artwork)
  16:    Visualizer data
```

### Audio Format
- 48kHz sample rate
- 16-bit, 24-bit, or 32-bit signed PCM (negotiated via client hello)
- Stereo (2 channels) or Mono (1 channel)
- Audio sample data is little-endian; header timestamps are big-endian

### Client State (`client/state`)
Reports player state to server:
- `state`: "synchronized" or "error"
- `volume`: 0-100
- `muted`: boolean
- `static_delay_ms`: device audio output latency compensation (milliseconds)

## Audio Pipeline

The shipped audio pipeline uses Android's `AudioTrack` (Java API) in `MODE_STREAM` (push mode). Incoming binary WebSocket frames are decoded to PCM (if encoded), anchored against the synchronized server clock via a Kalman time filter, and written to `AudioTrack` with sample insert/drop correction to maintain sync.

Key modules:
- **Protocol** - WebSocket connection, JSON/binary frame parsing (`sendspin/protocol/`)
- **Clock Sync** - 2D Kalman time filter (`SendspinTimeFilter.kt`)
- **Audio Buffer** - Timestamped chunk queue and state machine (`SyncAudioPlayer.kt`)
- **Audio Output** - `AudioTrack` in `SyncAudioPlayer.kt` with DAC-aware start gating and sample insert/drop sync correction

AAudio/Oboe would provide callback-precise hardware latency and lower-latency writes, and is a **possible future migration** — but is not the currently shipped path. Code and docs should describe `AudioTrack` as the present audio output.

## Development Environment

- **Host OS**: Linux (Arch)
- **IDE**: Android Studio (Linux build), or any editor + command-line Gradle
- **JDK**: Gradle manages a JVM 21 toolchain automatically — no `JAVA_HOME` export required
- **Android SDK**: standard install, typically under `$HOME/Android/Sdk/`
- **ADB**: use the SDK's platform-tools (`$HOME/Android/Sdk/platform-tools/adb`) or a distro package (`pacman -S android-tools` on Arch)

### Connecting to the Shield

Shield Pro over USB is awkward; use ADB over network:

```bash
# On the Shield: Developer options -> "Network debugging" (or similar). Note the IP.
adb connect <shield-ip>:5555
adb devices                  # confirm "device" (not "unauthorized")
```

A dialog on the Shield prompts to accept the host key the first time.

## Build, Install, Test

All Gradle work happens inside the `android/` subdirectory.

```bash
cd android
./gradlew assembleDebug       # build debug APK
./gradlew installDebug        # install to connected Shield
./gradlew test                # JVM unit tests (fast)
./gradlew lintDebug           # Android lint
./gradlew check               # tests + lint
```

The first build downloads the JDK toolchain and Android dependencies (a few hundred MB, one-time).

## Upstream Merge Workflow

```bash
git fetch upstream
git merge upstream/main       # or: git rebase upstream/main
```

Expected conflicts: usually none. When they happen:

- **Pure MA file** (under `musicassistant/` on either module): take upstream as-is.
- **SendSpin core file** (`sendspin/`, `playback/`, `SyncAudioPlayer.kt`, non-MA parts of `shared/`): resolve carefully — these are the files that matter.
- **Mixed file** (`MainActivity.kt`, `AppShell.kt`, `NowPlayingScreen.kt`, `NowPlayingHeadUnit.kt`, `UserSettings.kt`): review both sides, keep SendSpin semantics intact, accept MA changes as data.

## Code Style

- **No emojis**: Do not use emojis in code, logs, or UI strings unless explicitly approved by the user.
- Use ASCII alternatives: `us` instead of `μs`, `->` instead of `→`, `+/-` instead of `±`
- **No self-citation**: Never cite yourself (e.g., "Co-Authored-By: Claude") in commits, comments, or release notes.

## Release Process

**IMPORTANT**: Before creating a new version tag (e.g., `v2.1.3`):
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Build and test
3. Commit the version bump
4. Then create and push the tag

### versionCode Scheme

Encoded semantic version: `MAJOR * 10000 + MINOR * 100 + PATCH`

| Segment | Digits | Range |
|---------|--------|-------|
| MAJOR   | 4      | 0-9999 |
| MINOR   | 2      | 0-99   |
| PATCH   | 2      | 0-99   |

Examples: `2.0.0` = 20000, `2.1.3` = 20103, `10.5.22` = 100522

Pre-release suffixes (alpha, beta, rc) do NOT affect the versionCode -- they only appear in versionName. A pre-release shares the same versionCode as its eventual stable release.

## License

MIT License (see `LICENSE` in repo root).

## Reference Implementation

The Python CLI (`sendspin-cli`) is the canonical reference for SendSpin protocol behavior and the audio sync algorithm. All features work as expected; use it to verify correct behavior when debugging. Obtain a copy from upstream if you need it.

Key files to study (wherever you have the CLI checked out):
- `audio.py` - Main audio playback with time sync (~1500 lines)
- `protocol.py` - WebSocket protocol handling
- `clocksync.py` - Clock synchronization algorithm

The CLI shows the canonical approach:
- Uses `sounddevice` which provides `outputBufferDacTime` in callback
- State machine: WAITING_FOR_START -> PLAYING -> REANCHORING
- Sync correction via sample insert/drop (+/-4% rate adjustment)
- Measures sync error: `expected_play_time - actual_dac_time`

### Android Deviations from the Python Reference

- **Speed-correction cap**: `SyncAudioPlayer.MAX_SPEED_CORRECTION = 0.02` (+/-2%), vs the reference's +/-4%. The tighter cap is a safety margin against over-correction given that `AudioTrack` DAC-timing jitter on Android can be larger than desktop `sounddevice`. Revisit if high-drift scenarios fail to converge.
- **Audio output API**: `AudioTrack` in `MODE_STREAM` instead of `sounddevice`. DAC position is read via `AudioTrack.getTimestamp()` for sync-error measurement and start-gating.
