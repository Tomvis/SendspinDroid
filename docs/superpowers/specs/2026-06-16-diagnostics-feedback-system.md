# Diagnostics & Feedback System

**Date:** 2026-06-16
**Status:** Draft, pending review
**Scope:** Unify SendSpinDroid's existing-but-scattered logging/diagnostics surfaces into one coherent, safe, easy-to-use feedback system: a single hub, a one-tap "Report a problem" flow that produces a *sanitized* bundle and a pre-filled GitHub issue, crash capture, and the connection-handoff telemetry as one pillar. Supersedes the standalone build order in `2026-06-16-connection-telemetry-design.md` (that spec remains the detailed design for the Connection Health pillar).

---

## 1. Problem

The app already captures a lot — but the pieces are disconnected, display-only, unsafe to share, and produce nothing for a first-occurrence bug.

- **No report flow / no GitHub anything.** No "Report a bug" affordance, repo link, or issue pre-fill. Today a user must: hit bug → Settings → Debug → Export → choose a share target → find the repo themselves → file. Most won't.
- **The richest artifact never reaches a report.** `PlaybackService.getStats()` / "Stats for Nerds" (`ui/stats/`) is the single most useful debugging payload (connection, network, clock sync, DAC, buffer, sync correction, coordinator session/reconnect state) and it is **display-only** — not in the exported log bundle.
- **No crash capture.** No `Thread.setDefaultUncaughtExceptionHandler`. And `AppLog.level` defaults to **OFF**, so a first-occurrence bug usually captures nothing; the user must reproduce with logging enabled.
- **Exports are not sanitized — a privacy risk.** Log exports and the stats snapshot include the **server address**; `AppLog.session.start(serverName, serverAddress)` logs both, and proxy config can carry a **token**. One-tap sharing into a *public* GitHub issue would be one-tap leaking infrastructure/PII.

## 2. Goals

- **One hub.** A single "Diagnostics & Feedback" surface that unifies status, connection health, live diagnostics, logging controls, telemetry opt-in, and reporting.
- **One-tap useful report.** "Report a problem" bundles a sanitized diagnostic snapshot + recent logs and opens a pre-filled GitHub issue with the environment auto-filled and a repro-steps prompt.
- **Safe by construction.** A single redaction layer scrubs everything that leaves the device — addresses, proxy URLs, tokens, **and** server names.
- **Something to report from the first bug.** Lightweight always-on capture (WARN+) so reports aren't empty by default.
- **Capture crashes.** An uncaught-exception handler persists the crash; offer a report on next launch.
- **Reuse, don't reinvent.** Build on `AppLog`, `LogcatBridge`, `LogFileWriter`, `getStats()`, and the existing Settings log controls. Fold the handoff telemetry in as the "Connection Health" pillar.

## 3. Non-goals

- No third-party analytics/crash SDK (no Firebase/Crashlytics). Plain HTTPS + local files only.
- No automatic crash *upload*. Crash capture stays local/manual unless the user files a report. (Anonymous handoff *telemetry* upload is separate and opt-in — see the Connection Health pillar.)
- No new logging backend for app logs — keep the rotating file capture; just make it safe, richer, and reachable.
- No redesign of "Stats for Nerds" — link to it; don't replace it.

## 4. Current assets (what we build on)

| Asset | Location | Role in the new system |
|---|---|---|
| `AppLog` (9 categories, 5 levels, prefs-backed) | `logging/AppLog.kt`, `LogCategory.kt`, `LogLevel.kt` | Capture facade; default level changes to lightweight always-on |
| `LogcatBridge` (PID-filtered logcat → file) | `logging/LogcatBridge.kt` | Capture pipe; runs when level > OFF |
| `LogFileWriter` (rotating ~10 MB, `shareIntent`) | `logging/LogFileWriter.kt` | Bundle source; export now passes through the redactor + includes the snapshot |
| Settings → Debug (level picker, stats, Export/Clear) | `ui/settings/SettingsScreen.kt` | Folds into the hub |
| "Stats for Nerds" + `getStats()` snapshot | `ui/stats/`, `playback/PlaybackService.kt:3146` | Snapshot is captured (sanitized) into every report |
| `ConnectionCoordinator` session/reconnect state | `coordinator/` | Feeds both the snapshot and the handoff recorder |

## 5. Architecture

```
Diagnostics & Feedback (hub)
├─ Status            app version/build, device, live connection summary
├─ Connection Health handoff-episode timeline   ← telemetry spec pillar
├─ Live diagnostics  → existing "Stats for Nerds"
├─ Logging           level picker (default WARN+), capture status
├─ Telemetry         opt-in upload toggle (off by default)
└─ Report a problem  ← keystone

Cross-cutting foundations (used by every sharing path):
  • Redactor        scrubs addresses / proxy URLs / tokens / server names
  • Snapshot        getStats() + connection health, sanitized, attached to reports
  • Crash capture   uncaught-exception handler → log file → next-launch prompt
```

