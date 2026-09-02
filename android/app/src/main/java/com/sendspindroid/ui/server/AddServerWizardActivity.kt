package com.sendspindroid.ui.server

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.sendspindroid.R
import com.sendspindroid.UnifiedServerRepository
import com.sendspindroid.discovery.NsdDiscoveryManager
import com.sendspindroid.model.ConnectionPreference
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.musicassistant.MaSettings
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.network.NetworkEvaluator
import com.sendspindroid.network.TransportType
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SendSpinEndpoint
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.AddServerWizardScreen
import com.sendspindroid.ui.wizard.ConnectionTestState
import com.sendspindroid.ui.wizard.DiscoveredServerUi
import com.sendspindroid.ui.wizard.WizardStep
import com.sendspindroid.ui.wizard.WizardStepAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Full-screen wizard Activity for adding or editing unified servers.
 *
 * Uses Jetpack Compose for UI via AddServerWizardScreen.
 * Handles lifecycle-aware operations like mDNS discovery and connection testing.
 */
class AddServerWizardActivity : FragmentActivity() {

    companion object {
        private const val TAG = "AddServerWizardActivity"

        // Intent extras
        const val EXTRA_EDIT_SERVER_ID = "edit_server_id"
        const val EXTRA_DISCOVERY_MODE = "discovery_mode"

        // Result extras
        const val RESULT_SERVER_ID = "server_id"

        // Timeout for transient connection tests
        private const val TEST_TIMEOUT_MS = 6_000L
    }

