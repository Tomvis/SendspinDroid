package com.sendspindroid.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.AddServerWizardScreen
import com.sendspindroid.ui.wizard.ConnectionTestState
import com.sendspindroid.ui.wizard.DiscoveredServerUi
import com.sendspindroid.ui.wizard.WizardState
import com.sendspindroid.ui.wizard.WizardStep
import com.sendspindroid.ui.wizard.WizardStepAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a TV each wizard step starts with focus on a control, the keyboard waits
 * for OK, and picking a discovered server moves on to Next.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerWizardTvTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val livingRoom = DiscoveredServerUi("1", "Living Room", "192.168.1.100:8927")
    private val office = DiscoveredServerUi("2", "Office", "192.168.1.101:8927")

    private var state by mutableStateOf(WizardState())

    private fun showWizard(
        initial: WizardState,
        onBack: () -> Unit = {},
        onStepAction: (WizardStepAction) -> Unit = {}
    ) {
        state = initial
        composeTestRule.setContent {
            CompositionLocalProvider(LocalFormFactor provides FormFactor.TV) {
                SendSpinTheme {
                    AddServerWizardScreen(
                        state = state,
                        onClose = {},
                        onBack = onBack,
                        onNext = {},
                        onSave = {},
                        onStepAction = onStepAction
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    // Each step has one text field: the address, or the name on Finish. While
    // it is read-only it has no SetText action, so it is found by its text.
    private fun textField() =
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    @Test
    fun `the find-server step starts on the address field and holds the keyboard until OK`() {
        showWizard(WizardState(isSearching = true, isNextEnabled = false))

        textField().assertIsFocused()
        // Read-only, so focus alone opens no keyboard
        textField().assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))

        textField().performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        textField().assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
    }

    @Test
    fun `the find-server step starts on the first discovered server`() {
        showWizard(WizardState(discoveredServers = listOf(livingRoom, office), isSearching = true, isNextEnabled = false))

        composeTestRule.onNodeWithText("Living Room").assertIsFocused()
    }

    @Test
    fun `a server found while the address field is untouched takes the focus`() {
        showWizard(WizardState(isSearching = true, isNextEnabled = false))
        textField().assertIsFocused()

        state = state.copy(discoveredServers = listOf(livingRoom))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Living Room").assertIsFocused()
    }

    @Test
    fun `a discovered server that goes away while focused gives focus to the address field`() {
        showWizard(WizardState(discoveredServers = listOf(livingRoom), isSearching = true, isNextEnabled = false))
        composeTestRule.onNodeWithText("Living Room").assertIsFocused()

        state = state.copy(discoveredServers = emptyList())
        composeTestRule.waitForIdle()

        textField().assertIsFocused()
    }

    @Test
    fun `picking a discovered server marks it and moves focus to Next`() {
        showWizard(
            WizardState(discoveredServers = listOf(livingRoom, office), isSearching = true, isNextEnabled = false),
            onStepAction = { action ->
                if (action is WizardStepAction.SelectDiscoveredServer) {
                    state = state.copy(localAddress = action.server.address, isNextEnabled = true)
                }
            }
        )

        composeTestRule.onNodeWithText("Living Room").performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Living Room").assertIsSelected()
        composeTestRule.onNodeWithText("Next").assertIsFocused()
    }

    @Test
    fun `after a failed test the step starts on the address, under which the error is shown`() {
        showWizard(
            WizardState(
                localAddress = "192.168.1.100:8927",
                discoveredServers = listOf(livingRoom),
                isSearching = true,
                localTestState = ConnectionTestState.Failed("Connection timed out")
            )
        )

        textField().assertIsFocused()
        composeTestRule.onNodeWithText("Connection timed out", substring = true).assertIsDisplayed()
    }

    @Test
    fun `after a failed test the address takes focus even when servers have pushed it out of view`() {
        val many = (1..12).map { DiscoveredServerUi("$it", "Server $it", "192.168.1.$it:8927") }
        showWizard(
            WizardState(
                localAddress = "192.168.1.100:8927",
                discoveredServers = many,
                isSearching = true,
                localTestState = ConnectionTestState.Failed("Connection timed out")
            )
        )

        textField().assertIsFocused()
        composeTestRule.onNodeWithText("Connection timed out", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the finish step starts on Save when the name is filled`() {
        showWizard(WizardState(currentStep = WizardStep.SS_Finish, serverName = "Living Room", isNextEnabled = true))

        composeTestRule.onNodeWithText("Save").assertIsFocused()
    }

    @Test
    fun `the finish step starts on the name field when it is blank`() {
        showWizard(WizardState(currentStep = WizardStep.SS_Finish, serverName = "", isNextEnabled = false))

        textField().assertIsFocused()
    }

    @Test
    fun `focus moves to the new step when the step changes`() {
        showWizard(WizardState(isSearching = true, isNextEnabled = false))
        textField().assertIsFocused()

        state = state.copy(currentStep = WizardStep.SS_Finish, serverName = "Living Room", isNextEnabled = true)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save").assertIsFocused()
    }

    @Test
    fun `the testing step starts on Cancel, which steps back`() {
        var backs = 0
        showWizard(WizardState(currentStep = WizardStep.SS_TestLocal), onBack = { backs++ })

        val cancel = composeTestRule.onNodeWithText("Cancel")
        cancel.assertIsFocused()
        cancel.performKeyInput { pressKey(Key.DirectionCenter) }

        composeTestRule.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `the title is shown once and the top bar has no back arrow`() {
        showWizard(WizardState(isSearching = true, isNextEnabled = false))

        composeTestRule.onAllNodesWithText("Find Your Server").assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription("Back").assertCountEquals(0)
    }
}
