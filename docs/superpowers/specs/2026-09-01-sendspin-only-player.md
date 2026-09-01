# SendSpin-Only Player: Scope and Target Architecture

**Date:** 2026-09-01
**Status:** Accepted
**Authoritative protocol spec:** https://github.com/sendspin/spec

## Goal

Refocus SendSpinDroid from a hybrid Music Assistant control app into a
purpose-built, spec-compliant SendSpin **player** for TVs, tablets and phones
used as music endpoints.

Music Assistant has its own, better native application. This app should not
compete with it. It should be the best way to turn an Android device into a
SendSpin playback endpoint.

## The conformance rule

Every feature must correspond to a role or message defined in the SendSpin
spec. This is the single test for whether code stays.

The spec defines exactly seven roles:

| Role | Purpose |
|---|---|
| `player@v1` | outputs audio |
| `source@v1` | captures audio from a local input and streams it to the server |
| `controller@v1` | controls the current group |
| `metadata@v1` | displays text metadata describing the currently playing audio |
| `artwork@v1` | displays artwork images |
| `visualizer@v1` | visualizes audio |
| `color@v1` | receives colors derived from the current audio |

And this complete message set:

`client/init` `server/init` `noise/handshake` `client/hello` `server/hello`
`server/activate` `client/state` `server/state` `client/time` `server/time`
`stream/start` `stream/clear` `stream/end` `stream/request-format`
`client/command` `server/command` `group/update` `pair/abort` `server/unpair`
`client/goodbye`

**The spec has no concept of a media library, browsing, search, or a viewable
playback queue.** `controller@v1` controls the *current group* -- the transport
commands observed on the wire against a production server were: `play`, `pause`,
`stop`, `next`, `previous`, `seek`, `seek_relative`, `volume`, `mute`,
`shuffle`, `unshuffle`, `repeat_off`, `repeat_one`, `repeat_all`, `switch`.

Anything that browses, searches, or lists content is Music Assistant REST/
WebSocket API surface with no protocol counterpart, and leaves scope.

## Decisions

1. **Android Auto keeps server selection; the MA library branches are dropped.**

   *Revised 2026-09-01 after implementation. The original wording claimed the
   browse tree was "entirely MA-backed" and could be removed wholesale. That is
   false.* `playback/AutoBrowseTree.kt` mixes two concerns in one file:

   - **SendSpin server selection** -- `MEDIA_ID_DISCOVERED`,
     `MEDIA_ID_SERVER_PREFIX`, `MEDIA_ID_SAVED_SERVER_PREFIX`,
     `MEDIA_ID_MESSAGE_NO_SERVERS`. This is a genuine SendSpin feature and the
     only way to choose or switch servers from a car. It **stays**.
   - **MA library browse** -- `MEDIA_ID_MA_PLAYLISTS`, `MEDIA_ID_MA_ALBUMS`,
     `MEDIA_ID_MA_ARTISTS`, `MEDIA_ID_MA_RADIO` and their item prefixes, backed
     by `getPlaylists` / `getAlbums` / `getArtists` / `getRadioStations` /
     `search`. This **goes**.

   Android Auto therefore retains server browsing plus now-playing and transport
   via MediaSession.

2. **The app is Now Playing first.** Primary UI is now-playing plus transport
   controls. When not connected, the server picker is shown. The four-tab
   navigation (`HOME`, `SEARCH`, `LIBRARY`, `PLAYLISTS`) is removed entirely.

3. **Remote access via WebRTC/proxy is cut for now.** The player advertises
   itself locally by default. Remote connection by entering a specific URL is
   retained. A SendSpin-native remote story (possibly WebRTC) will be specified
   later; it will not depend on Music Assistant's signaling server.

4. **Target devices are TVs, tablets and phones used as music players.** The
   form-factor foundation already exists (`LEANBACK_LAUNCHER`, `banner_tv`,
   `uses-feature leanback`, `ui/adaptive/FormFactor.kt`, `TvFocusHelpers.kt`,
   `isTvDevice`, `WindowSizeClass`) and is retained.

## Target architecture

```
Server picker  (not connected)
      |
      v
Now Playing  (connected)  -- artwork, metadata, transport controls, volume
      |
      +-- Settings
      +-- Diagnostics
```

No tab bar. No detail screens. No library.

### What stays

| Area | LOC | Why |
|---|---|---|
| `app/sendspin/` + `shared/sendspin/` | 14,513 | protocol, clock sync, crypto, transports |
| `app/playback/` | 6,060 | player role: audio pipeline, MediaSession |
| `ui/main/` | 5,170 | already contains the target shell |
| `ui/settings/`, `ui/theme/`, `ui/adaptive/`, `ui/dialogs/` | 2,810 | app chrome and form-factor support |
| `ui/server/`, `ui/wizard/` | 4,488 | server picker and setup (MA paths removed) |
| `app/discovery/` | 426 | local advertisement and mDNS |
| `app/diagnostics/`, `app/logging/` | 506+ | support tooling |

