# Cut Remote Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the WebRTC and proxy remote-access stack, including its Music Assistant signaling dependency and the `io.getstream:stream-webrtc-android` native library, leaving local connection and manual address entry.

**Architecture:** Deletion in dependency order, consumers before producers. The UI entry points go first so nothing can reach the stack, then the Music Assistant plumbing that rode on it, then the transports themselves, then the native dependency. Nothing is built -- the SendSpin wizard path already supports manual address entry via `localAddress`.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4, MockK, Robolectric, WebRTC (being removed)

**Spec:** `docs/superpowers/specs/2026-09-01-sendspin-only-player.md` (decision 3, plus the section "What the WebRTC transport actually was")

## Global Constraints

- No emojis in code, logs, or UI strings. ASCII only: `us` not the micro sign, `->` not an arrow glyph, `+/-` not the plus-minus glyph.
- No self-citation in commits or comments. No mention of Claude or AI, no Co-Authored-By lines.
- Build command prefix: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew <task>`
- Every task ends with `:app:compileDebugKotlin` and `:app:testDebugUnitTest` passing. Run `:shared:testAndroidHostTest` too when `:shared` is touched.
- NO VESTIGIAL STUBS. Delete declarations rather than emptying them. No parameter nothing reads, no default that swallows a call, no `if (true)`. Collapse conditionals to the surviving branch.
- Clean up orphans YOUR change creates. Leave pre-existing dead code alone and note it.
- Do NOT touch `com.sendspindroid.musicassistant` beyond the two items named in Task 2. The package is load-bearing for playback and server setup, and Plan C owns it.

## Five checks that have each caught a real defect in this project

Run these mechanically, not as things to bear in mind. Each one has cost a fix round.

1. **Line numbers are locators, not addresses.** Every number here was read at plan time and shifts as you delete. Locate by symbol name; re-grep after each deletion.
2. **Same-package files need no import.** A file declaring `package com.sendspindroid.remote` appears in no import grep. Search for package DECLARATIONS, in BOTH modules and every source set including `androidTest`.
3. **Kotlin default arguments hide dead chains.** For every file you touch, enumerate parameters with defaults and prove a surviving caller passes a non-default value. Delete the parameter, not just the supplier.
4. **A helper may carry a safety property unrelated to its purpose.** Read the KDoc of anything you delete. One earlier task removed an empty-state helper and silently removed a documented Android Auto invariant that had previously failed Google Play review.
5. **Persisted preference keys hide deleted features.** A settings toggle for a removed feature evades import, default-argument AND resource sweeps: its strings are still referenced, its parameter is still read, and it imports nothing deleted. Walk `UserSettings.kt` properties against consumers OUTSIDE `ui/settings`. Two orphans are already known and are NOT yours to fix here: `albumArtistsOnly` and `cachedPlayerId`, both with zero consumers anywhere. Note any new ones you find.

---

### Task 1: Remove the remote and proxy UI entry points

Delete the user-facing paths first so nothing can reach the stack while it is being dismantled.

**Files:**
- Delete: `android/app/src/main/java/com/sendspindroid/ui/remote/` (3 files, 588 lines: `RemoteConnectDialog.kt`, `ProxyConnectDialog.kt`, `QrScannerDialog.kt`)
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt` (imports at :83-84 and their call sites)
- Modify: `android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardActivity.kt` (:30 `RemoteConnection`, :35 `QrScannerDialog`, the scan handler around :343)
- Modify: `android/app/src/main/java/com/sendspindroid/ui/server/AddServerWizardViewModel.kt` (`remoteId`, proxy fields)
- Modify: `android/app/src/main/java/com/sendspindroid/ui/wizard/WizardNavigation.kt` (`RemoteAccessMethod` at :58-62)
- Modify: `android/app/src/main/res/layout/item_saved_proxy_server.xml` (delete if unreferenced after)
- Test: `android/app/src/test/java/com/sendspindroid/ui/RemoteUiGoneTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: no UI path reaches `com.sendspindroid.remote`. `RemoteAccessMethod` no longer offers `REMOTE_ID` or `PROXY`.

**Note on `QrScannerDialog`:** it has exactly two callers, `RemoteConnectDialog.kt:119` and `AddServerWizardActivity.kt:343`, and both scan a remote ID. Deleting it removes the app's only QR *scanning* capability. The pairing flow *displays* a QR code for other devices to scan and does not scan one, so nothing else needs it today. Say so in your report -- if a future pairing flow wants to scan a token, this is the code it would restore.

**On `RemoteAccessMethod`:** it has three values -- `NONE`, `REMOTE_ID`, `PROXY`. Removing two leaves a single-valued enum, which is a vestigial type. If `NONE` is the only survivor and the enum is only used to branch, delete the enum and collapse its consumers to the local path. Report what you found either way.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/ui/RemoteUiGoneTest.kt`:

