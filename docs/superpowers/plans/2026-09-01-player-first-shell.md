# Player-First Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Now Playing the app's only root destination, with the server picker shown when disconnected, so no Music Assistant screen is reachable.

**Architecture:** `AppShell` already treats `selectedNavTab == null && currentDetail == null` as the Now Playing state -- browse is the exception, not the default. This plan removes the browse branch and its navigation state rather than building a new shell. Music Assistant packages are left in place and become orphaned; a follow-up plan deletes them.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4, MockK, Robolectric, kotlinx-coroutines-test

**Spec:** `docs/superpowers/specs/2026-09-01-sendspin-only-player.md`

## Global Constraints

- No emojis in code, logs, or UI strings. ASCII only: `us` not the micro sign, `->` not an arrow glyph, `+/-` not the plus-minus glyph.
- No self-citation in commits, comments, or release notes.
- Build command prefix: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew <task>`
- Every task must end with `:app:compileDebugKotlin` and `:app:testDebugUnitTest` passing.
- Do not delete anything under `com.sendspindroid.musicassistant` or `com.sendspindroid.remote` in this plan. Orphaning is intended; deletion is the follow-up plan.
- Form-factor support (`FormFactor.PHONE/TABLET/TV/HEADUNIT`) must keep working. Do not remove `ui/adaptive/` helpers.
- Unit tests that read source files use paths relative to the `android/app` module directory, which is the working directory for `:app:testDebugUnitTest`.

---

### Task 1: Make Now Playing the root destination

`AppShell` defaults `selectedNavTab` to `NavTab.HOME` whenever MA is connected, and a `LaunchedEffect` forces it back to `HOME` on every reconnect. Both must go so the shell settles on its existing Now Playing zero state (`selectedNavTab == null && currentDetail == null`).

This is a deletion, not an abstraction. Do not introduce a helper object or a policy function -- the project forbids abstractions for single-use code, and a function that ignores its argument and returns a constant is exactly that.

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/ui/AppShell.kt` (the `selectedNavTab` declaration and the `LaunchedEffect(isMaConnected)` block that follows it)
- Test: `android/app/src/test/java/com/sendspindroid/ui/NowPlayingRootTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `selectedNavTab` is `null` at composition and is never reassigned from connection state. Task 2 deletes the variable entirely; Task 3 deletes the `NavTab` type.

**Locating the edit:** the plan cites line numbers valid only at base commit
38f8e4a. Find the code by content, not by line number.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/ui/NowPlayingRootTest.kt`:

```kotlin
package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SendSpinDroid is a SendSpin player. The protocol defines no library,
 * browse, or search concept, so Now Playing is the only root destination
 * and connection state must not select a browse tab.
 *
 * AppShell encodes Now Playing as selectedNavTab == null && currentDetail
 * == null, so the rule is enforced by asserting that nothing in the file
 * selects NavTab.HOME or reacts to connection state by changing the tab.
 */
class NowPlayingRootTest {

    private fun appShellLines(): List<String> {
        val source = File("src/main/java/com/sendspindroid/ui/AppShell.kt")
        require(source.exists()) { "AppShell.kt not found at " + source.absolutePath }
        return source.readLines().map { it.trim() }
    }

    @Test
    fun connectionStateNeverSelectsABrowseTab() {
        val offending = appShellLines().filter { it.contains("NavTab.HOME") }
        assertEquals("Now Playing is the only root; nothing may select HOME", emptyList<String>(), offending)
    }

    @Test
    fun noLaunchedEffectResetsTheTabOnConnect() {
        val offending = appShellLines().filter { it.startsWith("LaunchedEffect(isMaConnected)") }
        assertEquals("connection changes must not reset the root destination", emptyList<String>(), offending)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*NowPlayingRootTest*"`

Expected: both tests FAIL -- `NavTab.HOME` appears in the `selectedNavTab` initialiser and inside the `LaunchedEffect`, and the `LaunchedEffect(isMaConnected)` line is present.

- [ ] **Step 3: Make the minimal change**

Replace the `selectedNavTab` declaration:

```kotlin
    var selectedNavTab by remember { mutableStateOf<NavTab?>(if (isMaConnected) NavTab.HOME else null) }
```

with:

```kotlin
    // Now Playing is the only root destination; SendSpin defines no browse surface.
    var selectedNavTab by remember { mutableStateOf<NavTab?>(null) }
```

Then delete the whole `LaunchedEffect(isMaConnected) { ... }` block that follows it, which set `NavTab.HOME` on connect and `null` on disconnect. It has nothing left to decide.

Change nothing else. Leave every browse branch in place -- Task 2 removes those.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*NowPlayingRootTest*"`

Expected: PASS, 2 tests.

- [ ] **Step 5: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. If the compiler warns that `isMaConnected` is now unused, leave it -- Task 2 and the follow-up plan remove its remaining readers.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/ui/AppShell.kt android/app/src/test/java/com/sendspindroid/ui/NowPlayingRootTest.kt
git commit -m "refactor(ui): make Now Playing the only root destination"
```

---

### Task 2: Remove the remaining browse surface from AppShell

Task 1 already removed the tab bar, the tab-content rendering and the title
`when`. What remains is the detail-navigation half: the imports, the
`navigateToDetail` callbacks, the `currentDetail` branches, and the
`selectedNavTab` variable itself.

**This task closes a hole Task 1 left open.** Task 1's test forbids only the
literal `NavTab.HOME`, and a live path still assigns `selectedNavTab =
NavTab.LIBRARY`. Browse is therefore still reachable. The strengthened test
below forbids the `NavTab` type outright, which is the invariant that was
actually intended.

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/ui/AppShell.kt`
- Modify: `android/app/src/test/java/com/sendspindroid/ui/NowPlayingRootTest.kt`

**Locating the edits:** all line numbers below were read from the file at the
start of this task and will shift as you delete. Work from the bottom of the
file upward, or re-grep after each deletion. Never trust a stale line number.

**Interfaces:**
- Consumes: an `AppShell` with no tab bar and no tab-content rendering (Task 1).
- Produces: an `AppShell` with zero references to `NavTab`, `DetailDestination`, `selectedNavTab`, `currentDetail`, `isBrowsing`, or any `ui.navigation` / `ui.detail` / `musicassistant` symbol. Task 3 then deletes the types themselves.

- [ ] **Step 1: Strengthen the failing test**

Replace the two test methods in `android/app/src/test/java/com/sendspindroid/ui/NowPlayingRootTest.kt` with the following, keeping the package, imports and the `appShellLines()` helper as they are:

```kotlin
    @Test
    fun nothingSelectsABrowseDestination() {
        val offending = appShellLines().filter { it.contains("NavTab") }
        assertEquals("Now Playing is the only root; NavTab must be unreachable", emptyList<String>(), offending)
    }

    @Test
    fun noDetailNavigationRemains() {
        val offending = appShellLines().filter {
            it.contains("DetailDestination") || it.contains("navigateToDetail") || it.contains("currentDetail")
        }
        assertEquals("SendSpin defines no browse surface to navigate into", emptyList<String>(), offending)
    }

    @Test
    fun noBrowseScreenImportsRemain() {
        val offending = appShellLines().filter {
            it.startsWith("import com.sendspindroid.musicassistant") ||
                it.startsWith("import com.sendspindroid.ui.navigation") ||
                it.startsWith("import com.sendspindroid.ui.detail")
        }
        assertEquals("AppShell must not import MA or browse packages", emptyList<String>(), offending)
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*NowPlayingRootTest*"`

Expected: all three FAIL. Roughly 24 MA/navigation/detail imports remain, plus the `NavTab` and `DetailDestination` imports, the `selectedNavTab` declaration, five `navigateToDetail` callbacks, and several `currentDetail` branches.

- [ ] **Step 3: Delete the remaining browse surface**

Work bottom-up so earlier line numbers stay valid:

