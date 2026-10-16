package com.sendspindroid.ui.compose

import android.view.ContextThemeWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.sendspindroid.R
import com.sendspindroid.ui.main.NowPlayingHeadUnit
import com.sendspindroid.ui.main.TrackMetadata
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests that NowPlayingHeadUnit renders a simplified layout with
 * large album art and minimal controls for car head unit use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingHeadUnitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun headUnit_displaysTrackTitle() {
        composeTestRule.setContent {
            SendSpinTheme {
                NowPlayingHeadUnit(
                    metadata = TrackMetadata(
                        title = "Highway Star",
                        artist = "Deep Purple",
                        album = "Machine Head"
                    ),
                    groupName = "",
                    artworkSource = null,
                    isBuffering = false,
                    isPlaying = true,
                    controlsEnabled = true,
                    accentColor = null,
                    positionMs = 60000,
                    durationMs = 375000,
                    onPreviousClick = {},
                    onPlayPauseClick = {},
                    onNextClick = {},
                    onSwitchGroupClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Highway Star").assertIsDisplayed()
    }

    @Test
    fun headUnit_displaysArtistAndAlbum() {
        composeTestRule.setContent {
            SendSpinTheme {
                NowPlayingHeadUnit(
                    metadata = TrackMetadata(
                        title = "Highway Star",
                        artist = "Deep Purple",
                        album = "Machine Head"
                    ),
                    groupName = "",
                    artworkSource = null,
                    isBuffering = false,
                    isPlaying = true,
                    controlsEnabled = true,
                    accentColor = null,
                    positionMs = 0,
                    durationMs = 375000,
                    onPreviousClick = {},
                    onPlayPauseClick = {},
                    onNextClick = {},
                    onSwitchGroupClick = {}
                )
            }
        }

        // Head unit shows "Artist -- Album" format
        composeTestRule.onNodeWithText("Deep Purple -- Machine Head").assertIsDisplayed()
    }

    @Test
    fun headUnit_displaysPlayButton() {
        composeTestRule.setContent {
            SendSpinTheme {
                NowPlayingHeadUnit(
                    metadata = TrackMetadata(
                        title = "Test",
                        artist = "Artist",
                        album = ""
                    ),
                    groupName = "",
                    artworkSource = null,
                    isBuffering = false,
                    isPlaying = false,
                    controlsEnabled = true,
                    accentColor = null,
                    positionMs = 0,
                    durationMs = 0,
                    onPreviousClick = {},
                    onPlayPauseClick = {},
                    onNextClick = {},
                    onSwitchGroupClick = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Play button").assertIsDisplayed()
    }

    @Test
    fun headUnit_displaysPreviousAndNextButtons() {
        composeTestRule.setContent {
            SendSpinTheme {
                NowPlayingHeadUnit(
                    metadata = TrackMetadata(
                        title = "Test",
                        artist = "",
                        album = ""
                    ),
                    groupName = "",
                    artworkSource = null,
                    isBuffering = false,
                    isPlaying = true,
                    controlsEnabled = true,
                    accentColor = null,
                    positionMs = 0,
                    durationMs = 0,
                    onPreviousClick = {},
                    onPlayPauseClick = {},
                    onNextClick = {},
                    onSwitchGroupClick = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Previous track button").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Next track button").assertIsDisplayed()
    }

    @Test
    fun headUnit_notPlaying_showsNotPlayingText() {
        composeTestRule.setContent {
            SendSpinTheme {
                NowPlayingHeadUnit(
                    metadata = TrackMetadata.EMPTY,
                    groupName = "",
                    artworkSource = null,
                    isBuffering = false,
                    isPlaying = false,
                    controlsEnabled = false,
                    accentColor = null,
                    positionMs = 0,
                    durationMs = 0,
                    onPreviousClick = {},
                    onPlayPauseClick = {},
                    onNextClick = {},
                    onSwitchGroupClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Not Playing").assertIsDisplayed()
    }

    @Test
    fun headUnit_withGroupName_displaysGroupLabel() {
        composeTestRule.setContent {
            SendSpinTheme {
                NowPlayingHeadUnit(
                    metadata = TrackMetadata(
                        title = "Test Song",
                        artist = "Artist",
                        album = ""
                    ),
                    groupName = "Living Room",
                    artworkSource = null,
                    isBuffering = false,
                    isPlaying = true,
                    controlsEnabled = true,
                    accentColor = null,
                    positionMs = 0,
                    durationMs = 0,
                    onPreviousClick = {},
                    onPlayPauseClick = {},
                    onNextClick = {},
                    onSwitchGroupClick = {}
                )
            }
        }

        // Group label uses "Group: %s" string resource
        composeTestRule.onNodeWithText("Group: Living Room").assertIsDisplayed()
    }

    @Test
    fun headUnit_hasNoFavoriteButton() {
        composeTestRule.setContent {
            SendSpinTheme {
                NowPlayingHeadUnit(
                    metadata = TrackMetadata(
                        title = "Test",
                        artist = "",
                        album = ""
                    ),
                    groupName = "",
                    artworkSource = null,
                    isBuffering = false,
                    isPlaying = true,
                    controlsEnabled = true,
                    accentColor = null,
                    positionMs = 0,
                    durationMs = 0,
                    onPreviousClick = {},
                    onPlayPauseClick = {},
                    onNextClick = {},
                    onSwitchGroupClick = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Add current track to favorites").assertDoesNotExist()
    }

    /**
     * Server colors make primary on_light in light mode, a dark color, with
     * white for the icon on it: the icon is onPrimary, not a fixed black.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun headUnit_playIconIsOnPrimary() {
        composeTestRule.setContent {
            // The app's theme, as MainActivity has: the icon's path is filled
            // with its colorOnSurface, which the test activity's platform
            // theme lacks, leaving nothing for the tint to color
            val context = ContextThemeWrapper(LocalContext.current, R.style.Theme_SendSpinDroid)
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color.Black, onPrimary = Color.White)) {
                    NowPlayingHeadUnit(
                        metadata = TrackMetadata(
                            title = "Test",
                            artist = "",
                            album = ""
                        ),
                        groupName = "",
                        artworkSource = null,
                        isBuffering = false,
                        isPlaying = false,
                        controlsEnabled = true,
                        accentColor = null,
                        positionMs = 0,
                        durationMs = 0,
                        onPreviousClick = {},
                        onPlayPauseClick = {},
                        onNextClick = {},
                        onSwitchGroupClick = {}
                    )
                }
            }
        }

        val button = composeTestRule.onNodeWithContentDescription("Play button").captureToImage().toPixelMap()
        val white = (0 until button.width).sumOf { x -> (0 until button.height).count { y -> button[x, y] == Color.White } }
        assertTrue("the play icon shows on the black play button", white > 0)
    }
}
