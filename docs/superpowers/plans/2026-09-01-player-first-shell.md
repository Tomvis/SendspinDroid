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

### Task 2: Remove the browse tab bar and detail rendering from AppShell

With `selectedNavTab` permanently null, every browse branch in `AppShell` is dead code that still forces MA imports. Removing it is what actually orphans the MA UI packages.

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/ui/AppShell.kt` (imports at 65-97, `browseNavTabs` at 296-302, detail callbacks at 307-320, title `when` at 340-344, branches at 393-396 and 490-530)
- Modify: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/java/com/sendspindroid/ui/AppShellPurityTest.kt`

**Locating the edits:** the plan cites line numbers valid only at base commit
38f8e4a, and Task 1 has already shifted them by about ten lines. Find each
region by content -- the import text, the `browseNavTabs` name, the `when
(selectedNavTab)` expression -- not by line number.

**Interfaces:**
- Consumes: `selectedNavTab` pinned to `null` at composition (Task 1)
- Produces: an `AppShell` with no import of `com.sendspindroid.ui.navigation`, `com.sendspindroid.ui.detail`, or `com.sendspindroid.musicassistant`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/ui/AppShellPurityTest.kt`:

```kotlin
package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AppShell is the app's navigation hub. In a SendSpin-only player it must not
 * reference Music Assistant packages or the browse/detail screens built on
 * them -- those are MA API surface with no SendSpin spec counterpart.
 *
 * A source-level assertion is used deliberately: the goal is that the import
 * edge is gone, which is what lets the follow-up plan delete those packages.
 */
class AppShellPurityTest {

    private val forbiddenPrefixes = listOf(
        "import com.sendspindroid.musicassistant",
        "import com.sendspindroid.ui.navigation",
        "import com.sendspindroid.ui.detail"
    )

    @Test
    fun appShellHasNoMusicAssistantOrBrowseImports() {
        val source = File("src/main/java/com/sendspindroid/ui/AppShell.kt")
        require(source.exists()) { "AppShell.kt not found at " + source.absolutePath }

        val offending = source.readLines()
            .map { it.trim() }
            .filter { line -> forbiddenPrefixes.any { line.startsWith(it) } }

        assertEquals("AppShell must not import MA or browse packages", emptyList<String>(), offending)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*AppShellPurityTest*"`

Expected: FAIL, listing the MA, navigation and detail imports currently at lines 65-97.

- [ ] **Step 3: Strip the browse branches**

In `AppShell.kt`, make these edits in order:

1. Delete imports at lines 65-72 (`MaAlbum`, `MaArtist`, `MaAudiobook`, `MaPlaylist`, `MaTrack`, `EnqueueMode`, `MusicAssistant`, `MaLibraryItem`), 75-82 (`BulkAddState`, `PlaylistPickerDialog`, the five `*DetailScreen` imports, `PlaylistDetailViewModel`), 83 (`DetailDestination`), 85 (`NavTab`), and 90-97 (all `com.sendspindroid.ui.navigation.*`).
2. Delete the `browseNavTabs` map (lines 296-302) and the composable that renders it as a bottom or side navigation bar.
3. Delete the five detail-navigation callbacks (lines 307-320) that call `viewModel.navigateToDetail(...)`.
4. Replace the title expression `when (selectedNavTab) { ... }` (lines 340-344) with `stringResource(R.string.nav_now_playing)`.
5. Replace lines 393-394 with `val isNowPlaying = true`, then simplify every conditional that read `isBrowsing`, deleting the browse arms. Line 396 becomes unconditional on the Now Playing side.
6. Delete the browse and detail rendering blocks (lines 490-530), keeping only the Now Playing content and the server-picker branch.
7. Delete the `selectedNavTab` and `currentDetail` state declarations once nothing reads them.

Add to `android/app/src/main/res/values/strings.xml` if `nav_now_playing` is not already defined:

```xml
    <string name="nav_now_playing">Now Playing</string>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*AppShellPurityTest*"`

Expected: PASS.

- [ ] **Step 5: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. `CurrentDetailDerivationTest` may still pass at this point; Task 3 removes it.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/ui/AppShell.kt android/app/src/main/res/values/strings.xml android/app/src/test/java/com/sendspindroid/ui/AppShellPurityTest.kt
git commit -m "refactor(ui): remove browse tab bar and detail navigation from AppShell"
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

At the end of this plan the app is Now Playing first, no Music Assistant screen is reachable, and these packages are orphaned but still compiled:

- `com.sendspindroid.musicassistant`
- `com.sendspindroid.ui.navigation`
- `com.sendspindroid.ui.detail`
- `com.sendspindroid.ui.queue`

The follow-up plan deletes those, plus `app/remote/`, `shared/remote/`, `ProxyWebSocketTransport`, the Android Auto browse tree in `PlaybackService`, the `io.getstream:stream-webrtc-android` dependency, and the residual MA references in `PlaybackService.kt`, `SendSpin.kt`, `UnifiedServerRepository.kt`, `AddServerWizardViewModel.kt` and `strings.xml`.
