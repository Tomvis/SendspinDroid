![GitHub all releases](https://img.shields.io/github/downloads/chrisuthe/SendspinDroid/total?color=blue)

# SendSpin Player for Android

A native Android client for [SendSpin](https://www.sendspin-audio.com/). It plays from any SendSpin server, Music Assistant included.


## Features

### Synchronized Playback
- Precision-synced audio across unlimited rooms
- Background audio with lock screen and notification controls
- Android Auto integration
- Android TV support
- Hardware volume buttons with bidirectional sync
- Play, pause, skip and group switching from the app; seek from the lock screen, notification or Android Auto
- Phone calls pause the group; other apps' audio mutes this player
- Adjustable sync offset for speaker delay compensation

### Audio Quality
- **Opus** -- efficient compressed streaming
- **FLAC** -- lossless quality
- **PCM** -- uncompressed raw audio, 16, 24 or 32-bit
- Preferred codec selection
- 48 kHz stereo output

### Interface
- Material You dynamic colors -- matches your wallpaper on Android 12+
- Full dark and light theme support
- Now playing view with album art and playback controls; swipe the art for the artist photo when the server provides one
- Layouts for phones, tablets, TVs and car head units
- Full-screen immersive mode
- Keep screen on while playing
- Portrait and landscape support

### Connectivity
- Advertises itself on the local network (mDNS/Zeroconf) and waits for a server to connect, by default
- Or searches for servers that advertise themselves, and connects to one
- Manual server entry for direct connections, in either mode
- Encrypted connection to the server, with pairing by token or QR code
- Automatic reconnection when the server or the network comes back
- Works on WiFi and Ethernet, on the same network as the server

### Server Management
- Multi-server support with saved server list
- Add Server wizard with guided setup (discover, test, save)
- Default server with optional auto-start on boot

### Settings
- Custom player name
- Display preferences (full-screen mode, keep screen on, layout mode)
- Audio sync offset tuning
- Preferred codec selection
- Low memory mode for older devices
- High power mode for always-on devices
- Debug logging with log export
- Stats for Nerds -- real-time sync diagnostics

## Getting Started

1. **Install** -- Download the latest APK from [Releases](https://github.com/chrisuthe/SendSpinDroid/releases)
2. **Open** -- The app advertises itself on your network and waits for a SendSpin server to connect
3. **Play** -- The player appears in your server (Music Assistant, for example); play something to it

For development and testing guides, see the [Wiki](https://github.com/chrisuthe/SendspinDroid/wiki).

### Connecting to a Server

The app and a server find each other in one of two ways, and uses one at a time:

- **Waiting for a server** (the default): the app advertises itself as `_sendspin._tcp` and listens on port 8928 (or another port if that one is taken; the one in use is shown). A server that finds it connects by itself. A notification says the app is waiting, so a server can connect while the app is in the background. If several servers connect, the one that is playing is kept; a server with nothing to play does not take the player from one that has.
- **Searching for servers**: tap "Search for servers instead". The app stops advertising and lists the servers it finds; tap one to connect. "Wait for a server to connect instead" goes back.
- **Manual entry**: tap "Add Server" and type your server address (e.g., `192.168.1.100:8927`). Saved servers are listed in both modes. Connecting to one stops the advertising for as long as that connection lasts.

With Auto-Start on Boot, the app starts waiting for a server when the device boots, or connects to the default server if it is set to search.

The app connects on the local network only. To use it away from home, bring the device onto that network with a VPN.

### Requirements

- Android 8.0 (Oreo) or higher
- A [SendSpin](https://www.sendspin-audio.com/) server on your network that speaks SendSpin 1.0 (Music Assistant 2.11 or newer)

### Installing the APK

Since SendSpin Player isn't on the Play Store, you'll need to allow installation from your browser:

1. Download the APK from [Releases](https://github.com/chrisuthe/SendSpinDroid/releases)
2. Open the downloaded file
3. When prompted, tap **Settings** -> enable **Allow from this source**
4. Go back and tap **Install**

## Architecture

SendSpin Player is built with native Kotlin:

- **Jetpack Compose** for the whole interface
- **A Media3 `MediaLibraryService`** that owns the connection and playback, for background audio, notifications and Android Auto
- **A shared Kotlin Multiplatform module** for the SendSpin protocol, encryption and clock synchronization
- **`AudioTrack`** for output, with playback aligned to the server's clock
- **Coroutines** for async operations and WebSocket communication

### The SendSpin Protocol

SendSpin Player speaks the [SendSpin Protocol](https://www.sendspin-audio.com/) -- a WebSocket-based streaming protocol designed for real-time synchronized audio. The server timestamps every audio chunk and each client uses precision clock synchronization to play them at exactly the right moment. The result: every speaker in your home plays the same beat at the same time.

## License

MIT -- see [LICENSE](LICENSE) for the full text.

## Third-Party Acknowledgments

SendSpin Player uses the following open-source libraries:

| Library | License | Copyright |
|---------|---------|-----------|
| [AndroidX](https://developer.android.com/jetpack/androidx) (Core, AppCompat, Activity, Lifecycle, Media3, Compose, Security, Preference, Fragment) | Apache 2.0 | The Android Open Source Project |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache 2.0 | The Android Open Source Project |
| [Kotlin, Kotlinx Coroutines & Kotlinx Serialization](https://github.com/Kotlin) | Apache 2.0 | JetBrains s.r.o. and contributors |
| [Ktor](https://github.com/ktorio/ktor) | Apache 2.0 | JetBrains s.r.o. and contributors |
| [OkHttp](https://github.com/square/okhttp) | Apache 2.0 | Square, Inc. |
| [Coil](https://github.com/coil-kt/coil) | Apache 2.0 | Coil Contributors |
| [Bouncy Castle](https://www.bouncycastle.org/) | MIT | The Legion of the Bouncy Castle Inc. |
| [ZXing](https://github.com/zxing/zxing) (pairing QR code) | Apache 2.0 | ZXing authors |

SendspinDroid IS NOT affiliated with or endorsed by Sendspin, Music Assistant or other Open Home Foundation projects directly, this is a standalone project.
