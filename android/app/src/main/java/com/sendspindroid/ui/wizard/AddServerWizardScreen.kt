package com.sendspindroid.ui.wizard

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.TvOverscanHorizontal
import com.sendspindroid.ui.adaptive.TvOverscanVertical
import com.sendspindroid.ui.adaptive.tvFocusRing
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
    val isTv = LocalFormFactor.current == FormFactor.TV

    // In landscape the keyboard leaves a strip of the screen. The title bar
    // would take half of it, so it steps aside while the keyboard is up; the
    // step shows its own title and the system Back still works. A TV keeps
    // it: there the steps do not repeat the title.
    val typingInLandscape = !isTv && WindowInsets.isImeVisible &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // System Back steps back as the Back button does, and from the testing
    // step abandons the test; only from the first step does it close.
    BackHandler(enabled = state.currentStep != WizardStep.SS_FindServer, onBack = onBack)

    // A remote can only move focus from one control to the next, so on a TV
    // each step starts with focus on one. Leaving a step takes its focused
    // control away, so this is done again on every step change.
    val primaryButtonFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val firstServerFocus = remember { FocusRequester() }
    val testingButtonFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    // The find-server step is a lazy list, which composes only what is in
    // view, and a FocusRequester with nothing composed behind it does
    // nothing. So on a TV its scroll is kept here, to bring an item into
    // view first.
    val findServerListState = rememberLazyListState()
    var focusNextOnceEnabled by remember { mutableStateOf(false) }
    if (isTv) {
        val currentState by rememberUpdatedState(state)
        LaunchedEffect(state.currentStep) {
            // The new step is laid out in the next frame
            withFrameNanos { }
            val now = currentState
            when (now.currentStep) {
                // The first server found, unless a failed test has left its
                // error under the address field, which is near the end
                WizardStep.SS_FindServer ->
                    if (now.localTestState !is ConnectionTestState.Failed &&
                        searchAllowed && now.discoveredServers.isNotEmpty()
                    ) {
                        findServerListState.scrollToItem(0)
                        firstServerFocus
                    } else {
                        val last = findServerListState.layoutInfo.totalItemsCount - 1
                        findServerListState.scrollToItem(last.coerceAtLeast(0))
                        addressFocus
                    }
                WizardStep.SS_TestLocal -> testingButtonFocus
                // Save once it can be pressed, else the name it waits for
                WizardStep.SS_Finish ->
                    if (now.isNextEnabled) primaryButtonFocus else nameFocus
            }.requestFocus()
        }

        // Next is enabled by the address a picked server fills in, which
        // comes back from the view model a frame or two later.
        LaunchedEffect(focusNextOnceEnabled, state.isNextEnabled) {
            if (focusNextOnceEnabled && state.isNextEnabled) {
                primaryButtonFocus.requestFocus()
                focusNextOnceEnabled = false
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            if (!typingInLandscape) TopAppBar(
                title = { Text(getStepTitle(state.currentStep)) },
                modifier = Modifier
                    .tvColumn(isTv)
                    .then(if (isTv) Modifier.padding(top = TvOverscanVertical) else Modifier),
                navigationIcon = {
                    // On a TV the remote's Back key steps back; an arrow
                    // there would be one more stop for the D-pad, and one
                    // that closes the wizard.
                    if (!isTv) {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.wizard_back)
                            )
                        }
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
                isNextEnabled = state.isNextEnabled,
                isTv = isTv,
                primaryButtonFocusRequester = primaryButtonFocus
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .tvColumn(isTv)
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
                    onBack = onBack,
                    onSave = onSave,
                    onServerSelected = {
                        onStepAction(WizardStepAction.SelectDiscoveredServer(it))
                        if (isTv) focusNextOnceEnabled = true
                    },
                    searchAllowed = searchAllowed,
                    findServerListState = if (isTv) findServerListState else null,
                    addressFocusRequester = addressFocus,
                    firstServerFocusRequester = firstServerFocus,
                    testingButtonFocusRequester = testingButtonFocus,
                    nameFocusRequester = nameFocus
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
    onBack: () -> Unit,
    onSave: () -> Unit,
    onServerSelected: (DiscoveredServerUi) -> Unit,
    searchAllowed: Boolean,
    findServerListState: LazyListState?,
    addressFocusRequester: FocusRequester,
    firstServerFocusRequester: FocusRequester,
    testingButtonFocusRequester: FocusRequester,
    nameFocusRequester: FocusRequester
) {
    when (step) {
        WizardStep.SS_FindServer -> FindServerStep(
            discoveredServers = state.discoveredServers,
            localAddress = state.localAddress,
            isSearching = state.isSearching,
            onAddressChange = { onStepAction(WizardStepAction.UpdateLocalAddress(it)) },
            onServerSelected = onServerSelected,
            onStartSearch = { onStepAction(WizardStepAction.StartDiscovery) },
            onSubmit = onNext,
            searchAllowed = searchAllowed,
            error = (state.localTestState as? ConnectionTestState.Failed)?.error,
            listState = findServerListState ?: rememberLazyListState(),
            addressFocusRequester = addressFocusRequester,
            firstServerFocusRequester = firstServerFocusRequester
        )
        WizardStep.SS_TestLocal -> TestingStep(
            testState = state.localTestState,
            onRetry = { onStepAction(WizardStepAction.RetryLocalTest) },
            onCancel = onBack,
            buttonFocusRequester = testingButtonFocusRequester
        )
        WizardStep.SS_Finish -> FinishStep(
            serverName = state.serverName,
            isDefault = state.setAsDefault,
            connectionSummary = state.connectionSummary,
            onNameChange = { onStepAction(WizardStepAction.UpdateServerName(it)) },
            onDefaultChange = { onStepAction(WizardStepAction.UpdateSetAsDefault(it)) },
            saveEnabled = state.isNextEnabled,
            onSave = onSave,
            nameFocusRequester = nameFocusRequester
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
    isTv: Boolean,
    primaryButtonFocusRequester: FocusRequester,
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
                .tvColumn(isTv)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .then(if (isTv) Modifier.padding(bottom = TvOverscanVertical) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.tvFocusRing(shape = ButtonDefaults.outlinedShape)
            ) {
                Text(stringResource(R.string.wizard_back))
            }

            Spacer(modifier = Modifier.weight(1f))

            // Next/Save button
            Button(
                onClick = if (isFinalStep) onSave else onNext,
                enabled = isNextEnabled,
                modifier = Modifier.tvFocusRing(primaryButtonFocusRequester, ButtonDefaults.shape)
            ) {
                Text(
                    if (isFinalStep) stringResource(R.string.wizard_save)
                    else stringResource(R.string.wizard_next)
                )
            }
        }
    }
}

/** Width of the wizard's column on a TV. */
private val TvColumnWidth = 640.dp

/**
 * On a TV, narrows to a [TvColumnWidth] column in the middle of the screen,
 * inside its safe area. The phone layout stretched across 960dp puts Back and
 * Next, or a label and its field, too far apart to read as one form.
 */
private fun Modifier.tvColumn(isTv: Boolean): Modifier =
    if (isTv) {
        fillMaxWidth()
            .padding(horizontal = TvOverscanHorizontal)
            .wrapContentWidth()
            .widthIn(max = TvColumnWidth)
    } else {
        this
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