```kotlin
package com.sendspindroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remote access via Music Assistant's signaling server and via an authenticated
 * reverse proxy is removed. SendSpin's spec defines no remote-access mechanism,
 * and the signaling endpoint was a third-party dependency. Local connection and
 * manual address entry remain.
 */
class RemoteUiGoneTest {

    @Test
    fun remoteUiPackageIsDeleted() {
        val dir = File("src/main/java/com/sendspindroid/ui/remote")
        assertFalse("ui/remote must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun nothingImportsTheRemoteUiPackage() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import com.sendspindroid.ui.remote") }
                    .map { file.name + ": " + it }
            }
        assertEquals("nothing may import ui.remote", emptyList<String>(), offending)
    }

    @Test
    fun wizardOffersNoRemoteOrProxyMethod() {
        val source = File("src/main/java/com/sendspindroid/ui/wizard/WizardNavigation.kt")
        require(source.exists()) { "WizardNavigation.kt not found at " + source.absolutePath }
        val text = source.readText()
        val offending = listOf("REMOTE_ID", "PROXY").filter { text.contains(it) }
        assertEquals("the wizard must offer no remote or proxy method", emptyList<String>(), offending)
    }

    @Test
    fun localAddressEntrySurvives() {
        val source = File("src/main/java/com/sendspindroid/ui/server/AddServerWizardViewModel.kt")
        require(source.exists()) { "wizard view model not found at " + source.absolutePath }
        assertTrue("manual address entry must survive", source.readText().contains("localAddress"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*RemoteUiGoneTest*"`

Expected: the first three FAIL. `localAddressEntrySurvives` PASSES already -- it is an over-deletion guard, not a driver.

- [ ] **Step 3: Delete the UI, caller side first**

Work outward-in so no definition is deleted while a caller survives:

1. `MainActivity.kt` -- delete the `ProxyConnectDialog` and `RemoteConnectDialog` imports and every call site. If a menu item, button or handler existed only to open them, delete that too.
2. `AddServerWizardActivity.kt` -- delete the `RemoteConnection` and `QrScannerDialog` imports, the QR scan handler around :343, and any wizard branch that routed to remote or proxy setup.
3. `AddServerWizardViewModel.kt` -- delete `remoteId`, the proxy URL and auth fields, and anything that only fed them.
4. `WizardNavigation.kt` -- remove `REMOTE_ID` and `PROXY` from `RemoteAccessMethod`, then apply the note above about the single-valued enum.
5. Delete the package:

```bash
git rm -r android/app/src/main/java/com/sendspindroid/ui/remote
```

6. Check whether `item_saved_proxy_server.xml` still has a referencing layout or adapter; delete it if not.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*RemoteUiGoneTest*"`

Expected: PASS, 4 tests.

- [ ] **Step 5: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL. Compile errors naming `RemoteConnection` or `SignalingClient` are expected NOT to appear yet -- those are Task 3's. If they do, a caller was missed here.

- [ ] **Step 6: Sweep orphaned resources**

Extract every resource the deleted files referenced and keep only those now unreferenced:

```bash
git diff --cached -- android/app/src/main/java/com/sendspindroid/ui/remote \
  | grep -oE "R\.(string|drawable|layout)\.[A-Za-z0-9_]+" | sort -u > /tmp/candidates.txt

while read -r ref; do
  name="${ref##*.}"
  kind=$(echo "$ref" | cut -d. -f2)
  n=$(grep -rl "R\.$kind\.$name\b\|@$kind/$name\b" android/app/src --include=*.kt --include=*.xml 2>/dev/null | wc -l)
  [ "$n" -eq 0 ] && echo "ORPHANED: $kind/$name"
done < /tmp/candidates.txt
```

Delete only what prints as ORPHANED. Keep the word-boundary markers -- without them a substring match makes `qr_scanner_hint` look live when only `qr_scanner_hint_long` survives.

- [ ] **Step 7: Commit**

```bash
git add -A android/app/src
git commit -m "refactor(ui): remove the remote and proxy connection UI"
```

---

### Task 2: Remove the Music Assistant plumbing that rode on WebRTC