### 5.1 The Redactor (foundation — build first)
A single `RedactionFilter` applied to **any** text/snapshot leaving the device (log export, report snapshot, GitHub body). Export-time filtering (not source rewriting) so it covers existing free-form logs without touching hundreds of call sites.

Redacts:
- **IPv4/IPv6 addresses** and `host:port` forms → `<addr>`
- **URLs** (`ws://`, `wss://`, `http(s)://`, `ma-proxy://`) → scheme + `<redacted>`
- **Tokens / bearer-ish / long base64 strings** → `<token>`
- **The active server's name and address** (known at report time — redact those exact strings everywhere they appear) → `<server>`

Kept: `remoteId` (an identifier, not a secret), device model, app version, categorical/timing fields. A follow-up can reduce raw-address logging at the source, but the export-time filter is the guarantee.

### 5.2 "Report a problem" flow
1. User taps Report (from the hub, or from a next-launch crash prompt, or a future inline error action).
2. App assembles a **bundle**: redacted recent logs (combined file) + a redacted `getStats()` snapshot + recent handoff episodes + an environment block (version, build, device, Android, configured-method *types* only).
3. App writes the redacted bundle to a shareable file (reuses `LogFileWriter.shareIntent` plumbing).
4. App opens a **pre-filled GitHub issue**: `https://github.com/chrisuthe/SendspinDroid/issues/new?...` with title, the environment block in the body, and a repro-steps template. (Pair with a `.github/ISSUE_TEMPLATE` bug form so manual issues match.)
5. Because a URL can't carry attachments, the flow guides the user to attach the bundle file it just saved (one clear instruction + the file already in the share/Downloads).

### 5.3 Crash capture
- Install `Thread.setDefaultUncaughtExceptionHandler` early (Application.onCreate) that appends the stack + a short breadcrumb tail to the log file and sets a "crashed last run" flag, then delegates to the previous handler.
- On next launch, a gentle, dismissible prompt: "SendSpin closed unexpectedly last time — send a report?" → routes into the Report flow with the crash pre-attached. Local-only unless the user sends.
- Optional small in-memory breadcrumb ring (last ~N log lines) so the crash report has recent context even at the lightweight default level.

### 5.4 Lightweight always-on logging
- Change the default `AppLog.level` from `OFF` to a **lightweight always-on** posture (WARN+; optionally an in-memory ring buffer for INFO/DEBUG breadcrumbs without continuous file IO). Full picker remains for deep repro.
- Document the (small) battery/IO cost; ensure the logcat subprocess is resilient (it already retries).

## 6. Relationship to the Connection Health pillar

The handoff-episode recorder, ring buffer, and opt-in upload are specified in `2026-06-16-connection-telemetry-design.md`. In this system:
- The **Connection Health timeline** is a hub section backed by that recorder.
- Handoff episodes are included (sanitized) in the **Report a problem** bundle.
- The **opt-in upload** toggle lives in the hub's Telemetry section.
- `reLoginPrompted` remains the in-field watchdog for PR #174.

## 7. Phasing (foundation-first)

- **Phase 1 — Make sharing safe & rich (foundation).** `RedactionFilter` + apply it to existing log export; fold the `getStats()` snapshot into the exported bundle; flip the default to lightweight always-on logging. *Immediately improves the export users already have, and unblocks everything else.*
- **Phase 2 — The hub + Report flow + crash capture.** "Diagnostics & Feedback" hub consolidating the existing controls; "Report a problem" → bundle + pre-filled GitHub issue + `.github` issue template; uncaught-exception handler + next-launch prompt.
- **Phase 3 — Connection Health pillar.** Handoff-episode recorder + timeline (Phase A of the telemetry spec); feeds the snapshot and the hub.
- **Phase 4 — Telemetry upload.** Opt-in uploader + collector (Phase B of the telemetry spec).

Each phase is independently shippable and useful; later phases plug into the foundations from earlier ones.

## 8. Decisions made

- **Default logging:** lightweight always-on (WARN+ / ring buffer), not OFF.
- **Redaction scope:** redact addresses, proxy URLs, tokens, **and** server names. Keep `remoteId`, device, version, categorical fields.
- **Crash capture:** local uncaught-exception handler + next-launch prompt; never auto-uploaded.
- **Report transport:** sanitized bundle file + pre-filled GitHub issue (no backend; backend is only for the separate opt-in handoff telemetry).
- **Reuse:** build on `AppLog`/`LogcatBridge`/`LogFileWriter`/`getStats()`/Stats-for-Nerds; handoff telemetry is the Connection Health pillar.

## 9. Open questions

- Hub entry point: a dedicated top-level screen vs. expanding Settings → Debug into "Diagnostics & Feedback".
- Redactor for free-form logs: pattern-based (IP/URL/token regex + known server strings) is the pragmatic choice; how aggressive on "long base64" to avoid mangling legitimate payloads (e.g. codec headers in logs)?
- Breadcrumb ring buffer size and whether INFO/DEBUG go to it by default while file capture stays at WARN+.
- GitHub issue: classic `issues/new?title=&body=` query params vs. an issue-form template with typed fields.
