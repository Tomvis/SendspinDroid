package com.sendspindroid.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.MaCredentials
import com.sendspindroid.musicassistant.MaEndpoint
import com.sendspindroid.musicassistant.MaSettings
import com.sendspindroid.musicassistant.testMaAuth
import com.sendspindroid.musicassistant.transport.MaApiTransport
import com.sendspindroid.network.WebSocketUrlBuilder
import com.sendspindroid.ui.wizard.ClientMode
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
import kotlinx.coroutines.launch
import com.sendspindroid.musicassistant.transport.MaTransportException
import java.io.IOException

/**
 * ViewModel for the Add Server Wizard Activity.
 *
 * Implements a branching state machine for the wizard flow:
 *
 * SendSpin path:
 *   ClientType → SS_FindServer → SS_TestLocal → SS_Finish
 *
 * MA local path:
 *   ClientType → MA_NetworkQuestion → MA_FindServer → MA_TestLocal →
 *   MA_Login -> MA_Finish
 */
class AddServerWizardViewModel : ViewModel() {

    // Current wizard step
    private val _currentStep = MutableStateFlow(WizardStep.ClientType)
    val currentStep: StateFlow<WizardStep> = _currentStep.asStateFlow()

    // Client mode (SendSpin vs Music Assistant)
    private val _clientMode = MutableStateFlow(ClientMode.SENDSPIN)
    var clientMode: ClientMode
        get() = _clientMode.value
        set(value) { _clientMode.value = value }

    // Transient routing state (not persisted in WizardState)
    var isOnLocalNetwork: Boolean = true
        private set

    // Connection test state
    private val _localTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val localTestState: StateFlow<ConnectionTestState> = _localTestState.asStateFlow()

    private val _maTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val maTestState: StateFlow<ConnectionTestState> = _maTestState.asStateFlow()

    // Discovered servers from mDNS
    private val _discoveredServers = MutableStateFlow<List<DiscoveredServerUi>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServerUi>> = _discoveredServers.asStateFlow()

    // Whether mDNS discovery is in progress
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Network hint (auto-detected, set by Activity)
    private val _networkHint = MutableStateFlow("")

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

    // Music Assistant login
    private val _maUsername = MutableStateFlow("")
    var maUsername: String
        get() = _maUsername.value
        set(value) { _maUsername.value = value }

    private val _maPassword = MutableStateFlow("")
    var maPassword: String
        get() = _maPassword.value
        set(value) { _maPassword.value = value }

    private val _maToken = MutableStateFlow<String?>(null)
    var maToken: String?
        get() = _maToken.value
        set(value) { _maToken.value = value }

    private val _maPort = MutableStateFlow(MaSettings.getDefaultPort())
    var maPort: Int
        get() = _maPort.value
        set(value) { _maPort.value = value }

    // Discovered server info (pre-filled from mDNS)
    var discoveredServerName: String? = null
    var discoveredServerAddress: String? = null

    // Editing mode
    private val _editingServer = MutableStateFlow<UnifiedServer?>(null)
    var editingServer: UnifiedServer?
        get() = _editingServer.value
        private set(value) { _editingServer.value = value }

    var isLoading: Boolean = false

    // Derived: isMusicAssistant for backward compatibility with save logic
    val isMusicAssistant: Boolean
        get() = _clientMode.value == ClientMode.MUSIC_ASSISTANT

    // ========================================================================
    // Combined Wizard State (for Compose)
    // ========================================================================

