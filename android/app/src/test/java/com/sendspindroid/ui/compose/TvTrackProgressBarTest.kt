package com.sendspindroid.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.rememberFocusFallback
import com.sendspindroid.ui.main.components.TvTrackProgressBar
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A TV remote moves a Material3 slider by 1% a press, and each press is a
 * seek of its own: when the server offers seek, Left and Right on the focused
 * bar step through the track instead, and a run of presses goes to the
 * server as one seek.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvTrackProgressBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val seeks = mutableListOf<Long>()
    private var durationMs by mutableLongStateOf(180_000L)
    private var positionMs by mutableLongStateOf(60_000L)
    private var positionUpdatedAt by mutableLongStateOf(1L)

    /** A paused 3:00 track at 1:00, so the display shows exactly the position it holds. */
    private fun showBar(seekable: Boolean) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides FormFactor.TV) {
                SendSpinTheme {
                    val play = remember { FocusRequester() }
                    Column {
                        // First in line: where focus would land if nothing moved it
                        Button(onClick = {}) { Text("Menu") }
                        TvTrackProgressBar(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            playbackSpeed = 0,
                            accentColor = null,
                            positionUpdatedAt = positionUpdatedAt,
                            seekable = seekable,
                            onSeek = { seeks += it },
                            modifier = rememberFocusFallback(
                                canFocus = seekable && durationMs > 0,
                                fallback = play
                            )
                        )
                        Button(onClick = {}, modifier = Modifier.focusRequester(play)) {
                            Text("Play")
                        }
                    }
                }
            }
        }
    }

    private fun bar() = composeTestRule.onNodeWithContentDescription(
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.track_position)
    )

    /**
     * Presses [key] on the bar. performKeyInput does not wait for the UI, so
     * the press would otherwise reach the composition only at the next wait,
     * and the clock advanced before that would not count.
     */
    private fun press(key: Key) {
        bar().performKeyInput { pressKey(key) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `Right on the focused bar seeks 10 s on once the presses stop`() {
        showBar(seekable = true)
        bar().requestFocus()

        press(Key.DirectionRight)
        composeTestRule.mainClock.advanceTimeBy(500)
        // Each seek restarts the stream for the group: none while stepping.
        assertTrue(seeks.isEmpty())

        composeTestRule.mainClock.advanceTimeBy(500)
        assertEquals(listOf(70_000L), seeks)
    }

    @Test
    fun `a run of presses adds up to one seek and shows where it goes`() {
        showBar(seekable = true)
        bar().requestFocus()

        repeat(3) {
            press(Key.DirectionRight)
            composeTestRule.mainClock.advanceTimeBy(300)
        }
        press(Key.DirectionLeft)
        composeTestRule.mainClock.advanceTimeBy(300)

        assertTrue(seeks.isEmpty())
        composeTestRule.onNodeWithText("1:20").assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(600)
        assertEquals(listOf(80_000L), seeks)
    }

    @Test
    fun `a press against the start after the seek went out does not hold the bar there`() {
        positionMs = 5_000
        showBar(seekable = true)
        bar().requestFocus()

        press(Key.DirectionLeft)
        composeTestRule.mainClock.advanceTimeBy(900)
        assertEquals(listOf(0L), seeks)

        // Still held: the bar cannot go below 0:00.
        press(Key.DirectionLeft)
        // The server answers from elsewhere in the track.
        positionMs = 30_000
        positionUpdatedAt = 2L
        composeTestRule.mainClock.advanceTimeBy(5_000)

        composeTestRule.onNodeWithText("0:30").assertIsDisplayed()
        assertEquals(listOf(0L), seeks)
    }

    @Test
    fun `presses that come back to the position that went out still wait for the server`() {
        showBar(seekable = true)
        bar().requestFocus()

        press(Key.DirectionRight)
        composeTestRule.mainClock.advanceTimeBy(900)
        assertEquals(listOf(70_000L), seeks)

        // Away and back before the screen next updates.
        bar().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionLeft)
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(900)
        // The server answers from elsewhere in the track.
        positionMs = 30_000
        positionUpdatedAt = 2L
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.onNodeWithText("0:30").assertIsDisplayed()
    }

    @Test
    fun `without seek the bar does not take focus`() {
        showBar(seekable = false)
        bar().assertIsDisplayed()
        bar().assert(!isFocusable())
    }

    @Test
    fun `focus goes to Play when the focused bar goes away`() {
        showBar(seekable = true)
        bar().requestFocus()
        bar().assertIsFocused()

        // The next track has no known duration: the bar is not shown at all.
        durationMs = 0
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Play").assertIsFocused()
    }
}