### What goes

| Area | LOC | Kind |
|---|---|---|
| `ui/navigation/` (home, library, playlists, search) | 4,132 | delete |
| `ui/detail/` (album, artist, audiobook, playlist, podcast) | 4,246 | delete |
| `shared/musicassistant/` | 4,345 | delete |
| `app/musicassistant/` | 2,162 | delete |
| `ui/queue/` | 1,811 | delete |
| `app/remote/` | 889 | delete |
| `shared/remote/` (SignalingClient) | 585 | delete |
| `sendspin/transport/ProxyWebSocketTransport.kt` | -- | delete |
| `io.getstream:stream-webrtc-android:1.3.4` | -- | drop dependency |

Approximately 18,200 LOC of 64,546 (28%).

### Surgical decontamination

These files stay but need MA references removed:

| File | Refs | Coupling |
|---|---|---|
| `playback/PlaybackService.kt` | 69 | Auto browse tree, queue updates, image proxy, MA DataChannel |
| `res/values/strings.xml` | 32 | user-facing MA strings |
| `ui/server/AddServerWizardViewModel.kt` | 20 | MA server setup path |
| `ui/AppShell.kt` | 17 | tab navigation, MA screen wiring |
| `MainActivity.kt` | 13 | MA state plumbing |
| `ui/player/PlayerViewModel.kt` | 12 | MA queue/track types |
| `UnifiedServerRepository.kt` | 8 | MA server records |
| `sendspin/SendSpin.kt` | 4 | `getMaApiDataChannel()`, `drainMaApiMessageBuffer()` |

The protocol core containing only 4 MA references is the key structural fact:
the dependency arrow points MA -> SendSpin and never the reverse, so this is a
deletion job at the UI and API layers, not an untangling job at the protocol
layer.

## Sequencing

**Construct, switch, then delete.** Build and switch to the player-first shell
before removing anything. This keeps the app working at every commit and makes
the deletion self-verifying: if the shell still works after a package is
removed, that package was genuinely orphaned.

Deleting first would leave the app without a usable main screen for the middle
of the project and make regressions hard to attribute.

## Dependency ordering (added 2026-09-01, after phase 1)

The stay/go table above lists packages to delete but implies they are independent.
They are not. Verified after the player-first shell landed:

- `com.sendspindroid.ui.navigation` is the ONLY genuinely orphaned package. One
  test imports it: `ui/compose/SearchScreenResultsTest.kt` uses
  `SearchViewModel.SearchState`.
- `com.sendspindroid.ui.detail` survives through exactly one edge:
  `ui/queue/SaveQueueAsPlaylistDialog.kt` imports `ui.detail.components.BulkAddState`.
  Removing the queue surface unblocks it.
- `com.sendspindroid.ui.queue` is the live queue surface, reached from
  `AppShell`, `NowPlayingScreen`, `NowPlayingHeadUnit` and `MainActivity`.
- `com.sendspindroid.musicassistant` **cannot be deleted wholesale.** Around 40
  files under `app/src/main` import it, including `PlaybackService`,
  `SendSpinApp`, `AutoVoiceSearch` and the add-server wizard. It is load-bearing
  for playback, artwork and server setup, not just for browsing.

The non-browse Music Assistant dependencies, and why they exist:

| Consumer | Uses | Purpose |
|---|---|---|
| `SendSpinApp` | `MaSettings.initialize()` | app-wide settings bootstrap |
| `AddServerWizard` | `MaEndpoint`, `MaSettings` | models Local / Proxy / Remote setup |
| `PlaybackService` | `MaProxyImageFetcher` | fetches artwork over the WebRTC DataChannel |
| `PlaybackService` | `queueUpdates` | prefetch about 1s before a track change |
| `PlaybackService` | `getMaApiDataChannel` | MA API tunnelled over WebRTC in Remote mode |
| `DefaultServerPinger` | `SignalingClient` | remote reachability probe |

The load-bearing consequence: `MaProxyImageFetcher` and the MA API DataChannel
exist because of REMOTE ACCESS, not because of browsing. In Proxy and Remote mode
artwork URLs are not directly reachable, so album art is tunnelled through MA's
WebRTC data channel -- which is why `sendspin/SendSpin.kt`, the protocol core,
carries four MA-aware lines. Cutting remote access therefore also retires the
image proxy, the DataChannel plumbing, the signaling client, and the wizard's
Proxy/Remote modes.

