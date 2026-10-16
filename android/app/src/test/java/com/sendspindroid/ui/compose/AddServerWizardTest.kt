package com.sendspindroid.ui.compose

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.requestFocus
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.AddServerWizardScreen
import com.sendspindroid.ui.wizard.ConnectionTestState
import com.sendspindroid.ui.wizard.DiscoveredServerUi
import com.sendspindroid.ui.wizard.WizardState
import com.sendspindroid.ui.wizard.WizardStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that AddServerWizardScreen renders the correct step content
 * as the wizard state progresses forward and backward.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerWizardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun wizard_opensOnFindServer() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // No client-type question: the first thing asked for is the server
        composeTestRule.onAllNodesWithText("Find Your Server")[0].assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Music Assistant").assertCountEquals(0)
    }

    @Test
    fun wizard_whileAdvertising_doesNotSearchAndSaysSo() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_FindServer, isSearching = false),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {},
                    searchAllowed = false
                )
            }
        }

        composeTestRule.onAllNodesWithText("Not searching", substring = true)[0].assertIsDisplayed()
        // No "searching" state and no way to start one; the address field stays.
        composeTestRule.onAllNodesWithText("Search Again").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Discovered on network").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Server Address", substring = true)[0].assertIsDisplayed()
    }

    @Test
    fun wizard_whileSearching_offersToSearchAgain() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_FindServer, isSearching = false),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        composeTestRule.onAllNodesWithText("Search Again")[0].assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Not searching", substring = true).assertCountEquals(0)
    }

    @Test
    fun wizard_findServerStep_showsFindServerTitle() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_FindServer),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // The top bar shows "Find Server" title
        composeTestRule.onAllNodesWithText("Find Your Server")[0].assertIsDisplayed()
    }

    @Test
    fun wizard_finishStep_showsSaveButton() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_Finish),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // Finish step shows "Save" button instead of "Next"
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun wizard_finishStep_showsBackButton() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_Finish),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test
    fun wizard_stepNavigation_forward_updatesContent() {
        var state by mutableStateOf(WizardState(currentStep = WizardStep.SS_FindServer))

        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = state,
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // Initially on FindServer
        composeTestRule.onAllNodesWithText("Find Your Server")[0].assertIsDisplayed()

        // Navigate forward to Finish
        state = state.copy(currentStep = WizardStep.SS_Finish)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Save Server")[0].assertIsDisplayed()
    }

    @Test
    fun wizard_stepNavigation_backward_updatesContent() {
        var state by mutableStateOf(WizardState(currentStep = WizardStep.SS_Finish))

        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = state,
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // Initially on Finish -- "Save Server" appears in both the top bar and step heading
        composeTestRule.onAllNodesWithText("Save Server")[0].assertIsDisplayed()

        // Navigate backward to FindServer
        state = state.copy(currentStep = WizardStep.SS_FindServer)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Find Your Server")[0].assertIsDisplayed()
    }

    @Test
    fun wizard_configStep_showsNextButton() {
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = WizardState(currentStep = WizardStep.SS_FindServer),
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }

        // Config steps show "Next" button
        composeTestRule.onNodeWithText("Next").assertIsDisplayed()
    }

    // ------------------------------------------------------------------------
    // System Back, a failed test's error and the name field's Done key, which
    // behave the same on every form factor
    // ------------------------------------------------------------------------

    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun showWizard(
        state: WizardState,
        onBack: () -> Unit = {},
        onClose: () -> Unit = {},
        onSave: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            SendSpinTheme {
                AddServerWizardScreen(
                    state = state,
                    onClose = onClose,
                    onBack = onBack,
                    onNext = {},
                    onSave = onSave,
                    onStepAction = {}
                )
            }
        }
    }

    @Test
    fun `system Back on Finish steps back instead of closing the wizard`() {
        var backs = 0
        var closed = false
        showWizard(WizardState(currentStep = WizardStep.SS_Finish), onBack = { backs++ }, onClose = { closed = true })

        composeTestRule.runOnIdle { backDispatcher.onBackPressed() }

        assertEquals(1, backs)
        assertFalse(closed)
    }

    @Test
    fun `system Back while testing goes back too`() {
        var backs = 0
        showWizard(WizardState(currentStep = WizardStep.SS_TestLocal), onBack = { backs++ })

        composeTestRule.runOnIdle { backDispatcher.onBackPressed() }

        assertEquals(1, backs)
    }

    @Test
    fun `system Back on the first step is left to close the wizard`() {
        showWizard(WizardState(currentStep = WizardStep.SS_FindServer))

        composeTestRule.runOnIdle { assertFalse(backDispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun `a failed test shows its error under the address`() {
        showWizard(
            WizardState(
                currentStep = WizardStep.SS_FindServer,
                localAddress = "192.168.1.100:8927",
                localTestState = ConnectionTestState.Failed("Connection timed out")
            )
        )

        composeTestRule.onNodeWithText("Connection timed out", substring = true).assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test
    fun `the address field takes typing on a phone as soon as it is focused`() {
        showWizard(WizardState(currentStep = WizardStep.SS_FindServer))

        composeTestRule.onNode(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
    }

    @Test
    fun `a server turning up does not take focus from the address being typed`() {
        var state by mutableStateOf(WizardState(currentStep = WizardStep.SS_FindServer, isSearching = true))
        composeTestRule.setContent {
            SendSpinTheme {
                AddServerWizardScreen(
                    state = state,
                    onClose = {},
                    onBack = {},
                    onNext = {},
                    onSave = {},
                    onStepAction = {}
                )
            }
        }
        composeTestRule.onNode(hasSetTextAction()).requestFocus()
        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()

        state = state.copy(discoveredServers = listOf(DiscoveredServerUi("1", "Living Room", "192.168.1.100:8927")))
        composeTestRule.waitForIdle()

        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `Done on the name field saves when Save is enabled`() {
        var saves = 0
        showWizard(
            WizardState(currentStep = WizardStep.SS_Finish, serverName = "Living Room", isNextEnabled = true),
            onSave = { saves++ }
        )

        composeTestRule.onNode(hasSetTextAction() and hasText("Living Room")).performImeAction()

        composeTestRule.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun `Done on the name field does not save while Save is disabled`() {
        var saved = false
        showWizard(
            WizardState(currentStep = WizardStep.SS_Finish, serverName = "", isNextEnabled = false),
            onSave = { saved = true }
        )

        composeTestRule.onNode(hasSetTextAction()).performImeAction()

        composeTestRule.runOnIdle { assertFalse(saved) }
    }
}
