# SendSpinDroid Design Suggestions
                s
Design analysis of the [visual audit](view-audit.html), organized by screen with cross-cutting issues at the end. Each item is tagged with priority and includes a checkbox for tracking.

---

## Screen 1: Now Playing

### Phone Portrait (390x844)

- [ ] **[P1] Queue pill button redundancy** -- The "Queue" pill below the volume slider duplicates the queue icon in the secondary row. Remove the pill or replace it with a chip showing track count (e.g., "Queue - 8 tracks").
- [ ] **[P2] Group label opacity** -- "Group: Whole House" at 12px with 0.9 opacity on #D0BCFF is hard to read. Remove the opacity; the primary color alone provides sufficient visual hierarchy below the 14px artist line.
- [ ] **[P2] Secondary row contrast** -- Secondary action icons at 16px font with 0.7 opacity on #CAC4D0 will fail the 4.5:1 WCAG contrast ratio against #1C1B1F. Use full opacity or increase icon size.
- [ ] **[P3] Vertical rhythm** -- Gaps between sections are inconsistent (16px above art, 24px below art, 24px above volume). Standardize to 16px or 24px throughout.
- [ ] **[P2] Volume slider ambiguity** -- The volume slider does not clarify whether it controls device volume or server-reported volume (`client/state.volume`). Add a subtle label or differentiate these controls if both exist.

### Phone Landscape (844x390)

- [ ] **[P2] Missing secondary actions** -- Favorite and music note are dropped in landscape. At minimum, shuffle/repeat should be preserved since they are playback controls, not decorative. Expose missing actions via the "..." overflow.
- [ ] **[P1] Touch targets on controls** -- Prev/next buttons at 40px are below the 48dp Material 3 minimum. Increase to 48px or add invisible padding to meet the target.
- [ ] **[P3] Nav rail active state** -- No rail item is highlighted on the Now Playing screen (there is no "Playing" rail item for phones). Consider dimming the rail slightly or highlighting the item the user navigated from.

### Tablet 7" Portrait (600x1024)

- [ ] **[P2] Queue icon in top bar is redundant** -- The queue panel is already persistently visible. Repurpose this icon as a "collapse queue" toggle, or remove it.
- [ ] **[P3] Album art could grow** -- Art is 200px in a 298px available column. Could grow to 240px for better visual impact.

### Tablet 7" Landscape (1024x600)

- [ ] **[P3] Visual center of mass** -- The 55/45 split causes the player+art to feel left-heavy due to the nav rail. Consider 60/40 player/queue split.
- [ ] **[P2] Missing secondary actions** -- Same as phone landscape: shuffle/repeat are dropped despite ample space.

### Tablet 10" (Portrait & Landscape)

- [ ] **[P3] Queue item thumbnails** -- At 44-48px, they are small for arm's-length reading on a 10" device. Grow to 52-56px.
- [ ] **[P2] Play button size inconsistency** -- Portrait uses 88px play button, landscape uses 72px. Standardize to 80px or 88px across all tablet form factors.

### TV (1920x1080)

- [ ] **[P1] TV safe zones** -- 48px padding is only 2.5% inset. TV content must be 5% inset (96px) to stay within safe zones. Increase padding to 96px.
- [ ] **[P1] D-pad focus states** -- No focus indicators are shown. TV navigation relies entirely on D-pad focus. Design focus ring/elevation treatment for all interactive elements.
- [ ] **[P2] Top bar too thin** -- 56px top bar with 20px title is too small at TV viewing distance (2-3 meters). Increase to 72px with 24px title.
- [ ] **[P2] Progress bar too thin** -- 6px progress bar is hard to see from a distance. Use 10-12px with a larger playhead. If scrubbing is disabled (server controls position), visually indicate the bar is read-only.
- [ ] **[P1] Group label too small** -- "Group: Whole House" at 16px/0.9 opacity is near-invisible from couch distance. This is critical information for a multi-room sync app. Render at 20px minimum with same weight as artist text.

### Head Unit (900x1600)

- [ ] **[P2] System chrome insets** -- The 80px bottom nav may conflict with Android Automotive OS system chrome. Account for OEM-specific safe insets.
- [ ] **[P2] Up Next touch targets** -- Up Next rows at ~56px total height are borderline. Android Automotive recommends 76dp minimum for touch targets.
- [ ] **[P3] Missing volume control** -- Volume is controlled via physical knobs in vehicles. If server `volume` still needs adjustment, expose it via overflow menu.