1. Delete the detail rendering block -- the `if (currentDetail != null) { ... detail = currentDetail!!, ... }` region near the end of the composable, including every `*DetailScreen` call it makes.
2. Delete the branch that sets `selectedNavTab = NavTab.LIBRARY` and calls `viewModel.setCurrentNavTab(NavTab.LIBRARY)`, and the sibling that resets `selectedNavTab = null`, together with whatever control -- a button, an action, a callback -- made that branch reachable. This is the hole referenced above.
3. Delete `val isBrowsing` and `val isNowPlaying`. Every consumer collapses: `isBrowsing` is now always false and `isNowPlaying` always true, so simplify each conditional accordingly rather than leaving `if (true)`. Where a conditional chose between a Now Playing value and a browse value -- for example `if (isNowPlaying) nowPlayingQueueVisible else browseQueueVisible` -- keep the Now Playing side and delete the browse variable if nothing else reads it.
4. Delete the `topBarTitle` `if (currentDetail != null)` branch, keeping the Now Playing title, and the remaining `currentDetail` conditionals.
5. Delete the five `navigateToDetail` callbacks.
6. Delete the `selectedNavTab` and `currentDetail` declarations.
7. Delete every import the above made unused: the `musicassistant`, `ui.navigation` and `ui.detail` imports, plus `NavTab` and `DetailDestination`.

Do not touch `PlayerBottomSheet`, `PlayerViewModel` or the `MiniPlayer` wiring -- Task 4 owns those.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*NowPlayingRootTest*"`

Expected: PASS, 3 tests.

- [ ] **Step 5: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. A compile error naming a browse screen means a reader survives -- delete the reader, do not restore the import.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/ui/AppShell.kt android/app/src/test/java/com/sendspindroid/ui/NowPlayingRootTest.kt
git commit -m "refactor(ui): remove detail navigation and browse imports from AppShell"
```

---

### Task 3: Remove browse navigation state from MainActivityViewModel

`NavTab` and `DetailDestination` now have no consumer in `AppShell`. Remove them and the back-stack machinery that exists to serve them.

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/ui/main/MainUiState.kt` (delete `enum class NavTab` and `sealed class DetailDestination`)
- Modify: `android/app/src/main/java/com/sendspindroid/ui/main/MainActivityViewModel.kt` (delete the nav-tab and detail back-stack members)
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt` (delete the `NavTab` import, the `currentNavTab` field, the `setCurrentNavTab` call)
- Delete: `android/app/src/test/java/com/sendspindroid/ui/main/CurrentDetailDerivationTest.kt`
- Test: `android/app/src/test/java/com/sendspindroid/ui/main/BrowseStateRemovedTest.kt`

**Interfaces:**
- Consumes: an `AppShell` with no browse branches (Task 2).
- Produces: `NavTab` and `DetailDestination` no longer exist anywhere in the module. `MainUiState.kt` retains `TrackMetadata`, `ArtworkSource`, `ReconnectingState`, `ServerStatus` and `PlayerColors`.

**Locating the edits:** the plan cites line numbers valid only at base commit
38f8e4a, and Tasks 1-2 have shifted them. Find each declaration by name.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/ui/main/BrowseStateRemovedTest.kt`:

```kotlin
package com.sendspindroid.ui.main

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SendSpin defines no library, browse, or search concept, so the app has no
 * browse destinations to model. NavTab and DetailDestination existed only to
 * navigate Music Assistant's library screens.
 */
class BrowseStateRemovedTest {

    private fun sourceLines(relativePath: String): List<String> {
        val source = File(relativePath)
        require(source.exists()) { "not found: " + source.absolutePath }
        return source.readLines().map { it.trim() }
    }

    @Test
    fun browseTypesAreGone() {
        val lines = sourceLines("src/main/java/com/sendspindroid/ui/main/MainUiState.kt")
        val offending = lines.filter {
            it.startsWith("enum class NavTab") || it.startsWith("sealed class DetailDestination")
        }
        assertEquals("browse navigation types must be deleted", emptyList<String>(), offending)
    }

    @Test
    fun playerStateTypesSurvive() {
        val text = File("src/main/java/com/sendspindroid/ui/main/MainUiState.kt").readText()
        val missing = listOf(
            "data class TrackMetadata",
            "sealed class ArtworkSource",
            "data class ReconnectingState",
            "sealed class ServerStatus",
            "data class PlayerColors"
        ).filterNot { text.contains(it) }
        assertEquals("player state types must be retained", emptyList<String>(), missing)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*BrowseStateRemovedTest*"`

Expected: `browseTypesAreGone` FAILS listing both declarations. `playerStateTypesSurvive` PASSES already -- it is a guard against over-deletion, not a driver.

- [ ] **Step 3: Delete the browse state**

