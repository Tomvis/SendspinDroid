package com.sendspindroid.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.sendspindroid.sendspin.pairing.PairingCodeFormat
import com.sendspindroid.sendspin.pairing.PairingToken
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.components.AdmissionNotice
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A TV remote can only press what has focus: the notice's button takes it
 * as each shape of the notice that has one appears.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdmissionNoticeTvTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var noticeShown by mutableStateOf(false)
    private var pairingCode by mutableStateOf<String?>(null)
    private var pairingCodeFormat by mutableStateOf(PairingCodeFormat.DIGITS)
    private var gestureRequested by mutableStateOf(false)

    /**
     * Shows the notice the way the app does: in place of what was on screen
     * when the server's admission state arrives.
     */
    private fun show(formFactor: FormFactor) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides formFactor) {
                SendSpinTheme {
                    if (noticeShown) {
                        AdmissionNotice(
                            state = AdmissionState.PAIRING,
                            serverName = "Kitchen",
                            onOpenPairingClick = {},
                            pairingCode = pairingCode,
                            pairingCodeFormat = pairingCodeFormat,
                            gestureRequested = gestureRequested
                        )
                    }
                }
            }
        }
        noticeShown = true
        composeTestRule.waitForIdle()
    }

    private fun button(label: String) = composeTestRule.onNode(hasText(label) and hasClickAction())

    @Test
    fun onATv_openSettingsHasFocus() {
        show(FormFactor.TV)

        button("Open settings").assertIsFocused()
    }

    @Test
    fun onATv_allowPairingTakesFocusWhenItReplacesTheCode() {
        pairingCode = "123456"
        show(FormFactor.TV)

        pairingCode = null
        gestureRequested = true
        composeTestRule.waitForIdle()

        button("Allow pairing").assertIsFocused()
    }

    @Test
    fun onATv_allowPairingTakesFocusWhenItReplacesAQrCode() {
        pairingCode = PairingToken.encodePairingCode(ByteArray(24) { (0xe0 + it).toByte() })
        pairingCodeFormat = PairingCodeFormat.QR_CODE
        show(FormFactor.TV)
        composeTestRule.onNodeWithContentDescription("QR code to pair this player").assertExists()

        pairingCode = null
        gestureRequested = true
        composeTestRule.waitForIdle()

        button("Allow pairing").assertIsFocused()
    }

    @Test
    fun onAPhone_theButtonIsNotFocused() {
        show(FormFactor.PHONE)

        button("Open settings").assertIsNotFocused()
    }
}