---

## Screen 2: Server List

### All Form Factors

- [ ] **[P1] No responsive adaptation** -- Server list is nearly identical across phone, 7" tablet, 10" tablet, and head unit. On tablets (600-1280px), the single-column list wastes vast amounts of space. Implement a two-column card grid or master-detail layout for tablets.
- [ ] **[P2] Server icon semantics** -- The play triangle icon for server items is ambiguous (implies "press to play" not "this is a server"). Use a router, speaker, or custom server icon.
- [ ] **[P2] Green status color not in theme** -- The #81C784 "Online" badge is not a Material 3 token. Define a semantic "positive" color in the design system for theming compatibility.
- [ ] **[P2] FAB overlap on scroll** -- The FAB at bottom-right will overlap the last list item when scrolling. Use scroll-inset behavior or an extended FAB anchored above the bottom edge.
- [ ] **[P2] Missing nav rail on tablet landscape** -- Tablet landscape server list omits the nav rail, while all other tablet landscape screens include it. Either show it in a disabled state or document why it is intentionally absent (pre-connection state).
- [ ] **[P3] No server switching flow** -- Once connected, there is no documented way to return to the server list or switch servers. The "..." overflow presumably handles this, but the flow is undesigned.

### TV

- [ ] **[P2] No FAB (correct)** -- TV correctly omits the FAB since adding servers from TV is impractical. Document this as intentional.
- [ ] **[P1] TV focus states** -- Same D-pad focus gap as Now Playing TV.

---

## Screen 3: Home / Browse

### No Playback State

- [ ] **[P2] Card scrollability affordance** -- Only 2 cards visible at 160px width on phone. Show 2.5 cards to hint that the row scrolls.
- [ ] **[P2] Card title truncation** -- Long titles truncate aggressively with `text-overflow: ellipsis` at 160px. Use `line-clamp: 2` (two-line wrap) instead.
- [ ] **[P3] Card subtitle size** -- 11px is at the WCAG minimum for body text. Increase to 12px for accessibility.
- [ ] **[P3] Home screen lacks server context** -- A "now playing" banner or card (e.g., "Living Room is playing Midnight City -- join?") would leverage the app's multi-room sync identity.

### Now Playing - Mini Player Active

- [ ] **[P2] Landscape side panel artist text** -- 10px artist text in the 200px landscape side panel is below WCAG practical minimum. Increase to 12px.
- [ ] **[P2] Landscape side panel skip button** -- Only shows play/pause, no skip-next. Users commonly skip tracks from browse screens.
- [ ] **[P3] Mini player on tablet landscape** -- The mini player floats at the bottom of the content area without a bottom nav anchor. Pin it more firmly with a stronger border or elevation.

---

## Screen 4: Queue

### Phone (Bottom Sheet)

- [ ] **[P3] Landscape height** -- Bottom sheet consumes ~75% of 390px in landscape. Document that sheet height is capped (e.g., max-height: 90%) and content scrolls internally.
- [ ] **[P2] State label mismatch** -- The "No Playback" state label shows a queue with "Now Playing: Midnight City." Either rename the state or show an empty queue for no-playback.

### Tablet (Side Panel)

- [ ] **[P3] Dimmed content** -- The 50% opacity dimmed Home content behind the queue panel shows truncated cards. Consider whether to show more content or replace with a "return to browsing" prompt.
- [ ] **[P2] Queue context inconsistency** -- The queue appears as an inline panel on Now Playing (Screen 1) AND as a side panel over Home (Screen 4). Clarify: are these different surfaces or the same panel shown in different contexts?

### TV

- [ ] **[P2] Queue panel width** -- 350px queue on a 1920px TV screen is only 19% of the viewport. For a lean-back interface where queue management is a primary activity, consider 420-480px or a 50/50 split.
- [ ] **[P1] TV focus states** -- Queue items need D-pad focus indicators.

---

## Cross-Cutting Issues

### Connection & Sync State (P0)