1. In `MainUiState.kt`: delete `enum class NavTab { HOME, SEARCH, LIBRARY, PLAYLISTS }` and the whole `sealed class DetailDestination` block including its `Album`, `Artist`, `Playlist`, `Podcast` and `Audiobook` members. Keep `TrackMetadata`, `ArtworkSource`, `ReconnectingState`, `ServerStatus` and `PlayerColors`.
2. In `MainActivityViewModel.kt`: delete `_currentNavTab` and `currentNavTab`, `_detailBackStack` and the derived `currentDetail`, and the functions `setCurrentNavTab`, `navigateToDetail`, `navigateDetailBack` and `clearDetailNavigation`.
3. In `MainActivity.kt`: delete the `com.sendspindroid.ui.main.NavTab` import, the `currentNavTab: Int` field, and the `viewModel.setCurrentNavTab(NavTab.LIBRARY)` call together with the branch that made it reachable.
4. Delete the obsolete test:

```bash
git rm android/app/src/test/java/com/sendspindroid/ui/main/CurrentDetailDerivationTest.kt
```

It tests the detail back stack being removed here.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*BrowseStateRemovedTest*"`

Expected: PASS, 2 tests.

- [ ] **Step 5: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. Any remaining compile error naming `NavTab` or `DetailDestination` is a reader Task 2 missed -- remove that reader rather than restoring the type.

- [ ] **Step 6: Commit**

```bash
git add -A android/app/src/main/java/com/sendspindroid/ui/main android/app/src/main/java/com/sendspindroid/MainActivity.kt android/app/src/test/java/com/sendspindroid/ui/main
git commit -m "refactor(ui): remove browse navigation state from MainActivityViewModel"
```

---

### Task 4: Remove the Music Assistant speaker-grouping sheet and queue entry point

The `ui/player/` package is not the transport UI -- that lives in `ui/main/` (`NowPlayingScreen`, `PlaybackControls`, `TrackProgressBar`, `VolumeSlider`). `PlayerSheetContent` renders "Available speakers", a group-member count and a current-speaker row, driven by `MusicAssistant.getAllPlayers()`, `powerOnPlayer()` and `setGroupMembers()`. It is Music Assistant's multi-room grouping feature.

SendSpin defines `group/update` as a server-to-client notification and provides no client-initiated grouping message, so a player cannot set group membership under the spec. The whole sheet is therefore deleted rather than decoupled.

Keep `MiniPlayerView.kt` (301 lines, zero MA references) and `ConnectionLoadingScreen.kt` (105 lines, zero MA references).

**Files:**
- Delete: `android/app/src/main/java/com/sendspindroid/ui/player/PlayerViewModel.kt` (275 lines, MA grouping)
- Delete: `android/app/src/main/java/com/sendspindroid/ui/player/PlayerSheetContent.kt` (425 lines, MA grouping)
- Delete: `android/app/src/main/java/com/sendspindroid/ui/player/PlayerBottomSheet.kt` (33 lines, thin wrapper over the above)
- Delete (conditional): `android/app/src/main/java/com/sendspindroid/ui/main/components/QueueButton.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/ui/queue/QueueSheetContent.kt` (remove the dead "Browse library" button, see Step 3b)
- Modify: `android/app/src/main/java/com/sendspindroid/ui/adaptive/AdaptiveDefaults.kt` (remove `showSideMiniPlayer` and `sideMiniPlayerWidth`, see Step 3b)
- Modify: `android/app/src/main/java/com/sendspindroid/ui/main/components/MiniPlayer.kt` (remove `MiniPlayerSide`, see Step 3b)
- Modify: `android/app/src/main/java/com/sendspindroid/ui/AppShell.kt:100-101, 291, 720`
- Test: `android/app/src/test/java/com/sendspindroid/ui/player/PlayerPackagePurityTest.kt`

**Locating the edits:** the plan cites line numbers valid only at base commit
38f8e4a. Tasks 1-3 have removed well over a hundred lines above them, so find
the `PlayerBottomSheet` import, the `playerViewModel` declaration and the
`PlayerBottomSheet(` call site by name.

