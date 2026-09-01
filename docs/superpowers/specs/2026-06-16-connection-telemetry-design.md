# Connection Handoff Telemetry Design

**Date:** 2026-06-16
**Status:** Draft, pending review
**Scope:** Instrument network-handoff recovery (the "drive away from Wi-Fi" scenario) so we can measure, in the field, whether the `ConnectionCoordinator` failover actually keeps audio alive — and get anonymous, opt-in feedback from real users. Spans two repos: the Android client (`SendspinDroid`) and a collector co-located with the marketing/docs site (`sendspindroid-website`).

---

## 1. Problem

"Audio stops when I drive away from the house" is the #2 reason we're hesitant to drop the 2.x beta label. The `ConnectionCoordinator` *has* failover machinery (on cellular it re-prioritizes `PROXY > REMOTE > LOCAL`, recomputed per reconnect attempt), but today we have **zero field visibility** into whether it works. We are guessing.

The measurement has a built-in trap: a **local-only** server is physically unreachable from cellular, so a failed recovery there is *expected*, not a bug. A raw "recovery rate" would be dominated by these expected failures and hide the real ones. The signal only becomes actionable when split by **what methods the server has configured**:

- `local-only` + cellular → failure is expected (product/education problem: "configure a remote path")
- `has remote/proxy` + cellular → failure is a real bug (failover isn't doing its job)

We also want to **confirm in production** that the re-login race fix (PR #174) actually eliminated forced re-logins — not just that a unit test passes.

## 2. Goals

- Measure **handoff recovery rate, split by configured methods**, across real users.
- Capture enough per-episode detail (attempts, methods, timings, outcome) to debug the real failures.
- Confirm PR #174 in the field via a `reLoginPrompted` signal trending to ~0.
- Ship a **local, always-on** episode recorder + diagnostics view usable by everyone (and by us as a dev tool), independent of any upload.
- **Strictly anonymous, opt-in** upload: off by default, categorical + timing data only, no addresses/server names/tokens. Consistent with the F-Droid / no-proprietary-SDK posture.
- Co-locate the collector with the existing site so it's one thing to deploy and operate.

## 3. Non-goals

- No third-party analytics SDK (no Firebase/Crashlytics). Plain HTTPS POST only.
- No per-user identification. A rotatable random install UUID is the only stable id, and it is not PII.
- No always-on streaming telemetry / no playback-content reporting. Episodes are discrete and infrequent (one per network handoff).
- No change to the `ConnectionCoordinator`'s connection logic. This is observation only — it consumes existing state flows.
- No data leaves the device unless the user explicitly opts in.

## 4. The `HandoffEpisode` (the wire contract)

This JSON object is the interface between the two repos; nail it down first. All fields are categorical or timing. **Never** included: IP/host addresses, server names, tokens, account identifiers, SSIDs, track/media metadata.

```jsonc
{
  "schemaVersion": 1,
  "installId": "uuid-v4",            // random, locally generated, rotatable; not PII
  "appVersion": "2.0.0-Beta13",      // correlate fixes to releases (e.g. #174)
  "androidSdk": 34,
  "episode": {
    "startTs": 1718560000000,        // epoch ms, device clock
    "trigger": "NETWORK_CHANGE",     // NETWORK_CHANGE | TRANSPORT_LOST
    "fromTransport": "WIFI",         // WIFI | CELLULAR | ETHERNET | VPN | NONE | UNKNOWN
    "toTransport": "CELLULAR",
    "wasPlaying": true,              // active playback vs idle (idle = noise, kept but tagged)
    "configuredMethods": {           // THE denominator
      "local": true, "proxy": false, "remote": false
    },
    "preHandoffMethod": "LOCAL",     // LOCAL | PROXY | REMOTE | null
    "attempts": [                    // capped (e.g. 12)
      { "method": "PROXY",  "result": "FAIL",    "errorClass": "IOEXCEPTION", "tOffsetMs": 0 },
      { "method": "REMOTE", "result": "FAIL",    "errorClass": "TIMEOUT",     "tOffsetMs": 4200 },
      { "method": "LOCAL",  "result": "FAIL",    "errorClass": "IOEXCEPTION", "tOffsetMs": 9000 }
    ],
    "outcome": "EXHAUSTED",          // RECOVERED | EXHAUSTED | ABANDONED (user left) | TIMEOUT
    "recoveredMethod": null,         // LOCAL | PROXY | REMOTE | null
    "recoveryMs": null,              // startTs -> Ready, if recovered
    "audioGapMs": null,              // loss -> audio resumed, if measurable; else null
    "reLoginPrompted": false         // did this episode surface loginRequired? (#174 watchdog)
  }
}
```

`errorClass` is a small closed enum (e.g. `IOEXCEPTION`, `TIMEOUT`, `AUTH_REJECTED`, `NO_ROUTE`, `CANCELLED`, `OTHER`) — never a raw message (messages can leak host/path).

**Strict validation contract (collector):** reject (HTTP 400, no insert) anything that isn't exactly this shape — unknown top-level keys, missing required fields, out-of-enum values, oversized arrays, or a `schemaVersion` the collector doesn't know. Strictness is the abuse defense (see §6).

## 5. Android client design (`SendspinDroid`)

### 5.1 `HandoffEpisodeRecorder`
A Service-scoped observer (owned by `PlaybackService`, alongside the `ConnectionCoordinator`). It does **no new measurement** — it folds existing signals into episodes:

- Opens an episode on a `NetworkEvaluator` transport transition or a `TransportState` loss **during an active session**.
- Appends an attempt per `ReconnectStatus.Attempting` with the selected method and the resulting `FailureReason`/error class.
- Closes on `Ready` (`RECOVERED`), `Failed(Exhausted)` (`EXHAUSTED`), session teardown (`ABANDONED`), or a max-duration timeout (`TIMEOUT`).
- Sets `reLoginPrompted` if `MusicAssistant.loginRequired` fired within the episode window.
- `configuredMethods`/`preHandoffMethod` come from the active `UnifiedServer` + coordinator selection.

### 5.2 Local ring buffer + "Connection Health" screen (ships to everyone)
- Keep the last N (≈50) episodes in a bounded buffer, lightly persisted (so a crash/restart keeps recent history).
- A diagnostics screen renders the episodes as a readable timeline. **Reuses the existing `LogFileWriterShareIntent`** share path so any user — opted in or not — can hand us a report.
- This is also our own dev tool and the in-field validator for #174.

### 5.3 Install id
- A random UUIDv4 generated on first run, stored in prefs, **rotatable** from settings ("Reset anonymous ID"). Lets the collector compute per-install rates and dedupe upload retries. Resets on reinstall / clear-data. Not PII.

### 5.4 Opt-in uploader
- **Off by default.** Gated by a single setting.
- Batches closed episodes; flushes opportunistically: on app foreground, **prefer unmetered network** (don't spend the cellular data we're measuring), exponential backoff on failure, hard cap on queue size (drop oldest), per-request body-size cap.
- Plain OkHttp `POST https://sendspinapp.com/api/telemetry` (already have OkHttp; no new dep). Drops the batch on a 4xx (our bug — don't infinite-retry malformed data); retries on 5xx/network.

### 5.5 Opt-in UX
- Settings toggle: "Help improve connection reliability — anonymous; sends no addresses, names, or account info." Links to the privacy note (hosted on the docs site).
- Exactly **one** gentle, dismissible prompt, shown **next app launch after a bad episode** (never while driving), offering to turn it on. No nag, no dark pattern, no pre-checked boxes.

## 6. Collector design (`sendspindroid-website`)

The site is a static Astro/Starlight build served by nginx; it can't take a POST. The collector is a **sidecar** behind the same nginx.

### 6.1 Service
- **Node + better-sqlite3** (consistent with the repo's Node toolchain; SQLite file on a volume = queryable, no DB server).
- Single route `POST /api/telemetry`:
  1. Enforce `Content-Type: application/json` and a small body cap.
  2. **Strict schema validation** against §4 (closed enums, required fields, array caps, known `schemaVersion`). Any deviation → `400`, no insert.
  3. Insert one row per episode; flatten `attempts` into JSON text or a child table.
  4. Respond `204`.
- No write secret (a client-embedded secret in an open-source app is not secret). Abuse is handled by strictness + nginx (below).

### 6.2 Storage (SQLite)
`episodes` table mirrors §4 plus `receivedAt` (server clock). **No client IP is stored — not even hashed.** The Node service must not read or persist the remote address, and nginx must not log it for this route (see §6.3). Index on `(appVersion, receivedAt)` and the `configuredMethods` flags for the split-by-config queries.

### 6.3 Deployment wiring (3 changes in the website repo)
1. `collector/` — service code + its own `Dockerfile`.
2. `docker-compose.yml` — add a `collector` service on the default network + a named volume for the DB:
   ```yaml
   collector:
     build: ./collector
     restart: unless-stopped
     volumes: [ "telemetry-db:/data" ]
   # volumes: { telemetry-db: {} }
   ```
3. `nginx.conf` — front the collector from the existing server block:
   ```nginx
   limit_req_zone $binary_remote_addr zone=telemetry:10m rate=10r/m;
   location /api/telemetry {
       limit_req zone=telemetry burst=5 nodelay;
       client_max_body_size 32k;
       access_log off;                 # no IPs logged for this route
       proxy_pass http://collector:PORT;
       # deliberately do NOT forward X-Real-IP / X-Forwarded-For to the collector
   }
   ```
   `$binary_remote_addr` is used only transiently in-memory for rate-limiting (nginx's `limit_req` zone); it is never written to disk or passed downstream.
No new subdomain, no DNS change, no SSR conversion. Upstream homelab proxy keeps fronting `sendspinapp.com` → the website container's nginx as today.

### 6.4 Reading the data (Phase C)
Start by querying the SQLite directly for the headline numbers (recovery rate split by `configuredMethods`; `reLoginPrompted` rate by `appVersion`). Wire Grafana/a small read endpoint later if desired.

## 7. Privacy summary

- Off by default; nothing leaves the device without an explicit opt-in.
- Categorical + timing only; an all-list schema, not a deny-list — fields not in §4 are impossible to send.
- No addresses, names, SSIDs, tokens, accounts, or media metadata.
- **No client IP addresses anywhere** — the collector never stores them and nginx never logs them for the telemetry route (IPs exist only transiently in nginx's in-memory rate-limit zone).
- Rotatable anonymous install id.
- A plain-language privacy note on the docs site, linked from the toggle.

## 8. Phasing

- **Phase A — Local recorder + diagnostics (ships to everyone, no network).** `HandoffEpisodeRecorder`, ring buffer, "Connection Health" screen + share. Immediately validates #174 and de-risks the §4 schema before any backend exists.
- **Phase B — Collector + opt-in upload.** Build the `sendspindroid-website` collector first (so there's a target), then the Android opt-in toggle + uploader + disclosure prompt.
- **Phase C — Query/dashboard.** Headline queries, then optional Grafana.

## 9. Decisions made

- Endpoint: **path-based** `https://sendspinapp.com/api/telemetry` via the existing nginx (no new subdomain).
- Collector: **Node + better-sqlite3**, co-located in `sendspindroid-website`.
- Abuse: **no write secret**; **strict allow-list schema validation** + nginx rate-limit + body-size cap.
- Audience: ship the **recorder to everyone**; **upload is opt-in**, off by default.
- Identity: rotatable random install UUID; strictly anonymous, categorical-only payload.
- `audioGapMs` is **best-effort**: use the true `SyncAudioPlayer` underrun→resume gap where cleanly available, otherwise approximate with coordinator reconnect timing and mark the field accordingly.
- **No IP addresses in telemetry at all** — not stored, not logged (collector + nginx), not even hashed.

## 10. Open questions

- Ring-buffer persistence depth and the diagnostics screen's placement (Settings → Diagnostics vs a hidden gesture).
- Where the privacy note lives in the Starlight sidebar.
