# Welcome Dialog: Name the Player Before It Is on the Network

**Date:** 2026-10-15
**Status:** Accepted

## Goal

Let a new user name the player in the first-run welcome dialog, before the app
advertises itself or searches for servers. The first mDNS announcement and the
first `client/hello` carry the name the user confirmed.

## The problem today

`MainActivity.onCreate` calls `initializeMediaController()` before
`showOnboardingIfNeeded()`. Building the controller binds `PlaybackService`,
whose `onCreate` ends in `connectionMode.start()`. A fresh install has no
default server, so `chooseConnectionModeOnce(false)` has put it in advertising
mode, and `startAdvertising()` announces the player on mDNS as `Build.MODEL`
while the welcome dialog is still on screen.

A server that browses for players (Music Assistant does) can find it and
register it under the model name before the user has touched anything. A
rename afterwards in Settings reaches the server (#346, #348), but the first
impression is already made under the wrong name.

## Decisions

**D1. The welcome is the "Welcome to SendSpin Player" dialog.** The one shown
once by `MainActivity.showOnboardingIfNeeded()` and recorded by
`onboarding_shown` in `sendspindroid_prefs`. Not the "Welcome to SendspinDroid"
hero card on the empty server list, which shows whenever there are no saved
servers.

**D2. The gate is in the activity: the service is not created until the
welcome is confirmed.** `initializeMediaController()` runs at once only when
`onboarding_shown` is already set; otherwise it runs when the user presses
Continue. With no controller there is no bind, no `PlaybackService`, and so no
listener, announcement or dial.

This holds because nothing else starts the service on a fresh install:

- `BootReceiver` acts only with the opt-in "Auto-start on boot" setting, which
  cannot have been set before the welcome.
- `SettingsActivity`, `StatsViewModel` and the diagnostics snapshot bind the
  service, but none of them can be reached while the welcome dialog is up: it
  is modal and cannot be cancelled.
- Android Auto can bind the service before the app's first open. Accepted: a
  car has no LAN with a server on it to hear the announcement, and reaching a
  server from there is a separate case (dialling out over a VPN). D6 covers
  the name in that case.

Searching needs no gate of its own. A fresh install has no default server, so
`chooseConnectionModeOnce(false)` selects advertising, and
`showServerListView()` does not start discovery, auto-connect or the default
server pinger in that mode.

Rejected alternatives:

- *A gate in the service* (`PlaybackService` skips `connectionMode.start()`
  until a shared flag is set, and a broadcast starts it). Covers Android Auto
  too, but moves the flag into `UserSettings` and adds a receiver and a start
  path to the service, for a case D2 accepts.
- *Start as today and rename on Continue.* The server may already have
  registered the model name, which is what this change exists to prevent.

**D3. The flag stays where it is.** `onboarding_shown` in
`sendspindroid_prefs`, read and written only by `MainActivity`. Existing
installs already have it set: they see no dialog, and their start-up is
unchanged.

**D4. The dialog.** Still a `MaterialAlertDialogBuilder`, still not
cancellable.

- Title unchanged: "Welcome to SendSpin Player".
- Body rewritten. The old text said "The app will automatically search for
  servers", which is no longer what a fresh install does:

  > Plays audio from a SendSpin or Music Assistant server, in sync with your
  > other speakers.
  >
  > Servers on your network will find this player by the name below. You can
  > change it later in Settings.

- An outlined Material text field, labelled "Player Name" (the existing
  `pref_player_name_title`), filled with `UserSettings.getPlayerName()`,
  single line.
- The keyboard's Done action does what Continue does, as in the Settings
  dialog: in landscape the keyboard can cover the dialog's buttons.
- The button reads "Continue" (was "Got it").

**D5. A blank name behaves as it does in Settings.** It is saved as typed;
`UserSettings.getPlayerName()` then falls back to `Build.MODEL`. No trimming,
because Settings does not trim.

**D6. Continue, in order:**

1. `UserSettings.setPlayerName(name)`.
2. Set `onboarding_shown`.
3. Send `SettingsViewModel.ACTION_PLAYER_NAME_CHANGED` on the
   `LocalBroadcastManager`. With no service running, nobody receives it. If
   Android Auto has started the service (D2), its `playerNameReceiver` gives
   the dial client the new name and announces again under it, as a Settings
   rename does.
4. `initializeMediaController()`. The service is created and starts
   advertising under the confirmed name.

The "waiting for a server as ..." card needs no change. It reads
`UserSettings.getPlayerName()` in the same composition scope that collects
`PlaybackService.advertisedPort`. That port goes from null to the bound port
once the service is advertising, which recomposes the card with the new name.

## Out of scope

- The hero card on the empty server list.
- The Settings player-name dialog.
- Android Auto before the first open (D2).
- Keeping a half-typed name across an activity recreation. Rotation does not
  recreate `MainActivity` (`configChanges`). Other configuration changes do,
  and the dialog is then shown again with the default name.

## Testing

New Robolectric test, `MainActivityWelcomeTest`, in the style of
`MainActivityRecreateTest` (builds `MainActivity`, reads private fields by
reflection):

- Fresh install: the welcome dialog is showing, and `mediaControllerFuture`
  is null.
- Type a name and press Continue: `UserSettings.getPlayerName()` returns it,
  `onboarding_shown` is set, and `mediaControllerFuture` is not null.
- Welcome already done: no dialog, and `mediaControllerFuture` is set during
  `onCreate`.

Then `./gradlew assembleDebug` and the app unit tests.

On a device, after a fresh install (`pm clear`):

- Logcat shows no `Advertising as` line while the dialog is up.
- After Continue it shows `Advertising as '<chosen name>'`.
- Music Assistant lists the player under the chosen name.
- Reinstalling over an existing install shows no dialog.