**Interfaces:**
- Consumes: nothing from Tasks 1-3.
- Produces: a `ui/player/` package containing only `MiniPlayerView.kt` and `ConnectionLoadingScreen.kt`, with no MA imports.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/ui/player/PlayerPackagePurityTest.kt`:

```kotlin
package com.sendspindroid.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The player package must be driven by SendSpin protocol state only:
 * server/state metadata, artwork frames, and controller commands.
 *
 * Music Assistant's multi-room grouping sheet lived here. SendSpin defines
 * group/update as a server-to-client notification with no client-initiated
 * grouping message, so a player cannot set group membership under the spec
 * and that UI has no counterpart to port to.
 */
class PlayerPackagePurityTest {

    @Test
    fun playerPackageHasNoMusicAssistantImports() {
        val dir = File("src/main/java/com/sendspindroid/ui/player")
        require(dir.isDirectory) { "player package not found at " + dir.absolutePath }

        val offending = dir.listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.musicassistant") }
                    .map { file.name + ": " + it }
            }

        assertEquals("player package must not import MA types", emptyList<String>(), offending)
    }

    @Test
    fun groupingSheetIsGone() {
        val removed = listOf("PlayerViewModel.kt", "PlayerSheetContent.kt", "PlayerBottomSheet.kt")
            .map { File("src/main/java/com/sendspindroid/ui/player/" + it) }
            .filter { it.exists() }
            .map { it.name }

        assertEquals("MA grouping sheet must be deleted", emptyList<String>(), removed)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*PlayerPackagePurityTest*"`

Expected: FAIL on both tests -- MA imports present in `PlayerViewModel.kt` and `PlayerSheetContent.kt`, and all three files still on disk.

- [ ] **Step 3: Delete the grouping sheet and unwire it**

```bash
git rm android/app/src/main/java/com/sendspindroid/ui/player/PlayerViewModel.kt
git rm android/app/src/main/java/com/sendspindroid/ui/player/PlayerSheetContent.kt
git rm android/app/src/main/java/com/sendspindroid/ui/player/PlayerBottomSheet.kt
```

In `AppShell.kt`: delete the imports at lines 100-101 (`PlayerBottomSheet`, `PlayerViewModel`), the `val playerViewModel: PlayerViewModel = viewModel()` declaration at line 291, and the `PlayerBottomSheet(...)` call site at line 720 together with any state that only existed to show or hide it.

Then check whether the queue entry point has any remaining caller:

```bash
grep -rn "QueueButton" android/app/src/main
```

If that returns only the definition, delete it:

```bash
git rm android/app/src/main/java/com/sendspindroid/ui/main/components/QueueButton.kt
```

- [ ] **Step 3b: Clear the orphans Task 2 left behind**

Task 2's deletions orphaned four declarations that fall outside its authorized
file scope. They belong to this task because it already owns the queue entry
point and the player package. Each was verified to have zero callers at the end
of Task 2 -- re-check with grep before deleting, since intervening work may have
added one.

1. `ui/queue/QueueSheetContent.kt` -- an `OutlinedButton` labelled to browse the
   library renders inside `QueueEmptyContent` (around line 695). Delete the button.

   There are TWO independent `onBrowseLibrary` chains and both are now dead.
   Delete both:

   - **Compose chain:** the `onBrowseLibrary` parameter on `QueueSheetContent`,
     on `QueueEmptyContent`, and on `NowPlayingScreen` -- including
     `NowPlayingScreen`'s `= {}` default, which is what currently swallows the
     click. Leaving the default in place would relocate the dead stub rather
     than remove it.
   - **Legacy fragment chain:** `QueueSheetFragment.kt:31` declares
     `var onBrowseLibrary: (() -> Unit)? = null`, assigned from
     `MainActivity.kt:2566` to a lambda calling
     `viewModel.setNavigationContentVisible(true)`. Delete the property, the
     assignment block in `MainActivity`, and any call through it.

   **Do NOT touch `setNavigationContentVisible` or `isNavigationContentVisible`
   themselves.** The flow has roughly nine live readers in `MainActivity`
   (including the back-press path at :967 and the MA-disconnect handler at
   :2608). It is part of the unfinished legacy-to-Compose migration, not the
   Music Assistant removal, and untangling it belongs to the follow-up plan.
   Deleting only the browse callback that fed it is correct and sufficient here.
2. `ui/adaptive/AdaptiveDefaults.kt` -- delete `showSideMiniPlayer` and
   `sideMiniPlayerWidth`. Both lost their only callers when Task 2 removed
   `SideMiniPlayerBar`.
3. `ui/main/components/MiniPlayer.kt` -- delete `MiniPlayerSide`, orphaned by the
   same deletion. Keep `MiniPlayer` itself: `ui/main/MiniPlayerComposeView.kt`
   still calls it for the legacy View-based path used by `MainActivity`.

Do not delete `AdaptiveDefaults.showMiniPlayer` here -- Task 2's fix round may
already have removed it. If it still exists and has zero callers, delete it.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*PlayerPackagePurityTest*"`

Expected: PASS, 2 tests.

- [ ] **Step 5: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A android/app/src/main/java/com/sendspindroid/ui/player android/app/src/main/java/com/sendspindroid/ui/main/components android/app/src/main/java/com/sendspindroid/ui/AppShell.kt android/app/src/test/java/com/sendspindroid/ui/player
git commit -m "refactor(ui): remove Music Assistant speaker-grouping sheet"
```

---

### Task 5: Device verification across form factors

Tasks 1-4 are verified by compilation and unit tests. This task confirms the shell works on hardware, which unit tests cannot show.

**Files:**
- Modify: `docs/superpowers/plans/2026-09-01-player-first-shell.md` (append verification record)

**Interfaces:**
- Consumes: the shell produced by Tasks 1-4.
- Produces: a verification record appended to this plan.

- [ ] **Step 1: Build and install**

```bash
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" devices -l
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s <device-serial> install -r android/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Start log capture**

```bash
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe -s <device-serial>"
$A logcat -c
$A logcat -G 16M
$A logcat -v threadtime > shell-verify.txt &
$A shell am start -n com.sendspindroid/.MainActivity
```

The 16M buffer matters: the default 256 KiB overflows during a playback session and loses the transitions being verified.

- [ ] **Step 3: Verify the disconnected state**

With no server connected, confirm the server picker is the visible root and that no tab bar is rendered.

- [ ] **Step 4: Verify the connected state**

Connect to a SendSpin server and start playback. Confirm Now Playing is the root, artwork renders, metadata updates on track change, and every transport control acts on the server: play, pause, next, previous, seek, volume, mute.

- [ ] **Step 5: Verify no browse surface remains**

Confirm there is no route to home, search, library, playlists, any detail screen, or a queue list. Confirm the back button from Now Playing does not reveal a browse destination.

- [ ] **Step 6: Verify form factors**

Repeat steps 3-5 on a phone and on a TV or TV emulator. On TV, confirm D-pad focus reaches every control via `TvFocusHelpers`, and that the app still appears in the leanback launcher.

- [ ] **Step 7: Record the result and commit**

Append a verification record to the end of this plan: device models, Android versions, what was exercised, and any defects found.

```bash
git add docs/superpowers/plans/2026-09-01-player-first-shell.md
git commit -m "docs(plan): record player-first shell device verification"
```

---

## Completion

At the end of this plan the app is Now Playing first, but Music Assistant is not fully disconnected from the live code path. Verified per package:

- `com.sendspindroid.ui.navigation` is genuinely orphaned: no file outside the package imports from it.
- `com.sendspindroid.ui.detail` is **not** orphaned. It stays alive through one edge: `ui/queue/QueueSheetContent.kt:134` calls `SaveQueueAsPlaylistDialog`, and `ui/queue/SaveQueueAsPlaylistDialog.kt:49` imports `ui.detail.components.BulkAddState` to drive its playlist-save flow.
- `com.sendspindroid.ui.queue` is **not** orphaned - it is the live queue surface, referenced from `AppShell.kt:47,323,340`, `ui/main/NowPlayingScreen.kt:69-70,733,977`, `ui/main/NowPlayingHeadUnit.kt:51-52`, and `MainActivity.kt:89,2562-2566`.
- `com.sendspindroid.musicassistant` is **not** orphaned either. It is the live MA WebSocket/API client and its data models, referenced well outside the doomed browse packages: `MainActivity.kt`, `playback/PlaybackService.kt`, `SendSpinApp.kt`, `playback/AutoVoiceSearch.kt`, `ui/main/NowPlayingHeadUnit.kt`, `ui/server/AddServerWizardActivity.kt` / `AddServerWizardViewModel.kt`, and all three files under `ui/queue/`.

So "no Music Assistant screen is reachable" is false: Now Playing's queue sheet (`ui/queue/QueueSheetContent.kt`) opens `SaveQueueAsPlaylistDialog`, which writes to a Music Assistant playlist via `com.sendspindroid.musicassistant.MusicAssistant`. That is a live, reachable Music Assistant library-write feature, not dead code.

The only package this branch leaves genuinely orphaned is `com.sendspindroid.ui.navigation`. The follow-up plan cannot delete `ui.detail` or `ui.queue` until it removes (or reimplements without the Music Assistant playlist-write path) the queue sheet and its save-as-playlist dialog. `com.sendspindroid.musicassistant` cannot be deleted wholesale at all - it is load-bearing for playback, the add-server wizard, and the queue; only browse-specific residue in it, if any, can be trimmed once `ui.navigation` and `ui.detail` are gone. The follow-up plan also still needs to remove `app/remote/`, `shared/remote/`, `ProxyWebSocketTransport`, the Android Auto browse tree in `PlaybackService`, the `io.getstream:stream-webrtc-android` dependency, and the residual MA references in `PlaybackService.kt`, `SendSpin.kt`, `UnifiedServerRepository.kt`, `AddServerWizardViewModel.kt` and `strings.xml`.

## Task 5 device verification record

- **Device**: Relndoo T901_US tablet, Android 15 (API 35).
- **App**: com.sendspindroid, versionName 2.0.0-Beta15, versionCode 20015, built from
  `feat/player-first-shell` (commit 96aad52).
- **Server**: Music Assistant SendSpin server at ws://10.0.2.8:8927/sendspin, connected via
  Noise handshake with an existing LONG_TERM PSK.
- **Form factors exercised**: tablet portrait, tablet landscape. Phone and TV/leanback NOT
  verified - no device or emulator available.

Results:

- 4a disconnected root (picker, no tab bar) - PASS, portrait and landscape, cold launch and
  after force-stop relaunch.
- 4b connected root (Now Playing, artwork/metadata/progress) - PASS. Metadata and artwork
  updated live across three track changes; elapsed-time text advanced while playing.
- 4c transport controls act on the server - PASS for play, pause, next, previous, volume, each
  confirmed via logcat round-trip (client click -> outgoing command -> `server/activate` /
  `group/update` / `stream/start` / `server/state` echo). Seek and mute have no UI control
  anywhere in the app to test (pre-existing scope, not part of this refactor).
- 4d no browse surface - PASS. Overflow menu on Now Playing has only Stats for Nerds, Edit
  Server, Switch Server, App Settings, Exit App. `AppShell.kt` measured at 353 lines (down
  from 1418), matching the plan's target.
- 4e back-press behavior - PASS for the immediate outcome: Back from Now Playing backgrounds
  the app to the launcher, no crash, no blank screen, no browse destination revealed. Testing
  it surfaced a DEFECT (below).
- 4f no crashes - PASS. No `FATAL EXCEPTION`/`AndroidRuntime` and no app-originated `E`-level
  log lines across the full session.

**Defect found** (reported, not fixed, per Task 5's scope): after backgrounding the app via
the system Back button from Now Playing and returning to it without the process being killed,
the transport controls (play/pause/next/previous/switch-group) go permanently disabled and the
elapsed-time display freezes at the moment of backgrounding - while playback, track
auto-advance, and metadata/artwork updates all continue correctly underneath. Recovery requires
a full `force-stop` + relaunch. Reproduced twice via the Back-button path; one trial via the
Home button did not reproduce it. `git diff a152c13..HEAD -- MainActivity.kt` shows tasks 1-4's
only change to that file (removing the `navigateDetailBack()` check, per Task 4's brief) does
not touch the player-state collection code responsible, so this is very likely a pre-existing
bug rather than a regression from this refactor - but it directly affects the transport
controls this task was told to treat as the highest-value check, so it is flagged here for
follow-up.

Phone and TV/leanback form factors could not be verified - no hardware or emulator was
available in this environment. The manifest declares `LEANBACK_LAUNCHER` and the optional
`android.software.leanback` feature, and TV-specific composables (`NowPlayingTv`,
`TvTrackProgressBar`) exist in source, but D-pad focus traversal and leanback-launcher
presence were not exercised.

Full detail: `.superpowers/sdd/2026-09-01-player-first-shell/task-5-report.md`.