### Remaining work, as three plans

| Plan | Scope | Approx LOC | Depends on |
|---|---|---|---|
| A. Cut remote/proxy | `app/remote/`, `shared/remote/`, WebRTC dependency, `SignalingClient`, `MaProxyImageFetcher`, DataChannel plumbing, `SendSpin.kt`'s 4 MA lines, wizard Proxy/Remote modes | ~1,500 + native dep | nothing |
| B. Remove browse and queue | `ui/navigation`, the MA half of the Auto browse tree, `ui/queue`, then `ui/detail` | ~10,200 | nothing |
| C. Retire the MA client | whatever survives in `musicassistant/` | ~6,500 | A and B |

Plan B is being executed first.

### Queue decision

The queue UI is deleted entirely. SendSpin defines no viewable queue, so it has
no protocol counterpart, and `SaveQueueAsPlaylistDialog` writes to a Music
Assistant playlist -- a library-write feature outside the player role. Removing
it also unblocks `ui/detail`.

## What the WebRTC transport actually was (recorded 2026-09-01, before deletion)

Decision 3 stands -- remote/WebRTC is cut. But the original justification in this spec
was partly wrong, and the correction is worth keeping, because the code is recoverable
from git while the analysis is not.

**The transport was not Music Assistant infrastructure.** `remote/WebRTCTransport.kt`
implemented `SendSpinTransport` -- a peer of `WebSocketTransport`, not an add-on -- and
opened a DataChannel named `"sendspin"` carrying the full SendSpin protocol, binary
audio frames included. Remote SendSpin *playback* genuinely worked over it. A second,
separate `"ma-api"` DataChannel carried Music Assistant's control API; that half was
genuinely MA-specific.

Which parts were actually coupled to Music Assistant:

| Component | MA-coupled | Note |
|---|---|---|
| `WebRTCTransport` | No | carried the SendSpin protocol itself |
| `SignalingClient` | Default only | `signalingUrl: String = DEFAULT_SIGNALING_URL` was a PARAMETER, defaulting to `wss://signaling.music-assistant.io/ws` |
| ICE servers | No | public STUN: Google x2, Cloudflare, Home Assistant |
| `"ma-api"` DataChannel, `getMaApiDataChannel` | Yes | MA control API tunnel |
| `MaProxyImageFetcher` | Yes | see artwork note below |

**Artwork did not need the proxy.** `MaProxyImageFetcher` existed because "in REMOTE
mode, images hosted on the MA server (via `/imageproxy`) can't be reached" -- it tunnelled
MA's HTTP artwork URLs. SendSpin carries artwork natively as binary message types 8-11
(`ARTWORK_BASE = 8`, channels 0-3), so a SendSpin-native remote connection would receive
artwork over the same DataChannel as audio, with no proxy at all.

**The open question that was never answered.** SendSpin is a synchronized player and
`SendspinTimeFilter` has no remote-specific handling -- the same Kalman filter runs over
WAN as over LAN. Measured locally on a Relndoo T901_US: ~475 us sync error at ~16 ms RTT.
Whether that holds over internet latency and jitter was never tested. Any future
SendSpin-native remote design must answer this first: if sync cannot hold, remote
playback is a broken feature no matter how clean the transport is. Bandwidth is the
secondary constraint -- PCM 48k/2ch/16-bit is roughly 1.5 Mbps sustained; Opus or FLAC
is far less.

**Why it is still being cut.** The signaling endpoint was a third-party dependency for a
SendSpin-only app, the WebRTC native library is a large APK cost, and the sync question
is unanswered. A future remote story should be designed against the SendSpin spec rather
than inherited from Music Assistant's signaling infrastructure. The six existing test
files under `remote/` and `e2e/RemoteConnectWebRTCTest.kt` are worth reading before
rebuilding.

## Out of scope

- A SendSpin-native remote access design (deferred; see decision 3)
- `source@v1` role (line-in capture)
- `visualizer@v1` and `color@v1` roles
- Any replacement for library browsing
- Migration tooling for users of the MA features

## Known related defect

Issue #253: `stream/end` is silently ignored because the client compares the
versioned `player@v1` against the spec's unversioned `player`. Independent of
this work but touches the same conformance theme.

## Verification criteria

1. `:app:assembleDebug` and the unit test suite pass at every task boundary.
2. No import of `com.sendspindroid.musicassistant` or `com.sendspindroid.remote`
   remains outside deleted files.
3. `io.getstream:stream-webrtc-android` is absent from the dependency tree.
4. On a device: connect to a SendSpin server, play audio, verify transport
   controls, artwork and metadata; verify Android Auto shows now-playing with
   working transport and no browse tree.
5. Verified on phone, tablet and TV form factors.