    val wizardState: StateFlow<WizardState> = combine(
        _currentStep,
        _localTestState,
        _maTestState,
        _localAddress,
        _serverName,
        _discoveredServers,
        _isSearching,
        _maUsername,
        _maPassword,
        _maToken,
        _clientMode,
        _setAsDefault
    ) { values ->
        val step = values[0] as WizardStep
        val localTest = values[1] as ConnectionTestState
        val maTest = values[2] as ConnectionTestState
        val localAddr = values[3] as String
        val name = values[4] as String
        @Suppress("UNCHECKED_CAST")
        val discovered = values[5] as List<DiscoveredServerUi>
        val searching = values[6] as Boolean
        val maUser = values[7] as String
        val maPass = values[8] as String
        val maTokenVal = values[9] as String?
        val mode = values[10] as ClientMode
        val isDefaultVal = values[11] as Boolean

        WizardState(
            currentStep = step,
            isEditMode = _editingServer.value != null,
            isNextEnabled = computeNextEnabled(step),
            clientMode = mode,
            serverName = name,
            setAsDefault = isDefaultVal,
            networkHint = _networkHint.value,
            connectionSummary = getConnectionMethodSummary(),
            localAddress = localAddr,
            discoveredServers = discovered,
            isSearching = searching,
            localTestState = localTest,
            maUsername = maUser,
            maPassword = maPass,
            maPort = _maPort.value,
            maToken = maTokenVal,
            maTestState = maTest
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
            // Card-selection steps — no Next button, but always "enabled"
            WizardStep.ClientType,
            WizardStep.MA_NetworkQuestion -> true

            // FindServer steps — need an address
            WizardStep.SS_FindServer,
            WizardStep.MA_FindServer -> _localAddress.value.isNotBlank()

            // Testing steps — disabled (auto-advance)
            WizardStep.SS_TestLocal,
            WizardStep.MA_TestLocal -> false

            // MA Login — enabled when token obtained
            WizardStep.MA_Login -> _maToken.value != null

            // Finish steps — need a name and at least one connection
            WizardStep.SS_Finish,
            WizardStep.MA_Finish -> _serverName.value.isNotBlank() && hasValidConnectionMethod()
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
            // Card-selection steps — handled by handleStepAction, not onNext
            WizardStep.ClientType,
            WizardStep.MA_NetworkQuestion -> true

            // SendSpin path
            WizardStep.SS_FindServer -> {
                if (localAddress.isBlank()) return false
                _currentStep.value = WizardStep.SS_TestLocal
                true
            }
            WizardStep.SS_TestLocal -> true // Handled by test completion
            WizardStep.SS_Finish -> true // Handled by Activity save

            // MA local path
            WizardStep.MA_FindServer -> {
                if (localAddress.isBlank()) return false
                _currentStep.value = WizardStep.MA_TestLocal
                true
            }
            WizardStep.MA_TestLocal -> true // Handled by test completion
            WizardStep.MA_Login -> {
                _currentStep.value = WizardStep.MA_Finish
                true
            }
            WizardStep.MA_Finish -> true // Handled by Activity save
        }
    }

    /**
     * Handle "Back" action based on current step.
     * Returns the previous step, or null if at the beginning (close wizard).
     */
    fun onBack(): WizardStep? {
        val previous = when (_currentStep.value) {
            WizardStep.ClientType -> null // Close wizard

            // SendSpin path
            WizardStep.SS_FindServer -> WizardStep.ClientType
            WizardStep.SS_TestLocal -> WizardStep.SS_FindServer
            WizardStep.SS_Finish -> WizardStep.SS_FindServer

            // MA path
            WizardStep.MA_NetworkQuestion -> WizardStep.ClientType
            WizardStep.MA_FindServer -> WizardStep.MA_NetworkQuestion
            WizardStep.MA_TestLocal -> WizardStep.MA_FindServer
            WizardStep.MA_Login -> WizardStep.MA_FindServer
            WizardStep.MA_Finish -> WizardStep.MA_Login
        }
        previous?.let { _currentStep.value = it }
        return previous
    }

    /**
     * Handle "Skip" action for optional steps (MA Login).
     */
    fun onSkipMaLogin() {
        maToken = null
        maUsername = ""
        maPassword = ""
        when (_currentStep.value) {
            WizardStep.MA_Login -> _currentStep.value = WizardStep.MA_Finish
            else -> { /* Unexpected */ }
        }
    }

    /**
     * Set the network hint text (called from Activity based on NetworkEvaluator).
     */
    fun setNetworkHint(hint: String) {
        _networkHint.value = hint
    }

    // ========================================================================
    // Connection Testing
    // ========================================================================

    /**
     * Called when local connection test completes successfully.
     * Routes to the correct next step based on path.
     */
    fun onLocalTestSuccess(message: String = "Connection successful") {
        _localTestState.value = ConnectionTestState.Success(message)

        when (_currentStep.value) {
            WizardStep.SS_TestLocal -> _currentStep.value = WizardStep.SS_Finish
            WizardStep.MA_TestLocal -> _currentStep.value = WizardStep.MA_Login
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
            WizardStep.MA_TestLocal -> _currentStep.value = WizardStep.MA_FindServer
            else -> { /* Unexpected */ }
        }
    }

    fun resetLocalTest() {
        _localTestState.value = ConnectionTestState.Idle
    }

    // ========================================================================
    // MA Login Testing
    // ========================================================================

