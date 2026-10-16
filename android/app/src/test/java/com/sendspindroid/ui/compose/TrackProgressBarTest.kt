package com.sendspindroid.ui.compose

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.components.TrackProgressBar
import com.sendspindroid.ui.main.components.formatTime
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The progress display interpolates from the time a position was received.
 * A new position that equals the last one (two tracks in a row starting at 0)
 * still has a new timestamp, and the display has to restart from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackProgressBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun samePosition_newTimestamp_restartsTheElapsedTime() {
        // Position 0 received 90 s ago: the display has counted up to 1:30.
        var positionUpdatedAt by mutableLongStateOf(SystemClock.elapsedRealtime() - 90_000L)

        composeTestRule.setContent {
            SendSpinTheme {
                TrackProgressBar(
                    positionMs = 0,
                    durationMs = 180_000,
                    playbackSpeed = 1000,
                    accentColor = null,
                    positionUpdatedAt = positionUpdatedAt
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(300)
        composeTestRule.onNodeWithText("1:30 / 3:00").assertIsDisplayed()

        // The next track reports position 0 as well, received now.
        positionUpdatedAt = SystemClock.elapsedRealtime()
        composeTestRule.mainClock.advanceTimeBy(300)

        composeTestRule.onNodeWithText("0:00 / 3:00").assertIsDisplayed()
    }

    @Test
    fun advancesAtTheServerSpeed() {
        // Position 0 reported 60 s ago at 1.5x: 90 s of the track have played.
        val reportedAt = SystemClock.elapsedRealtime() - 60_000L

        composeTestRule.setContent {
            SendSpinTheme {
                TrackProgressBar(
                    positionMs = 0,
                    durationMs = 180_000,
                    playbackSpeed = 1500,
                    accentColor = null,
                    positionUpdatedAt = reportedAt
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(300)

        composeTestRule.onNodeWithText("1:30 / 3:00").assertIsDisplayed()
    }

    private val seeks = mutableListOf<Long>()
    private var serverPositionMs by mutableLongStateOf(0L)
    private var serverPositionUpdatedAt by mutableLongStateOf(1L)

    /** A paused 3:00 track, so the display shows exactly the position it holds. */
    private fun setPausedBar(seekable: Boolean) {
        composeTestRule.setContent {
            SendSpinTheme {
                TrackProgressBar(
                    positionMs = serverPositionMs,
                    durationMs = 180_000,
                    playbackSpeed = 0,
                    accentColor = null,
                    positionUpdatedAt = serverPositionUpdatedAt,
                    seekable = seekable,
                    onSeek = { seeks += it }
                )
            }
        }
    }

    private fun bar() = composeTestRule.onNodeWithContentDescription(
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.track_position)
    )

    @Test
    fun seekable_dragAndRelease_seeksOnceOnRelease() {
        setPausedBar(seekable = true)

        bar().performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
        }
        // A seek restarts the stream for the whole group: none while dragging.
        assertTrue(seeks.isEmpty())

        bar().performTouchInput { up() }

        assertEquals(1, seeks.size)
        // Dragged right from the middle of a 3:00 track.
        assertTrue(seeks.single() in 90_001..180_000)
    }

    @Test
    fun notSeekable_touch_doesNotSeek() {
        setPausedBar(seekable = false)

        bar().performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
            up()
        }

        assertTrue(seeks.isEmpty())
    }

    @Test
    fun afterRelease_holdsTheDroppedPosition_untilTheServerReports() {
        setPausedBar(seekable = true)

        bar().performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(300)
        composeTestRule.onNodeWithText("${formatTime(seeks.single())} / 3:00").assertIsDisplayed()

        serverPositionMs = 120_000
        serverPositionUpdatedAt = 2L
        composeTestRule.mainClock.advanceTimeBy(300)

        composeTestRule.onNodeWithText("2:00 / 3:00").assertIsDisplayed()
    }

    @Test
    fun afterRelease_serverSilent_returnsToTheServerPosition() {
        setPausedBar(seekable = true)

        bar().performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(2_500)

        composeTestRule.onNodeWithText("0:00 / 3:00").assertIsDisplayed()
    }

    @Test
    fun seekable_upAndDown_moveFocusOff_withoutSeeking() {
        serverPositionMs = 90_000
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides FormFactor.PHONE) {
                SendSpinTheme {
                    Column {
                        Button(onClick = {}) { Text("Above") }
                        TrackProgressBar(
                            positionMs = serverPositionMs,
                            durationMs = 180_000,
                            playbackSpeed = 0,
                            accentColor = null,
                            positionUpdatedAt = serverPositionUpdatedAt,
                            seekable = true,
                            onSeek = { seeks += it }
                        )
                        Button(onClick = {}) { Text("Below") }
                    }
                }
            }
        }

        bar().requestFocus()
        bar().performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.onNodeWithText("Below").assertIsFocused()
        composeTestRule.onNodeWithText("1:30 / 3:00").assertIsDisplayed()

        bar().requestFocus()
        bar().performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.onNodeWithText("Above").assertIsFocused()
        composeTestRule.onNodeWithText("1:30 / 3:00").assertIsDisplayed()

        assertTrue(seeks.isEmpty())
    }

    @Test
    fun seekable_aRunOfLeftAndRightPresses_seeksOnceWhenTheyStop() {
        serverPositionMs = 90_000
        setPausedBar(seekable = true)
        bar().requestFocus()

        // A keyboard on a phone or tablet: the Slider alone would seek on
        // each key up.
        for (key in listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft)) {
            bar().performKeyInput { pressKey(key) }
            composeTestRule.waitForIdle()
            composeTestRule.mainClock.advanceTimeBy(300)
        }
        // Each seek restarts the stream for the group: none while stepping.
        assertTrue(seeks.isEmpty())
        composeTestRule.onNodeWithText("1:50 / 3:00").assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(600)
        assertEquals(listOf(110_000L), seeks)
    }
}
