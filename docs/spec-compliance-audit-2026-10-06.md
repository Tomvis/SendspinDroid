# Sendspin 1.0.0-rc1 Compliance Audit - 2026-10-06

Audit of SendSpinDroid at `0d14aba` (branch `fix/output-delay-naming`, 2.0.0-Beta17)
against `Sendspin/spec` tag `1.0.0-rc1` (`671a34d`, 2026-09-17), with live testing
against `aiosendspin` 10.0.0 (2026-10-05), the version Music Assistant dev pins
(`aiosendspin[server]==10.0.0`).

Supersedes `docs/spec-compliance-audit-2026-08-13.md`. Most of that document's
Phase 1-3 work has landed; this one covers what RC1 changed underneath it.

---

## 0. Status after the same-day fixes

Everything below section 0 describes the code as audited at `0d14aba`. By the end
of 2026-10-06 `main` had moved on as follows.

**Verdict now:** a strict aiosendspin 10.0.0 server
(`allow_unencrypted=False`, `allow_noncompliant_clients=False`) accepts the app
with no non-compliance flags, and the app plays from Music Assistant 2.11.0b4 on
a T901 tablet (Android 15). The app no longer depends on Music Assistant's
legacy shim, and no longer connects to pre-rc1 servers.

| Area | Items | PR | How it was verified |
|---|---|---|---|
| Cleartext after transport | section 4 | #266 | unit test |
| Wire core | W1-W4, W6, C2, C5, C6, management removal | #267 | strict server, tablet |
| Availability latch | found on device: `available: false` flapped at stream start and Music Assistant ended the stream | #267 | captured on tablet, unit test, tablet |
| Artwork | W5 | #268 | strict server (hashes match), tablet (58 KB image from Music Assistant) |
| Pairing | W7, W8, R1-R6, C11 | #269 | strict server, both methods; Pairing PSK on the tablet |
| Player behaviour | P1, P5, P8, P10 | #271 | tablet (skips, pause/resume, mute held across volume changes) |
| Sync accuracy | P2, P3, P4, start re-anchor | #276 | tablet: +/-0.1 ms smoothed over 6 min 40 s, 0 re-anchors in 26 starts, listened to |
| Controller | M2, M3 (media session) | #270 | strict server, tablet (media key sends `seek_relative`) |
| State and session | C1, C3, C4, C7-C10, M1 | #275 | strict server; goodbye on the tablet |
| Skip silence | found on device: a chunk decoded across `stream/clear` was queued stale | #277 | tablet: 8 skips, 2.8-3.0 s each |
| CI | conformance adapter on the encrypted wire, three scenarios; unit tests running again | #273, #274 | CI green |

**Still open**

- P6: low-memory mode caps the decoded queue at 10 s while advertising a larger byte capacity.
- P7: `min_buffer_ms` is still derived from time-sync RTT; `send_ahead` is parsed and unused.
- P9: no volume curve `(volume/100)^1.5` and no volume ramp (both SHOULD).
- R7: the pairing failure counter keeps the 5-attempt gate and is not reset by the operator gesture.
- M3, M4: no in-app controls for seek, repeat and shuffle; `album_artist`, `year` and `track` are not shown.
- Dynamic pairing code is verified against the strict server with the shared flows only, not on a device (it is an opt-in setting).
- Single-round dynamic pairing: a mistyped code aborts and needs a new attempt from the server, where the spec's SHOULD is `client/pair-retry`.
- Binary artwork reaches only the media session, and only when the server sends no `artwork_url`.
- Optional and unclaimed: `client/leave`, server-initiated connections, `color`, `visualizer`, `source`, static pairing code, `qr_code`.
- Unrelated to rc1, seen during testing: the add-server button sits behind the T901 taskbar; the Device Volume slider and track progress sometimes lag a refresh.

## 1. Verdict

**A strict RC1 server rejects us at `client/hello`.** The Noise handshake still
interoperates; everything after it is on a pre-RC1 wire.

We keep working against Music Assistant only because MA's hidden
`allow_legacy_clients` option defaults to `true`, which sets both
`allow_unencrypted` and `allow_noncompliant_clients` on the server. The
aiosendspin 10.0.0 release notes name SendSpinDroid as one of the clients that
"speak the older wire" and are "flagged as noncompliant".

### What was tested, and what was not

