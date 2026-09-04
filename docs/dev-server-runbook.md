# Sendspin dev server runbook

How to run a local Sendspin server that **refuses the legacy dialect**, so Phase 1
work is verified against a spec-compliant peer instead of Music Assistant's
compatibility shim.

Deliverable for audit item 0.2 (issue #190). See
`docs/spec-compliance-audit-2026-08-13.md`.

## Why this exists

Music Assistant ships `allow_legacy_clients=true`, which maps to aiosendspin's
`allow_unencrypted` / `allow_noncompliant_clients`. With that on, SendSpinDroid's
current `client/hello`-first dialect is accepted and every Phase 1 failure is
invisible. MA documents the toggle as temporary.

This server sets both flags to `False`. Against it, the current app **must fail to
connect** - that failure is the baseline Phase 1 has to turn green.

## One-time setup

Requires Python 3.12+.

```bash
python -m venv .venv
# Windows
.venv\Scripts\activate
# macOS/Linux
source .venv/bin/activate

pip install "aiosendspin[server] @ git+https://github.com/sendspin/aiosendspin@90feb19894793749eb017f9e1bb21929dc8fe94a"
```

**This is not a released version, and the pin above is a deliberate stand-in.**
As of this writing, aiosendspin 10.0.0 -- the version with the dynamic-pairing-code
API this dev server needs -- exists only as a **draft GitHub release**
(`draft=true`, `published_at=null`) with **no git tag**; it has not been
published to PyPI. The dynamic-pairing-code surface
(`PairMethod.DYNAMIC_PAIRING_CODE`, `run_dynamic_pairing_code_server`,
`PairingCodeFormat`) exists only on the `aiosendspin` repository's `main`
branch. The commit above is `main` as of 2026-08-31.

Pin the exact commit SHA, never `@main`: acceptance evidence gathered against
a branch that moves underneath it is not reproducible, and there would be no
way to tell a real regression in SendSpinDroid from an unrelated upstream
change landing on `main` between test runs.

**Revisit this pin once aiosendspin 10.0.0 actually ships** (a real PyPI
release with a git tag). At that point switch back to a normal version pin
(`pip install "aiosendspin[server]>=10,<11"`, matching whatever Music
Assistant has moved to by then) and drop this note. Until then, do not
assume anyone reading this later can run `pip install aiosendspin==10.0.0`
and get something that works -- it will not resolve to anything on PyPI.

Prior to this, the script pinned `aiosendspin==9.1.0`, matching what Music
Assistant currently requires (`music_assistant/providers/sendspin/manifest.json`).
`aiosendspin.noise.*` is not a stability-guaranteed API, so expect renames
across major versions -- the 10.x pairing module alone renamed `DYNAMIC_PIN`
to `DYNAMIC_PAIRING_CODE`, `STATIC_PIN` to `STATIC_PAIRING_CODE`, and
`decode_token` to `decode_psk_token` relative to 9.1.x.

## Running

```bash
python ci/conformance/dev_server.py --name "Sendspin Dev" --trust-all-unpaired
```

Expected startup output (every line is prefixed with
`HH:MM:SS INFO    dev_server: `, elided here for width; on a first run a
"Generated a new server identity" line precedes it, and aiosendspin logs a
couple of its own lines):

```
========================================================================
Sendspin dev server 'Sendspin Dev' listening on 0.0.0.0:8927/sendspin
server_id: O67GqkUcDwDyoaIToq1vqI474fNR3sZp8CV1kul35W0
identity:  .dev\sendspin\identity.key (do NOT delete - see runbook)
records:   .dev\sendspin\pairing_store.json
allow_unencrypted=False  allow_noncompliant_clients=False
auto-trusting unpaired clients on connect (--trust-all-unpaired)
========================================================================
```

The `server_id` must be **identical** on every restart. If it changes, the
identity file was recreated and every paired client is now broken (see below).

### Console commands

The server reads commands on stdin while running:

| command | effect |
|---|---|
| `clients` | list connected clients with pairing and security state |
| `trust [id]` | make an unpaired (Sentinel-PSK) client playback-capable |
| `untrust [id]` | revoke that |
| `pair <token>` | pair using a `SP:0...` pairing token from the device (Phase 2) |
| `pair-dynamic [id]` | pair using the six-digit dynamic pairing code shown on the device |
| `unpair [id]` | drop the record and send `server/unpair` (exercises item 2.7) |
| `quit` | stop |

`id` defaults to the only connected client when omitted.

### `--trust-all-unpaired` is not optional in practice

A Sentinel-keyed client completes the handshake but is **not playback-capable**
until the operator trusts it. In aiosendspin,
`SendspinConnection._playback_capable` requires
`client_info.unpaired_access.enabled and _trusted_unpaired`, and the latter comes
from `pairing_store.trusted_unpaired(client_id)`.

Forget this and you get a clean handshake followed by a `server/activate` with an
empty `active_roles` - which reads exactly like a bug in the client's
`server/activate` handling (item 1.6). It is the single easiest way to lose a day
on Phase 1. `--trust-all-unpaired` takes it off the table.

## Dynamic pairing code procedure

This exercises the CPace-based dynamic pairing flow end to end: the tablet
displays a six-digit code, the operator reads it and types it at this
server's console, and both sides derive a shared long-term PSK from a
CPace exchange keyed by that code -- never by transmitting the code itself.

1. Start the server as above and connect the tablet to it (manual entry or
   discovery; see "Windows and WSL2" below if discovery does not find it).
2. Confirm the tablet shows up: run `clients` at the console, or watch for
   the `client connected: ...` log line.
3. On the server console, run:
   ```
   pair-dynamic
   ```
   (or `pair-dynamic <client_id>` if more than one client is connected).
   The console prints "initiating dynamic pairing; read the six digits off
   the device screen" and then blocks waiting for input -- this is
   `run_dynamic_pairing_code_server`'s `pairing_code_provider` callback,
   which this script implements by reading a line from stdin.
4. On the tablet, choose dynamic pairing. It displays a six-digit code.
5. Read that code and type it at the server console, then press Enter, at
   the prompt:
   ```
   Enter the pairing code shown on the device:
   ```
6. On success the console prints "pairing initiated" and the server logs the
   client's pairing state moving to paired. Confirm:
   - **The pairing record persists.** Check
     `.dev/sendspin/pairing_store.json` (or your `--pairing-store` path) for
     a new entry after the exchange completes, and confirm it survives a
     server restart (`quit`, restart, `clients` should show the device as
     `paired=True` on reconnect without repeating this procedure).
   - **The re-handshake to the new long-term PSK succeeds.** The dynamic
     pairing exchange itself runs over the *old* connection security; once
     it finishes, the client is expected to reconnect (or the connection is
     expected to renegotiate) using the newly stored PSK. Confirm the
     tablet's connection stays healthy across that transition rather than
     dropping and failing to come back.

A wrong code produces a `pair/abort` with reason `pairing_code_mismatch`
instead of a success -- see the device-acceptance checklist below for what to
confirm about the client's recovery from that.

## Verifying the target is configured correctly

Run the automated checks:

```bash
python ci/conformance/verify_dev_server.py
```

which asserts, on a throwaway state directory:

- the persistent identity is stable across restarts, and `server_id` is 43 chars
- an empty identity file is refused rather than silently replaced
- a corrupt identity file is refused rather than silently replaced
- a strict server **closes** a legacy `client/hello`-first connection
- a **permissive** server accepts that same connection

The last check is the control that gives the one before it meaning. Without it,
a server that was simply broken would also "reject" the legacy client and this
script would report success for the wrong reason. It uses `dev_server`'s own
`build_server`, so it exercises the shipped configuration rather than a copy.

Expected output (aiosendspin also logs an "Accepting unencrypted legacy
connection (transition mode)" line during the control):

```
PASS identity is stable across restarts
PASS server_id is 43 base64url chars
     server_id: Q14IWqmBv7Me1wJAXmOqW-Ge9BXKK_6g6fJZotcERwA
PASS refuses to mint over an EMPTY identity file
PASS refuses to mint over a CORRUPT identity file
PASS strict server REJECTS the legacy client/hello
PASS control: permissive server ACCEPTS the same legacy client/hello

ALL CHECKS PASSED
```

The `server_id` differs on every run: the script uses a fresh temporary state
directory, so it never reuses the one your dev server prints.

Then point the current app build (2.0.0-Beta14) at the running server. It must
discover the server and then **fail to establish a session**, with the server
logging the `client/hello`-first frame being rejected. That failure is the
expected pre-Phase-1 baseline.

## Device acceptance checklist

Unit and instrumentation tests cover most of the dynamic-pairing-code
implementation, but the items below only exist at the seam between the app,
the OS, and a human, so they can only be verified by actually running this
procedure against a real tablet and a real server. Work through this list
during device acceptance and record the result of each:

- **The four `activePairingMethod` out-of-sequence guards** in
  `handleServerPairInit` / `Auth` / `Confirm` -- confirm the client rejects
  or ignores a pairing message that arrives in the wrong order or for a
  method that is not the one currently active, rather than crashing or
  silently accepting it.
- **`runDynamicPairingActions`' fail-closed path** when the handshake hash,
  store, or cipher suite is null -- these are states that should be
  unreachable in a real run; confirm that if one is somehow hit, the client
  aborts the pairing rather than proceeding with missing material.
- **The `dynamicPairingFlow` lazy-build versus reuse branch** -- pair once,
  then pair again (e.g. after a successful `verify` or a second device) and
  confirm the flow object is correctly rebuilt or reused rather than reusing
  stale state from the first attempt.
- **Every `DynamicPairingAction` arm in the handler dispatch loop** -- walk
  through a full successful pairing and confirm each action the flow emits
  is actually handled (not just the happy-path subset exercised by unit
  tests with a fake transport).
- **`resetForRehandshake()` clearing dynamic-flow fields mid-attempt** --
  trigger a rehandshake (e.g. by forcing a reconnect) while a dynamic
  pairing attempt is in progress and confirm no stale field from the
  aborted attempt leaks into the next one.
- **The `COMMAND_ALLOW_PAIRING` custom-command round trip and
  `MainActivity.onAllowPairingClicked()`** -- confirm the gesture-gated
  "Allow pairing" button actually reaches the service via the MediaSession
  custom command and unblocks the pending pairing attempt.
- **A wrong code producing `pair/abort` with `pairing_code_mismatch` and the
  UI recovering** -- see step 4 in the plan; confirm the app shows a clear
  failure state and lets the operator retry rather than getting stuck.
- **Five failed attempts escalating the sixth to the gesture gate, and a
  success de-escalating** -- confirm the attempt counter is per-pairing-
  session state that a subsequent success actually clears, not a counter
  that stays escalated forever once tripped.
- **MediaSession IPC delivery, TalkBack announcing the code, and on-screen
  legibility across a room** -- confirm the six digits reach the UI promptly
  over the MediaSession IPC boundary, that TalkBack reads the code aloud
  usably, and that the digits are legible at a normal viewing distance (not
  just readable in a close-up screenshot).

## Resetting state

| to reset | delete | consequence |
|---|---|---|
| all pairings | `.dev/sendspin/pairing_store.json` | clients must re-pair; safe |
| the server's identity | `.dev/sendspin/identity.key` | **destructive** |

Deleting `identity.key` changes `server_id`. Every stored-pubkey pairing record on
every paired device then matches on `psk_id` but fails the `server_id` check, and
the spec's failure handling for that is to close the WebSocket **with no
application-level error message** (`connection.md#failure-handling`). The symptom
is an unexplained disconnect loop with nothing useful in any log. The script
refuses to overwrite a corrupt identity file for this reason.

## Windows and WSL2

Run the server on the **Windows host**, not inside WSL2. WSL2 sits behind a NAT,
so neither mDNS advertising nor an inbound WebSocket from a phone reaches it.

If WSL2 is unavoidable:

```powershell
netsh interface portproxy add v4tov4 listenport=8927 listenaddress=0.0.0.0 `
  connectport=8927 connectaddress=<wsl-ip>
```

...and use the app's **Add Server Manually** flow with an explicit `host:8927`,
because discovery will not work. Android's `NsdManager` is also unreliable on some
OEM builds and on networks with client isolation, so keep manual entry in mind
regardless of WSL2.

## Debugging the Noise prologue

The prologue is the concatenation of `client/init` and `server/init`'s **exact
wire bytes** - the spec requires hashing what was sent and received, not a
re-serialization. `kotlinx.serialization` will not round-trip byte-identically,
so a mismatch here is the most likely silent failure in item 1.2.

```bash
python ci/conformance/dev_server.py --debug
```

raises aiosendspin and this script to DEBUG. **Be aware of what that does not
give you:** aiosendspin 9.1.0 does not log the raw init bytes at any level. It
builds the prologue in `aiosendspin/noise/driver.py` as
`client_init_text.encode() + server_init_text.encode()` with no log statement.
To diff our concatenation against the server's you have to capture the frames
yourself - a WebSocket proxy in front of the server, or a local one-line patch
adding a log call to that function. (An earlier version of this runbook claimed
`--dump-wire` surfaced these bytes. It did not; the flag has been renamed to
`--debug` to stop promising it.)

## Relationship to the conformance harness

The harness (`.github/workflows/conformance.yml`) constructs its own server via
`Sendspin/conformance`'s `aiosendspin_server.py` adapter, which hardcodes
`allow_unencrypted=True` and regenerates the identity per run. That is fine for
the legacy scenarios it runs today but cannot be the encrypted target.

`ci/conformance/register_sendspindroid.py` now rewrites that literal to read the
`CONFORMANCE_ALLOW_UNENCRYPTED` environment variable, defaulting to the existing
behaviour, so a future Phase 1 exit criterion can flip one variable in CI instead
of forking the harness. The patch fails loudly if the literal disappears upstream.