- [ ] **Connection status indicator** -- The top bar shows "Living Room" as a static subtitle. There is no visual feedback for reconnecting, sync drift, or error states. Add an animated dot, status chip, or color change to the server name subtitle. This is the app's core value proposition -- users must see sync health at a glance.
- [ ] **Stream error state** -- No mockup exists for `stream/stop` or `state: "error"`. Design an error banner, reconnecting overlay, or toast for mid-playback connection loss.

### Group Management (P2)

- [ ] **Group interaction** -- "Group: Whole House" is display-only. Users cannot see which rooms are in the group, or add/remove their device. At minimum, design a popover from the group label showing group members.

### Progress Bar Semantics (P1)

- [ ] **Read-only progress bar** -- If the server controls playback position (no client scrubbing), the progress bar creates a false affordance. Remove the scrub thumb, use a distinct color, or add an accessibility label stating "Position controlled by server."

### Navigation (P2)

- [ ] **Nav rail labels at 80px width** -- The 80px rail shows labels (Home, Search, etc.) at ~9px. Material 3 specifies 80px for collapsed (icon-only) rails and 256px for expanded (icon+label) rails. Either remove labels or widen the rail. Current labels are unreadable.
- [ ] **Overflow menu ("...") is undefined** -- Every screen shows "..." but the menu contents are unspecified. Define per-screen: Settings, Disconnect, About, Cast, EQ, Sleep Timer, etc.

### Touch Targets (P1)

- [ ] **Inconsistent button sizes** -- Secondary row buttons, queue item overflow buttons, and some control buttons are below 48dp. Audit all interactive elements and ensure 48dp minimum.

### Missing Screens (P1-P2)

- [ ] **[P1] Settings screen** -- Audio latency (`static_delay_ms`), bit depth, channel config, server settings.
- [ ] **[P1] Add Server screen** -- The FAB "+" destination. Cover: entering address, naming, connection test.
- [ ] **[P1] Connection flow** -- What happens between tapping a server and being connected? Loading state, error state, retry flow.
- [ ] **[P2] Empty states** -- Home with no content, Queue with no tracks, Server List with no servers.
- [ ] **[P2] Notification / lock screen** -- MediaSession surfaces are critical for a background audio app.
- [ ] **[P3] Android Auto browse tree** -- Head unit is mocked, but the actual Android Auto MediaSession UI is not.

### Protocol-Specific UI Gaps (P3)

- [ ] **Artwork channels** -- The protocol supports 4 artwork channels (types 8-11). Only channel 0 (album art) is represented. Channels 1-3 could carry visualizer or supplemental art.
- [ ] **Visualizer data** -- Binary message type 16 sends visualizer data. No UI represents this. Consider a toggle-able visualizer overlay on the Now Playing album art.

---

## Code Verification (2026-03-08)

Cross-referenced P0/P1 items against the actual codebase. Several "issues" from the
mockup analysis are already handled in the real app:

| Item | Verdict | Notes |
|------|---------|-------|
| TV safe zones (96px) | Already handled | `AdaptiveDefaults.kt` uses 48.dp + `overscanSafe()` modifier |
| TV D-pad focus states | Already handled | `TvFocusHelpers.kt` has full focus ring + scale animation |
| TV group label size | Already handled | Uses `labelMedium`/`labelLarge` typography that scales |
| Settings screen | Already exists | `SettingsScreen.kt` -- 700+ lines, fully implemented |
| Add Server screen | Already exists | `AddServerWizardScreen.kt` -- multi-step wizard |
| Connection flow | Already exists | `ConnectionLoadingScreen.kt` + progress indicator |

**Remaining actionable items (4):**

1. **P0: Connection status visibility** -- `ReconnectingBanner.kt` exists but integration needs verification
2. **P1: Touch targets** -- Compact layout buttons hardcoded to 44.dp (below 48dp minimum)
3. ~~**P1: Progress bar semantics**~~ -- Already handled: mobile uses text-only, TV uses LinearProgressIndicator, no Slider/thumb anywhere. `isCurrentMediaItemSeekable()` returns false with test coverage.
4. **P1: Server list responsive** -- Single-column `LazyColumn` everywhere, no tablet grid

---

## Priority Legend

| Tag | Meaning |
|-----|---------|
| P0 | Blocking -- must fix before implementation begins |
| P1 | Fix before implementation of that screen |
| P2 | Fix in first iteration |
| P3 | Consider for second iteration |
