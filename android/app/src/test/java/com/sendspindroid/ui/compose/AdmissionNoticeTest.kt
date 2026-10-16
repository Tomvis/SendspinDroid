package com.sendspindroid.ui.compose

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.sendspindroid.sendspin.pairing.PairingCodeFormat
import com.sendspindroid.sendspin.pairing.PairingToken
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.ui.main.components.AdmissionNotice
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pairing attempt that did not finish is explained on the main screen,
 * where the user is while the server waits (#225).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdmissionNoticeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val failure = "Pairing with Kitchen timed out."

    private fun show(
        state: AdmissionState,
        lastFailure: String?,
        pairingCode: String? = null,
        gestureRequested: Boolean = false,
        pairingCodeFormat: PairingCodeFormat = PairingCodeFormat.DIGITS,
    ) {
        composeTestRule.setContent {
            SendSpinTheme {
                AdmissionNotice(
                    state = state,
                    serverName = "Kitchen",
                    onOpenPairingClick = {},
                    pairingCode = pairingCode,
                    pairingCodeFormat = pairingCodeFormat,
                    gestureRequested = gestureRequested,
                    lastFailure = lastFailure
                )
            }
        }
    }

    /** The spec's reference token for the code 0xe0..0xf7. */
    private val qrToken = PairingToken.encodePairingCode(ByteArray(24) { (0xe0 + it).toByte() })

    @Test
    fun aQrCodeEmissionIsShownAsAQrCodeOnly() {
        // pairing.md, "QR-code emission": the token is "rendered as a QR code
        // on its display". The owner chose the QR alone, with no token text.
        show(AdmissionState.PAIRING, null, pairingCode = qrToken, pairingCodeFormat = PairingCodeFormat.QR_CODE)

        composeTestRule.onNodeWithText("Scan this code on the server").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan this code with Kitchen to pair this player.").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("QR code to pair this player").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(qrToken, substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Enter this code on the server").assertCountEquals(0)
    }

    @Test
    fun theQrCodesDescriptionSaysWhatItIs_withoutTheToken() {
        // A description is read aloud, and the token is the pairing secret.
        show(AdmissionState.PAIRING, null, pairingCode = qrToken, pairingCodeFormat = PairingCodeFormat.QR_CODE)

        val description = composeTestRule
            .onNodeWithContentDescription("QR code to pair this player")
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()
        assertFalse(description, qrToken in description || qrToken.removePrefix("SP:1") in description)
    }

    @Test
    fun digitsAreStillShownAsDigits() {
        show(AdmissionState.PAIRING, null, pairingCode = "123456")

        composeTestRule.onNodeWithText("Enter this code on the server").assertIsDisplayed()
        composeTestRule.onNodeWithText("123-456").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("QR code to pair this player").assertCountEquals(0)
    }

    @Test
    fun aNewAttemptsQrCodeIsNotShownUnderTheLastOnesFailure() {
        show(AdmissionState.PAIRING, failure, pairingCode = qrToken, pairingCodeFormat = PairingCodeFormat.QR_CODE)

        composeTestRule.onNodeWithText("Scan this code on the server").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(failure).assertCountEquals(0)
    }

    @Test
    fun whileWaitingToPair_theLastFailureIsShownWithTheGuidance() {
        show(AdmissionState.PAIRING, failure)

        composeTestRule.onNodeWithText(failure).assertIsDisplayed()
        composeTestRule.onNodeWithText("Pair this player").assertIsDisplayed()
    }

    @Test
    fun withNoFailure_theNoticeIsAsItWas() {
        show(AdmissionState.PAIRING, null)

        composeTestRule.onNodeWithText("Pair this player").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(failure).assertCountEquals(0)
    }

    @Test
    fun aServerThatGaveUpOnPairingStillShowsWhy() {
        // After an abort the server drops back to waiting for approval.
        show(AdmissionState.AWAITING_APPROVAL, failure)

        composeTestRule.onNodeWithText(failure).assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for approval").assertIsDisplayed()
    }

    @Test
    fun aNewAttemptsCodeIsNotShownUnderTheLastOnesFailure() {
        // "Start pairing again on the server" over a code the server is
        // waiting for would send the user to restart an attempt in progress.
        show(AdmissionState.PAIRING, failure, pairingCode = "123456")

        composeTestRule.onNodeWithText("Enter this code on the server").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(failure).assertCountEquals(0)
    }

    @Test
    fun aNewAttemptsGesturePromptIsNotShownUnderTheLastOnesFailure() {
        show(AdmissionState.PAIRING, failure, gestureRequested = true)

        composeTestRule.onAllNodesWithText("Allow pairing").assertCountEquals(2)
        composeTestRule.onAllNodesWithText(failure).assertCountEquals(0)
    }
}
