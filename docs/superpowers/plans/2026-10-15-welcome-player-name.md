# Welcome Player Name Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new user names the player in the welcome dialog, and the app does not create `PlaybackService` (and so does not advertise or search) until they press Continue.

**Architecture:** `MainActivity` builds its `MediaController`, which binds and so creates `PlaybackService`, only once the welcome has been confirmed: at once in `onCreate` for an existing install, from Continue on the first run. The welcome dialog gains an outlined text field for the player name, built in code (the app has no XML layouts), and its copy is rewritten.

**Tech Stack:** Kotlin, Material Components 1.13 (`MaterialAlertDialogBuilder`, `TextInputLayout`), `LocalBroadcastManager`, Robolectric 4.14 + JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-15-welcome-player-name-design.md`

## Global Constraints

- The welcome flag stays `onboarding_shown` in `sendspindroid_prefs`, read and written only by `MainActivity` (spec D3).
- Title unchanged: "Welcome to SendSpin Player". Button: "Continue". Body, verbatim (spec D4): "Plays audio from a SendSpin or Music Assistant server, in sync with your other speakers.\n\nServers on your network will find this player by the name below. You can change it later in Settings."
- Field label is the existing `pref_player_name_title` ("Player Name").
- A blank name is saved as typed, with no trimming (spec D5).
- Continue order (spec D6): `setPlayerName`, set flag, send `ACTION_PLAYER_NAME_CHANGED`, `initializeMediaController()`.
- No emojis; ASCII in code, logs and strings (project CLAUDE.md).
- Commits carry no self-reference and no Co-Authored-By line (user CLAUDE.md).

## Review Focus

1. The user keeps the default: the field opens filled with the name the player would use (`Build.MODEL` on a fresh install). Pinned in Task 2.
2. The user clears the field and presses Continue: the player uses the device model name, as Settings does. Pinned in Task 2.
3. The user presses Done on the keyboard instead of Continue: same outcome as Continue. Pinned in Task 2.
4. An existing install: no dialog, and the controller is built during `onCreate` as before. Pinned in Task 1.
5. A service already running (Android Auto started it): it is told of the new name. Pinned in Task 2 by the broadcast assertion.

---

### Task 1: Build the controller only after the welcome

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt` (`onCreate` ~line 372-381, `showOnboardingIfNeeded` ~line 414-427)
- Test: `android/app/src/test/java/com/sendspindroid/MainActivityWelcomeTest.kt` (create)

**Interfaces:**
- Consumes: `MainActivity.initializeMediaController()` (private, existing), `MainActivity.mediaControllerFuture` (private field, read by reflection in tests), `UserSettings.initializeForTesting(plainPrefs, encryptedPrefs)` (internal, existing).
- Produces: test helpers in `MainActivityWelcomeTest` (`welcomeDialog()`, `controllerFuture(activity)`, `confirm(dialog)`) that Task 2 adds tests beside.

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/com/sendspindroid/MainActivityWelcomeTest.kt`:

```kotlin
package com.sendspindroid

