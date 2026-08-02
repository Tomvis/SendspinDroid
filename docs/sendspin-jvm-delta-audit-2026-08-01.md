# sendspin-jvm Delta Audit - 2026-08-01

Follow-up to [`spec-compliance-audit-2026-06-12.md`](spec-compliance-audit-2026-06-12.md),
which evaluated the official JVM library
([github.com/Sendspin/sendspin-jvm](https://github.com/Sendspin/sendspin-jvm)) at
**v0.3.0** (tagged 2026-06-09). This audit re-runs that comparison against
sendspin-jvm **HEAD (99c8efc, 2026-07-31)** and supersedes several of the June
document's conclusions.

Method: 227 distinct behaviors were extracted from the reference library's
sources, tests, and `plans/` docs, then each was checked against this fork and
adversarially re-verified against the real code.

**Result: 132 already covered, 75 refuted as non-gaps (or places this fork is
better), 9 confirmed - all low severity.**

## Headline

SendSpinDroid is ahead of sendspin-jvm for a player-role TV client. There is no
architectural win available from adopting the library, and the June
recommendation ("do not switch now") still holds - now with stronger evidence:

- **`JsonOptional` drives no behavior in the reference.** Its only consumer of
  `metadata.title` is a log line; `server/state` is handed to the app verbatim.
  This fork implements real absent-vs-null merge semantics one layer down in
  `PlaybackState.withMetadata` (`resolveStr`/`resolveInt`). That is a capability
  the library lacks, not one it offers.
- **`SEEK_HANDOFF_MS` / `pendingStopJob` is scheduled for deletion upstream.**
  The reference's own `CLAUDE.md` "Future simplifications" lists it as a
  temporary aiosendspin workaround. `PlaybackService.enterIdle()` already
  achieves the same device-teardown avoidance.
- The reference does not implement sync-state error reporting, `external_source`,
  decoders, audio output, or any of this fork's proxy/WebRTC/watchdog machinery.

## Status of the June 2026 audit items

All P1/P2/P4 items and P3 items 6/7/8/10 landed on `enhanced` and were verified
against HEAD. Two remain open, both deliberately:

| Item | State | Note |
|------|-------|------|
| P3.9 server-initiated connections | **Still open - do not build now** | Spec PR #122 is mid-replacing this model with Noise/PSK pairing plus activities-based arbitration and a new `concurrent_attempt` goodbye reason. sendspin-jvm itself is still on the pre-encryption `connection_reason` model. Building now means implementing a spec being rewritten. |
| P4.13 `group/update.playback_state` leniency | **Largely obsolete** | The June doc's status note was wrong - no commit ever touched this (`git blame` dates the enum to 6538e4c4, 2025-12-24, predating the audit). But the spec moved to `playing \| paused \| stopped` and all three already map correctly. Not worth touching. |

Note P1.1 ("move `state` to top-level") was correct in June and is now itself
superseded - see below.

## Changes made

### 1. `server/command` `mute` with a missing field no longer unmutes

`MessageParser.kt` defaulted a missing `mute` field to `false` and applied it, so
a malformed `{"command":"mute"}` frame **audibly unmuted the device and restored
the last volume**. This was also internally inconsistent: `volume` (`-1` ->
out of range -> ignored) and `set_static_delay` (range-checked and logged) both
already rejected missing/invalid values.

The old behavior was pinned by a test (`parseServerCommand_muteMissing_usesDefault`),
which has been replaced by `parseServerCommand_muteMissing_isIgnored` plus a
non-boolean case.

### 2. `client/state` now emits `available` alongside `state` (spec PR #115)

Spec PR #115 replaced the `state` enum with an `available` boolean. aiosendspin
still normalizes the legacy field but flags the client noncompliant and will
reject it once `allow_noncompliant_clients` defaults to false. Both fields are
now sent, so pre- and post-#115 servers are satisfied.

**The mapping is `available = (syncState != "external_source")`, deliberately not
the reference's hardcoded `true`.** The reference hardcodes it only because that
library has no external-source concept; this fork does, and relies on it
(`AUDIOFOCUS_LOSS` -> `setExternalSource(true)` -> server parks us in a solo
group). Equally important, `"error"` must stay `available = true`: it is reported
until the Kalman filter converges, which includes every connect and every
re-anchor, so mapping it to `false` would evict the player from its group each
time. Three tests lock this down.

### 3. `client/command` sends `muted`, not `mute`

The spec is asymmetric and the reference confirms it: client -> server
`client/command` uses `muted` (`Messages.kt:145`); server -> client
`server/command` uses `mute` (`Messages.kt:325`). This fork sent `mute` in both
directions. Currently a dead path (`setGroupMute` has no callers), so this is a
correctness-when-wired-up fix; the parser side was already right.

### 4. Underrun counter is edge-triggered

`SyncAudioPlayer` incremented `bufferUnderrunCount` once per poll iteration while
starved, so a single brief starvation showed as a three-digit number in the stats
sheet. Now latched via `inUnderrun`, counted once per event, and logged once.
This also produces the edge signal `AdaptiveBufferPolicy` needs.

The latch must be re-armed wherever the queue is deliberately emptied, or a
stale latch swallows the next real event. Since there were six such sites, the
`chunkQueue.clear()` + `totalQueuedSamples.set(0)` pair was extracted into
`discardQueuedAudio()`, which also clears the latch - making the invariant
structural rather than a convention each new call site has to remember.

### 5. Removed the dead Java-WebSocket dependency

`org.java-websocket:Java-WebSocket:1.6.0` (~140 KB) had zero source usages after
the server-initiated scaffolding was pruned in 3c400e8, and the ProGuard
keep-rules actively prevented R8 from shrinking it out. Both removed; release
build with R8 verified.

## Open, needs a decision

### Server-pushed `static_delay_ms` is not persisted

`SendspinTimeFilter.resetAndDiscard()` deliberately zeroes `serverSyncOffsetMicros`,
with a documented rationale about not letting one server's correction skew
another's. That objection is real but argues only against persisting a *global*
scalar.

Failure scenario: a controller calibrates +250 ms for the AVR; Wi-Fi blips;
`PlaybackService` reconnects -> `prepareForConnection()` -> the 250 ms is gone.
The Shield then plays 250 ms early while still reporting `synchronized`, and
advertises a `static_delay_ms` 250 low so the server sizes its send-ahead wrong.
Recovery depends on the server re-pushing unprompted.

Suggested shape, which respects the existing rationale: persist **keyed by
`server_id`** in `UserSettings`, written from the existing
`PlaybackService.onSyncOffsetApplied` override, restored in
`SendSpin.onHandshakeComplete` only when the live value is still 0 (so a live
push always wins and the restore is a pure cold-start seed). Clamp -5000..5000,
not the reference's 0..5000, since the MA `client/sync_offset` extension is
signed. Must not reuse `KEY_SYNC_OFFSET_MS` - that is the user slider.

### `staticDelayMicros` sign convention

The reference subtracts static delay at schedule time; this fork may double-count
roughly 2x the measured hardware latency. Inaudible on a single player (a
constant offset just shifts everything), audible only in a multi-room group or
when compared against the Python CLI.

**Do not blind-flip the sign.** The DAC-aware start-gating path forms a
difference of two `clientToServer` results, which cancels the constant term, so
the real exposure may be limited to the Kalman fallback path and to sync-error
measurement. Verify empirically against the Python CLI on the same group first.
If confirmed, the fix is to separate the semantics - subtract
`autoMeasuredDelayMicros` (hardware compensation) while continuing to add
`userSyncOffsetMicros`/`serverSyncOffsetMicros` (user intent) - not to flip the
sum.

### Buffer eviction under low-memory mode

Capacity eviction removes the queue head (about to play) rather than the
furthest-future chunk, so each eviction makes an immediate audible hole while
retaining the surplus future audio it was trying to make room for. No effect in
the shipped default configuration; only reachable if low-memory mode is enabled.
Because arrival order is timestamp order over an ordered WebSocket, refusing the
*incoming* chunk is equivalent to the reference's evict-furthest-future policy
and needs no tail removal (which `ConcurrentLinkedQueue` cannot do efficiently).

## Not worth adopting

- **Perceptual volume curve** (`(vol/100)^1.5`) - Android's `STREAM_MUSIC`
  already applies a perceptual mapping; the reference hand-rolls it because it
  applies software gain per track.
- **Volume/mute persistence via `ClientSettingsStore`** - delegated to the
  Android device stream, which survives reboots and is re-seeded on connect.
- **`visualizer@v1` / `color@v1` roles** - correctly not claimed. Advertising a
  role obliges implementing all its messages.
- **Bounded discovery retry** - the reference retries with zero delay and clears
  its server map between attempts; this fork's behavior is better.
- **Periodic 5 s `client/state` heartbeat** - this fork emits on state change
  plus adaptive-buffer updates, which is strictly better informed.