| Test | Result |
|---|---|
| Real shared handshake driver + wire codec + builders (`NoiseHandshakeCheck`) vs aiosendspin 10.0.0, `allow_unencrypted=False`, `allow_noncompliant_clients=False` | Noise KKpsk2 handshake **OK**. `client/hello` **rejected**: `client/hello declared artwork@v1_support, superseded by the client/state artwork object`. No `server/activate` is ever sent |
| Same probe, encryption mandatory, deviations logged instead of rejected | Connects, roles activate after trust. Server flags four hello deviations (section 2) and silently notes a fifth (`trust_level`) |
| CI conformance workflow | Red on `main` every run since 2026-09-04 |
| `:conformance-client:fatJar` on this branch | Did not compile (`Main.kt:203` still passed `staticDelayMs`, renamed in `0d14aba`). Fixed locally with a one-token change, uncommitted |

Not tested: the Android app itself on a device, a real Music Assistant nightly,
audio streaming, and any pairing flow. Strict mode stops at the hello, so nothing
behind it can be exercised live until the hello is fixed. Everything in sections
3-6 beyond the items marked "verified" comes from reading spec and code.

### The legacy flag is why fixes must land together

aiosendspin 10.0.0 classifies a client as legacy-wire when its `client/hello`
carries `trust_level` or `player@v1_support.supported_commands`
(`server/connection.py:1440`). That one flag selects the 9-byte audio header,
type-2/3 fragment framing and legacy artwork delivery. A list-shaped
`supported_pair_methods` separately makes the server expect hellos after a
re-handshake (`:1454`).

So the hello is wrong in exactly the ways the server sniffs for. Fixing the hello
alone flips the server to the RC1 wire in one step, and audio breaks (4 extra
header bytes in every chunk). Items W1-W6 below are one atomic change.

---

## 2. Server-reported deviations (observed)

Logged by aiosendspin 10.0.0 against our real `client/hello`:

1. `client/hello declared artwork@v1_support, superseded by the client/state artwork object` (the strict-mode rejection)
2. `client/hello artwork used pre-rename dimension keys: media_height, media_width`
3. `client/hello declared player supported_commands, superseded by client/state`
4. `client/hello sent supported_pair_methods as a list`
5. `trust_level_used=True` (parsed, tolerated without a warning)

Also observed: we send `client/hello` before `server/hello` arrives. The server
did not flag it; the spec says "Sent by the client once it has received
`server/hello`".

---

## 3. Wire-breaking gaps (S1)

Severity: **S1** breaks against a strict RC1 server. **S2** spec violation with
interop, security, audible or user-visible effect. **S3** low practical impact.
**S4** optional.

Paths: `shared/` = `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/`,
`app/` = `android/app/src/main/java/com/sendspindroid/`,
`Handler` = `app/sendspin/protocol/SendSpinProtocolHandler.kt`.

| # | Gap | RC1 | Code | Verified |
|---|---|---|---|---|
| W1 | `client/hello` shape | No `trust_level`; no `artwork@v1_support`; `player@v1_support` has only `supported_formats` and `buffer_capacity`; `supported_pair_methods` is an object keyed by method; `dynamic_pairing_code` descriptor has no `locations` | `shared/protocol/message/MessageBuilder.kt:100, 126-171` | Live + read |
| W2 | Audio chunk header | 13 bytes: type, int64 timestamp, **uint32 `send_ahead`**, then the frame (`roles/player/v1.md`) | `shared/protocol/message/BinaryMessageParser.kt:102-109` reads 9; `SendSpinProtocol.kt:23` | Read |
| W3 | Fragmentation | Single ID 1: `[1][flags][orig_type][data]`, bit 1 = first, bit 0 = last; IDs 2-3 reserved | `shared/protocol/message/Fragmentation.kt:79-163`, `SendSpinProtocol.kt:36-40` use IDs 2/3. An inbound ID-1 fragment is dropped as unknown | Read |
| W4 | Re-handshake | "Neither `server/hello` nor `client/hello` is re-sent. That activation is a subsequent one" | `app/sendspin/SendSpin.kt:652-667` re-sends `client/hello`; `Handler:711-715` resets `handshakeComplete`, `activationSeen`, `activeRoles`. `handshakeComplete` then never returns to true, silently suppressing goodbye and `client/state` updates for the rest of the connection | Read |
| W5 | Artwork | Configured by an `artwork` object in `client/state`; delivered as announce/part/cancel (`[type][flags][timestamp][total_size]`, `[type][flags][data]`, `[type][flags]`); `width`/`height`; clear is an announce with `total_size` 0 | No `artwork` object is ever sent (`MessageBuilder.kt:357-405`), so an RC1 server sends no artwork. Parser still expects `[type][timestamp][image]` (`BinaryMessageParser.kt:147-150`). Masked today because `metadata.artwork_url` is preferred | Read |
| W6 | Activity table | long-term: `[]`, `['playback']`. pairing PSK and Sentinel: `[]`, `['pairing']`, `['playback']`, `['playback','pairing']` | `shared/protocol/ServerActivate.kt:150-169`: pairing PSK accepts only `['pairing']`; Sentinel rejects `['playback','pairing']`; long-term accepts `['pairing']` and `management`. Legal activations get goodbye `unauthorized` | Read |
| W7 | Pairing PSK flow | "the client MUST send `client/pair-init` followed immediately by `client/pair-finalize`" | `shared/pairing/PairingPskFlow.kt:191-197` sends finalize only; there is no builder for a `pair-init` without `commit_B` | Read |
| W8 | CPace `sid` | `"sendspin-pair-pake-v1" \|\| h \|\| pairing_index \|\| round` (round as big-endian uint32, starting at 1) | `shared/pairing/DynamicPairingCodeFlow.kt:309-317` omits `round`. Every derived key differs, so dynamic pairing always ends in `pairing_code_mismatch` | Read |

