package com.sendspindroid.ui.compose

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.NowPlayingScreen
import com.sendspindroid.ui.main.PlaybackState
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where Now Playing shows the year and the album artist (#215). The year
 * follows the album on every layout but the head unit's. The album artist
 * gets a line of its own only where there is height for it: on a TV, and on
 * a tablet at least 600dp tall, but not on a phone turned on its side, which
 * is as wide as a tablet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingAlbumArtistYearTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val viewModel = MainActivityViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun show(formFactor: FormFactor, albumArtist: String? = "Various Artists", year: Int? = 1999) {
        viewModel.updateConnectionState(AppConnectionState.Connected("Kitchen", "192.168.1.4:8927"))
        viewModel.updatePlaybackState(true, PlaybackState.READY)
        viewModel.updateMetadata("Bohemian Rhapsody", "Queen", "Now 44", albumArtist, year)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides formFactor) {
                SendSpinTheme {
                    NowPlayingScreen(
                        viewModel = viewModel,
                        onPreviousClick = {},
                        onPlayPauseClick = {},
                        onNextClick = {},
                        onSwitchGroupClick = {},
                        onVolumeChange = {},
                        onSeek = {},
                        onOpenPairingClick = {},
                        onAllowPairingClick = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertLine(text: String) = composeTestRule.onNodeWithText(text).assertIsDisplayed()

    private fun assertNoAlbumArtist() =
        composeTestRule.onNodeWithText("Album artist", substring = true).assertDoesNotExist()

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun `a phone shows the year, but not the album artist`() {
        show(FormFactor.PHONE)

        assertLine("Queen \u2022 Now 44 (1999)")
        assertNoAlbumArtist()
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun `a phone on its side shows the year, but not the album artist`() {
        // As wide as a 10" tablet, which is what its form factor says
        show(FormFactor.TABLET_10)

        assertLine("Queen \u2022 Now 44 (1999)")
        assertNoAlbumArtist()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port")
    fun `a tablet shows the year, and the album artist`() {
        show(FormFactor.TABLET_7)

        assertLine("Queen \u2022 Now 44 (1999)")
        assertLine("Album artist: Various Artists")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `a tablet on its side shows the year, and the album artist`() {
        show(FormFactor.TABLET_10)

        assertLine("Queen \u2022 Now 44 (1999)")
        assertLine("Album artist: Various Artists")
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    // Fork: the TV Now Playing screen (NowPlayingTvScreen) has its own
    // "Album · Year" line and no album-artist line
    fun `a TV shows the year, and not the album artist`() {
        show(FormFactor.TV)

        assertLine("Now 44 \u00b7 1999")
        assertNoAlbumArtist()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    fun `a TV does not repeat an album artist that is the track artist`() {
        show(FormFactor.TV, albumArtist = "Queen", year = 1975)

        assertLine("Now 44 \u00b7 1975")
        assertNoAlbumArtist()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port")
    fun `a track without them reads as it did`() {
        show(FormFactor.TABLET_7, albumArtist = null, year = null)

        assertLine("Queen \u2022 Now 44")
        assertNoAlbumArtist()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port")
    fun `a head unit shows neither`() {
        show(FormFactor.HEADUNIT)

        assertLine("Queen -- Now 44")
        assertNoAlbumArtist()
    }
}
