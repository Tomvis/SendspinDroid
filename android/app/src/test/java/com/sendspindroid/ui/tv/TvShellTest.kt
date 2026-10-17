package com.sendspindroid.ui.tv

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.sendspindroid.ui.adaptive.TvOverscanHorizontal
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV side menu is driven by a remote alone: Left from the screen, Back or
 * Menu opens it on the screen's own entry, Right and its entries return to the
 * screen, and Back in it leaves.
 *
 * The screen here is a Previous and a Play button side by side, so the menu,
 * at the left edge, returns to Previous: the nearest control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land")
class TvShellTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var left = 0
    private var stats = 0

    private fun showShell() {
        composeTestRule.setContent {
            SendSpinTheme {
                TvShell(
                    home = TvMenuEntry("Now Playing", Icons.Default.PlayArrow),
                    entries = listOf(TvMenuEntry("Stats", Icons.Default.Info) { stats++ }),
                    footerEntries = listOf(TvMenuEntry("Settings", Icons.Default.Settings)),
                    onLeave = { left++ }
                ) {
                    // Past the open menu's edge, as the controls of the
                    // app's screens are
                    Row(modifier = Modifier.padding(start = 320.dp)) {
                        Button(onClick = {}) { Text("Previous") }
                        Button(onClick = {}) { Text("Play") }
                    }
                }
            }
        }
    }

    private fun pressBack() {
        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `left from the screen opens the menu on its own entry`() {
        showShell()
        composeTestRule.onNodeWithText("Previous").requestFocus()

        composeTestRule.onNodeWithText("Previous").performKeyInput { pressKey(Key.DirectionLeft) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()
    }

    @Test
    // An Arabic locale lays the app out right to left; its strings stay English
    @Config(qualifiers = "+ar")
    fun `in a right-to-left layout right from the screen opens the menu, at the right edge`() {
        composeTestRule.setContent {
            SendSpinTheme {
                TvShell(
                    home = TvMenuEntry("Now Playing", Icons.Default.PlayArrow),
                    entries = emptyList(),
                    footerEntries = emptyList(),
                    onLeave = {}
                ) {
                    Row(modifier = Modifier.padding(start = 320.dp)) {
                        Button(onClick = {}) { Text("Previous") }
                        Button(onClick = {}) { Text("Play") }
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("Previous").requestFocus()

        composeTestRule.onNodeWithText("Previous").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()
    }

    @Test
    fun `right from the menu returns to the screen`() {
        showShell()
        composeTestRule.onNodeWithText("Play").requestFocus()
        pressBack()
        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()

        composeTestRule.onNodeWithText("Now Playing").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Previous").assertIsFocused()
    }

    @Test
    fun `right from the menu lands on the screen when what had focus there is gone`() {
        var showPrevious by mutableStateOf(true)
        composeTestRule.setContent {
            SendSpinTheme {
                TvShell(
                    home = TvMenuEntry("Now Playing", Icons.Default.PlayArrow),
                    entries = emptyList(),
                    footerEntries = emptyList(),
                    onLeave = {}
                ) {
                    Row(modifier = Modifier.padding(start = 320.dp)) {
                        if (showPrevious) Button(onClick = {}) { Text("Previous") }
                        Button(onClick = {}) { Text("Play") }
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("Previous").requestFocus()
        pressBack()
        showPrevious = false
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Now Playing").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `with nothing on the screen to focus the menu collapses, and a key opens it`() {
        composeTestRule.setContent {
            SendSpinTheme {
                TvShell(
                    home = TvMenuEntry("Now Playing", Icons.Default.PlayArrow),
                    entries = emptyList(),
                    footerEntries = emptyList(),
                    onLeave = {}
                ) {
                    Text("Connecting", modifier = Modifier.padding(start = 320.dp))
                }
            }
        }
        pressBack()
        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()

        // Choosing the screen's entry has nowhere to send focus: the menu
        // collapses, its labels go, and focus stays on the entry
        composeTestRule.onNodeWithText("Now Playing").performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Now Playing").assertDoesNotExist()

        composeTestRule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()
    }

    @Test
    fun `focus that falls into the menu when the screen loses its control leaves the menu collapsed`() {
        var connecting by mutableStateOf(false)
        composeTestRule.setContent {
            SendSpinTheme {
                TvShell(
                    home = TvMenuEntry("Now Playing", Icons.Default.PlayArrow),
                    entries = emptyList(),
                    footerEntries = emptyList(),
                    onLeave = {}
                ) {
                    // A screen that goes, with the control that had focus,
                    // for one with nothing to focus
                    Row(modifier = Modifier.padding(start = 320.dp)) {
                        if (connecting) Text("Connecting") else Button(onClick = {}) { Text("Kitchen Server") }
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("Kitchen Server").requestFocus()

        connecting = true
        composeTestRule.waitForIdle()

        // The platform gives the focus to the menu, which has its labels only
        // while it is open
        composeTestRule.onNode(isFocused()).assertExists()
        composeTestRule.onNodeWithText("Now Playing").assertDoesNotExist()

        // and a key opens it
        composeTestRule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()
    }

    @Test
    fun `back opens the menu and back in the menu leaves`() {
        showShell()
        composeTestRule.onNodeWithText("Play").requestFocus()

        pressBack()
        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()
        assertEquals(0, left)

        pressBack()
        assertEquals(1, left)
        composeTestRule.onNodeWithText("Previous").assertIsFocused()
    }

    @Test
    fun `menu key opens the menu`() {
        showShell()
        composeTestRule.onNodeWithText("Play").requestFocus()

        composeTestRule.onNodeWithText("Play").performKeyInput { pressKey(Key.Menu) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()
    }

    @Test
    fun `an entry closes the menu before it acts`() {
        showShell()
        composeTestRule.onNodeWithText("Play").requestFocus()
        pressBack()

        composeTestRule.onNodeWithText("Now Playing").performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Stats").assertIsFocused()
        composeTestRule.onNodeWithText("Stats").performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        assertEquals(1, stats)
        composeTestRule.onNodeWithText("Previous").assertIsFocused()
    }

    // Fork: no rail beside the screen while the menu is collapsed
    @Test
    fun `collapsed, the menu takes almost no room and left from the screen's margin still opens it`() {
        composeTestRule.setContent {
            SendSpinTheme {
                TvShell(
                    home = TvMenuEntry("Now Playing", Icons.Default.PlayArrow),
                    entries = emptyList(),
                    footerEntries = emptyList(),
                    onLeave = {}
                ) {
                    Row(modifier = Modifier.padding(start = TvOverscanHorizontal)) {
                        Button(onClick = {}) { Text("Previous") }
                    }
                }
            }
        }
        composeTestRule.onNodeWithText("Previous").assertLeftPositionInRootIsEqualTo(TvOverscanHorizontal)
        composeTestRule.onNodeWithText("Previous").requestFocus()

        composeTestRule.onNodeWithText("Previous").performKeyInput { pressKey(Key.DirectionLeft) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()
    }

    // Fork: nothing of the shell shows where the rail was
    @Test
    fun `collapsed, the screen draws from the left edge and right from the menu still returns to it`() {
        composeTestRule.setContent {
            SendSpinTheme {
                TvShell(
                    home = TvMenuEntry("Now Playing", Icons.Default.PlayArrow),
                    entries = emptyList(),
                    footerEntries = emptyList(),
                    onLeave = {}
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag("screen")) {
                        // Past the open menu's edge, as in showShell
                        Button(onClick = {}, modifier = Modifier.padding(start = 320.dp)) { Text("Previous") }
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("screen").assertLeftPositionInRootIsEqualTo(0.dp)
        composeTestRule.onNodeWithTag("screen").assertWidthIsEqualTo(960.dp)
        composeTestRule.onNodeWithText("Previous").requestFocus()
        composeTestRule.onNodeWithText("Previous").performKeyInput { pressKey(Key.DirectionLeft) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Now Playing").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Previous").assertIsFocused()
    }

    // Fork: the TV Now Playing screen has nothing to focus, and Right from the
    // open menu still closes it
    @Test
    fun `with nothing on the screen to focus right from the menu collapses it`() {
        composeTestRule.setContent {
            SendSpinTheme {
                TvShell(
                    home = TvMenuEntry("Now Playing", Icons.Default.PlayArrow),
                    entries = emptyList(),
                    footerEntries = emptyList(),
                    onLeave = {}
                ) {
                    Text("Battery", modifier = Modifier.padding(start = 320.dp))
                }
            }
        }
        pressBack()
        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()

        composeTestRule.onNodeWithText("Now Playing").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Now Playing").assertDoesNotExist()

        // Right again does not bring back the menu just closed
        composeTestRule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Now Playing").assertDoesNotExist()

        // Collapsed with focus in it, the Menu key opens it again
        composeTestRule.onNode(isFocused()).performKeyInput { pressKey(Key.Menu) }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Now Playing").assertIsFocused()
    }
}
