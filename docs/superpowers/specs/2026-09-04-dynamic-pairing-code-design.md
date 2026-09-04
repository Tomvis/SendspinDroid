# Dynamic Pairing Code: CPace Responder and the Pairing Flow

**Date:** 2026-09-04
**Status:** Accepted
**Authoritative protocol spec:** https://github.com/sendspin/spec (`pairing.md`)
**PAKE reference:** draft-irtf-cfrg-cpace-21

## Goal

Add `dynamic_pairing_code` to SendSpinDroid: the client derives a per-session
six-digit code bound to the Noise handshake, displays it, and authenticates the
pairing with a CPace PAKE round. Pairing PSK stays exactly as it is.

## Why this is an addition, not a replacement

`pairing.md` is explicit: "clients **must** implement Pairing PSK and **may**
additionally offer at most one pairing-code method". Pairing PSK is the one
mandatory client method, so removing it would move us from conformant to
non-conformant.

What we are missing is a SHOULD: "Clients with a usable out-channel (display,
speaker, etc.) should offer `dynamic_pairing_code`". SendSpinDroid has a
display, so it should offer it. "At most one" means offering Dynamic
permanently forecloses Static -- which is the right trade, since Static is
"intended for devices without one" and is "vulnerable to MITM if the pairing
code is disclosed".

## Decisions

**D1. Emission format: `digits` only.** `formats: ["digits"]`. The operator
reads six digits off the screen and types them into the server. A text box
exists in every server UI; a QR scanner may not. Dropping `qr_code` also drops
the version-1 pairing-token encoding entirely.

**D2. Out-channels: `display` only.** Never `speaker`. Advertising a speaker
channel obliges us to accept a server-supplied **digit audio pack** -- ten
clips, per-clip decode validation, a 2-second per-clip limit, a `max_bytes`
total, and a "pack incomplete" protocol error -- a whole subsystem for a device
with a perfectly good screen.

**D3. Responder role only.** The server is CPace initiator (role A); we are
always responder (role B). Implementing role A would be untestable code that
exists to be wrong.

**D4. Build to the current spec, not to what Music Assistant speaks today.**
See "The interop gap" below. This is a deliberate, informed choice to be
correct now and interoperable later, consistent with the reasoning in PR #238
that rejected keeping a second wire format alive.

**D5. Follow the spec on the failure counter where it diverges from the 9.1.1
reference.** See "Known divergence".

## The interop gap

The pairing protocol changed incompatibly between aiosendspin 9.x and 10.x.
Music Assistant ships 9.1.x.

| | aiosendspin 9.1.1 (what MA ships) | aiosendspin 10.x / current spec |
|---|---|---|
| Method id | `PairMethod.DYNAMIC_PIN` | `dynamic_pairing_code` |
| The code | operator-chosen PIN, negotiated length via `min_pin_length`, `max(client_min, server_min)` | derived, fixed six digits |
| Binding | none | `commit_B` / `nonce_A` / `wrapped_nonce_B` commit-and-reveal |
| Descriptor | `min_pin_length` | `formats`, `out_channels` |
| PAKE | CPace-X25519-SHA512 + MCF | CPace-X25519-SHA512 + MCF |

MA's provider (`music_assistant/providers/sendspin/helpers.py`) calls
`PairMethod.DYNAMIC_PIN` and `replace(descriptor, min_pin_length=...)`; neither
the enum member nor the field exists in current aiosendspin.

**Consequence, stated plainly so it is not later mistaken for a defect:** on
delivery, this feature will not pair with Music Assistant. Pairing with MA
continues to use Pairing PSK, which is unaffected. Dynamic pairing becomes
usable with MA when MA moves to aiosendspin 10.x.

**This does not make the work unverifiable.** `ci/conformance/dev_server.py` is
already a local aiosendspin server built for exactly this situation -- so that
Phase 1 could be verified "against a spec-compliant peer instead of Music
Assistant's compatibility shim". Pinned to aiosendspin 10.x it becomes the
acceptance peer for end-to-end pairing.

The PAKE core is identical across both revisions, so none of the cryptographic
work is at risk from this gap.

## Architecture

Two pure layers, separately verifiable, plus wiring.

```
:shared  crypto/cpace/
           Elligator2.kt        RFC 9380 map_to_curve_elligator2 on X25519Field
           CPaceX25519.kt       generator string, scalar_mult_vfy, ISK, MCF tags
           CPaceResponder.kt    start / derive / verify / tag / isk   (role B)
:shared  pairing/
           DynamicPairingCodeFlow.kt   event -> action state machine
           PairingCode.kt              commit, digest, six-digit derive
           PairingWindow.kt            window lifetime + failure counter policy
:shared  protocol/message/             pair-pending/init/auth/confirm build+parse
:app     protocol/                     handler wiring: messages <-> flow
:app     ui/                           code display in the PAIRING admission state
```