W7 and W8 mean **both pairing methods we offer fail against RC1**, independently
of the hello.

---

## 4. Security finding (S2, fix regardless of RC1)

**Cleartext text messages are processed after encryption starts.**
`app/sendspin/SendSpin.kt:1547-1557`: once the driver is in `Transport`, a
WebSocket text message goes straight to `handleTextMessage`, which dispatches
`server/command`, `server/unpair`, `stream/*`, pairing messages and
`noise/handshake` with no check that it arrived encrypted (`Handler:822`). The
driver's own rejection branch (`SendSpinHandshakeDriver.kt:109-113`) is
unreachable from this path.

Spec, `connection.md` Failure Handling: "a cleartext message received after
switching to transport mode - is a **silent failure**: the detecting side closes
the connection".

On a plain `ws://` LAN connection, anything on-path can inject unauthenticated
application messages. Verified by reading both the listener and the handler.

---

## 5. Other gaps

### 5.1 Connection and messaging

| # | Sev | Gap | Code |
|---|---|---|---|
| C1 | S2 | No Sentinel Fallback: "On a lookup miss in the initial handshake the client completes the second handshake message with the Sentinel PSK instead of failing". A client that lost its record can never reconnect or be offered re-pairing | `shared/protocol/SendSpinHandshakeDriver.kt:219-223` |
| C2 | S2 | `psk_category` (`'lt'`/`'pr'`/`'sn'`) never read; lookup is by `psk_id` across all categories; an out-of-range value is not a silent failure | `SendSpinHandshakeDriver.kt:266-271`, `RehandshakeDriver.kt:126-129`, `shared/crypto/PskCandidateSet.kt:30` |
| C3 | S2 | `server/state` treated as a delta. RC1: "Every message MUST carry the full state of each role object it includes"; an omitted `progress` "clears the client's position". Omitted fields keep the previous track's values | `shared/protocol/StatePatches.kt:23-53`, `Handler:1618-1647` |
| C4 | S2 | A role removed by `server/activate` clears nothing (stream output, buffers, metadata, controller state) | `Handler:976-984` |
| C5 | S2 | `stream/request-format` no longer exists; the preference is `format` in the `client/state` player object. Live codec switching is a silent no-op against RC1 | `MessageBuilder.kt:433-451`, `Handler:564-573`, `app/playback/PlaybackService.kt:224` |
| C6 | S2 | Pairing quiesces playback: any activation containing `pairing` stops time sync and withholds `client/state`. RC1: "Pairing can run alongside playback" | `Handler:1014-1019` |
| C7 | S3 | Cleartext `server/error` (`unsupported_version`, `unsupported_suite`, `malformed`) is reported as "server lacks encryption"; `reason` never read | `SendSpinHandshakeDriver.kt:137-146` |
| C8 | S3 | No handshake-phase timeout wired (`onTimeout()` has no production caller) | `SendSpinHandshakeDriver.kt:130` |
| C9 | S3 | `client/goodbye` usually loses the race with `transport.close()`; `another_server` and `shutdown` have no call sites (inferred from structure, not observed) | `Handler:295-298`, `SendSpin.kt:1117-1147` |
| C10 | S3 | No gate on sending before activation or for inactive roles; a stale codec from the previous session survives a reconnect (`clearEncryptedTransport()` is never called) | `Handler:549-596, 731-747` |
| C11 | S3 | `pairing.format` not parsed; `pairing_required` only evaluated for Sentinel sessions; unknown `activities` values dropped rather than handled | `ServerActivate.kt:130-145, 214` |

