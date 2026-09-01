# Remove Browse and Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the Music Assistant browse and queue surfaces so no library, search, or queue UI remains reachable, and the Android Auto tree offers SendSpin server selection only.

**Architecture:** Pure deletion in dependency order. `ui/navigation` is already orphaned and goes first. The Android Auto tree loses its MA branches but keeps server selection. Removing `ui/queue` then severs the single edge keeping `ui/detail` alive, so `ui/detail` goes last. Nothing is built.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4, MockK, Robolectric, Media3 MediaLibraryService

**Spec:** `docs/superpowers/specs/2026-09-01-sendspin-only-player.md`

## Global Constraints

- No emojis in code, logs, or UI strings. ASCII only: `us` not the micro sign, `->` not an arrow glyph, `+/-` not the plus-minus glyph.
- No self-citation in commits or comments. No mention of Claude or AI, no Co-Authored-By lines.
- Build command prefix: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew <task>`
- Every task ends with `:app:compileDebugKotlin` and `:app:testDebugUnitTest` passing.
- NO VESTIGIAL STUBS. Deleting a concept means deleting its declaration, not emptying it. No parameter nothing reads, no lambda or nullable default that swallows a call, no `if (true)`. Collapse every conditional to its surviving branch.
- Clean up orphans YOUR change creates. Leave pre-existing dead code alone and note it instead. Verify with a repo-wide grep before deciding which case applies.
- Do NOT touch `com.sendspindroid.musicassistant` or `com.sendspindroid.remote`. They are load-bearing for playback, artwork and server setup, and are owned by later plans.
- Unit tests that read source files use paths relative to the `android/app` module directory.

## Three lessons from the previous phase, which apply to every task here

1. **Line numbers are locators, not addresses.** Every number below was read at
   the start of this plan and shifts as you delete. Locate by symbol name and
   re-grep after each deletion.
2. **Kotlin default arguments hide dead chains.** `onBrowseLibrary: () -> Unit = {}`,
   `showPlayerButton: Boolean = false` and `queueViewModel: QueueViewModel? = null`
   all keep compiling after their only real supplier is deleted, so nothing errors
   and the feature silently cannot activate. When you delete a supplier, follow the
   parameter to every declaration and delete the parameter too -- do not leave the
   default behind.
3. **A file can mix concerns.** `AutoBrowseTree.kt` holds SendSpin server selection
   AND MA library browse in one 253-line file. Grepping for a Music Assistant symbol
   finds the MA half and tells you nothing about what sits beside it. Read whole
   files before deleting from them.

---

### Task 1: Delete the orphaned browse package

`com.sendspindroid.ui.navigation` (4,132 lines: home, library, browse, playlists, search) has no production caller. One test imports it.

**Files:**
- Delete: `android/app/src/main/java/com/sendspindroid/ui/navigation/` (entire package, 14 files)
- Delete: `android/app/src/test/java/com/sendspindroid/ui/compose/SearchScreenResultsTest.kt`
- Test: `android/app/src/test/java/com/sendspindroid/ui/BrowsePackagesGoneTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.sendspindroid.ui.navigation` no longer exists. Task 4 extends the same test to cover `ui.detail`.

- [ ] **Step 1: Confirm the package is genuinely orphaned**

```bash
grep -rn "import com.sendspindroid.ui.navigation" android/app/src --include=*.kt | grep -v "/ui/navigation/"
```

Expected: exactly two hits -- `ui/compose/SearchScreenResultsTest.kt:8` and a string literal inside `ui/NowPlayingRootTest.kt` (that one is a test assertion, not an import; leave it). If you find a production caller, STOP and report it as a decision point.

- [ ] **Step 2: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/ui/BrowsePackagesGoneTest.kt`:

```kotlin
package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * SendSpin defines no library, browse, or search concept, so the packages that
 * implemented those against the Music Assistant API have no protocol
 * counterpart and must not exist.
 *
 * Task 4 extends this to ui/detail once the queue surface stops importing it.
 */
class BrowsePackagesGoneTest {

    @Test
    fun navigationPackageIsDeleted() {
        val dir = File("src/main/java/com/sendspindroid/ui/navigation")
        assertFalse("ui/navigation must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun nothingImportsTheNavigationPackage() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.ui.navigation") }
                    .map { file.name + ": " + it }
            }
        assertEquals("nothing may import ui.navigation", emptyList<String>(), offending)
    }
}
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*BrowsePackagesGoneTest*"`