Two Music Assistant features existed only because remote access tunnelled them. Both go now, before the transports they depend on.

**`MaProxyImageFetcher`** tunnels Music Assistant's HTTP artwork URLs over the WebRTC DataChannel, because "in REMOTE mode, images hosted on the MA server (via `/imageproxy`) can't be reached". With remote access gone, artwork is fetched directly over HTTP on the local network. SendSpin also carries artwork natively as binary message types 8-11, so the protocol path is unaffected either way.

**The `ma-api` DataChannel** carries Music Assistant's control API over WebRTC in REMOTE mode. `SendSpin.kt` exposes it through `getMaApiDataChannel()` and `drainMaApiMessageBuffer()` -- the only two Music-Assistant-aware functions in the entire SendSpin protocol core. Removing them makes that layer genuinely protocol-pure.

**Files:**
- Delete: `android/app/src/main/java/com/sendspindroid/musicassistant/MaProxyImageFetcher.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/SendSpinApp.kt` (:11 import, :41 Coil registration)
- Modify: `android/app/src/main/java/com/sendspindroid/playback/PlaybackService.kt` (:624 second Coil registration, :1332 comment, :1715 and :1737 `ma-proxy://` URL rewriting, and the `setMaApiDataChannel` call)
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpin.kt` (`getMaApiDataChannel` and `drainMaApiMessageBuffer`, around :1169-1181)
- Modify: `android/app/src/main/java/com/sendspindroid/musicassistant/MusicAssistant.kt` (`setMaApiDataChannel` and the DataChannel transport wiring)
- Delete: `android/app/src/main/java/com/sendspindroid/musicassistant/transport/MaDataChannelTransport.kt` and its test, if the DataChannel was its only source
- Test: `android/app/src/test/java/com/sendspindroid/sendspin/ProtocolCoreIsPureTest.kt`

**Interfaces:**
- Consumes: a codebase with no remote UI (Task 1).
- Produces: `com.sendspindroid.sendspin` contains zero Music Assistant references. Task 3 can then delete the transports without touching the protocol core.

**Scope guard:** `MusicAssistant.kt` is otherwise off-limits. Touch only `setMaApiDataChannel` and whatever became unreachable because the DataChannel is gone. Do NOT remove `MusicAssistant.initialize`, `queueUpdates`, `connectionState`, or anything serving playback or server setup.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/sendspindroid/sendspin/ProtocolCoreIsPureTest.kt`:

```kotlin
package com.sendspindroid.sendspin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SendSpin protocol core must know nothing about Music Assistant. Its only
 * MA-aware surface was the ma-api DataChannel, which existed to tunnel MA's
 * control API over WebRTC remote access. With remote access removed there is
 * nothing left to tunnel.
 */
class ProtocolCoreIsPureTest {

    private fun protocolSources(): List<File> {
        val dir = File("src/main/java/com/sendspindroid/sendspin")
        require(dir.isDirectory) { "protocol core not found at " + dir.absolutePath }
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    @Test
    fun protocolCoreHasNoMusicAssistantReferences() {
        val offending = protocolSources().flatMap { file ->
            file.readLines()
                .map { it.trim() }
                .filter { it.contains("musicassistant") || it.contains("MaApi") }
                .map { file.name + ": " + it }
        }
        assertEquals("the protocol core must not reference Music Assistant", emptyList<String>(), offending)
    }

    @Test
    fun protocolCoreStillExists() {
        val names = protocolSources().map { it.name }
        assertTrue("SendSpin.kt must survive", names.contains("SendSpin.kt"))
        assertTrue("the protocol package must not be emptied", names.size > 5)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*ProtocolCoreIsPureTest*"`

Expected: `protocolCoreHasNoMusicAssistantReferences` FAILS, naming `SendSpin.kt`'s `getMaApiDataChannel` and `drainMaApiMessageBuffer`. `protocolCoreStillExists` PASSES -- an over-deletion guard.

- [ ] **Step 3: Remove the image proxy**

Delete `MaProxyImageFetcher.kt`. Then remove BOTH Coil registrations -- `SendSpinApp.kt:41` and `PlaybackService.kt:624`. Two registration sites is easy to half-fix; grep for `MaProxyImageFetcher` afterwards and confirm zero hits.

In `PlaybackService.kt`, delete the `ma-proxy://` URL rewriting around :1715 and :1737 and collapse whatever conditional selected it, so artwork URLs pass through unmodified. Fix the stale comment at :1332 that describes fetching via the WebRTC DataChannel.

