package com.sendspindroid.ui.compose

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sendspindroid.ui.main.components.TrackProgressBar
import com.sendspindroid.ui.theme.SendSpinTheme
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
                    isPlaying = true,
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
}
