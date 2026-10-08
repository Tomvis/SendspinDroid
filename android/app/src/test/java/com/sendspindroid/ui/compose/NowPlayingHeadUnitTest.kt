package com.sendspindroid.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.sendspindroid.ui.main.NowPlayingHeadUnit
import com.sendspindroid.ui.main.TrackMetadata
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
