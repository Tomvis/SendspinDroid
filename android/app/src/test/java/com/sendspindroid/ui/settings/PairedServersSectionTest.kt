package com.sendspindroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.sendspindroid.sendspin.pairing.PairedServer
import com.sendspindroid.sendspin.pairing.PairingOutcome
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
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

    /**
     * On a TV, between two other controls: the one after the list is where
     * focus goes once the list is empty, and the one before it is where focus
     * would land if it only went to the first thing that takes it.
     */
    private fun showOnTv(initial: List<PairedServer>) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides FormFactor.TV) {
                SendSpinTheme {
                    var servers by remember { mutableStateOf(initial) }
                    val afterList = remember { FocusRequester() }
                    Column {
                        Button(onClick = {}) { Text("Before the list") }
                        PairedServersSection(
                            servers = servers,
                            lastOutcome = null,
                            onForget = {
                                forgotten += it
                                servers = servers - it
                            },
                            afterListFocusRequester = afterList
                        )
                        Button(onClick = {}, modifier = Modifier.focusRequester(afterList)) {
                            Text("After the list")
                        }
                    }
                }
            }
        }
    }

    /** OK on the focused row, then Right from Cancel and OK on the dialog's Forget. */
    private fun forgetFocusedRowOnTv(rowText: String) {
        composeTestRule.onNodeWithText(rowText).performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.onNodeWithText("Cancel").performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.onNode(hasText("Forget") and hasAnyAncestor(isDialog()))
            .performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test
    fun onATv_eachRowIsOneFocusStop_andOkAsksToForget_onCancel() {
        showOnTv(listOf(kitchen, office))

        // Two rows and the controls around them; no Forget buttons of their own.
        composeTestRule.onAllNodes(isFocusable()).assertCountEquals(4)
        val row = composeTestRule.onNodeWithText("Kitchen")
        row.requestFocus()
        row.performKeyInput { pressKey(Key.DirectionCenter) }

        composeTestRule.onNodeWithText("Forget Kitchen?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsFocused()
        assertEquals(emptyList<PairedServer>(), forgotten)
    }

    @Test
    fun onATv_forgettingARowMovesFocusToTheNextOne_andPastTheListAfterTheLast() {
        showOnTv(listOf(kitchen, office))
        composeTestRule.onNodeWithText("Kitchen").requestFocus()

        forgetFocusedRowOnTv("Kitchen")
        composeTestRule.onNodeWithText(officeId).assertIsFocused()

        forgetFocusedRowOnTv(officeId)
        composeTestRule.onNodeWithText("After the list").assertIsFocused()
        assertEquals(listOf(kitchen, office), forgotten)
    }
}
