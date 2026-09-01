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

1. **Android Auto browse tree is dropped.** The browse tree is entirely
   MA-backed (`getPlaylists`, `getAlbums`, `getArtists`, `getRadioStations`,
   `search`). With MA gone there is nothing to browse. Android Auto retains
   now-playing and transport controls via MediaSession only.

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