Expected: both FAIL -- the directory exists and `SearchScreenResultsTest.kt` imports from it.

- [ ] **Step 4: Delete the package and its test**

```bash
git rm -r android/app/src/main/java/com/sendspindroid/ui/navigation
git rm android/app/src/test/java/com/sendspindroid/ui/compose/SearchScreenResultsTest.kt
```

`SearchScreenResultsTest` tests `SearchViewModel.SearchState`, a type being deleted with the package. There is nothing to rewrite it against, so it goes rather than being ported.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*BrowsePackagesGoneTest*"`

Expected: PASS, 2 tests.

- [ ] **Step 6: Sweep resources the deletion orphaned**

The deleted screens were the last users of several drawables and strings. Derive
the candidate list mechanically rather than guessing -- extract every resource the
deleted files referenced, then keep only those with zero references remaining:

```bash
# 1. Extract every resource name the deleted files referenced (from git, since they are gone)
git show HEAD -- android/app/src/main/java/com/sendspindroid/ui/navigation   | grep -oE "R\.(string|drawable)\.[A-Za-z0-9_]+" | sort -u > /tmp/candidates.txt

# 2. For each candidate, count references in the CURRENT tree
while read -r ref; do
  name="${ref##*.}"
  kind=$(echo "$ref" | cut -d. -f2)
  n=$(grep -rl "R\.$kind\.$name\b\|@$kind/$name\b" android/app/src --include=*.kt --include=*.xml 2>/dev/null | wc -l)
  [ "$n" -eq 0 ] && echo "ORPHANED: $kind/$name"
done < /tmp/candidates.txt
```

Delete only the names that print as ORPHANED. `ic_nav_home`, `ic_nav_library` and
`ic_nav_search` were used by these screens -- expect them in the list, but let the
check decide rather than assuming.

Keep the word-boundary markers shown in the grep pattern: a substring match makes `ic_nav_home` look live
when only `ic_nav_home_selected` survives, and the reverse.

- [ ] **Step 7: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A android/app/src
git commit -m "refactor(ui): delete the orphaned browse package"
```

---

### Task 2: Drop the Music Assistant branches from the Android Auto tree

`playback/AutoBrowseTree.kt` mixes two concerns. SendSpin server selection stays -- it is the only way to choose a server from a car. The MA library branches go.

**KEEP:** `MEDIA_ID_ROOT`, `MEDIA_ID_DISCOVERED`, `MEDIA_ID_SERVER_PREFIX`, `MEDIA_ID_SAVED_SERVER_PREFIX`, `MEDIA_ID_MESSAGE_PREFIX`, `MEDIA_ID_MESSAGE_NO_SERVERS`, and every code path serving them.

**DELETE:** `MEDIA_ID_MA_PLAYLISTS`, `MEDIA_ID_MA_ALBUMS`, `MEDIA_ID_MA_ARTISTS`, `MEDIA_ID_MA_RADIO`, `MEDIA_ID_MA_PLAYLIST_PREFIX`, `MEDIA_ID_MA_ALBUM_PREFIX`, `MEDIA_ID_MA_ARTIST_PREFIX`, `MEDIA_ID_MA_TRACK_PREFIX`, `MEDIA_ID_MA_RADIO_ITEM_PREFIX`, `MEDIA_ID_MA_QUEUE_ITEM_PREFIX`, their tree nodes, their empty-state messages, and the `PlaybackService` fetch helpers behind them.

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/playback/AutoBrowseTree.kt` (253 lines)
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` (4,285 lines) -- the `onGetChildren` / `onGetItem` / `onSearch` MA branches, the `MusicAssistant.getPlaylists` / `getAlbums` / `getArtists` / `getRadioStations` / `search` helpers, and the voice-search path at roughly :3783-3835
- Delete: `android/app/src/main/java/com/sendspindroid/playback/AutoVoiceSearch.kt` (Music Assistant library search for Android Auto voice commands -- see Step 3)
- Test: `android/app/src/test/java/com/sendspindroid/playback/AutoBrowseTreeTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: an Auto tree with server selection only. `PlaybackService` no longer calls any Music Assistant library method. It still imports `musicassistant` for artwork, queue prefetch and connection state -- that is expected and owned by a later plan.

**Read `AutoBrowseTree.kt` in full before editing.** It is 253 lines and mixes both concerns.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/playback/AutoBrowseTreeTest.kt`:

```kotlin
package com.sendspindroid.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android Auto keeps SendSpin server selection -- it is the only way to choose
 * or switch a server from a car -- but must not offer a Music Assistant
 * library. SendSpin's protocol has no library, so those branches had no
 * counterpart to keep.
 */
