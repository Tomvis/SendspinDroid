package com.sendspindroid.ui.compose

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.NowPlayingScreen
import com.sendspindroid.ui.main.PlaybackState
import com.sendspindroid.ui.main.controlsFormFactor
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Now Playing keeps its controls on screen whatever the shape of the window
 * (#376): a phone on its side, which reads as a tablet by width, and short or
 * square floating windows. The qualifiers are the space Now Playing itself
 * has, the window less its bars. Where nothing can make everything fit, the
 * controls are reachable by scrolling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingFitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val viewModel = MainActivityViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun show(formFactor: FormFactor) {
        viewModel.updateConnectionState(AppConnectionState.Connected("Kitchen", "192.168.1.4:8927"))
        viewModel.updatePlaybackState(true, PlaybackState.READY)
        viewModel.updateMetadata("Bohemian Rhapsody", "Queen", "A Night at the Opera", null, 1975)
        viewModel.updateGroupName("Kitchen Phone")
        // A seekable track: the progress bar at its tallest
        viewModel.updateTrackProgress(60_000, 354_000, SystemClock.elapsedRealtime(), 1000)
        viewModel.updateSeekable(true)
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
                        onMuteToggle = {},
                        onSeek = {},
                        onOpenPairingClick = {},
                        onAllowPairingClick = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private val previous get() = composeTestRule.onNodeWithContentDescription("Previous track button")
    private val play get() = composeTestRule.onNodeWithContentDescription("Pause button")
    private val next get() = composeTestRule.onNodeWithContentDescription("Next track button")
    private val switchGroup get() = composeTestRule.onNodeWithContentDescription("Switch to next playback group")
    private val volume get() = composeTestRule.onNodeWithText("Device Volume")

    private fun SemanticsNodeInteraction.bounds(): DpRect = getUnclippedBoundsInRoot()

    private fun assertOnScreen(node: SemanticsNodeInteraction) {
        val root = composeTestRule.onRoot().bounds()
        val b = node.bounds()
        assertTrue(
            "$b is not inside the screen $root",
            b.left >= root.left && b.top >= root.top && b.right <= root.right && b.bottom <= root.bottom
        )
    }

    private fun assertAllOnScreen() {
        listOf(previous, play, next, switchGroup, volume).forEach(::assertOnScreen)
        // The volume slider used to be drawn over the switch-group button
        assertTrue(switchGroup.bounds().bottom <= volume.bounds().top)
    }

    @Test
    @Config(qualifiers = "w411dp-h760dp-port")
    fun `a phone fits everything, with switch group in a row of its own`() {
        show(FormFactor.PHONE)

        assertAllOnScreen()
        assertTrue(switchGroup.bounds().top >= play.bounds().bottom)
    }

    @Test
    @Config(qualifiers = "w993dp-h330dp-land")
    fun `a phone on its side keeps the transport on screen`() {
        // As wide as a 10" tablet, which is what its form factor says
        show(FormFactor.TABLET_10)

        listOf(previous, play, next, switchGroup).forEach(::assertOnScreen)
        // Short, so the phone's buttons, and switch group joins the transport row
        assertEquals(72f, (play.bounds().bottom - play.bounds().top).value, 0.5f)
        assertTrue(switchGroup.bounds().left >= next.bounds().right)
        assertTrue(switchGroup.bounds().bottom <= volume.bounds().top)
        // On a device the volume fits too. Robolectric lays text out in lines
        // about twice a device's height, which pushes it below the fold here.
        volume.performScrollTo()
        assertOnScreen(volume)
    }

    @Test
    @Config(qualifiers = "w535dp-h425dp-land")
    fun `a square floating window fits everything`() {
        show(FormFactor.PHONE)

        assertAllOnScreen()
    }

    @Test
    @Config(qualifiers = "w1101dp-h620dp-land")
    fun `a tablet on its side keeps its layout`() {
        show(FormFactor.TABLET_10)

        assertAllOnScreen()
        assertTrue(switchGroup.bounds().top >= play.bounds().bottom)
        // The tablet's buttons, not the phone's 72dp
        assertEquals(88f, (play.bounds().bottom - play.bounds().top).value, 0.5f)
    }

    @Test
    @Config(qualifiers = "w655dp-h180dp-land")
    fun `a window too short for everything scrolls to each control`() {
        show(FormFactor.TABLET_7)

        listOf(previous, play, next, switchGroup, volume).forEach {
            it.performScrollTo()
            assertOnScreen(it)
        }
    }

    @Test
    fun `only a tablet's controls shrink, and only when short`() {
        assertEquals(FormFactor.PHONE, controlsFormFactor(FormFactor.TABLET_10, compactHeight = true))
        assertEquals(FormFactor.PHONE, controlsFormFactor(FormFactor.TABLET_7, compactHeight = true))
        assertEquals(FormFactor.TABLET_10, controlsFormFactor(FormFactor.TABLET_10, compactHeight = false))
        assertEquals(FormFactor.TV, controlsFormFactor(FormFactor.TV, compactHeight = true))
        assertEquals(FormFactor.HEADUNIT, controlsFormFactor(FormFactor.HEADUNIT, compactHeight = true))
    }
}
