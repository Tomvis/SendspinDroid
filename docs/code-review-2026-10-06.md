# Code Review: Simplicity and Quality - 2026-10-06

Review of `main` at `4766b14` in seven sections, after the Sendspin 1.0.0-rc1
work. Goal, in the owner's words: "The concept (sync clocks, measure drift, play
at the right time) is somewhat simple, let's try to keep it that way without
costing accuracy."

Read-only review. Nothing here has been changed yet. Items marked **verified**
were re-read by a second pass; everything else is one reviewer's reading of the
code and its callers, not reproduced on a device unless stated.

Sections: 1 clock and timing, 2 audio output, 3 playback orchestration,
4 session layer, 5 wire layer, 6 security, 7 connection management and UI
boundary. Not reviewed: the Music Assistant command client and the Compose
screens as visual code.

---

## 1. The picture

The measured sync path is small and sound: DAC-aware start, per-chunk
measurement, one-frame correction, one-shot resync (about 700 lines of
`SyncAudioPlayer`), a standard Kalman clock filter, and a wire layer and crypto
core that transcribe the spec closely. The reviewers recommend leaving those
alone.

The excess is around it, and has three sources:

1. **A reconnect design that was replaced but not removed.** `PlaybackService`
   sets `selfReconnectEnabled = false` (**verified**), so the client's own
   reconnect loop, backoff, network pause, clock freeze/thaw and the
   play-from-buffer DRAINING state never run in production. They still account
   for about 330 lines of `SendSpin`, about 130 of `SyncAudioPlayer`, about 110 of
   `PlaybackService`, about 85 of the time filter and roughly 1,400 lines of
   tests.
2. **A view layer that was replaced but not removed.** `MainActivity` detaches
   the XML layout at startup (**verified**) and still has 122 references to its
   views: about 950 lines of the activity and about 3,500 lines of layouts and
   view classes.
3. **State mirrored between layers instead of owned once.** Connection state
   has eleven representations and takes nine hops to reach the screen. Stream
   state is held by the handler, the service and the player. Metadata exists in
   six copies. Stats are declared four times.

Several real defects sit in those seams. They are listed first.

---

## 2. Defects

### 2.1 Connection