class AutoBrowseTreeTest {

    private fun treeSource(): String =
        File("src/main/java/com/sendspindroid/playback/AutoBrowseTree.kt").readText()

    @Test
    fun serverSelectionSurvives() {
        val src = treeSource()
        val missing = listOf(
            "MEDIA_ID_ROOT",
            "MEDIA_ID_DISCOVERED",
            "MEDIA_ID_SERVER_PREFIX",
            "MEDIA_ID_SAVED_SERVER_PREFIX",
            "MEDIA_ID_MESSAGE_NO_SERVERS"
        ).filterNot { src.contains(it) }
        assertEquals("server selection must survive in the Auto tree", emptyList<String>(), missing)
    }

    @Test
    fun musicAssistantBranchesAreGone() {
        val src = treeSource()
        val offending = listOf(
            "MEDIA_ID_MA_PLAYLISTS",
            "MEDIA_ID_MA_ALBUMS",
            "MEDIA_ID_MA_ARTISTS",
            "MEDIA_ID_MA_RADIO",
            "MEDIA_ID_MA_PLAYLIST_PREFIX",
            "MEDIA_ID_MA_ALBUM_PREFIX",
            "MEDIA_ID_MA_ARTIST_PREFIX"
        ).filter { src.contains(it) }
        assertEquals("MA library branches must be gone from the Auto tree", emptyList<String>(), offending)
    }

    @Test
    fun playbackServiceCallsNoLibraryMethods() {
        val src = File("src/main/java/com/sendspindroid/playback/PlaybackService.kt").readText()
        val offending = listOf(
            "MusicAssistant.getPlaylists",
            "MusicAssistant.getAlbums",
            "MusicAssistant.getArtists",
            "MusicAssistant.getRadioStations",
            "MusicAssistant.search"
        ).filter { src.contains(it) }
        assertEquals("PlaybackService must not call MA library methods", emptyList<String>(), offending)
    }