- [ ] **Step 4: Remove the MA API DataChannel plumbing**

In `SendSpin.kt`, delete `getMaApiDataChannel()` and `drainMaApiMessageBuffer()` and the `org.webrtc.DataChannel` import they needed.

In `PlaybackService.kt`, delete the `setMaApiDataChannel` call and the buffered-message drain feeding it.

In `MusicAssistant.kt`, delete `setMaApiDataChannel` and any transport-selection branch that chose the DataChannel path. If `MaDataChannelTransport` and `MaApiTransportFactory`'s DataChannel branch become unreachable, delete them and their tests -- verify with a grep first and report what you found.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*ProtocolCoreIsPureTest*"`

Expected: PASS, 2 tests.

- [ ] **Step 6: Verify build and full suite**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A android/app/src
git commit -m "refactor(musicassistant): remove the WebRTC image proxy and API DataChannel"
```

---

### Task 3: Delete the transports

With no UI path and no Music Assistant plumbing, the transports themselves are orphaned.

**Files:**
- Delete: `android/app/src/main/java/com/sendspindroid/remote/` (`WebRTCTransport.kt`, `RemoteConnection.kt`, and any siblings)
- Delete: `android/shared/src/commonMain/kotlin/com/sendspindroid/remote/` (`SignalingClient.kt`, `IceCandidateInfo.kt`, `IceServerConfig.kt`, `RemoteCertificateVerifier.kt`)
- Delete: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/transport/ProxyWebSocketTransport.kt`
- Modify: `android/app/src/main/java/com/sendspindroid/sendspin/SendSpin.kt` (`createRemoteTransport` around :1130, the proxy transport creation around :1142, `ConnectionMode.REMOTE` and `PROXY` branches)
- Modify: `android/app/src/main/java/com/sendspindroid/network/DefaultServerPinger.kt` (:7 `SignalingClient` import and the remote reachability probe around :408)
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/musicassistant/MaConnectionMode.kt` (`REMOTE`, `PROXY`)
- Modify: `android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/transport/BaseWebSocketTransport.kt` (:29 KDoc references `ProxyWebSocketTransport`)
- Delete these tests: `app/src/test/.../e2e/ProxyConnectAuthTest.kt`, `e2e/RemoteConnectWebRTCTest.kt`, `remote/RemoteConnectionParseTest.kt`, `remote/RemoteConnectionValidationTest.kt`, `remote/WebRTCFactoryLifecycleTest.kt`, `shared/.../remote/RemoteCertificateVerifierTest.kt`, `remote/SignalingClientConnectRaceTest.kt`, `remote/SignalingClientRemoteIdTest.kt`, `shared/.../transport/ProxyWebSocketTransportTest.kt`
- Modify: `android/app/src/test/java/com/sendspindroid/sendspin/SendSpinClientDisconnectTest.kt` (references `ProxyWebSocketTransport` in comments and setup)
- Test: extend `RemoteUiGoneTest.kt` from Task 1

**Interfaces:**
- Consumes: no UI path (Task 1), no MA plumbing (Task 2).
- Produces: `com.sendspindroid.remote` no longer exists in either module. `ConnectionMode` and `MaConnectionMode` offer only `LOCAL`.

**On the connection-mode enums:** both reduce to a single value. A single-valued enum that is only used to branch is vestigial -- delete it and collapse its consumers. But check first whether either is persisted or serialized anywhere; if a stored value names `REMOTE` or `PROXY`, removing the constant changes how old data deserializes. Report what you find before deciding.

- [ ] **Step 1: Extend the failing test**

Add to `RemoteUiGoneTest.kt`:

```kotlin
    @Test
    fun remoteTransportPackageIsDeletedFromApp() {
        val dir = File("src/main/java/com/sendspindroid/remote")
        assertFalse("app remote package must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun remoteTransportPackageIsDeletedFromShared() {
        val root = File("../shared/src/commonMain/kotlin")
        require(root.isDirectory) { "shared module not found at " + root.absolutePath + " -- module may have moved" }
        val dir = File(root, "com/sendspindroid/remote")
        assertFalse("shared remote package must be deleted, found " + dir.absolutePath, dir.exists())
    }

    @Test
    fun proxyTransportIsDeleted() {
        val root = File("../shared/src/commonMain/kotlin")
        require(root.isDirectory) { "shared module not found at " + root.absolutePath + " -- module may have moved" }
        val f = File(root, "com/sendspindroid/sendspin/transport/ProxyWebSocketTransport.kt")
        assertFalse("ProxyWebSocketTransport must be deleted", f.exists())
    }
```

The `require` guards matter: without them, a wrong relative path makes an absence assertion pass for the wrong reason.

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*RemoteUiGoneTest*"`

Expected: the three new tests FAIL.

- [ ] **Step 3: Unwire, then delete**

Callers first:

1. `DefaultServerPinger.kt` -- delete the `SignalingClient` import and the remote reachability probe around :408, collapsing whatever conditional selected it.
2. `SendSpin.kt` -- delete `createRemoteTransport`, the proxy transport creation, and the `ConnectionMode.REMOTE` / `PROXY` branches. Apply the single-valued-enum note.
3. `MaConnectionMode.kt` -- same treatment.
4. `BaseWebSocketTransport.kt:29` -- update the KDoc that lists `ProxyWebSocketTransport` as a subclass.
5. `SendSpinClientDisconnectTest.kt` -- remove or retarget the parts that construct a `ProxyWebSocketTransport`. Keep any test covering surviving disconnect behaviour.

Then delete:

```bash
git rm -r android/app/src/main/java/com/sendspindroid/remote
git rm -r android/shared/src/commonMain/kotlin/com/sendspindroid/remote
git rm android/shared/src/commonMain/kotlin/com/sendspindroid/sendspin/transport/ProxyWebSocketTransport.kt
git rm android/app/src/test/java/com/sendspindroid/e2e/ProxyConnectAuthTest.kt
git rm android/app/src/test/java/com/sendspindroid/e2e/RemoteConnectWebRTCTest.kt
git rm -r android/app/src/test/java/com/sendspindroid/remote
git rm -r android/shared/src/androidHostTest/kotlin/com/sendspindroid/remote
git rm android/shared/src/androidHostTest/kotlin/com/sendspindroid/sendspin/transport/ProxyWebSocketTransportTest.kt
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*RemoteUiGoneTest*"`

Expected: PASS, 7 tests.

- [ ] **Step 5: Verify build and both suites**

Run:
```
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest
```

Expected: both BUILD SUCCESSFUL. Report the test-count change and account for every removed test as covering deleted code.

- [ ] **Step 6: Commit**

```bash
git add -A android
git commit -m "refactor(transport): delete the WebRTC and proxy transports"
```

---

### Task 4: Drop the WebRTC dependency and sweep orphans

**Files:**
- Modify: `android/app/build.gradle.kts` (:277 and the comment block above it)
- Modify: `android/app/src/main/AndroidManifest.xml` (permissions only WebRTC needed, if any)
- Test: extend `RemoteUiGoneTest.kt`

**Interfaces:**
- Consumes: a codebase with no `org.webrtc` references (Task 3).
- Produces: `io.getstream:stream-webrtc-android` absent from the dependency tree.

- [ ] **Step 1: Confirm nothing imports WebRTC**

```bash
grep -rn "org.webrtc" android/app/src android/shared/src --include=*.kt
```

Expected: no hits. If any remain, STOP -- Task 3 missed a consumer, and removing the dependency would break the build in a way that looks like a dependency problem rather than a missed deletion.

- [ ] **Step 2: Extend the failing test**

Add to `RemoteUiGoneTest.kt`:

```kotlin
    @Test
    fun webRtcDependencyIsGone() {
        val gradle = File("build.gradle.kts")
        require(gradle.exists()) { "app build.gradle.kts not found at " + gradle.absolutePath }
        val offending = gradle.readLines()
            .map { it.trim() }
            .filter { !it.startsWith("//") && it.contains("webrtc") }
        assertEquals("the WebRTC dependency must be removed", emptyList<String>(), offending)
    }

    @Test
    fun noSourceImportsWebRtc() {
        val roots = listOf(File("src/main/java"), File("src/test/java"))
        val offending = roots
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import org.webrtc") }
                    .map { file.name + ": " + it }
            }
        assertEquals("no source may import org.webrtc", emptyList<String>(), offending)
    }
```

Note the filter excludes comment lines, so leaving an explanatory comment about the removal will not fail the test.

- [ ] **Step 3: Run it to make sure it fails**

Run: `cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests "*RemoteUiGoneTest*"`

Expected: `webRtcDependencyIsGone` FAILS. `noSourceImportsWebRtc` should already PASS after Task 3.

- [ ] **Step 4: Remove the dependency**

Delete line :277 of `android/app/build.gradle.kts` and the "Remote Access (WebRTC + QR Scanning)" comment block above it. Check whether any QR-scanning library came in with it and is now unused; the wizard's QR scanner was deleted in Task 1.

Check `AndroidManifest.xml` for permissions that existed only for WebRTC (camera for QR scanning, for instance) and remove any that no longer have a user.

- [ ] **Step 5: Verify the dependency is actually gone**

```bash
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -i webrtc
```

Expected: no output. Record the APK size before and after with `ls -la app/build/outputs/apk/debug/app-debug.apk` around a rebuild -- the native library is large and the saving is worth knowing.

- [ ] **Step 6: Run the preference and resource sweeps**

Run the persisted-preference sweep from the checks at the top of this plan and report the result. `albumArtistsOnly` and `cachedPlayerId` are already known orphans and are NOT yours to fix -- note any others.

Then sweep resources orphaned by Tasks 1-3 using the extraction method in Task 1 Step 6, substituting each deleted path.

- [ ] **Step 7: Verify build and both suites, then commit**

```
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :shared:testAndroidHostTest
```

```bash
git add -A android
git commit -m "build: drop the WebRTC dependency"
```

---

### Task 5: Device verification

**Files:**
- Modify: `docs/superpowers/plans/2026-09-01-cut-remote-access.md` (append the verification record)

**Interfaces:**
- Consumes: the app produced by Tasks 1-4.
- Produces: a verification record appended to this plan.

- [ ] **Step 1: Build and install**

```bash
cd android && JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" devices -l
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" -s <serial> install -r android/app/build/outputs/apk/debug/app-debug.apk
```

A Relndoo T901_US tablet has been used throughout, serial `T901YCU250305206`. Pass `-s <serial>` on every adb command -- a stale offline emulator is also attached. In Git Bash, prefix commands containing device paths with `MSYS_NO_PATHCONV=1`.

Record the APK size against the pre-change build; dropping a native library should be visible.

- [ ] **Step 2: Capture logs**

```bash
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe -s <serial>"
$A logcat -c
$A logcat -G 16M
$A logcat -v threadtime > remote-verify.txt &
$A shell am start -n com.sendspindroid/.MainActivity
```

The 16M buffer matters -- the default overflows during playback. Delete `remote-verify.txt` when done; do not commit it.

- [ ] **Step 3: Verify local playback is unaffected**

Connect to a SendSpin server on the local network and play. Confirm Now Playing renders with artwork and metadata, and that play, pause, next, previous and volume each act on the server -- check logcat for the outgoing command and the server response, not just the UI reacting.

**Artwork is the specific risk in this plan.** Task 2 removed the image-proxy fetcher. On a local connection artwork should be fetched directly over HTTP. Confirm album art actually renders, and check logcat for image-load failures.

- [ ] **Step 4: Verify the remote paths are gone**

Confirm the server setup wizard offers no remote-ID or proxy option and no QR scanner, and that manual address entry still works -- add a server by typing its address and connect to it.

- [ ] **Step 5: Check for crashes**

```bash
grep -E "FATAL EXCEPTION|AndroidRuntime|UnsatisfiedLinkError" remote-verify.txt
```

`UnsatisfiedLinkError` is worth grepping specifically: removing a native library can leave a dangling JNI reference that only fails at runtime, which no unit test would catch.

- [ ] **Step 6: Record the result and commit**

Append a verification record: device model, Android version, APK size before and after, what was exercised, PASS/FAIL per item, what could not be verified, and any defect found.

```bash
git add docs/superpowers/plans/2026-09-01-cut-remote-access.md
git commit -m "docs(plan): record remote-access removal device verification"
```

---

## Completion

At the end of this plan the app connects only over the local network or to a manually entered address. `com.sendspindroid.remote` no longer exists in either module, `ProxyWebSocketTransport` is gone, the `io.getstream:stream-webrtc-android` native dependency is removed, and `com.sendspindroid.sendspin` contains zero Music Assistant references -- the protocol core is genuinely protocol-pure for the first time.

`com.sendspindroid.musicassistant` still exists and is still load-bearing for playback, artwork and server setup. Plan C owns retiring it. Verify its remaining consumers with a fresh grep before planning against it, and note that `MusicAssistant.playMedia` was already fully orphaned by the browse-and-queue removal.

The analysis of what the WebRTC transport actually was -- that it carried the SendSpin protocol rather than only Music Assistant control, that the signaling URL was a parameter, that artwork rides the protocol natively as binary types 8-11, and that clock sync over WAN was never tested -- is recorded in the spec under "What the WebRTC transport actually was". Read it before designing any future remote-access story.
