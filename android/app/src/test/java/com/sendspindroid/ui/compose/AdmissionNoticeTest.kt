package com.sendspindroid.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.ui.main.components.AdmissionNotice
import com.sendspindroid.ui.theme.SendSpinTheme
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
    ) {
        composeTestRule.setContent {
            SendSpinTheme {
                AdmissionNotice(
                    state = state,
                    serverName = "Kitchen",
                    onOpenPairingClick = {},
                    pairingCode = pairingCode,
                    gestureRequested = gestureRequested,
                    lastFailure = lastFailure
                )
            }
        }
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
