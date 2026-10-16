# Timing and Playback Audit - 2026-10-14

End-to-end check of clock sync, ingress, stream lifecycle, decoding, scheduling,
sync correction, reported player state and buffering against the Sendspin spec.

- **App:** `main` at `c2d0bdd3` (code identical to 2.0.0-Beta21).
- **Spec:** `Sendspin/spec` `origin/main` at `ee8aad9` (2026-10-08, "Define where
  receive timestamps are taken"). Normative files read: `messaging.md`,
  `roles/player/v1.md` (and cross-references).
- **Time-filter reference (normative by reference):** `Sendspin/time-filter` at
  `531e270` (2026-10-14, "Ignore samples with non-positive max_error"). This commit
  landed today and is newer than anything the app was built against.
- **Server reference:** `Sendspin/aiosendspin` at `d024f53` (2026-10-13).

## Method

1. Nine read-only auditors, one per area, listed every client-side MUST/SHOULD
   sentence in their passages, quoted it, and traced the code
   (260 requirements: 182 comply, 35 partial, 18 deviate, 13 need a runtime
   check, 12 not applicable).
2. Nine skeptical verifiers re-derived each deviation from the code, the spec
   text, the Android framework sources (SDK `android-36`) and AOSP native code,
   and tried to refute it (135 items: 59 confirmed, 60 partial, 10 need a runtime
   test, 6 refuted). Severities and reachability below are the verified ones.
3. Device run on the T901 (Android 15) against MA Production, speaker muted,
   log level Debug for the run (restored to Warn afterwards).

Severity: **S1** MUST broken in normal use (audible/sync/interop); **S2** MUST
broken in an edge case, or SHOULD missed in normal use with audible effect;
**S3** SHOULD missed, or rare-edge MUST with minor effect; **S4** docs/tests only.
No S1 was found.

## 1. Verdict

The core of the timing path is spec-conformant:

- The Kalman filter is a term-for-term port of `cpp@531e270` (predict, update,
  adaptive forgetting, drift-significance gate, both conversions, Config
  defaults). Three local layers sit on top of it (section 3.2).
- Transmit and receive timestamps are taken where the spec says (T1 in the
  transport's `beforeWrite`, T4 and chunk arrival at frame delivery, before
  decryption), on one monotonic clock (`System.nanoTime`) shared with
  `AudioTimestamp`.
- The audio chunk header, unsigned `send_ahead`, its saturation values, the use
  of `send_ahead` only for delay measurement, fragmentation, and the
  delivery-order processing of `stream/start`, `stream/clear` and `stream/end`
  relative to audio all comply.
- `available` is not reported true until the filter converges (per connection).
- The correction loop measures exactly the spec's error, and drops/duplicates
  single whole frames bit-exactly, at most one 21 us step per 20 ms
  (0.104-0.113 %, well inside +/-0.5 % over 150 ms).
- `stream/clear` and `stream/end` clear the right buffers; `min_buffer_ms` is
  derived from `arrival - compute_client_time(timestamp - send_ahead)` with
  saturated samples excluded; `output_delay_ms` is clamped, subtracted and
  persisted.

What does not hold is mostly in the **recovery paths** (what happens after a
large error, an underrun, a network event, or a format change), the **volume
law**, and a few **latent** cases that no current server triggers.

## 2. Device measurements (T901, MA Production, Opus 48 kHz, Wi-Fi LAN)

| Check | Result |
|---|---|
| Steady state, 286 s | Raw sync error (1 Hz): never above 1 ms; p95 647 us, max 933 us. Smoothed (10 s): within +/-90 us. No one-shot resyncs, no reanchors. |
| Correction activity | +243 inserted / +37 dropped single frames in 286 s (about 1 per second, one frame each). |
| Clock filter | error 193-202 us, drift -16.3 ppm, every update moves the offset by about -54 us (the drift itself). Best RTT 6.9-49.6 ms, median 9.1 ms, none <= 0. |
| Client queue with MA | about 29.7 s (1,424,640 frames) - aiosendspin's 30 s horizon. |
| Sync offset +800 ms during playback | `Large sync error: -800ms` -> `Triggering reanchor: clearing buffers`. Queue 29.7 s -> 0. **30.5 s of silence**. |
| Sync offset back to 0 | `Large sync error: 800ms` -> reanchor. **29.8 s of silence**, where dropping 800 ms of late audio would have given none. |
| Codec Opus16 -> PCM32 mid-stream | Old track released with about 296 ms still written; new cold track dropped 2 stale chunks (50 ms) + 15 ms prefix. About 0.3 s silence and 0.36 s content lost (from logs; speaker muted, not acoustic). Releasing the Opus decoder logged 4x "MediaCodec discarded an unknown buffer". |
| LinkAddressesChanged | 0 events in about 11 min on a dual-stack network with six IPv6 temporary addresses. |

The accuracy numbers are the app's own measurement through `AudioTimestamp`;
an acoustic/loopback measurement against a reference player is still open.

## 3. Findings

### 3.1 Recovery paths in `SyncAudioPlayer` (fix together)

| Id | Sev | Finding | Reach with MA |
|---|---|---|---|
| E1-22 / E1-13 / G-12 | S3 | A raw error above 500 ms **clears the whole decoded queue** instead of dropping the late prefix or inserting silence (`SyncAudioPlayer.kt:1573-1579`, `triggerReanchor` :1422-1443). With MA's 30 s horizon that is 30 s of silence. **Device-confirmed** via the sync-offset dialog. A server `set_output_delay` step > 500 ms does the same (v1.md "SHOULD avoid audible interruptions"). | Any sync-offset change >= 500 ms in Settings; MA does not appear to send `set_output_delay` |
| E1-O2 | S3 | That reanchor is decided from **one raw reading**, unlike the one-shot snap (which needs raw and smoothed to agree) and unlike the Python reference. | Unknown / rare |
| E2-O2 / E1-16 | S2 | An **empty decoded queue is treated as an output underrun** (:1533-1543): every 5 ms it resets the sync filter and restarts the 500 ms correction hold, even with ~300 ms still in the track. On a live stream whose chunk duration + arrival delay exceeds lead - 300 ms, corrections never run and the +/-1 ms floor is lost within a minute or two. | MA buffered: never. MA live: brief, occasional. Non-MA live server with >= 50 ms chunks at the min_buffer lead: continuous |
| E1-19 | S3 | In PLAYING, chunks that arrive late after a real underrun are written immediately and play late for about 0.5 s before a prefix snap (v1.md:139 SHOULD drop late chunks). | Live sources after a Wi-Fi stall longer than the lead |
| E1-O4 | S3 | The gap filler stamps silence at the old, already-past position after an upstream stall, roughly doubling the outage; allocation is uncapped Int math (OOM / overflow on very long gaps). | Live sources after an upstream stall longer than the lead |
| E1-O1 | S2 | `AudioTimestamp.framePosition` from `AudioTrack` wraps at 2^32 (SDK javadoc: "the low-order 32 bits of position is in wrapping frame units") but is subtracted from a 64-bit `totalFramesWritten` (:1845, :1857, :1374). After **24.85 h** of uninterrupted playing at 48 kHz, pacing stops writing and output stays silent until a clear/start. Fix: `(written - pos).toInt().toLong()`. | 24/7 radio or a long gapless queue |
| A2-O1 / E1-O5 | S3 | `updateSyncError` and the reanchor run against a reset, not-ready filter; inside the 5 s reanchor cooldown the one-shot resync is uncapped and `writeSilence` can block the audio thread for the clock-epoch difference. | Rare (filter reset during playback + recent reanchor) |
| C-05 | S2 | An in-place PCM-format change while the old player is still in WAITING_FOR_START with < 200 ms queued **deadlocks the decode worker** (old queue never drains): the rest of the stream is silent. | Rare |
| C-O3 / E1-O7 | S3 | The 200 ms buffered-before-start gate: a stream (or post-clear segment) shorter than 200 ms never plays; live starts can lose 0-30 ms. Same gate causes C-05. | Rare |
| E2-25 | S3 | The one-shot snap waits for the lagging smoothed estimate to pass 1 ms, so real steps of about 1-2.4 ms are slewed and exceed the floor for up to about 1 s. | Occasional, brief |
| E2-10 | S3 | A route with no usable `AudioTimestamp` runs open loop: starts late by the below-mixer latency and drifts unbounded. AOSP mixer tracks normally get a server-side timestamp, so this is rare. | Rare |

### 3.2 Time filter fidelity (`SendspinTimeFilter.kt`, `TimeSyncManager.kt`)

`messaging.md`: "Clients MUST use the time-filter algorithm". The core matches
`cpp@531e270`; three local layers change its output:

| Id | Sev | Finding |
|---|---|---|
| A1-29 + new | S3 | **IQR outlier pre-rejection** (:82-87, :351-354, :388-425) is in neither reference. After a genuine offset step it drops 9 of the first 12 samples (recovery 18-23 samples vs 9-14). On low-RTT links (wired TV boxes, very good 5 GHz) it **cascades during warm-up** once the cadence slows: the median trails the drifting offset and valid samples get rejected (simulated peak error 393-584 us vs 150-190 us). |
| A1-17 / A1-21 | S3 | **+/-500 ppm drift clamp** (:342, :469), with the covariance left unclamped. Up to about 100 us during warm-up; a few hundred us on jittery Wi-Fi. |
| A1-13 / A2-06 | S3 | **max_error <= 0 is clamped to 1 us** (`TimeSyncManager.kt:203`, filter :324) where `cpp@531e270` now ignores the sample; best-of-burst (`minByOrNull { rtt }`) also prefers it. The filter then snaps and stays pinned for minutes. MA's timestamps are causal with us resolution, so only a non-conforming server triggers it. |
| A2-O2 | S3 | `CLOCK_MONOTONIC` stops in deep sleep while the server's raw clock runs. A suspend under about 20 s while paused and connected leaves the converged filter off by the sleep time; the IQR gate slows recovery to tens of seconds. |
| A2-02 | S4 | The burst is pipelined at 50 ms spacing rather than send-and-await (README "Recommended Usage", non-normative). Irrelevant on a LAN. |
| A1-14, A1-26 | S4 | First-sample time check and `errorMicros` truncation differ trivially from the reference. |

Recommendation: make `SendspinTimeFilter` a pure port of `cpp@531e270` (remove
the IQR gate and drift clamp, ignore `maxError <= 0`, exclude `rtt <= 0` before
best-of-burst) and add a golden-vector test against a transcription of the C++.

### 3.3 Network events

| Id | Sev | Finding |
|---|---|---|
| E1-O3 / C-O1 / A2-13 | S2 | Any change in the active network's `LinkAddress` set (IPv6 address added/removed/deprecated) calls `SendSpin.onNetworkChanged()` (`PlaybackService.kt:712-717`), which **resets the time filter and clears the whole player queue** while the socket and stream continue (`SendSpin.kt:895-902`, `SendSpinEngine.kt:319-325`). Result: 3-7 s of muted/silent playback, and `available: true` re-sent with an unconverged filter. The reset is unnecessary: the WebSocket path survives an address change, and a real path change kills the socket, whose reconnect resets the filter. |
| new (V2) | S2 | `ConnectionCoordinator` uses `registerNetworkCallback(INTERNET)`, which reports **all** matching networks, not only the default. With cellular kept up in the background while on Wi-Fi, a cellular reconnect emits `IdentityChanged` (filter reset + reconnect of a healthy Wi-Fi connection). Needs a device check with mobile data on. |
| A2-O9 | S4 | The stall watchdog measures elapsed time on the wall clock. (It also currently heals long deep-sleep steps by reconnecting, so only move it to a monotonic clock together with the A2-O2 fix.) |

### 3.4 Volume and reported player state

| Id | Sev | Finding |
|---|---|---|
| F-01 | S2 | Server volume is mapped **linearly onto the STREAM_MUSIC index** (`PlaybackService.kt:1943-1958`); the device volume curve decides the gain. v1.md: "SHOULD convert volume ... as amplitude = (volume / 100)^1.5". On the AOSP default curve with 15 steps: volume 50 -> -21 dB (law: -9 dB), 20 -> -40 dB (law: -21 dB). In a mixed group this player is 6-19 dB quieter than spec-following peers. |
| F-02 | S3 | Server volumes 1-3 map to index 0 (silent) while `client/state` reports `volume: 3, muted: false`; steps inside one index bucket do nothing. |
| F-17 | S2 | The long-lived dial client reads the persisted `output_delay_ms` only at construction (`SendSpin.kt:283`); `connectLocal()` never re-reads it. A delay set later by a server-initiated connection is neither applied nor reported on later dialled connections in that service lifetime. Fix: `timeFilter.setOutputDelayMs(host.outputDelayMs())` next to `timeFilter.reset()`. |
| F-07 | S3 | An output-route change (BT/wired connect) that switches to a different stored index is not reported to the server. |
| F-13 | S3 | `volume` is advertised as settable on fixed/full-volume outputs (Android TV over HDMI, safe-media-volume), and the commanded value is reported even when nothing changed. |
| F-22 | S3 | `required_lead_time_ms` drops to the warm 650 ms after the first stream of a connection and is never raised again before a cold restart (paused track, new AudioTrack). |
| F-23 | S3 | `min_buffer_ms` increases are not debounced: one late chunk raises it within about 1 s for 5 minutes (window max, not an upper-tail percentile). With MA this costs the whole group about +0.9 s on every start/skip for 5 minutes after one stall. |
| F-31 / F-O4 / E2-06 | S3 | The non-spec **sync offset** (Settings slider, or a non-spec `client/sync_offset` push) shifts playout without being reflected in `required_lead_time_ms`/`min_buffer_ms`; a negative offset beyond about -90 ms truncates warm starts. A pushed offset survives later dialled connections. |
| A2-O3 | S3 | `client/state` is built on the caller's thread and sent from a later launch; concurrent senders can deliver a stale `available` last. Microsecond window. |
| F-03 | refuted | Volume ramp: AudioFlinger ramps track and stream volume changes over one mixer period on both mixer paths, so the SHOULD is met by the platform for this track. |

### 3.5 Format switches and codecs

| Id | Sev | Finding |
|---|---|---|
| C-04 / C-06 / D-18 / B-23 | S2 | An in-place `stream/start` that changes the PCM output format (bit depth or channels) releases the old AudioTrack as soon as its chunk queue empties, discarding the ~250-300 ms already written (AOSP does not mix a terminated track's remaining frames), and the new track starts cold and drops its late head. v1.md:98 "Clients MUST keep buffered chunks". **Device-confirmed** (Opus16 -> PCM32). Reached with MA when the user changes the codec preference to or from PCM while playing. |
| new (V5) | S3 | The old MediaCodec is released without an EOS drain, so a chunk whose PCM the codec still holds is lost at the switch (the device logged 4 discarded buffers). |
| new (V4) | S3 | A pending format switch can replace a player that a new stream has just reused, so the new stream plays through a wrong-format player (noise / wrong speed). Rare race. |
| C-08 | S3 | Both mono and stereo are advertised although a channel switch gaps (v1.md:98 SHOULD list a single `sample_rate` and `channels`). MA never switches channels by default. |
| D-15 | S3 | Opus `bit_depth` is not ignored (v1.md: "ignored for opus"): a non-16 value opens the AudioTrack in the wrong encoding. Latent; MA always sends 16. Fix: normalise to 16 in `parseStreamStart`. |
| D-09 | S3 | FLAC chunks with more than one frame (allowed by v1.md:31) break on the AOSP decoder, which emits one frame per input. Latent; MA sends one frame per chunk. |
| D-O1 | S3 | A fatal MediaCodec error is never recovered within a stream; `stream/clear` does not help. |
| D-O2, D-O3, D-O4, C-O6 | S4 | Opus packets > 5760 B leak an input slot; decoder output format unchecked; missing `codec` defaults to PCM; FLAC STREAMINFO after flush. All unreachable with MA. |

### 3.6 Availability, audio focus, role removal

| Id | Sev | Finding |
|---|---|---|
| C-26 | S3 | After reporting `available: false` (permanent focus loss), a `stream/start` that crossed that report makes the app take focus back, pausing the user's other app, and report `available: true`; the server ends the stream anyway. |
| F-40 | S3 | After a permanent focus loss nothing local (Play, opening the app) returns the client to `available: true`; only a server 'playing' / `stream/start` does. |
| C-21 | S3 | Removing `player@v1` stops output but keeps audio focus, wake/Wi-Fi locks and the idle track; a later group/update 'playing' re-takes focus on a connection that is no longer a player. |
| B-27 | S3 | While unavailable, in-flight chunks are decoded and played muted instead of discarded (v1.md:108 SHOULD). Negligible (one round trip of audio). |
| F-39 | refuted | Reporting `available: false` on a permanent focus loss is a permitted policy (the spec cites local media playback). |

### 3.7 Ingress, buffering, memory

| Id | Sev | Finding |
|---|---|---|
| B-O2 | S3 | Every audio chunk hops through the **main Looper** before the decoder (`SendSpinEngine.kt:269`, scope on the main dispatcher). On a live source a main-thread stall above about 350 ms underruns; the hop is not counted in `min_buffer_ms`. |
| G-01 | S3 | Normal mode advertises 13.44 MB `buffer_capacity` but has no cap on decoded PCM; a server with no horizon cap filling Opus to capacity would decode 13-17 minutes (155-204 MB). MA caps at 30 s, so latent. |
| B-O4 + new | S4 | The goodbye is encrypted on the caller's thread while `client/time` encrypts on the timer thread, so nonce order and wire order can rarely invert; a rejected enqueue of an already-encrypted frame leaves a nonce gap. |
| A2-10 | open | Receive stamps are taken at Ktor delivery (MUST met); the SHOULD "as close to arrival as possible" may lag by the processing time of frames queued ahead (rendezvous channel). Needs measurement. |

### 3.8 Docs and tests (S4)

- `StreamStartDecoderPipelineTest` and `DecoderSetupTest` test local copies of
  obsolete logic, not `SendSpinEngine`; they cannot catch regressions.
- No golden-vector test against the time-filter reference; tests pin the local
  deviations; every `SyncAudioPlayer` test runs at 48 kHz; `SyncErrorFilterTest`
  uses default, not production, parameters.
- `CLAUDE.md` describes `SendSpinClient.kt`/OkHttp (now `SendSpin.kt`/Ktor), one
  artwork channel (two are declared), and omits the 500 ms whole-queue reanchor
  from "Android Deviations".
- Stale comments: a spec 'error'/'synchronized' state and mute rule that the
  spec no longer has; `INTERVAL_MS` "4x per second"; "+/-4% rate adjustments";
  the unreachable REANCHORING state; `SyncErrorFilter` cadence (40 Hz, not 10 Hz);
  buffer-size comments (32 MB/8 MB); the format-switch comment that claims the
  old audio is played out; non-ASCII `us` symbols in log lines.

## 4. Still open (runtime)

- Acoustic or loopback accuracy against a reference player (the +/-1 ms floor
  and +/-0.5 ms target are only self-measured here).
- Rarity of one-shot resyncs over hours (0 in the 5-minute run).
- Cold `required_lead_time_ms` (1500 ms) on devices and BT outputs slower than
  the T901.
- Background-cellular `IdentityChanged` (needs mobile data on while on Wi-Fi).
- 24/32-bit mono low-latency tracks on every device that advertises them.

## 5. Suggested fix batches

1. **SyncAudioPlayer recovery:** keep the queue and re-enter start gating on a
   large error (E1-22/E1-13), confirm a reanchor over two readings (E1-O2),
   detect underrun from the output rather than the queue (E2-O2/E1-16/E1-19),
   signed 32-bit position differences (E1-O1), `isReady` guard plus a capped,
   interruptible snap (A2-O1), gap fill only ahead of the cursor with Long math
   (E1-O4), start gating once the head is due regardless of 200 ms (C-05/C-O3).
2. **Network events:** stop resetting on `LinkAddressesChanged`, compare
   address+prefix only, track the default network only (E1-O3, V2-new); detect
   deep-sleep steps and shift the filter (A2-O2).
3. **Time filter:** pure port of `cpp@531e270` plus golden-vector test (3.2).
4. **Volume:** dB-matched index mapping via `getStreamVolumeDb` (F-01/F-02),
   report route changes (F-07), read back / fixed-volume handling (F-13).
5. **Reported state:** reload `output_delay_ms` per connection (F-17), re-raise
   the cold lead (F-22), debounce `min_buffer_ms` increases (F-23), account for
   the sync offset (F-31/F-O4), build `client/state` inside the send (A2-O3).
6. **Format switches:** keep one AudioTrack format with software conversion, or
   overlap the old and new players and drain the decoder (C-04, V5/V4-new);
   advertise stereo only (C-08); normalise Opus bit depth (D-15).
7. **Focus/availability:** one policy for focus after `available: false`
   (C-26, F-40, C-21, B-27).
8. **Docs/tests:** section 3.8.
