package com.sendspindroid.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.ui.wizard.ConnectionTestState
import com.sendspindroid.ui.wizard.DiscoveredServerUi
import com.sendspindroid.ui.wizard.WizardState
import com.sendspindroid.ui.wizard.WizardStep
import com.sendspindroid.ui.wizard.WizardStepAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

/**
 * ViewModel for the Add Server Wizard Activity.
 *
 * Implements the state machine for the wizard flow:
 *
 *   SS_FindServer -> SS_TestLocal -> SS_Finish
 */
class AddServerWizardViewModel : ViewModel() {

    // Current wizard step
    private val _currentStep = MutableStateFlow(WizardStep.SS_FindServer)
    val currentStep: StateFlow<WizardStep> = _currentStep.asStateFlow()

    // Connection test state
    private val _localTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val localTestState: StateFlow<ConnectionTestState> = _localTestState.asStateFlow()

    // Discovered servers from mDNS
    private val _discoveredServers = MutableStateFlow<List<DiscoveredServerUi>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServerUi>> = _discoveredServers.asStateFlow()

    // Whether mDNS discovery is in progress
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // ========================================================================
    // Server Data Fields (reactive for Compose)
    // ========================================================================

    private val _serverName = MutableStateFlow("")
    var serverName: String
        get() = _serverName.value
        set(value) { _serverName.value = value }

    private val _setAsDefault = MutableStateFlow(false)
    var setAsDefault: Boolean
        get() = _setAsDefault.value
        set(value) { _setAsDefault.value = value }

    // Local connection
    private val _localAddress = MutableStateFlow("")
    var localAddress: String
        get() = _localAddress.value
        set(value) { _localAddress.value = value }

    // Discovered server info (pre-filled from mDNS)
    var discoveredServerName: String? = null
    var discoveredServerAddress: String? = null

    // Editing mode
    private val _editingServer = MutableStateFlow<UnifiedServer?>(null)
    var editingServer: UnifiedServer?
        get() = _editingServer.value
        private set(value) { _editingServer.value = value }

    var isLoading: Boolean = false

    // ========================================================================
    // Combined Wizard State (for Compose)
    // ========================================================================

