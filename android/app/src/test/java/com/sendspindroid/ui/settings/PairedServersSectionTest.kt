package com.sendspindroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sendspindroid.sendspin.pairing.PairedServer
import com.sendspindroid.sendspin.pairing.PairingOutcome
import com.sendspindroid.ui.theme.SendSpinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The paired-servers list, Forget and the client id in Settings > Pairing
 * (#225).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairedServersSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val kitchenId = "kitchenkitchenkitchenkitchenkitchenkitchen0"
    private val officeId = "officeofficeofficeofficeofficeofficeoffice0"
    private val kitchen = PairedServer(pskId = "psk-kitchen", serverId = kitchenId, name = "Kitchen")
    private val office = PairedServer(pskId = "psk-office", serverId = officeId, name = null)

    private val forgotten = mutableListOf<PairedServer>()

    private fun show(servers: List<PairedServer>, lastOutcome: PairingOutcome? = null) {
        composeTestRule.setContent {
            SendSpinTheme {
                PairedServersSection(
                    servers = servers,
                    lastOutcome = lastOutcome,
                    onForget = { forgotten += it }
                )
            }
        }
    }

    @Test
    fun withNoRecords_saysHowToPair() {
        show(emptyList())

        composeTestRule.onNodeWithText("No server is paired with this device.", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("paste the token", substring = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Forget").assertCountEquals(0)
    }

    @Test
    fun oneRowPerRecord_namedWhenTheNameIsKnown() {
        show(listOf(kitchen, office))

        composeTestRule.onAllNodesWithText("Forget").assertCountEquals(2)
        // A named server shows its name, with its id beneath.
        composeTestRule.onNodeWithText("Kitchen").assertIsDisplayed()
        composeTestRule.onNodeWithText(kitchenId).assertIsDisplayed()
        // An unnamed one is known by its id alone.
        composeTestRule.onAllNodesWithText(officeId).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("No server is paired", substring = true).assertCountEquals(0)
    }

    @Test
    fun aRecordWithNoServerIsStillARow() {
        show(listOf(PairedServer(pskId = "psk-old", serverId = null, name = null)))

        composeTestRule.onNodeWithText("Pairing from an older version").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Forget").assertCountEquals(1)
    }

    @Test
    fun forgetAsksFirst_andSaysItIsLocalOnly() {
        show(listOf(kitchen, office))

        composeTestRule.onAllNodesWithText("Forget")[0].performClick()

        composeTestRule.onNodeWithText("Forget Kitchen?").assertIsDisplayed()
        composeTestRule.onNodeWithText("removes the pairing on this device only", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("will not connect until you pair again", substring = true)
            .assertIsDisplayed()
        assertEquals("nothing is forgotten until confirmed", emptyList<PairedServer>(), forgotten)
    }

    @Test
    fun cancellingTheDialogForgetsNothing() {
        show(listOf(kitchen, office))
        composeTestRule.onAllNodesWithText("Forget")[0].performClick()

        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.onAllNodesWithText("Forget Kitchen?").assertCountEquals(0)
        assertEquals(emptyList<PairedServer>(), forgotten)
    }

    @Test
    fun confirmingForgetsThatRecordOnce() {
        show(listOf(kitchen, office))
        // The second row: the office server, known only by its id.
        composeTestRule.onAllNodesWithText("Forget")[1].performClick()
        composeTestRule.onNodeWithText("Forget $officeId?").assertIsDisplayed()

        // The dialog's own button, now the last "Forget" on screen.
        composeTestRule.onAllNodesWithText("Forget")[2].performClick()

        assertEquals(listOf(office), forgotten)
        composeTestRule.onAllNodesWithText("Forget $officeId?").assertCountEquals(0)
    }

    @Test
    fun theLastOutcomeIsShownAboveTheList() {
        show(
            listOf(kitchen),
            lastOutcome = PairingOutcome.Aborted("attempt_timeout", sentByUs = true, server = "Kitchen")
        )

        composeTestRule.onNodeWithText("Last pairing attempt").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pairing with Kitchen timed out", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun withNoOutcomeYet_thereIsNoOutcomeHeading() {
        show(listOf(kitchen))

        composeTestRule.onAllNodesWithText("Last pairing attempt").assertCountEquals(0)
    }

    @Test
    fun theClientIdIsShownWhole() {
        val clientId = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE"
        composeTestRule.setContent {
            SendSpinTheme { PairingClientId(clientId = clientId) }
        }

        composeTestRule.onNodeWithText("Client ID").assertIsDisplayed()
        composeTestRule.onNodeWithText(clientId).assertIsDisplayed()
    }

    @Test
    fun onANarrowScreen_aLongIdDoesNotPushForgetOffTheRow() {
        composeTestRule.setContent {
            SendSpinTheme {
                Column(modifier = Modifier.width(240.dp)) {
                    PairedServersSection(
                        servers = listOf(office),
                        lastOutcome = null,
                        onForget = { forgotten += it }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Forget").assertIsDisplayed()
    }
}