import android.content.Context
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The welcome comes before the network. Building the MediaController binds
 * PlaybackService, which advertises the player as soon as it is created, so
 * on the first run the controller waits for the welcome's Continue: the
 * user names the player before any server can see it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityWelcomeTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun freshSettings() {
        // UserSettings is a process-wide object: give it this test's
        // preferences, or a name saved by an earlier test would carry over.
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        UserSettings.initializeForTesting(prefs, prefs)
    }

    private fun welcomeDone() =
        app.getSharedPreferences("sendspindroid_prefs", Context.MODE_PRIVATE)
            .getBoolean("onboarding_shown", false)

    private fun controllerFuture(activity: MainActivity): Any? =
        MainActivity::class.java.getDeclaredField("mediaControllerFuture").run {
            isAccessible = true
            get(activity)
        }

    private fun welcomeDialog(): AlertDialog? =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }

    private fun confirm(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // The dialog delivers the click through its handler.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a first run shows the welcome and does not start the service`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNotNull(welcomeDialog())
        assertNull(controllerFuture(activity))
    }

    @Test
    fun `continue starts the service and the welcome is not shown again`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        confirm(welcomeDialog()!!)

        assertNotNull(controllerFuture(activity))
        assertTrue(welcomeDone())
    }

    @Test
    fun `after the welcome the service starts at once`() {
        app.getSharedPreferences("sendspindroid_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_shown", true).commit()

        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        assertNull(welcomeDialog())
        assertNotNull(controllerFuture(activity))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (from `android/`): `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.MainActivityWelcomeTest`
Expected: `a first run shows the welcome and does not start the service` FAILS (the controller future is set in `onCreate`). The other two pass, since they describe what already happens.

- [ ] **Step 3: Gate the controller**

In `MainActivity.onCreate`, replace the unconditional `initializeMediaController()`:

```kotlin
        initializeDiscoveryManager()
        initializeDefaultServerPinger()
        // The controller binds PlaybackService, which advertises the player
        // as soon as it is created: on the first run the user names it in
        // the welcome first, and Continue builds the controller.
        if (prefs.getBoolean(PREF_ONBOARDING_SHOWN, false)) initializeMediaController()
        setupUI()
```

In `showOnboardingIfNeeded()`, build it from the button:

```kotlin
                .setPositiveButton(R.string.onboarding_got_it) { dialog, _ ->
                    // Mark onboarding as shown
                    prefs.edit().putBoolean(PREF_ONBOARDING_SHOWN, true).apply()
                    initializeMediaController()
                    dialog.dismiss()
                }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.MainActivityWelcomeTest --tests com.sendspindroid.MainActivityRecreateTest`
Expected: all PASS. `MainActivityRecreateTest` is a first run under Robolectric now and puts its own controller in the field, so it is unaffected.

If idling the looper in `confirm` fails on the controller's bind to a service Robolectric does not run, run only the dialog's queued click with `shadowOf(Looper.getMainLooper()).runOneTask()` instead of `idle()`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/MainActivity.kt android/app/src/test/java/com/sendspindroid/MainActivityWelcomeTest.kt
git commit -m "feat(welcome): start the playback service only after the welcome"
```

---

### Task 2: The player name in the welcome dialog

**Files:**
- Modify: `android/app/src/main/java/com/sendspindroid/MainActivity.kt` (`showOnboardingIfNeeded`, imports)
- Modify: `android/app/src/main/res/values/strings.xml:42-45`
- Test: `android/app/src/test/java/com/sendspindroid/MainActivityWelcomeTest.kt`

**Interfaces:**
- Consumes: Task 1's `welcomeDialog()`, `controllerFuture(activity)`, `confirm(dialog)`; `UserSettings.getPlayerName()`, `UserSettings.setPlayerName(name)`; `SettingsViewModel.ACTION_PLAYER_NAME_CHANGED`.
- Produces: `MainActivity.onWelcomeConfirmed(name: String)` (private); string `onboarding_continue`.

- [ ] **Step 1: Write the failing tests**

Add to `MainActivityWelcomeTest` (with imports `android.content.BroadcastReceiver`, `android.content.Intent`, `android.content.IntentFilter`, `android.os.Build`, `android.view.View`, `android.view.ViewGroup`, `android.view.inputmethod.EditorInfo`, `android.widget.EditText`, `androidx.core.view.children`, `androidx.localbroadcastmanager.content.LocalBroadcastManager`, `com.sendspindroid.ui.settings.SettingsViewModel`, `org.junit.Assert.assertEquals`):

```kotlin
    private fun View.firstEditText(): EditText? = when (this) {
        is EditText -> this
        is ViewGroup -> children.firstNotNullOfOrNull { it.firstEditText() }
        else -> null
    }

    private fun AlertDialog.nameField(): EditText = window!!.decorView.firstEditText()!!

    @Test
    fun `the welcome offers the name the player would use`() {
        Robolectric.buildActivity(MainActivity::class.java).create()

        assertEquals(Build.MODEL, welcomeDialog()!!.nameField().text.toString())
    }

    @Test
    fun `continue keeps the name typed in the welcome`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val dialog = welcomeDialog()!!

        dialog.nameField().setText("Kitchen")
        confirm(dialog)

        assertEquals("Kitchen", UserSettings.getPlayerName())
        assertNotNull(controllerFuture(activity))
    }

    @Test
    fun `a blank name falls back to the device model`() {
        Robolectric.buildActivity(MainActivity::class.java).create()
        val dialog = welcomeDialog()!!

        dialog.nameField().setText("")
        confirm(dialog)

        assertEquals(Build.MODEL, UserSettings.getPlayerName())
    }

    @Test
    fun `done on the keyboard does what continue does`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val field = welcomeDialog()!!.nameField()

        field.setText("Kitchen")
        field.onEditorAction(EditorInfo.IME_ACTION_DONE)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Kitchen", UserSettings.getPlayerName())
        assertNotNull(controllerFuture(activity))
        assertTrue(welcomeDone())
    }

    @Test
    fun `a service already running is told the new name`() {
        var told = 0
        LocalBroadcastManager.getInstance(app).registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { told++ }
            },
            IntentFilter(SettingsViewModel.ACTION_PLAYER_NAME_CHANGED)
        )
        Robolectric.buildActivity(MainActivity::class.java).create()

        confirm(welcomeDialog()!!)

        assertEquals(1, told)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.MainActivityWelcomeTest`
Expected: the five new tests FAIL (`nameField()` throws: the dialog has no EditText).

- [ ] **Step 3: Rewrite the strings**

In `android/app/src/main/res/values/strings.xml`, replace the Onboarding block:

```xml
    <!-- Onboarding -->
    <string name="onboarding_title">Welcome to SendSpin Player</string>
    <string name="onboarding_message">Plays audio from a SendSpin or Music Assistant server, in sync with your other speakers.\n\nServers on your network will find this player by the name below. You can change it later in Settings.</string>
    <string name="onboarding_continue">Continue</string>