    // No-op callback for transient SendSpin instances used in wizard connection tests.
    // All methods are intentionally empty — the wizard only cares about connectionState.
    private val noopSendSpinCallback = object : SendSpin.Callback {
        override fun onServerDiscovered(name: String, address: String) {}
        override fun onStateChanged(state: String) {}
        override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) {}
        override fun onMetadataUpdate(
            title: String, artist: String, album: String,
            artworkUrl: String, durationMs: Long, positionMs: Long, playbackSpeed: Int
        ) {}
        override fun onArtwork(imageData: ByteArray) {}
        override fun onArtworkCleared() {}
        override fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {}
        override fun onStreamClear() {}
        override fun onStreamEnd() {}
        override fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) {}
        override fun onVolumeChanged(volume: Int) {}
        override fun onMutedChanged(muted: Boolean) {}
        override fun onSyncOffsetApplied(offsetMs: Double, source: String) {}
        override fun onNetworkChanged() {}
    }

    private val viewModel: AddServerWizardViewModel by viewModels()

    // Discovery manager for mDNS
    private var discoveryManager: NsdDiscoveryManager? = null
    private val discoveredServers = mutableMapOf<String, DiscoveredServer>()

    private data class DiscoveredServer(val name: String, val address: String, val path: String)

    // Network evaluator for auto-detecting network type
    private var networkEvaluator: NetworkEvaluator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle edit mode
        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_EDIT_SERVER_ID)?.let { serverId ->
                UnifiedServerRepository.getServer(serverId)?.let { server ->
                    val existingMaToken = if (server.isMusicAssistant) {
                        MaSettings.getTokenForServer(server.id)
                    } else null
                    viewModel.initForEdit(server, existingMaToken)
                }
            }
        }

        // Initialize discovery manager
        discoveryManager = NsdDiscoveryManager(this, discoveryListener)

        // Initialize network evaluator and set hint
        networkEvaluator = NetworkEvaluator(this).also { evaluator ->
            evaluator.evaluateCurrentNetwork()
            val state = evaluator.networkState.value
            val hint = when (state.transportType) {
                TransportType.WIFI -> "You appear to be on WiFi"
                TransportType.CELLULAR -> "You appear to be on cellular data"
                TransportType.ETHERNET -> "You appear to be on Ethernet"
                TransportType.VPN -> "You appear to be on a VPN"
                TransportType.UNKNOWN -> ""
            }
            viewModel.setNetworkHint(hint)
        }

        // Edge-to-edge: transparent system bars with proper inset handling
        enableEdgeToEdge()

        setContent {
            SendSpinTheme {
                val state by viewModel.wizardState.collectAsStateWithLifecycle()

                // Auto-start discovery when entering a FindServer step
                LaunchedEffect(state.currentStep) {
                    if ((state.currentStep == WizardStep.SS_FindServer ||
                         state.currentStep == WizardStep.MA_FindServer) && !state.isSearching) {
                        startDiscovery()
                    }
                }

                AddServerWizardScreen(
                    state = state,
                    onClose = { finish() },
                    onBack = { handleBack() },
                    onNext = { handleNext() },
                    onSkip = { viewModel.onSkipMaLogin() },
                    onSave = { attemptSave() },
                    onStepAction = { action -> handleStepAction(action) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryManager?.cleanup()
        discoveryManager = null
    }

    // ========================================================================
    // Navigation Handlers
    // ========================================================================

    private fun handleBack() {
        if (viewModel.onBack() == null) {
            finish()
        }
    }

    private fun handleNext() {
        when (viewModel.currentStep.value) {
            // FindServer steps — validate address and start test
            WizardStep.SS_FindServer,
            WizardStep.MA_FindServer -> {
                if (viewModel.localAddress.isBlank()) {
                    showToast(getString(R.string.wizard_local_address_hint))
                    return
                }
                startLocalConnectionTest()
            }

            // MA Login step -- test connection if no token yet
            WizardStep.MA_Login -> {
                if (viewModel.maToken != null) {
                    viewModel.onNext()
                } else {
                    startMaConnectionTest()
                }
            }

            // Finish steps — handled by onSave
            WizardStep.SS_Finish,
            WizardStep.MA_Finish -> {
                // Handled by onSave
            }

            // Card-selection and testing steps — no Next action
            else -> viewModel.onNext()
        }
    }

    // ========================================================================
    // Step Action Handler
    // ========================================================================

    private fun handleStepAction(action: WizardStepAction) {
        val needsActivityHandling = viewModel.handleStepAction(action)

        if (needsActivityHandling) {
            when (action) {
                WizardStepAction.StartDiscovery -> startDiscovery()
                WizardStepAction.TestMaConnection -> startMaConnectionTest()
                else -> { /* Handled by ViewModel */ }
            }
        }
    }

    // ========================================================================
    // mDNS Discovery
    // ========================================================================

    private val discoveryListener = object : NsdDiscoveryManager.DiscoveryListener {
        override fun onServerDiscovered(name: String, address: String, path: String, friendlyName: String) {
            runOnUiThread {
                val server = DiscoveredServer(friendlyName, address, path)
                discoveredServers[name] = server
                updateDiscoveredServersInViewModel()
            }
        }

        override fun onServerLost(name: String) {
            runOnUiThread {
                discoveredServers.remove(name)
                updateDiscoveredServersInViewModel()
            }
        }

        override fun onDiscoveryStarted() {
            runOnUiThread {
                viewModel.setSearching(true)
            }
        }

        override fun onDiscoveryStopped() {
            runOnUiThread {
                viewModel.setSearching(false)
            }
        }

        override fun onDiscoveryError(error: String) {
            runOnUiThread {
                viewModel.setSearching(false)
                showToast(error)
            }
        }
    }

    private fun startDiscovery() {
        discoveredServers.clear()
        viewModel.updateDiscoveredServers(emptyList())
        discoveryManager?.startDiscovery()
    }

    private fun stopDiscovery() {
        discoveryManager?.stopDiscovery()
    }

    private fun updateDiscoveredServersInViewModel() {
        // Use the map key (the mDNS service instance name) as the UI id,
        // not server.name (the friendly/display name). Friendly names are
        // not required to be unique -- e.g. two Music Assistant instances
        // on the same LAN both advertise friendlyName="Music Assistant",
        // and using the friendly name as the id would produce duplicate
        // Compose LazyColumn keys and crash the wizard.
        val servers = discoveredServers.map { (instanceName, server) ->
            DiscoveredServerUi(
                id = instanceName,
                name = server.name,
                address = server.address
            )
        }
        viewModel.updateDiscoveredServers(servers)
    }

    // ========================================================================
    // Connection Testing
    // ========================================================================

    private fun startLocalConnectionTest() {
        // Navigate to the correct testing step
        val testStep = when (viewModel.currentStep.value) {
            WizardStep.SS_FindServer -> WizardStep.SS_TestLocal
            WizardStep.MA_FindServer -> WizardStep.MA_TestLocal
            else -> return
        }
        viewModel.navigateTo(testStep)

        lifecycleScope.launch {
            delay(500) // Brief delay for UI to show

            val result = testLocalConnection(viewModel.localAddress)

            result.fold(
                onSuccess = { responseCode ->
                    delay(500) // Brief success display
                    viewModel.onLocalTestSuccess("Connected (HTTP $responseCode)")
                },
                onFailure = { error ->
                    Log.e(TAG, "Local connection test failed", error)
                    viewModel.onLocalTestFailed(error.message ?: "Unknown error")
                }
            )
        }
    }

    private suspend fun testLocalConnection(address: String): Result<Int> {
        Log.d(TAG, "Testing local connection to: $address")
        val transient = SendSpin(
            deviceName = android.os.Build.MODEL,
            callback = noopSendSpinCallback,
        )
        transient.selfReconnectEnabled = false
        transient.connect(SendSpinEndpoint.Local(address))
        return try {
            val terminal = withTimeoutOrNull(TEST_TIMEOUT_MS) {
                transient.connectionState.first {
                    it is TransportState.Ready || it is TransportState.Failed
                }
            }
            when (terminal) {
                is TransportState.Ready ->
                    Result.success(101) // 101 Switching Protocols — WebSocket upgrade succeeded
                is TransportState.Failed ->
                    Result.failure(IOException("Connection failed: ${terminal.reason::class.simpleName}"))
                null ->
                    Result.failure(IOException("Connection timed out"))
                else ->
                    Result.failure(IOException("Unexpected state: $terminal"))
            }
        } finally {
            transient.destroy()
        }
    }

    private fun startMaConnectionTest() {
        viewModel.testMaConnection { success ->
            if (success) {
                // Advance past the login step
                viewModel.onNext()
            }
        }
    }

    // ========================================================================
    // Save
    // ========================================================================

    private fun attemptSave() {
        if (viewModel.serverName.isBlank()) {
            showToast(getString(R.string.wizard_name_required))
            return
        }

        if (!viewModel.hasValidConnectionMethod()) {
            showToast(getString(R.string.wizard_at_least_one_method))
            return
        }

        val hasLocal = viewModel.localAddress.isNotBlank()
        val serverId = viewModel.getServerId()

        val server = UnifiedServer(
            id = serverId,
            name = viewModel.serverName,
            lastConnectedMs = viewModel.editingServer?.lastConnectedMs ?: 0L,
            local = if (hasLocal) LocalConnection(
                address = viewModel.localAddress,
                path = "/sendspin"
            ) else null,
            connectionPreference = ConnectionPreference.AUTO,
            isDiscovered = false,
            isDefaultServer = viewModel.setAsDefault,
            isMusicAssistant = viewModel.isMusicAssistant
        )

        // Save to repository
        UnifiedServerRepository.saveServer(server)

        // Save MA token if we have one
        if (viewModel.isMusicAssistant && viewModel.maToken != null) {
            MaSettings.setTokenForServer(serverId, viewModel.maToken!!)
        } else if (!viewModel.isMusicAssistant) {
            MaSettings.clearTokenForServer(serverId)
        }

        // Update default server if needed
        if (viewModel.setAsDefault) {
            UnifiedServerRepository.setDefaultServer(serverId)
        } else if (viewModel.editingServer?.isDefaultServer == true) {
            UnifiedServerRepository.setDefaultServer(null)
        }

        // Return result and finish
        val resultIntent = Intent().apply {
            putExtra(RESULT_SERVER_ID, serverId)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    // ========================================================================
    // Utility
    // ========================================================================

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