### 5.2 Pairing

| # | Sev | Gap | Code |
|---|---|---|---|
| R1 | S2 | `pairing_index` counts only accepted pairing activations; after one `method_not_supported` reply every later `client/pair-init` is silently discarded as stale | `Handler:969-974, 1021-1034` |
| R2 | S2 | After its own abort the dynamic flow closes the socket on in-flight pairing messages that RC1 says to discard silently | `DynamicPairingCodeFlow.kt:204-294` |
| R3 | S2 | A `server_kc` mismatch leaves the code on screen and the attempt timer running; two minutes later the timer closes the connection | `DynamicPairingCodeFlow.kt:234-237, 265-266` |
| R4 | S2 | A new pairing activation during a PSK attempt is ignored instead of superseding it (the case spec `b31adb2` closes) | `PairingPskFlow.kt:185-189` |
| R5 | S3 | Pairing PSK can be withdrawn from candidates and from the hello, with no UI to restore it. RC1: "The client MUST keep its pairing PSK among its handshake PSK candidates at all times" | `shared/crypto/PskCandidates.kt:27-29`, `SendSpin.kt:684-686` |
| R6 | S3 | A new record does not replace the old one for the same server | `shared/crypto/InMemoryTrustStore.kt:38-48`, `Handler:1390` |
| R7 | S3 | Failure escalation is the old model (threshold 5 per emission; RC1 is 20 rounds, reset by the operator gesture). Stricter than required, so only the missing reset deviates | `shared/pairing/PairingFailureCounter.kt:37` |
| R8 | S4 | `client/pair-retry` and multi-round attempts, `qr_code` format, static pairing code, local unpaired-access toggle | - |

### 5.3 Player

| # | Sev | Gap | Code |
|---|---|---|---|
| P1 | S2 | `stream/start` during an active stream discards all buffered audio. RC1: "Clients MUST keep buffered chunks and decode each chunk in the format that was in effect when it was received" | `PlaybackService.kt:1491, 1531-1536` |
| P2 | S2 | Speed cap is +/-2%. RC1: "MUST stay within +/-0.5% of normal speed, measured as a sliding average over 150 ms" | `app/sendspin/SyncAudioPlayer.kt:277` |
| P3 | S2 | 10 ms dead band and +/-50 ms start tolerance against "MUST keep this error within +/-1 ms" | `SyncAudioPlayer.kt:273, 307` |
| P4 | S2 | No one-shot resync between 10 ms and 500 ms | `SyncAudioPlayer.kt:302, 2134-2139` |
| P5 | S2 | Mute is implemented as volume 0, so a volume change while muted makes audio audible while still reporting `muted: true`. RC1: "a volume change ... MUST NOT clear the mute state" | `PlaybackService.kt:1620-1649` |
| P6 | S2 | Low-memory mode caps the decoded queue at 10 s while advertising a byte capacity a compressed stream fills with far more, evicting next-to-play audio (server fill behaviour inferred) | `MessageBuilder.kt:460-468`, `SyncAudioPlayer.kt:1247-1258` |
| P7 | S3 | `min_buffer_ms` derived from time-sync RTT rather than chunk arrival delay (needs `send_ahead`, so blocked on W2) | `Handler:386-411` |
| P8 | S3 | Out-of-range `set_output_delay` dropped instead of clamped | `shared/protocol/message/MessageParser.kt:247-253` |
| P9 | S3 | Late chunks not dropped during playback; no volume curve `(volume/100)^1.5`; no ramp | `SyncAudioPlayer.kt:1342-1442`, `PlaybackService.kt:2131-2142` |
| P10 | S4 | `muted` not persisted; unknown codec falls back to PCM; a `mute` command without the field unmutes | `MessageParser.kt:239`, `AudioDecoderFactory.kt:45-48` |