```

(`onboarding_got_it` goes: Task 2 removes its only use.)

- [ ] **Step 4: Add the field and the confirm path**

Replace `showOnboardingIfNeeded()` in `MainActivity.kt`:

```kotlin
    /**
     * Shows the welcome dialog if this is the user's first launch, with the
     * player's name to change before any server can see it. Continue builds
     * the MediaController, and so starts PlaybackService.
     */
    private fun showOnboardingIfNeeded() {
        if (prefs.getBoolean(PREF_ONBOARDING_SHOWN, false)) return
        val nameField = TextInputEditText(this).apply {
            setText(UserSettings.getPlayerName())
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val nameLayout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.pref_player_name_title)
            addView(nameField)
        }
        // A dialog's custom view gets no inset of its own: line it up with
        // the message.
        val inset = (24 * resources.displayMetrics.density).toInt()
        val content = FrameLayout(this).apply {
            setPadding(inset, 0, inset, 0)
            addView(nameLayout)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.onboarding_title)
            .setMessage(R.string.onboarding_message)
            .setView(content)
            .setPositiveButton(R.string.onboarding_continue) { _, _ ->
                onWelcomeConfirmed(nameField.text.toString())
            }
            .setCancelable(false)
            .show()
        // Done does what Continue does: with the keyboard up in landscape the
        // dialog's buttons can be out of reach.
        nameField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            true
        }
    }

    /** The user named the player and pressed Continue. */
    private fun onWelcomeConfirmed(name: String) {
        UserSettings.setPlayerName(name)
        prefs.edit().putBoolean(PREF_ONBOARDING_SHOWN, true).apply()
        // A service Android Auto started already advertises: it takes the
        // name the way it takes a rename in Settings.
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(SettingsViewModel.ACTION_PLAYER_NAME_CHANGED))
        initializeMediaController()
    }
```

Add the imports that are not there yet: `android.content.DialogInterface`, `android.view.inputmethod.EditorInfo`, `androidx.localbroadcastmanager.content.LocalBroadcastManager`, `com.google.android.material.textfield.TextInputEditText`, `com.google.android.material.textfield.TextInputLayout`, `com.sendspindroid.ui.settings.SettingsViewModel`. (`FrameLayout` and `MaterialAlertDialogBuilder` are already imported.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests com.sendspindroid.MainActivityWelcomeTest --tests com.sendspindroid.MainActivityRecreateTest`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/sendspindroid/MainActivity.kt android/app/src/main/res/values/strings.xml android/app/src/test/java/com/sendspindroid/MainActivityWelcomeTest.kt
git commit -m "feat(welcome): name the player in the welcome dialog"
```

---

### Task 3: Verify, push, PR

- [ ] **Step 1: Full build and unit tests**

Run: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no test failures.

- [ ] **Step 2: Device check (fresh install)**

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell pm clear com.sendspindroid
"$ADB" logcat -c
"$ADB" shell monkey -p com.sendspindroid -c android.intent.category.LAUNCHER 1
"$ADB" logcat -d | grep "Advertising as"
```
Expected: no `Advertising as` line while the dialog is up. Change the name, press Continue, run the last line again: `Advertising as '<chosen name>' on port ...`. Music Assistant lists the player under that name.

- [ ] **Step 3: Push and open the PR**

Push `feat/welcome-player-name` and open a PR against `main` that links the spec.
