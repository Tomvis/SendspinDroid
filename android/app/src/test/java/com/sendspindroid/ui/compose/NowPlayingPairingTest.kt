package com.sendspindroid.ui.compose

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.sendspin.pairing.PairingCodeFormat
import com.sendspindroid.sendspin.pairing.PairingToken
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.NowPlayingScreen
import com.sendspindroid.ui.main.PlaybackState
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pairing code, and the Allow pairing prompt, are shown while pairing runs
 * alongside playback (#220). "A server/activate that adds 'pairing' to
 * activities does not by itself affect active_roles" (pairing.md, Entering
 * and leaving pairing), so an approved player keeps its roles, and its
 * admission state stays READY, through the whole attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingPairingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val viewModel = MainActivityViewModel(ApplicationProvider.getApplicationContext<Application>())

    private var allowPairingClicks = 0

    /** Connected and playing, with roles live: the server admitted the player. */
    private fun show(formFactor: FormFactor = FormFactor.PHONE) {
        viewModel.updateConnectionState(AppConnectionState.Connected("Kitchen", "192.168.1.4:8927"))
        viewModel.updatePlaybackState(true, PlaybackState.READY)
        viewModel.updateMetadata("Highway Star", "Deep Purple", "Machine Head", null, null)
        viewModel.updateAdmissionState(AdmissionState.READY)
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
                        onAllowPairingClick = { allowPairingClicks++ },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun update(change: MainActivityViewModel.() -> Unit) {
        composeTestRule.runOnUiThread { viewModel.change() }
        composeTestRule.waitForIdle()
    }

    private fun button(label: String) = composeTestRule.onNode(hasText(label) and hasClickAction())

    private fun assertNowPlaying() {
        composeTestRule.onNodeWithText("Highway Star").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enter this code on the server").assertDoesNotExist()
        button("Allow pairing").assertDoesNotExist()
    }

    private fun assertCodeShown() {
        composeTestRule.onNodeWithText("Enter this code on the server").assertIsDisplayed()
        composeTestRule.onNodeWithText("123-456").assertIsDisplayed()
        composeTestRule.onNodeWithText("Highway Star").assertDoesNotExist()
    }

    @Test
    fun `a code emitted alongside playback is shown in place of Now Playing`() {
        show()
        assertNowPlaying()

        update { updatePairingCode("123456") }

        assertCodeShown()
    }

    @Test
    fun `a qr_code emission alongside playback is shown as a QR code in place of Now Playing`() {
        val token = PairingToken.encodePairingCode(ByteArray(24) { (0xe0 + it).toByte() })
        show()

        update { updatePairingCode(token, PairingCodeFormat.QR_CODE) }

        composeTestRule.onNodeWithText("Scan this code on the server").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("QR code to pair this player").assertIsDisplayed()
        composeTestRule.onNodeWithText(token, substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Highway Star").assertDoesNotExist()

        update { updatePairingCode(null) }
        assertNowPlaying()
    }

    @Test
    fun `Allow pairing alongside playback is shown in place of Now Playing, and works`() {
        show()

        update { updatePairingGestureRequested(true) }

        composeTestRule.onNodeWithText("Highway Star").assertDoesNotExist()
        button("Allow pairing").assertIsDisplayed().performClick()
        assertEquals(1, allowPairingClicks)
    }

    @Test
    fun `Now Playing returns when the attempt ends`() {
        show()
        update { updatePairingCode("123456") }
        assertCodeShown()

        // Every end of the attempt clears the code and the gesture together.
        update {
            updatePairingCode(null)
            updatePairingGestureRequested(false)
        }

        assertNowPlaying()
    }

    @Test
    fun `Now Playing shows between the Allow pairing tap and the code`() {
        show()
        update { updatePairingGestureRequested(true) }

        // The service clears the gesture at the tap, but the code comes only
        // with server/pair-init, the reply to the client/pair-init the tap
        // sends. For that round trip neither is up.
        update { updatePairingGestureRequested(false) }
        assertNowPlaying()

        update { updatePairingCode("123456") }
        assertCodeShown()
    }

    @Test
    fun `on a head unit the code is shown in place of Now Playing, which returns after`() {
        show(FormFactor.HEADUNIT)
        assertNowPlaying()

        update { updatePairingCode("123456") }
        assertCodeShown()

        update { updatePairingCode(null) }
        assertNowPlaying()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    fun `on a TV Allow pairing takes focus, and Now Playing returns after`() {
        // Fork: the TV Now Playing surface has no on-screen transport
        // controls (the remote's media keys drive playback), so there is no
        // Pause button to hold or regain focus.
        show(FormFactor.TV)
        assertNowPlaying()

        update { updatePairingGestureRequested(true) }
        button("Allow pairing").assertIsFocused()

        update { updatePairingGestureRequested(false) }
        assertNowPlaying()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land")
    fun `on a TV Now Playing returns after a code`() {
        show(FormFactor.TV)

        update { updatePairingCode("123456") }
        assertCodeShown()

        update { updatePairingCode(null) }
        assertNowPlaying()
    }
}