The split exists so the crypto can be tested against published vectors in
isolation. Folded into the state machine, every cryptographic assertion would
have to be smuggled through protocol sequencing.

No new dependency. BouncyCastle 1.80 already ships for the Noise layer and
provides `X25519Field` (constant-time field arithmetic, which Elligator2
needs), `X25519.scalarMult` for arbitrary base points, SHA-512 and HMAC.

### The flow

`DynamicPairingCodeFlow` mirrors the existing `PairingPskFlow`: a pure
`onEvent(event): List<PairingAction>` with no I/O, so timeouts, aborts and
sequence violations are testable without a socket.

| Event | Actions |
|---|---|
| `PairingActivation(method, format, index, pskCategory)` | escalated and no window: `SendPairPending(index)`. Otherwise generate `nonce_B`, `SendPairInit(index, commit_B)`, start attempt timeout |
| `WindowOpened` | `SendPairInit(index, commit_B)`, start attempt timeout |
| `ServerPairInit(nonce_A)` | derive six digits; `IncrementFailureCounter`; `EmitPairingCode`; CPace `start(prs = code ASCII, sid)` |
| `ServerPairAuth(Ya)` | `SendPairAuth(Yb)`, then `derive(Ya)` |
| `ServerPairConfirm(Ta)` | verify fails: `SendPairAbort(pairing_code_mismatch)`. Verify passes: `ResetFailureCounter`, then `SendPairConfirm(Tb, wrapped_nonce_B)` and `SendPairFinalize(wrapped_psk)` together |
| `ServerPairFinalize` | `PersistRecord(psk, serverId)`, `StopEmittingCode` |
| `AttemptTimeout` | `SendPairAbort(attempt_timeout)` |
| `NonPairingActivation`, `PairAbortReceived`, `ConnectionClosed` | discard all state, `StopEmittingCode`, persist nothing |

`client/pair-confirm` and `client/pair-finalize` are emitted as one action list:
the spec sends them "back-to-back, no server response awaited".

**Protocol errors** -- malformed or missing field, a share of the wrong length
or encoding a low-order point, a commitment that does not open, a value that
fails to decrypt -- close the WebSocket with no application-level message and
persist nothing. This is a distinct action from `SendPairAbort`; conflating the
two would report the failure reason to an unauthenticated peer.

### Policy

**`sid`** is `"sendspin-pair-pake-v1" || h || counter`, `counter` a big-endian
uint32 of the pairing activations since the last Noise handshake -- the same
value sent as `pairing_index`. Both derive from one field so they cannot drift.

**Wrapping**, per field: `K_wrap = SHA-256(label || sid || ISK)` with labels
`sendspin-pair-psk-wrap-v1` and `sendspin-pair-nonce-wrap-v1`, then the
connection's negotiated AEAD, a 12-byte zero nonce, empty associated data, 48
bytes out. The zero nonce is safe only because each key is per-field and used
exactly once; that reasoning belongs in the code, because a bare zero nonce is
otherwise the kind of thing a later reader "fixes" into a vulnerability.

**Failure counter**: one counter for the method, persisted across reboots, not
partitioned by server or address. Increments when code emission starts, at most
once per attempt. Resets when `server_kc` verifies, whether or not the attempt
finalizes. At **5** the method escalates: every subsequent attempt is
gesture-gated until a reset de-escalates it. Escalation is not an error state
and the method stays offered.

**The gesture** is an in-app "Allow pairing" button. The spec permits "any
equivalent implementation-defined action" and asks that gestures be "deliberate
and hard to induce remotely"; on a tablet with no spare hardware buttons, an
explicit on-device tap is the honest equivalent and still requires physical
presence. Window lifetime five minutes, closing silently on expiry.

### The cryptographic core

```
DSI      = "CPace255"                            s_in_bytes = 128 (SHA-512)
gen_str  = lv_cat(DSI, PRS, zero_bytes(len_zpad), CI, sid)
           len_zpad = MAX(0, 128 - len(prepend_len(PRS)) - len(prepend_len(DSI)) - 1)
u        = decodeUCoordinate(SHA-512(gen_str)[0..32], 255)
(g, v)   = map_to_curve_elligator2(u)            -- RFC 9380, v discarded
Yb       = X25519(yb, g)
K        = scalar_mult_vfy(yb, Ya)               -- low-order -> all-zero, rejected
ISK      = SHA-512(lv_cat("CPace255_ISK", sid, K) || lv_cat(Ya, ADa) || lv_cat(Yb, ADb))
mac_key  = SHA-512("CPaceMac" || sid || ISK)
Ta / Tb  = HMAC-SHA-512(mac_key, lv_cat(Ya, ADa) / lv_cat(Yb, ADb))
```

`CI` is empty, `ADa` is `"server"`, `ADb` is `"client"`.