    @Test
    fun playbackServiceStillServesServerBrowsing() {
        val src = File("src/main/java/com/sendspindroid/playback/PlaybackService.kt").readText()
        assertTrue("onGetChildren must survive for server selection", src.contains("onGetChildren"))
        assertTrue("the discovered-servers branch must survive", src.contains("MEDIA_ID_DISCOVERED"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*AutoBrowseTreeTest*"`

Expected: `musicAssistantBranchesAreGone` and `playbackServiceCallsNoLibraryMethods` FAIL. The two survival tests PASS already -- they are guards against over-deletion, not drivers.

- [ ] **Step 3: Strip the MA branches**

In `AutoBrowseTree.kt`: delete the MA constants, the tree nodes that expose Playlists / Albums / Artists / Radio as browsable children of root, and the MA arms of the empty-state message function. Keep the server-selection nodes and the no-servers message.

In `PlaybackService.kt`: delete the `onGetChildren` and `onGetItem` branches that match MA media IDs, the `onSearch` implementation if it only ever searched Music Assistant, and the private helpers that call `MusicAssistant.getPlaylists` / `getAlbums` / `getArtists` / `getRadioStations` / `search`. Delete the MA media-ID constants mirrored at the top of the file.

If `onSearch` becomes an override whose body no longer does anything meaningful, delete the override entirely rather than leaving it returning an empty result -- an empty override is a vestigial stub.

**Android Auto voice search goes with it.** This is not optional and the test above
forces it: `playbackServiceCallsNoLibraryMethods` asserts `MusicAssistant.search` is
absent, and the voice-search path is what calls it.

- `playback/AutoVoiceSearch.kt` imports `com.sendspindroid.musicassistant.SearchResults`
  and exposes `pickFromResults`, `unavailableMessage` and `noRecentTracksMessage`.
- `PlaybackService.kt` drives it at roughly :3783-3835 -- the `onSearch` / voice-command
  path that turns "play X" from a car into a Music Assistant library search.

Delete `AutoVoiceSearch.kt` and the PlaybackService voice-search path together. Then
check whether `notifyVoiceSearchError` and any voice-search-only string resources are
left with zero callers, and delete those too.

Why it cannot be kept: voice search is Music Assistant LIBRARY search. SendSpin's
protocol defines no search message and no library, so there is nothing for a
SendSpin-native voice search to query. It only ever worked while MA was connected.
There is no port, only deletion.

Keep the MediaSession voice controls that do not need a library -- play, pause, next,
previous. Those are transport commands, not search, and they continue to work in Auto.

Do NOT remove `MusicAssistant.initialize`, `queueUpdates`, `connectionState`, `MaProxyImageFetcher`, or the DataChannel calls. Those serve artwork, prefetch and connection state, and belong to later plans.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*AutoBrowseTreeTest*"`

Expected: PASS, 4 tests.

- [ ] **Step 5: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. Any existing Auto-browse test that asserted MA branches must be updated to the new tree, not deleted wholesale -- report which you touched.

- [ ] **Step 6: Commit**

```bash
git add -A android/app/src
git commit -m "refactor(auto): drop Music Assistant branches from the browse tree"
```

---

### Task 3: Delete the queue surface

`com.sendspindroid.ui.queue` (1,794 lines, 4 files) is the live queue UI. SendSpin defines no viewable queue, and `SaveQueueAsPlaylistDialog` writes to a Music Assistant playlist -- a library-write feature outside the player role. Deleting it also severs the single edge keeping `ui/detail` alive, which Task 4 needs.

**Files:**
- Delete: `android/app/src/main/java/com/sendspindroid/ui/queue/` (QueueSheetContent.kt, QueueSheetFragment.kt, QueueViewModel.kt, SaveQueueAsPlaylistDialog.kt)
- Delete: `android/app/src/test/java/com/sendspindroid/ui/compose/QueueSheetSwipeTest.kt`
- Delete (conditional): `android/app/src/main/java/com/sendspindroid/ui/main/components/QueueButton.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/ui/AppShell.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/ui/main/NowPlayingScreen.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/ui/main/NowPlayingHeadUnit.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/ui/adaptive/AdaptiveDefaults.kt`
- Test: `android/app/src/test/java/com/sendspindroid/ui/QueueSurfaceGoneTest.kt`

**Interfaces:**
- Consumes: nothing from Tasks 1-2.
- Produces: `com.sendspindroid.ui.queue` no longer exists, and `ui.detail` has zero importers, which is Task 4's precondition.

**The wiring to remove, mapped from the caller side:**

| File | What references the queue |
|---|---|
| `AppShell.kt` | `QueueViewModel` import, `onQueueClick` parameter (twice), `nowPlayingQueueVisible` state, the queue toggle button and its `showQueueToggle` gate, the `queueViewModel` instance, `showQueueViewModel` |
| `NowPlayingScreen.kt` | `QueueButton` import, `QueueSheetContent` and `QueueViewModel` imports, `onQueueClick` parameter, `queueViewModel: QueueViewModel? = null` parameter, the `LaunchedEffect(queueViewModel)` block, `inlineQueueViewModel`, `showQueueButton`, `queueVisible`, `inlineQueueVisible` |
| `NowPlayingHeadUnit.kt` | `QueueUiState` and `QueueViewModel` imports |
| `MainActivity.kt` | `QueueSheetFragment` import, `showQueueSheet()`, the `onQueueClick = { showQueueSheet() }` lambda, and the call at :729 |
| `AdaptiveDefaults.kt` | `showInlineQueuePanel`, `hasTvQueueSidebar`, `showBrowseQueueSidebar` -- delete each ONLY if it ends with zero callers |
| `playback/PlaybackService.kt` | the Android Auto now-playing queue -- `MEDIA_ID_MA_QUEUE_ITEM_PREFIX`, `populatePlayerQueue()`, `createMaQueueMediaItem()`, and the `onGetItem` / playback branches that resolve that prefix. Reassigned here from Task 2; see below |

**The Android Auto now-playing queue comes out here too.** Task 2 left it in place
because a MediaSession queue is a different mechanism from a library browse tree, which
was a reasonable reading. But it is built from `MaQueueItem` -- Music Assistant data --
and SendSpin supplies no queue state to replace it. Leaving it would mean Android Auto
still shows an MA-sourced queue after this task removes the in-app queue for exactly
that reason.

Keep `MusicAssistant.queueUpdates` itself: `PlaybackService` also uses it to prefetch
about a second before a track change, which is a playback optimisation rather than a
queue surface. Delete only the Auto queue construction that consumes it. If removing
the Auto queue leaves `queueUpdates` with no remaining consumer, say so in your report
rather than deleting it -- a later plan owns that call.

Note `queueViewModel: QueueViewModel? = null` in `NowPlayingScreen`. That nullable default is exactly the shape that hides a dead chain -- delete the parameter, not just its supplier.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/ui/QueueSurfaceGoneTest.kt`:

```kotlin
package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SendSpin defines no viewable queue, so the queue surface has no protocol
 * counterpart. SaveQueueAsPlaylistDialog additionally wrote to a Music
 * Assistant playlist, a library-write feature outside the player role.
 */
class QueueSurfaceGoneTest {

    @Test
    fun queuePackageIsDeleted() {
        val dir = File("src/main/java/com/sendspindroid/ui/queue")
        assertFalse("ui/queue must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun nothingImportsTheQueuePackage() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.ui.queue") }
                    .map { file.name + ": " + it }
            }
        assertEquals("nothing may import ui.queue", emptyList<String>(), offending)
    }

    @Test
    fun nowPlayingScreenStillRenders() {
        val src = File("src/main/java/com/sendspindroid/ui/AppShell.kt").readText()
        assertTrue("AppShell must still render NowPlayingScreen", src.contains("NowPlayingScreen("))
    }

    @Test
    fun transportControlsSurvive() {
        val src = File("src/main/java/com/sendspindroid/ui/main/NowPlayingScreen.kt").readText()
        val missing = listOf(
            "onPlayPauseClick",
            "onNextClick",
            "onPreviousClick",
            "onVolumeChange"
        ).filterNot { src.contains(it) }
        assertEquals("transport controls must survive", emptyList<String>(), missing)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*QueueSurfaceGoneTest*"`

Expected: `queuePackageIsDeleted` and `nothingImportsTheQueuePackage` FAIL. The two survival tests PASS already -- they guard against over-deletion.

- [ ] **Step 3: Unwire the queue, caller side first**

Work outside-in so you never delete a definition that still has a caller. Remove the wiring in `MainActivity.kt`, `AppShell.kt`, `NowPlayingHeadUnit.kt`, then `NowPlayingScreen.kt`, using the table above. For each conditional that depended on queue visibility, collapse to the surviving branch rather than leaving a tautology.

Then delete the package and its test:

```bash
git rm -r android/app/src/main/java/com/sendspindroid/ui/queue
git rm android/app/src/test/java/com/sendspindroid/ui/compose/QueueSheetSwipeTest.kt
```

Then check the conditional deletions:

```bash
grep -rn "QueueButton" android/app/src/main
grep -rn "showInlineQueuePanel\|hasTvQueueSidebar\|showBrowseQueueSidebar" android/app/src/main
```

Delete each declaration that returns no callers. If one still has a caller, leave it and say which in your report.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*QueueSurfaceGoneTest*"`

Expected: PASS, 4 tests.

- [ ] **Step 5: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Sweep orphaned resources**

The queue sheet owned several strings, including `queue_empty_subtitle`, which reads "Add songs from your library or search to start playing" and refers to surfaces that no longer exist. Run the same mechanical sweep as Task 1 Step 6, substituting `ui/queue` for `ui/navigation` in the extraction command.

- [ ] **Step 7: Commit**

```bash
git add -A android/app/src
git commit -m "refactor(ui): delete the queue surface"
```

---

### Task 4: Delete the detail package

With the queue gone, `com.sendspindroid.ui.detail` (4,246 lines) has no importer. Its only remaining tie was `ui/queue/SaveQueueAsPlaylistDialog.kt` importing `ui.detail.components.BulkAddState`.

**Files:**
- Delete: `android/app/src/main/java/com/sendspindroid/ui/detail/` (entire package, 17 files)
- Modify: `android/app/src/test/java/com/sendspindroid/ui/BrowsePackagesGoneTest.kt` (extend from Task 1)

**Interfaces:**
- Consumes: a codebase where `ui/queue` no longer exists (Task 3).
- Produces: `com.sendspindroid.ui.detail` no longer exists.

- [ ] **Step 1: Confirm it is now orphaned**

```bash
grep -rn "import com.sendspindroid.ui.detail" android/app/src --include=*.kt | grep -v "/ui/detail/"
```

Expected: only the string literal inside `ui/NowPlayingRootTest.kt`, which is a test assertion rather than an import. If a real importer remains, STOP and report it -- Task 3 left something behind.

- [ ] **Step 2: Extend the failing test**

Add these two methods to `BrowsePackagesGoneTest.kt`, keeping the existing ones:

```kotlin
    @Test
    fun detailPackageIsDeleted() {
        val dir = File("src/main/java/com/sendspindroid/ui/detail")
        assertFalse("ui/detail must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun nothingImportsTheDetailPackage() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.ui.detail") }
                    .map { file.name + ": " + it }
            }
        assertEquals("nothing may import ui.detail", emptyList<String>(), offending)
    }
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*BrowsePackagesGoneTest*"`

Expected: `detailPackageIsDeleted` FAILS. `nothingImportsTheDetailPackage` should already pass if Task 3 was complete -- if it fails, that names the leftover importer.

- [ ] **Step 4: Delete the package**

```bash
git rm -r android/app/src/main/java/com/sendspindroid/ui/detail
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*BrowsePackagesGoneTest*"`

Expected: PASS, 4 tests.

- [ ] **Step 6: Sweep orphaned resources and verify the suite**

Run the same mechanical sweep as Task 1 Step 6, substituting `ui/detail` for `ui/navigation` in the extraction command.

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A android/app/src
git commit -m "refactor(ui): delete the detail package"
```

---

### Task 5: Device verification

Tasks 1-4 are verified by compilation and unit tests. This confirms the app still works on hardware and that Android Auto kept its server selection.

**Files:**
- Modify: `docs/superpowers/plans/2026-09-01-remove-browse-and-queue.md` (append the verification record)

**Interfaces:**
- Consumes: the app produced by Tasks 1-4.
- Produces: a verification record appended to this plan.

- [ ] **Step 1: Build and install**

```bash
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" devices -l
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s <serial> install -r android/app/build/outputs/apk/debug/app-debug.apk
```

A Relndoo T901_US tablet has been used for this project, serial `T901YCU250305206`. Pass `-s <serial>` on every adb command -- a stale offline emulator is also attached and bare commands fail with "more than one device". In Git Bash, prefix any command containing a device path with `MSYS_NO_PATHCONV=1`.

- [ ] **Step 2: Capture logs**

```bash
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe -s <serial>"
$A logcat -c
$A logcat -G 16M
$A logcat -v threadtime > browse-verify.txt &
$A shell am start -n com.sendspindroid/.MainActivity
```

The 16M buffer matters -- the 256 KiB default overflows during playback and loses the transitions being verified. Delete `browse-verify.txt` when finished; do not commit it.

- [ ] **Step 3: Verify the player still works**

Connect to a SendSpin server and play. Confirm Now Playing renders with artwork and metadata, and that play, pause, next, previous and volume each act on the server -- check logcat for the outgoing command and the server response, not just the UI reacting.

- [ ] **Step 4: Verify no queue surface remains**

Confirm there is no queue button, no queue sheet, and no inline queue panel on any layout. Check tablet portrait and landscape, since the inline panel was tablet-only.

- [ ] **Step 5: Verify Android Auto**

Using Android Auto's Desktop Head Unit or a real head unit if available, confirm the browse tree still lists discovered and saved SendSpin servers and that selecting one connects, and that no Playlists / Albums / Artists / Radio entries appear. If no head unit is available, say so plainly and mark this NOT VERIFIED rather than assuming.

- [ ] **Step 6: Check for crashes**

```bash
grep -E "FATAL EXCEPTION|AndroidRuntime" browse-verify.txt
```

Report anything found.

- [ ] **Step 7: Record the result and commit**

Append a verification record to this plan: device model, Android version, what was exercised, PASS/FAIL per item, what could not be verified, and any defect found.

```bash
git add docs/superpowers/plans/2026-09-01-remove-browse-and-queue.md
git commit -m "docs(plan): record browse and queue removal device verification"
```

---

## Completion

At the end of this plan `com.sendspindroid.ui.navigation`, `com.sendspindroid.ui.queue` and `com.sendspindroid.ui.detail` no longer exist, and Android Auto offers SendSpin server selection with no Music Assistant library.

`com.sendspindroid.musicassistant` still exists and is still load-bearing. `PlaybackService` continues to use it for artwork (`MaProxyImageFetcher`), queue prefetch (`queueUpdates`) and connection state; `SendSpinApp` uses `MaSettings`; the add-server wizard uses `MaEndpoint`. Verify this list with a fresh grep before planning against it.

Remaining after this plan:

- **Plan A** -- cut remote/proxy: `app/remote/`, `shared/remote/`, the `io.getstream:stream-webrtc-android` dependency, `SignalingClient`, `MaProxyImageFetcher`, the MA API DataChannel plumbing, `SendSpin.kt`'s four MA-aware lines, and the wizard's Proxy/Remote modes.
- **Plan C** -- retire whatever survives in `musicassistant/` once Plan A and this plan are both done.