The `output_delay_ms` change in `0d14aba` is complete and correct on the wire.
P2-P5 are unchanged from the August audit (its P1-P3 and P8).

### 5.4 Metadata, controller

| # | Sev | Gap | Code |
|---|---|---|---|
| M1 | S2 | Scheduled (future-timestamp) metadata is applied immediately; RC1 keeps a current state plus one pending update | `Handler:1618-1625` |
| M2 | S2 | `controller@v1` is claimed but `seek` and `seek_relative` are not implemented. RC1: "MUST implement all messages in this section" | `MessageBuilder.kt:413-425`, `app/playback/SendSpinPlayer.kt:418-441` |
| M3 | S3 | `supported_commands`, group volume/mute, repeat and shuffle never reach the UI or MediaSession; `controllerState` has no production consumer | `SendSpinPlayer.kt:629-636, 802-824` |
| M4 | S3 | `album_artist`, `year`, `track` parsed and dropped before the UI | `SendSpin.kt:832-840` |

`color`, `visualizer` and `source` are not claimed, and their messages are
ignored safely.

---

## 6. Code RC1 no longer defines

- The whole `management/*` subsystem: `shared/protocol/management/` (5 files),
  `Activity.MANAGEMENT`, `buildManagementResult`, dispatch at `Handler:861-862,
  1156-1198`. It still answers `management/*`, where RC1 says to ignore unknown
  types. aiosendspin keeps server-side management only for legacy clients and
  marks it deprecated.
- Record mode and shared-PSK records (`shared/crypto/PairingConfig.kt:44`,
  `app/sendspin/crypto/AndroidPairingConfigStore.kt:58-75`, `Handler:1292-1297`).
- `trust_level` and `forbidsRoleAtTrustLevel`; the `pin_mismatch` reason and
  `DYNAMIC_PIN` / `STATIC_PIN` identifiers.
- Legacy unencrypted remnants: `clientId`/`version` in `buildClientHello`, the
  `!isEncrypted` branches, `server_id`/`connection_reason`/`active_roles` parsed
  from `server/hello`. Side effect: `timeFilter.thaw` gets `serverId = ""`, so
  server-change detection on reconnect relies on the name alone.
- `client/sync_offset` is a Music Assistant extension, not spec. Kept
  deliberately; noted so it is a decision rather than an accident.

---

## 7. Test infrastructure

- **CI tests the wrong dialect.** `conformance-client/.../Main.kt`, the adapter
  the workflow runs, sends `client/hello` first in cleartext text frames. The
  harness moved its aiosendspin adapters to the real Noise handshake on
  2026-09-04, and the workflow has failed on `main` ever since.
- **`NoiseHandshakeCheck` is the right base** for a new adapter: it drives the
  shared handshake driver, wire codec, activation rules and builders. It stops
  short of audio parsing and pairing.
- **`ci/conformance/dev_server.py` runs unmodified on aiosendspin 10.0.0.** The
  runbook's commit pin can become `aiosendspin[server]==10.0.0`.
- The SUPPORTED set in the workflow is one scenario (`client-initiated-pcm`).

---

## 8. Suggested order

1. **Cleartext-after-transport rejection (section 4).** Small, independent,
   security-relevant.
2. **The atomic RC1 wire change: W1-W6** together with C2, C5 and C6, verified
   against `dev_server.py` in strict mode. Splitting it breaks audio against
   10.0.0.
3. **Pairing: W7, W8, then R1-R4**, each verified by a real pair against the
   strict dev server.
4. **Conformance adapter** rebuilt on the encrypted path, so 2 and 3 stay
   verified in CI.
5. **Delete management and record mode** (section 6) once 2 is in.
6. C1, C3, C4, P1, M1, M2, then the sync-quality items P2-P4, which need device
   measurement.

## 9. Open questions

- When does MA remove `allow_legacy_clients`? The option is hidden and still
  defaults to `true` on dev. Every `DEPRECATED(spec-pr-NNN)` marker in
  aiosendspin 10.0.0 says "remove in aiosendspin <version>" with no version.
- Whether existing installs have `pairingPskEnabled` or `unpaired_access`
  persisted as false by an older server (R5).
- CPace internals were checked against the spec's inputs, not against
  draft-irtf-cfrg-cpace-21 itself.
- Actual steady-state sync error needs a device measurement.