The draft leaves the MAC algorithm open; SendSpin pins HMAC-SHA-512 with
64-byte tags, which is what makes `Ta`/`Tb` constructible from the draft rather
than only from a reference implementation.

## Verification

| Layer | Verified against |
|---|---|
| Elligator2 | RFC 9380 published vectors |
| generator string, `Yb`, `K`, `ISK` | draft-21 appendix B.1.1 - B.1.6 |
| low-order point rejection | draft-21 appendix B.1.10 |
| `Ta` / `Tb` MCF tags | draft section 10.4.5-6 formula, cross-checked against the Python `cpace` package as an oracle |
| code derive, commit, wrapping | vectors computed from `aiosendspin` |
| flow, timeouts, sequencing | pure state-machine unit tests, no I/O |
| end to end | `ci/conformance/dev_server.py` pinned to aiosendspin 10.x, real pairing from the tablet |

The oracle step matters: install `cpace`, drive the responder path with fixed
inputs, dump `(PRS, sid, Ya, yb) -> (Yb, ISK, Ta, Tb)` as JSON, and pin the
Kotlin against it. It is the one construction with no official vectors, and a
`lv_cat` mistake changes every downstream byte, so the oracle catches prefix
errors immediately.

Vector tests live in `:shared` as static, deterministic tests with no Android
and no network, matching how the Noise layer is already tested.

## Risks

1. **No Music Assistant interop on delivery.** By construction, not by defect.
   Acceptance is against the dev server.
2. **`lv_cat` uses LEB128 length prefixes**, not fixed-width. A fixed-width
   implementation is self-consistent, passes every test it writes for itself,
   and fails only against a real peer. The B.1 vectors are the guard.
3. **Low-order point rejection** must cover low-order points on the curve *and*
   on the twist, mapping both to the all-zero 32 bytes and rejecting. This is
   the check that stops a peer forcing a known shared secret.
4. **Failure-counter divergence** from the 9.1.1 reference; see below.
5. **`pairing_index` base** (0- or 1-based) must be pinned against the
   reference rather than assumed. An off-by-one breaks every tag while looking
   entirely reasonable.
6. **Elligator2 must be constant-time**, since its input derives from the
   pairing code. This is why it is built on `X25519Field` rather than
   `BigInteger`.

## Known divergence

`pairing.md` states the failure counter "increments when the client starts
emitting the pairing code, at most once per attempt. **No other event
increments it**", and resets when `server_kc` verification succeeds. The 9.1.1
reference instead calls `record_pairing_code_failure()` on a `server_kc`
mismatch.

We follow the spec (D5). The practical difference is the escalation rate: under
the spec, five *attempts* escalate even if each got as far as a successful
`server_kc`; under the reference, five *failures* do.

## Adjacent cleanup

`ServerActivate.pinLength` and `PairAbortReason.PIN_LENGTH_UNACCEPTABLE` are
fossils of the pre-10.x variable-length PIN. `pin_length` appears nowhere in
the current spec; the field is parsed and never read, and the abort reason is
reserved for a task the spec change deleted.

They are removed as part of this work. They sit in the code this feature
touches and describe a mechanism that no longer exists, so leaving them invites
someone to wire the code length to a server-supplied value -- which is exactly
the vulnerability the derived, handshake-bound code was designed to remove.

## Out of scope

- `static_pairing_code` -- foreclosed by "at most one" once Dynamic is offered.
- `qr_code` emission and the version-1 pairing token (D1).
- The digit audio pack and the `speaker` out-channel (D2).
- CPace initiator (role A) (D3).
- Music Assistant interop, until MA moves to aiosendspin 10.x.
- The missing `management.md`: our code cites `management.md#record-mode` and
  implements against it, but no such file exists in the spec repository. Worth
  its own investigation; unrelated to this work.

## Verification criteria

1. Elligator2, generator, `Yb`, `K`, `ISK` and low-order rejection match the
   published vectors.
2. `Ta` and `Tb` match the Python `cpace` oracle byte for byte.
3. Code derivation, commitment and both wrappings match vectors computed from
   `aiosendspin`.
4. The flow state machine covers: happy path, `server_kc` mismatch, attempt
   timeout, server-cancelling activation, abort in both directions, connection
   drop mid-attempt, and every sequence violation as a protocol error.
5. Escalation: five attempts gate the sixth on the gesture; a successful
   `server_kc` de-escalates.
6. `client/hello` advertises `dynamic_pairing_code` with
   `out_channels: ["display"]` and `formats: ["digits"]`, and drops it when the
   method is disabled.
7. End to end against `ci/conformance/dev_server.py` on aiosendspin 10.x: the
   tablet displays a six-digit code, the operator enters it, the pairing record
   persists, and the server's re-handshake to the new long-term PSK succeeds.
8. Pairing PSK continues to work unchanged.