| # | Defect | Where | Status |
|---|---|---|---|
| D1 | A locally initiated `close()` fires no callback and never leaves the connected state. The stall watchdog, a protocol failure (including the cleartext rejection added in #266) and unpair all drop the socket and leave the app "connected" with nothing reconnecting | `BaseWebSocketTransport.kt:264-269`, callers `SendSpin.kt:593, 1311` | **verified** by reading; found independently by two reviewers; not reproduced |
| D2 | Auto-reconnect is triggered only by `MainActivity`. With no activity (task swiped away, boot auto-connect, Android Auto) or after activity recreation, a dropped connection is not retried | `MainActivity.kt:1811-1832`, `PlaybackService.kt:2963-2969` | **verified** by reading; found by three reviewers |
| D3 | Time sync is not stopped on an abnormal drop, so after the next handshake `client/time` can be sent before `server/activate` | `SendSpin.kt:1595-1669` | spec violation in a window of tens of ms |
| D4 | Per-connection state survives into the next connection because one client object lives for the whole service: `unpairHandled` is never reset (a second `server/unpair` is ignored); `dynamicPairingFlow` keeps the previous connection's handshake hash; pairing timers and a shown code are not cleared on a drop (`onConnectionClosedForPairing()` has no caller) | `SendSpinProtocolHandler.kt:1263, 1422, 1332` | |
| D5 | After `server/unpair` the app auto-reconnects; the spec says it should not | `SendSpin.kt:264`, `MainActivity.kt:1812-1829` | |
| D6 | `client/state` is gated on "hello received", not "activation received"; `setVolume`/`setMuted` are not gated at all | `SendSpinProtocolHandler.kt:425-649` | |
| D7 | The watchdog's stream-active flag is cleared by `stream/clear`, so after the first skip it uses the 20 s idle threshold while audio plays | `SendSpin.kt:865` | |

### 2.2 Audio

| # | Defect | Where | Status |
|---|---|---|---|
| D8 | If a FLAC or Opus decoder fails to start, or the codec is unknown, compressed bytes are played as PCM: full-scale noise | `AudioDecoderFactory.kt:29-49`, `PlaybackService.kt:1187-1201` | found by two reviewers |
| D9 | Decoded audio is stamped with the timestamp of the chunk just submitted, not the one it came from. If a device's codec has one chunk of latency, compressed streams play one chunk late and no measurement can see it | `MediaCodecDecoder.kt:103`, `PlaybackService.kt:1160` | needs a device check (count empty decode returns); affects accuracy |
| D10 | While locally paused (transient audio-focus loss) the queue grows without bound, about 11.5 MB/min, because the server keeps streaming | `SyncAudioPlayer.kt:675-746` | |
| D11 | One pause flag is shared by server pause, focus loss and headphone unplug, and `clearBuffer()` clears it. Likely effects: unplug headphones and the next skip resumes on the speaker; a track change during a call plays over the call | `PlaybackService.kt:1387-2616`, `SyncAudioPlayer.kt:1074` | inference |
| D12 | After a drained buffer the player is stopped, kept, and later reused without being started again | `PlaybackService.kt:1303` | only reachable through the dead DRAINING path today |
| D13 | Stream clear runs on a different thread from `queueChunk`; the player's generation counter does not protect the queue, and the loop's peek-then-poll can discard the wrong chunk | `SyncAudioPlayer.kt:1339-1363, 1949, 2088` | |
| D14 | In PLAYING, `dacTimestampsStable` can be false for the rest of a stream (after a resume or a fallback start), which disables write pacing | `SyncAudioPlayer.kt:1699, 1864` | sync measurement unaffected |

### 2.3 Metadata and UI

| # | Defect | Where |
|---|---|---|
| D15 | A full-state metadata update still inherits the previous track's album, artist, artwork URL and duration when the new track omits them: the parser is right, then two layers convert absent to "" and back to "keep" | `SendSpin.kt:818-821`, `PlaybackService.kt:1501-1504`, `PlaybackState.kt:45-81` |
| D16 | Artwork clears never reach the lock screen; the previous image stays until the next loads | `MetadataForwardingPlayer.kt:108-117` |
| D17 | Device Volume slider snaps back: `setVolume` does not update the cached value that every later extras broadcast resends | `PlaybackService.kt:2240-2253, 2047` |
| D18 | Track progress shows the previous track's elapsed time at the start of a new track: the position timestamp is only stamped when position > 0 | `PlaybackState.kt:79`, `TrackProgressBar.kt:61` |
| D19 | Add-server button under the taskbar: the server list is not a `Scaffold` and ignores navigation-bar insets | `AppShell.kt:124-150`, `ServerListScreen.kt:215-219` |
| D20 | A lost mDNS server may never be removed: loss is reported by service name, the list is keyed by TXT name | `NsdDiscoveryManager.kt:103` |
| D21 | Editing a saved server rewrites its path to `/sendspin` | `AddServerWizardActivity.kt:377-380` |

### 2.4 Security

| # | Weakness | Where | Status |
|---|---|---|---|
| S1 | The secrets file (identity private key, pairing PSK, long-term PSKs) is included in Android backup, and when the encrypted store cannot be opened the code opens the same file name in plaintext. After a restore or device transfer this would silently mint a new identity, lose all pairings and write secrets unencrypted | `AndroidManifest.xml:87`, `UserSettings.kt:137-160` | manifest and fallback **verified**; the restore behaviour is the reviewer's inference from the library |
| S2 | A failed pairing-record write is still reported as a successful pairing | `InMemoryTrustStore.kt:38-51`, `EncryptedPrefsTrustStore.kt:42-48` | |
| S3 | Pre-rc1 unbound records skip the server-identity check | `PskCandidateSet.kt:64-67` | |
| S4 | Test seams reachable from app code: a pinnable ephemeral key and CPace scalar on public constructors | `SendSpinHandshakeDriver.kt:51-56`, `CPaceResponder.kt:15-19` | no production caller |
| S5 | Key wiping is half-implemented; `NoiseTransport.destroy()` has no caller | `NoiseSession.kt:164-166, 243-246` | low severity |
| S6 | A cleartext dispatch overload survives on the transport listener for tests only | `SendSpin.kt:1581-1584` | unreachable in production |
| S7 | Saved-server records still persist removed remote/proxy URLs and tokens in plain preferences | `UnifiedServerRepository.kt:421-488` | |

The cryptographic core (Noise KKpsk2, CPace, wrapping, code derivation) was
found correct against the spec and about as small as it can be.

---

## 3. Dead code: delete, no behaviour change

Approximate production lines, with tests in brackets.

| Area | What | Lines |
|---|---|---|
| Session | The client's reconnect loop, backoff, network pause, freeze/thaw wiring, `suppressAutoReconnect`, `isRecoverableError`, `getClientId`, `onServerDiscovered`, write-only fields | ~380 (+~1,400 tests) |
| Service | Unreachable `Failed(Exhausted)` branch, DRAINING handling, unread extras, parameter-ignoring broadcast wrappers, `decoderReady`, `DecodeTask` equality, second `NetworkEvaluator`, unused `updateSyncOffset` | ~350 |
| Service tests | Eight test files that re-implement a method body in the test and assert on the copy | (~1,800) |
| Player | `REANCHORING` state (never observable), stuck-state watchdog that cannot detect what it is for, `setVolume`, write-only members, unfed stats, about 130 lines of stale documentation | ~350 |
| Clock | `baselineClientTime`, unused `rtt` parameter, duplicate accessors, `stability` (always 1.0), `resetAndDiscard`, unreachable branches | ~90 |
| Clock | `AdaptiveBufferPolicy`: outputs a constant 1500 ms on any link under about 700 ms RTT; half its inputs are never supplied | ~250 (+215) |
| Wire | Unused constants and parameters, hand-written equality nobody uses, duplicate goodbye constants, proxy-era transport hooks | ~120 |
| Security | `PairingConfigStore` interface and rotation, the never-read `used` flag, the obsolete psk_id namespace rule, uncalled trust-store members, token decode, duplicate scalar multiplication | ~250 (+70) |
| UI | The detached XML view layer in `MainActivity` and its layouts, view classes and a second unused server list | ~950 + ~3,500 |
| UI | 268 of 563 strings are unreferenced; dead `UserSettings` keys; unread view-model flows | ~350 |
| Non-rc1 | `client/sync_offset` and top-level `server/state.state` handling (confirm Music Assistant no longer sends them) | ~120 |

Total: roughly 3,000 lines of production Kotlin, 3,500 of layout and view code,
and 3,500 of tests.

---

## 4. Simplifications that keep behaviour

| # | Change | Effect |
|---|---|---|
| B1 | One `endConnection()` teardown in the client, called from every way a connection ends | Fixes D1, D3, D4; replaces six partial resets |
| B2 | Synchronous send: a plain lock in the wire codec in place of a coroutine mutex | Five send paths become two; removes the only `runBlocking`; goodbye ordering holds by construction |
| B3 | One reset in the player, run on the audio thread | Five drifted copies of a 25-line block become one; removes cross-thread races on the sync filter |
| B4 | Player starts once and releases once | ~120 lines defending a restart cycle that does not exist |
| B5 | One owner for "is a stream active, in what format": the handler passes the previous config with `stream/start` | Removes the service's copy and `StreamStartAction` |
| B6 | One mute mechanism (track gain) and one scheduling helper for metadata and artwork | ~40 lines |
| B7 | Two time-sync modes in place of five; the jitter-adaptive modes are live for about one burst per connection | ~50 (+150 tests) |
| B8 | Collapse the PSK and trust-store types: ten files and ~610 lines for a list of keys with a lookup | to ~300 lines in two files |
| B9 | One action vocabulary shared by the two pairing flows; plain methods in place of event classes | flows 594 -> ~380, interpreters 120 -> ~40 |
| B10 | Five small JSON accessors and one message-builder helper | ~70 lines; makes wrong-type handling consistent |
| B11 | Wake locks without the 20-minute refresh timers | ~60 lines; removes a way to lose the lock mid-playback |
| B12 | One transport class in place of base plus single subclass | ~45 lines |

`buildJsonObject` versus `@Serializable` classes was assessed: stay with
`buildJsonObject`. Serialization classes save 20-40 lines and make wire
correctness depend on encoder configuration.

---

## 5. Decisions for the owner

| # | Decision | Options | Risk |
|---|---|---|---|
| C1 | **Who owns reconnect.** Today: client loop disabled, coordinator loop started by the activity | A: keep the coordinator loop, delete the client's, start it from the service (same behaviour, works without the activity). B: re-enable the client's loop and delete the coordinator's (restores play-from-buffer and clock restore across a reconnect; has not run in production since the coordinator took over) | A low; B needs device checks |
| C2 | **Apply the drift estimate.** Conversion holds the offset flat between updates, so the reference steps every ~3 s and the player chases it. The stated reason (it mirrors the Python reference) is wrong: the reference and the spec apply drift | Apply drift in the conversion (simpler and likely more accurate, but the sync loop was tuned against the staircase), or shorten the converged sync interval from 3 s to 1 s (no model change) | needs device measurement |
| C3 | **`min_buffer_ms`.** | A: a constant per profile (delete `AdaptiveBufferPolicy`; identical behaviour). B: the spec method, from chunk arrival delay using `send_ahead` (~50 lines; changes latency and underrun margin) | A none; B listening tests |
| C4 | **Remove the fallback start path** and the pre-sync pending buffer (~200 lines). It starts by wall clock, ignoring silence already in the track, and relies on a later resync. No test covers it | Remove (a device that never yields timestamps would not play), or keep behind an explicit unsynchronised state | needs one capture with info-level sync logs to confirm it never runs on the T901 |
| C5 | **Local interruptions as mute, not pause.** Focus loss and headphone unplug silence the output while audio keeps draining in sync | Fixes D10 and D11; resume is instant and exact | device check |
| C6 | **Service-to-UI mechanism.** Session extras and custom commands for a single-process app | One in-process state holder collected by the view model (removes ~600 lines of `MainActivity`, ~150 of the service). MediaSession stays for the notification, lock screen, Bluetooth and Auto | device check |
| C7 | **`SendSpinPlayer` on Media3 `SimpleBasePlayer`**, removing `MetadataForwardingPlayer` | ~1,000 lines become ~200; fixes D16 | re-verify lock screen, Auto, AVRCP |
| C8 | **What a failed open of the encrypted store should do** (S1) | Plaintext in a separate file, visibly; or recreate encrypted and lose secrets; or refuse pairing | none to protocol |
| C9 | **Drop unbound records and removed remote/proxy data** (S3, S7) | Beta users with record-mode records re-pair; stored proxy tokens are discarded | irreversible for that data |
| C10 | **Multiplatform structure.** Targets are Android and JVM; the JVM target has one consumer, the conformance tool, and `commonMain` is already JVM-only | Keep, or become a plain JVM library (removes 15 expect/actual pairs and three source sets; loses the on-device crypto test unless rehomed) | none |
| C11 | **AES-GCM suite**: implemented and vector-tested, never negotiated | Keep, or delete ~70 lines threaded through every transcript file | vector tests and strict-server check |
| C12 | **`DefaultServerPinger`** (365 lines) was built for off-LAN modes that no longer exist | A 15-line retry, or nothing if C1 keeps retrying the default server | device check |

Lower-value design notes are in the reviewers' reports: a per-connection handler
object, a single session thread, moving the handler into the shared module,
moving sync offset and output delay out of the clock filter, and removing
forgetting from the sync-error filter.

---

## 6. Suggested order

1. **Defects that are small and independent**: D1 (six lines), D8, D15, D17,
   D18, D19, S1 backup rules, S2.
2. **Decide C1**, then delete the losing reconnect layer and DRAINING with it,
   and add the single teardown (B1). This removes the most code and fixes D2-D5.
3. **Delete the dead view layer** in `MainActivity` and the dead code in
   section 3.
4. **Player tidy-up**: B3, B4, C5, and C4 after the log capture.
5. **Measured experiments on the tablet**: C2 and D9, one at a time, with
   before/after numbers.
6. Structural items (C6, C7, B8, B9) as separate, individually verified
   changes.
