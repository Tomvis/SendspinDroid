package com.sendspindroid.ui.wizard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.steps.FindServerStep
import com.sendspindroid.ui.wizard.steps.FinishStep
import com.sendspindroid.ui.wizard.steps.TestingStep
import android.content.res.Configuration
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Main Add Server Wizard screen that hosts all wizard steps.
 * Uses animated content to transition between steps with slide animations.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddServerWizardScreen(
    state: WizardState,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
    onStepAction: (WizardStepAction) -> Unit,
    modifier: Modifier = Modifier,
    searchAllowed: Boolean = true,
) {
    // In landscape the keyboard leaves a strip of the screen. The title bar
    // would take half of it, so it steps aside while the keyboard is up; the
    // step shows its own title and the system Back still works.
    val typingInLandscape = WindowInsets.isImeVisible &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            if (!typingInLandscape) TopAppBar(
                title = { Text(getStepTitle(state.currentStep)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.wizard_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            WizardBottomBar(
                step = state.currentStep,
                onBack = onBack,
                onNext = onNext,
                onSave = onSave,
                isNextEnabled = state.isNextEnabled
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Progress indicator
            LinearProgressIndicator(
                progress = { getStepProgress(state.currentStep) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Animated step content
            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally { width -> width * direction } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> -width * direction } + fadeOut()
                    )
                },
                label = "wizard_step_transition",
                modifier = Modifier.fillMaxSize()
            ) { step ->
                WizardStepContent(
                    step = step,
                    state = state,
                    onStepAction = onStepAction,
                    onNext = { if (state.isNextEnabled) onNext() },
                    searchAllowed = searchAllowed
                )
            }
        }
    }
}

/**
 * Renders the content for the current wizard step.
 */
@Composable
private fun WizardStepContent(
    step: WizardStep,
    state: WizardState,
    onStepAction: (WizardStepAction) -> Unit,
    onNext: () -> Unit,
    searchAllowed: Boolean
) {
    when (step) {
        WizardStep.SS_FindServer -> FindServerStep(
            discoveredServers = state.discoveredServers,
            localAddress = state.localAddress,
            isSearching = state.isSearching,
            onAddressChange = { onStepAction(WizardStepAction.UpdateLocalAddress(it)) },
            onServerSelected = { onStepAction(WizardStepAction.SelectDiscoveredServer(it)) },
            onStartSearch = { onStepAction(WizardStepAction.StartDiscovery) },
            onSubmit = onNext,
            searchAllowed = searchAllowed
        )
        WizardStep.SS_TestLocal -> TestingStep(
            testState = state.localTestState,
            onRetry = { onStepAction(WizardStepAction.RetryLocalTest) }
        )
        WizardStep.SS_Finish -> FinishStep(
            serverName = state.serverName,
            isDefault = state.setAsDefault,
            connectionSummary = state.connectionSummary,
            onNameChange = { onStepAction(WizardStepAction.UpdateServerName(it)) },
            onDefaultChange = { onStepAction(WizardStepAction.UpdateSetAsDefault(it)) }
        )
    }
}

/**
 * Bottom bar with Back and Next/Save buttons.
 *
 * The testing step has no buttons (auto-advance).
 * The finish step shows Back + Save. The find-server step shows Back + Next.
 */
@Composable
private fun WizardBottomBar(
    step: WizardStep,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
    isNextEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    // Testing step — no bottom bar (auto-advance on completion)
    val isTestingStep = step == WizardStep.SS_TestLocal

    // Finish/save step
    val isFinalStep = step == WizardStep.SS_Finish

    // Hide bottom bar entirely for the testing step
    if (isTestingStep) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.wizard_back))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Next/Save button
            Button(
                onClick = if (isFinalStep) onSave else onNext,
                enabled = isNextEnabled
            ) {
                Text(
                    if (isFinalStep) stringResource(R.string.wizard_save)
                    else stringResource(R.string.wizard_next)
                )
            }
        }
    }
}

/**
 * Returns the title for the given wizard step.
 */
@Composable
private fun getStepTitle(step: WizardStep): String {
    return when (step) {
        WizardStep.SS_FindServer -> stringResource(R.string.wizard_find_server_title)
        WizardStep.SS_TestLocal -> stringResource(R.string.wizard_testing_title)
        WizardStep.SS_Finish -> stringResource(R.string.wizard_save_title)
    }
}

/**
 * Returns the progress (0-1) for the given wizard step.
 */
private fun getStepProgress(step: WizardStep): Float {
    return when (step) {
        WizardStep.SS_FindServer -> 0.33f
        WizardStep.SS_TestLocal -> 0.50f
        WizardStep.SS_Finish -> 1.0f
    }
}

// ============================================================================
// State and Actions
// ============================================================================

/**
 * Complete state for the wizard.
 */
data class WizardState(
    val currentStep: WizardStep = WizardStep.SS_FindServer,
    val isEditMode: Boolean = false,
    val isNextEnabled: Boolean = true,

    // Server data
    val serverName: String = "",
    val setAsDefault: Boolean = false,

    // Connection summary (for the Finish step)
    val connectionSummary: List<String> = emptyList(),

    // Local connection
    val localAddress: String = "",
    val discoveredServers: List<DiscoveredServerUi> = emptyList(),
    val isSearching: Boolean = false,
    val localTestState: ConnectionTestState = ConnectionTestState.Idle
)

/**
 * Discovered server UI model.
 */
data class DiscoveredServerUi(
    val id: String,
    val name: String,
    val address: String
)

/**
 * Actions that can be triggered from wizard steps.
 */
sealed class WizardStepAction {
    // Find server step
    data class UpdateLocalAddress(val address: String) : WizardStepAction()
    data class SelectDiscoveredServer(val server: DiscoveredServerUi) : WizardStepAction()
    data object StartDiscovery : WizardStepAction()
    data object RetryLocalTest : WizardStepAction()

    // Finish step
    data class UpdateServerName(val name: String) : WizardStepAction()
    data class UpdateSetAsDefault(val isDefault: Boolean) : WizardStepAction()
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true)
@Composable
private fun WizardFindServerPreview() {
    SendSpinTheme {
        AddServerWizardScreen(
            state = WizardState(
                currentStep = WizardStep.SS_FindServer,
                discoveredServers = listOf(
                    DiscoveredServerUi("1", "Living Room", "192.168.1.100:8927"),
                    DiscoveredServerUi("2", "Office", "192.168.1.101:8927")
                ),
                isSearching = true
            ),
            onClose = {},
            onBack = {},
            onNext = {},
            onSave = {},
            onStepAction = {}
        )
    }
}