    fun testMaConnection(onComplete: (Boolean) -> Unit) {
        val endpoint: MaEndpoint = when {
            localAddress.isNotBlank() ->
                MaEndpoint.Local(WebSocketUrlBuilder.extractHost(localAddress), maPort)
            else -> {
                _maTestState.value = ConnectionTestState.Failed("No MA endpoint available")
                onComplete(false)
                return
            }
        }

        if (maUsername.isBlank() || maPassword.isBlank()) {
            _maTestState.value = ConnectionTestState.Failed("Username and password required")
            onComplete(false)
            return
        }

        _maTestState.value = ConnectionTestState.Testing

        viewModelScope.launch {
            val result = testMaAuth(
                endpoint = endpoint,
                credentials = MaCredentials.UsernamePassword(maUsername, maPassword),
            )
            result.onSuccess { loginResult ->
                maToken = loginResult.accessToken
                _maTestState.value = ConnectionTestState.Success("Connected to Music Assistant")

                // Auto-populate server name from the MA server's base URL if still blank
                if (serverName.isBlank() && loginResult.baseUrl.isNotBlank()) {
                    serverName = extractServerNameFromUrl(loginResult.baseUrl)
                }

                if (maPort != MaSettings.getDefaultPort()) {
                    MaSettings.setDefaultPort(maPort)
                }

                onComplete(true)
            }.onFailure { e ->
                maToken = null
                _maTestState.value = when (e) {
                    is MaApiTransport.AuthenticationException ->
                        ConnectionTestState.Failed("Invalid credentials")
                    is MaTransportException, is IOException ->
                        ConnectionTestState.Failed("Network error")
                    else ->
                        ConnectionTestState.Failed(e.message ?: "Unknown error")
                }
                onComplete(false)
            }
        }
    }

    fun resetMaTest() {
        _maTestState.value = ConnectionTestState.Idle
    }

    /**
     * Extracts a human-friendly server name from a base URL.
     *
     * Examples:
     * - "http://192.168.1.100:8095" → "Music Assistant"
     * - "https://music.home.example.com" → "music.home.example.com"
     * - "https://ma.local:8095" → "ma.local"
     */
    private fun extractServerNameFromUrl(baseUrl: String): String {
        val host = try {
            java.net.URI(baseUrl).host ?: baseUrl
        } catch (_: Exception) {
            baseUrl
        }

        // If it's a raw IP address, just use a generic name
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
            return "Music Assistant"
        }

        // Strip port if still present and return the hostname
        return host.substringBefore(":")
    }

    // ========================================================================
    // Edit Mode
    // ========================================================================

    /**
     * Initialize the ViewModel for editing an existing server.
     * Routes to the appropriate Finish step based on server configuration.
     */
    fun initForEdit(server: UnifiedServer, existingMaToken: String?) {
        editingServer = server
        serverName = server.name
        setAsDefault = server.isDefaultServer

        clientMode = if (server.isMusicAssistant) ClientMode.MUSIC_ASSISTANT else ClientMode.SENDSPIN

        server.local?.let {
            localAddress = it.address
            isOnLocalNetwork = true
        }

        if (server.isMusicAssistant) {
            maToken = existingMaToken
        }

        // Route to the correct Finish step
        _currentStep.value = if (!server.isMusicAssistant) WizardStep.SS_Finish else WizardStep.MA_Finish
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

        if (isMusicAssistant && maToken != null) {
            methods.add("Music Assistant: Authenticated")
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
            // ClientType step — card tap navigates
            is WizardStepAction.SelectClientMode -> {
                clientMode = action.mode
                when (action.mode) {
                    ClientMode.SENDSPIN -> _currentStep.value = WizardStep.SS_FindServer
                    ClientMode.MUSIC_ASSISTANT -> _currentStep.value = WizardStep.MA_NetworkQuestion
                }
                false
            }

            // NetworkQuestion step -- card tap navigates. Both answers land on
            // MA_FindServer: with remote access cut, "different network" just
            // means entering the server's address manually there.
            is WizardStepAction.SelectNetworkLocation -> {
                isOnLocalNetwork = action.isLocal
                _currentStep.value = WizardStep.MA_FindServer
                false
            }

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
                    WizardStep.MA_TestLocal -> _currentStep.value = WizardStep.MA_FindServer
                    else -> { /* Unexpected */ }
                }
                false
            }

            // MA Login step
            is WizardStepAction.UpdateMaUsername -> {
                maUsername = action.username
                false
            }
            is WizardStepAction.UpdateMaPassword -> {
                maPassword = action.password
                false
            }
            is WizardStepAction.UpdateMaPort -> {
                maPort = action.port
                false
            }
            WizardStepAction.TestMaConnection -> {
                true // Activity should trigger MA connection test
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
        _clientMode.value = ClientMode.SENDSPIN
        _localAddress.value = ""
        _maUsername.value = ""
        _maPassword.value = ""
        _maToken.value = null
        _maPort.value = MaSettings.getDefaultPort()
        _editingServer.value = null
        _discoveredServers.value = emptyList()
        _isSearching.value = false
        _networkHint.value = ""

        isOnLocalNetwork = true
        discoveredServerName = null
        discoveredServerAddress = null
        isLoading = false

        _currentStep.value = WizardStep.ClientType
        _localTestState.value = ConnectionTestState.Idle
        _maTestState.value = ConnectionTestState.Idle
    }
}