    val wizardState: StateFlow<WizardState> = combine(
        _currentStep,
        _localTestState,
        _localAddress,
        _serverName,
        _discoveredServers,
        _isSearching,
        _setAsDefault
    ) { values ->
        val step = values[0] as WizardStep
        val localTest = values[1] as ConnectionTestState
        val localAddr = values[2] as String
        val name = values[3] as String
        @Suppress("UNCHECKED_CAST")
        val discovered = values[4] as List<DiscoveredServerUi>
        val searching = values[5] as Boolean
        val isDefaultVal = values[6] as Boolean

        WizardState(
            currentStep = step,
            isEditMode = _editingServer.value != null,
            isNextEnabled = computeNextEnabled(step),
            serverName = name,
            setAsDefault = isDefaultVal,
            connectionSummary = getConnectionMethodSummary(),
            localAddress = localAddr,
            discoveredServers = discovered,
            isSearching = searching,
            localTestState = localTest
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WizardState()
    )

    /**
     * Compute whether the "Next" button should be enabled based on current step.
     */
    private fun computeNextEnabled(step: WizardStep): Boolean {
        return when (step) {
            // FindServer step — needs an address
            WizardStep.SS_FindServer -> _localAddress.value.isNotBlank()

            // Testing step — disabled (auto-advance)
            WizardStep.SS_TestLocal -> false

            // Finish step — needs a name and at least one connection
            WizardStep.SS_Finish -> _serverName.value.isNotBlank() && hasValidConnectionMethod()
        }
    }

    // ========================================================================
    // Navigation Methods
    // ========================================================================

    /**
     * Navigate to a specific step. Used for programmatic navigation.
     */
    fun navigateTo(step: WizardStep) {
        _currentStep.value = step
    }

    /**
     * Handle "Next" action based on current step.
     * Returns true if navigation succeeded.
     */
    fun onNext(): Boolean {
        return when (_currentStep.value) {
            WizardStep.SS_FindServer -> {
                if (localAddress.isBlank()) return false
                _currentStep.value = WizardStep.SS_TestLocal
                true
            }
            WizardStep.SS_TestLocal -> true // Handled by test completion
            WizardStep.SS_Finish -> true // Handled by Activity save
        }
    }

    /**
     * Handle "Back" action based on current step.
     * Returns the previous step, or null if at the beginning (close wizard).
     */
    fun onBack(): WizardStep? {
        val previous = when (_currentStep.value) {
            WizardStep.SS_FindServer -> null // Close wizard
            WizardStep.SS_TestLocal -> WizardStep.SS_FindServer
            WizardStep.SS_Finish -> WizardStep.SS_FindServer
        }
        previous?.let { _currentStep.value = it }
        return previous
    }

    // ========================================================================
    // Connection Testing
    // ========================================================================

    /**
     * Called when local connection test completes successfully.
     */
    fun onLocalTestSuccess(message: String = "Connection successful") {
        _localTestState.value = ConnectionTestState.Success(message)

        when (_currentStep.value) {
            WizardStep.SS_TestLocal -> _currentStep.value = WizardStep.SS_Finish
            else -> { /* Unexpected */ }
        }
    }

    /**
     * Called when local connection test fails.
     */
    fun onLocalTestFailed(error: String) {
        _localTestState.value = ConnectionTestState.Failed(error)

        when (_currentStep.value) {
            WizardStep.SS_TestLocal -> _currentStep.value = WizardStep.SS_FindServer
            else -> { /* Unexpected */ }
        }
    }

    fun resetLocalTest() {
        _localTestState.value = ConnectionTestState.Idle
    }

    // ========================================================================
    // Edit Mode
    // ========================================================================

    /**
     * Initialize the ViewModel for editing an existing server.
     * Opens on the Finish step.
     */
    fun initForEdit(server: UnifiedServer) {
        editingServer = server
        serverName = server.name
        setAsDefault = server.isDefaultServer

        server.local?.let {
            localAddress = it.address
        }

        _currentStep.value = WizardStep.SS_Finish
    }

    val isEditMode: Boolean
        get() = editingServer != null

    fun getServerId(): String {
        return editingServer?.id ?: com.sendspindroid.UnifiedServerRepository.generateId()
    }

    // ========================================================================
    // Validation Helpers
    // ========================================================================

    fun hasValidConnectionMethod(): Boolean {
        return localAddress.isNotBlank()
    }

    fun getConnectionMethodSummary(): List<String> {
        val methods = mutableListOf<String>()

        if (localAddress.isNotBlank()) {
            methods.add("Local: $localAddress")
        }

        return methods
    }

    // ========================================================================
    // Step Action Handling (for Compose)
    // ========================================================================

    /**
     * Handle step-specific actions from the Compose UI.
     * Returns true if the action requires additional handling by the Activity.
     */
    fun handleStepAction(action: WizardStepAction): Boolean {
        return when (action) {
            // Find server step
            is WizardStepAction.UpdateLocalAddress -> {
                localAddress = action.address
                false
            }
            is WizardStepAction.SelectDiscoveredServer -> {
                applyDiscoveredServer(action.server.name, action.server.address)
                false
            }
            WizardStepAction.StartDiscovery -> {
                true // Activity should start mDNS discovery
            }
            WizardStepAction.RetryLocalTest -> {
                resetLocalTest()
                when (_currentStep.value) {
                    WizardStep.SS_TestLocal -> _currentStep.value = WizardStep.SS_FindServer
                    else -> { /* Unexpected */ }
                }
                false
            }

            // Finish step
            is WizardStepAction.UpdateServerName -> {
                serverName = action.name
                false
            }
            is WizardStepAction.UpdateSetAsDefault -> {
                setAsDefault = action.isDefault
                false
            }
        }
    }

    // ========================================================================
    // Discovery
    // ========================================================================

    fun updateDiscoveredServers(servers: List<DiscoveredServerUi>) {
        _discoveredServers.value = servers
    }

    fun setSearching(searching: Boolean) {
        _isSearching.value = searching
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    fun applyDiscoveredServer(name: String, address: String) {
        discoveredServerName = name
        discoveredServerAddress = address
        serverName = name
        localAddress = address
    }

    fun clear() {
        _serverName.value = ""
        _setAsDefault.value = false
        _localAddress.value = ""
        _editingServer.value = null
        _discoveredServers.value = emptyList()
        _isSearching.value = false

        discoveredServerName = null
        discoveredServerAddress = null
        isLoading = false

        _currentStep.value = WizardStep.SS_FindServer
        _localTestState.value = ConnectionTestState.Idle
    }
}
