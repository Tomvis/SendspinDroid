package com.sendspindroid

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import android.database.ContentObserver
import android.media.AudioManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.sendspindroid.diagnostics.DiagnosticsExport
import com.sendspindroid.logging.AppLog
import com.sendspindroid.logging.CrashHandler
import android.app.UiModeManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sendspindroid.discovery.DiscoveryGate
import com.sendspindroid.discovery.NsdDiscoveryManager
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import com.sendspindroid.coordinator.ReconnectStatus
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.playback.PlaybackService
import com.sendspindroid.sendspin.pairing.PairedServers
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.ui.settings.message
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.network.ConnectionSelector
import com.sendspindroid.network.DefaultServerPinger
import com.sendspindroid.ui.server.AddServerWizardActivity
import com.sendspindroid.ui.server.UnifiedServerConnector
import androidx.activity.viewModels
import kotlinx.coroutines.flow.combine
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.PlaybackState
import com.sendspindroid.ui.main.ArtworkSource
import com.sendspindroid.ui.main.ServerListScreen
import com.sendspindroid.ui.main.components.ConnectionModeState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.sendspindroid.ui.AppShell
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.determineFormFactor
import com.sendspindroid.ui.adaptive.isTvDevice
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Main activity for the SendSpinDroid audio streaming client.
 * Handles server discovery, connection management, and audio playback control.
 *
 * Architecture note: This activity currently handles too many responsibilities.
 * For v2, consider refactoring to MVVM pattern with ViewModel for better separation of concerns.
 */
class MainActivity : AppCompatActivity() {

    // Snackbar anchor -- the FrameLayout that hosts the Compose shell.
    // Set by setupComposeShell().
    private var snackbarAnchorView: View? = null

    // Scanning indicator for the Compose server list
    private val composeIsScanning = mutableStateOf(false)

    // Mirrors UserSettings.searchForServers for the Compose UI.
    private val composeSearching = mutableStateOf(false)

    // ViewModel for managing UI state (survives configuration changes)
    private val viewModel: MainActivityViewModel by viewModels()

    // Unified server support - connector for handling server connections
    private var unifiedServerConnector: UnifiedServerConnector? = null


    // Connection state machine - starts with ServerList (shows immediately on startup)
    private var connectionState: AppConnectionState = AppConnectionState.ServerList

    // Track the currently connected server ID for editing
    private var currentConnectedServerId: String? = null

    // NsdManager-based discovery (Android native - more reliable than Go's hashicorp/mdns)
    private var discoveryManager: NsdDiscoveryManager? = null

    // MediaController for communicating with PlaybackService
    // Provides playback control and state observation
    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Handler for UI operations
    private val handler = Handler(Looper.getMainLooper())

    // User manually disconnected flag - when true, blocks auto-connect to default server
    // Set when user taps "Switch Server" or manually selects a different server while connected
    // Cleared when user manually connects to the default server
    private var userManuallyDisconnected = false

    // Reconnecting indicator - persists while reconnection is in progress
    private var reconnectingSnackbar: Snackbar? = null

    // Default server pinger for auto-connect when mDNS hasn't found the server yet
    private var defaultServerPinger: DefaultServerPinger? = null

    // Charging state receiver for adaptive ping intervals
    private var chargingReceiver: BroadcastReceiver? = null

    // Volume control - uses device STREAM_MUSIC (Spotify-style)
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var volumeObserver: ContentObserver? = null

    // Android TV detection - used for D-pad navigation and remote control handling
    private val isTvDevice: Boolean by lazy {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    // Permission request launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "Notification permission granted")
        } else {
            Log.w(TAG, "Notification permission denied - playback notifications will not appear")
        }
    }

    // Add Server Wizard Activity launcher
    // Uses ActivityResult to receive the new/updated server ID when the wizard completes
    private val addServerWizardLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val serverId = result.data?.getStringExtra(AddServerWizardActivity.RESULT_SERVER_ID)
            if (serverId != null) {
                val server = UnifiedServerRepository.getServer(serverId)
                if (server != null) {
                    Log.d(TAG, "Server wizard completed: ${server.name}")
                    showSuccessSnackbar(getString(R.string.server_added, server.name))
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        // Delay before auto-connecting to default server (allows UI to render)
        private const val DEFAULT_SERVER_AUTO_CONNECT_DELAY_MS = 1_000L
        // SharedPreferences keys
        private const val PREFS_NAME = "sendspindroid_prefs"
        private const val PREF_ONBOARDING_SHOWN = "onboarding_shown"
    }

    // SharedPreferences for app state
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns a view suitable for anchoring Snackbars.
     *
     * This property returns [snackbarAnchorView] (the FrameLayout that hosts the
     * Compose shell), falling back to the window content view during the brief
     * window before [setupComposeShell] runs (e.g. in [onCreate] / [setupUI]).
     */
    private val snackbarView: View
        get() = snackbarAnchorView ?: findViewById(android.R.id.content)

    /**
     * Snackbar error types for different error scenarios.
     * Used to determine appropriate duration, colors, and actions.
     */
    private enum class ErrorType {
        NETWORK,          // Network connectivity errors
        CONNECTION,       // Server connection failures
        DISCOVERY,        // Server discovery failures
        PLAYBACK,         // Audio playback errors
        VALIDATION,       // Input validation errors
        GENERAL          // General errors
    }

    /**
     * Shows an error Snackbar with appropriate styling and optional retry action.
     *
     * @param message The error message to display
     * @param errorType The type of error (determines styling and duration)
     * @param retryAction Optional action to execute when user taps "Retry"
     */
    /**
     * Simple public overload for showing an error snackbar from Fragments.
     */
    fun showErrorSnackbar(message: String) {
        showErrorSnackbar(message, ErrorType.GENERAL, null)
    }

    private fun showErrorSnackbar(
        message: String,
        errorType: ErrorType = ErrorType.GENERAL,
        retryAction: (() -> Unit)? = null
    ) {
        val snackbar = Snackbar.make(
            snackbarView,
            message,
            if (errorType == ErrorType.VALIDATION) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
        )

        // Set error background color
        snackbar.view.setBackgroundColor(
            ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_error)
        )

        // Add retry action if provided
        retryAction?.let { action ->
            snackbar.setAction(getString(R.string.action_retry)) {
                action()
            }
            snackbar.setActionTextColor(
                ContextCompat.getColor(this, android.R.color.white)
            )
        }

        snackbar.show()
    }

    /**
     * If the previous run ended in an uncaught exception, show a gentle,
     * dismissible Snackbar offering to share a (redacted) diagnostics report.
     */
    private fun maybeShowCrashReportPrompt() {
        CrashHandler.consumePending(this) ?: return
        Snackbar.make(snackbarView, R.string.crash_report_prompt, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.crash_report_action) { shareDiagnostics() }
            .show()
    }

    /** Share the redacted diagnostics bundle via the system chooser. */
    private fun shareDiagnostics() {
        val intent = DiagnosticsExport.shareIntent(this)
        if (intent != null) {
            startActivity(Intent.createChooser(intent, getString(R.string.debug_share_chooser_title)))
        } else {
            Snackbar.make(snackbarView, R.string.debug_log_export_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    /**
     * Shows a success Snackbar with appropriate styling.
     *
     * @param message The success message to display
     */
    fun showSuccessSnackbar(message: String) {
        val snackbar = Snackbar.make(
            snackbarView,
            message,
            Snackbar.LENGTH_SHORT
        )

        // Set success background color (using primary color)
        snackbar.view.setBackgroundColor(
            ContextCompat.getColor(this, com.google.android.material.R.color.design_default_color_primary_dark)
        )

        snackbar.show()
    }

    /**
     * Shows an info Snackbar with default styling.
     *
     * @param message The info message to display
     */
    private fun showInfoSnackbar(message: String) {
        Snackbar.make(
            snackbarView,
            message,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    /**
     * Shows an indicator that the service is reconnecting to the server.
     *
     * @param attempt Current reconnection attempt number
     */
    private fun showReconnectingIndicator(attempt: Int) {
        // Don't show UI if activity is finishing or destroyed
        if (isFinishing || isDestroyed) {
            return
        }

        // Dismiss any existing reconnecting snackbar
        reconnectingSnackbar?.dismiss()

        val message = "Reconnecting (attempt $attempt)..."

        try {
            reconnectingSnackbar = Snackbar.make(
                snackbarView,
                message,
                Snackbar.LENGTH_INDEFINITE
            ).apply {
                // Use warning/info color instead of error
                view.setBackgroundColor(
                    ContextCompat.getColor(this@MainActivity, com.google.android.material.R.color.design_default_color_primary)
                )
                show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show reconnecting indicator", e)
        }
    }

    /**
     * Hides the reconnecting indicator (on successful reconnection or error).
     */
    private fun hideReconnectingIndicator() {
        reconnectingSnackbar?.dismiss()
        reconnectingSnackbar = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: must be called before super.onCreate so the
        // activity is set up to draw behind system bars on Android 15+.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Logging + crash capture are initialized in SendSpinApp.onCreate, before any Activity.
        AppLog.App.i("MainActivity onCreate (log level: ${AppLog.level})")

        // Initialize UnifiedServerRepository for unified server management
        // Single source of truth for all server state (discovered + saved)
        UnifiedServerRepository.initialize(this)

        // Initialize UserSettings for accessing user preferences
        UserSettings.initialize(this)
        UserSettings.chooseConnectionModeOnce(UnifiedServerRepository.getDefaultServer() != null)
        composeSearching.value = UserSettings.searchForServers

        applyFullScreenMode()

        // Initialize discovery manager BEFORE setupUI, because setupUI calls
        // showSearchingView() which starts auto-discovery
        initializeDiscoveryManager()
        initializeDefaultServerPinger()
        initializeMediaController()
        setupUI()

        // Install the Compose shell as the content view
        setupComposeShell()

        // Show onboarding dialog for first-time users
        showOnboardingIfNeeded()

        // If the previous run crashed, offer to send a report.
        maybeShowCrashReportPrompt()

        // Request notification permission for Android 13+
        requestNotificationPermission()
    }

    /**
     * Requests POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * This is required for playback notifications to appear.
     * If denied, playback will still work but without notification controls.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted")
                }
                else -> {
                    Log.d(TAG, "Requesting notification permission")
                    notificationPermissionLauncher.launch(permission)
                }
            }
        }
    }

    /**
     * Shows the onboarding dialog if this is the user's first launch.
     * Uses SharedPreferences to track if the dialog has been shown.
     */
    private fun showOnboardingIfNeeded() {
        if (!prefs.getBoolean(PREF_ONBOARDING_SHOWN, false)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.onboarding_title)
                .setMessage(R.string.onboarding_message)
                .setPositiveButton(R.string.onboarding_got_it) { dialog, _ ->
                    // Mark onboarding as shown
                    prefs.edit().putBoolean(PREF_ONBOARDING_SHOWN, true).apply()
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()
        }
    }

    /**
     * Apply full screen (immersive) mode based on user setting.
     * Uses WindowInsetsControllerCompat for backward compatibility to API 21.
     */
    private fun applyFullScreenMode() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (UserSettings.fullScreenMode) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateKeepScreenOn(isPlaying: Boolean) {
        val isConnected = connectionState is AppConnectionState.Connected ||
                          connectionState is AppConnectionState.Reconnecting
        val keepOn = (UserSettings.keepScreenOn && isPlaying) ||
                     (UserSettings.highPowerMode && isConnected)
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupUI() {
        // Server list is Compose-based (ServerListScreen in AppShell)
        // Setup unified server support (connector, observers)
        setupUnifiedServers()

        // Observe network state from PlaybackService/Coordinator for pinger callbacks
        // and network-loss snackbars (replaces the deleted ConnectivityManager.NetworkCallback).
        observeNetworkState()

        // Volume slider - controls device STREAM_MUSIC (Spotify-style)
        // Initialize slider to current device volume
        syncSliderWithDeviceVolume()

        // Start with server list view and begin discovery in background
        showServerListView()
    }

    /**
     * Installs the Compose shell as the activity's content view.
     *
     * The ComposeView hosts AppShell which provides:
     * - Server list (Compose)
     * - Now Playing screen (Compose)
     * - Toolbar (Compose)
     *
     * Business logic methods (volume, discovery, media controller) update
     * the ViewModel, which the Compose UI observes.
     */
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    private fun setupComposeShell() {
        val overlay = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

        // Create a FrameLayout wrapper for Compose
        val rootFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Add Compose as first child (fills screen)
        rootFrame.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootFrame)

        // rootFrame is the attached view in the hierarchy -- use it for Snackbar anchoring
        snackbarAnchorView = rootFrame

        overlay.setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val detectedFormFactor = determineFormFactor(
                windowSizeClass = windowSizeClass,
                isTv = isTvDevice
            )
            // Observe layout mode reactively so changes in Settings take effect immediately
            val layoutModeState = remember {
                mutableStateOf(UserSettings.layoutMode)
            }
            DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == UserSettings.KEY_LAYOUT_MODE) {
                        layoutModeState.value = UserSettings.layoutMode
                    }
                }
                PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                    .registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                        .unregisterOnSharedPreferenceChangeListener(listener)
                }
            }
            val formFactor = when (layoutModeState.value) {
                UserSettings.LayoutMode.HEADUNIT -> FormFactor.HEADUNIT
                UserSettings.LayoutMode.AUTO -> detectedFormFactor
            }

            SendSpinTheme {
                CompositionLocalProvider(LocalFormFactor provides formFactor) {
                    AppShell(
                        viewModel = viewModel,
                        serverListContent = {
                            val listeningPort by PlaybackService.advertisedPort.collectAsStateWithLifecycle()
                            val advertisingFailed by PlaybackService.advertisingFailed.collectAsStateWithLifecycle()
                            ServerListScreen(
                                savedServers = UnifiedServerRepository.savedServers,
                                discoveredServers = UnifiedServerRepository.filteredDiscoveredServers,
                                onlineSavedServerIds = UnifiedServerRepository.onlineSavedServerIds,
                                isScanning = composeIsScanning.value,
                                serverStatuses = emptyMap(),
                                reconnectInfo = emptyMap(),
                                onServerClick = { server -> onUnifiedServerSelected(server) },
                                onServerLongClick = { server -> showUnifiedServerContextMenu(server) },
                                onQuickConnectClick = { server -> onUnifiedServerSelected(server) },
                                onAddServerClick = { showAddServerWizard() },
                                mode = ConnectionModeState(
                                    searching = composeSearching.value,
                                    playerName = UserSettings.getPlayerName(),
                                    listeningPort = listeningPort,
                                    failed = advertisingFailed,
                                    onSearchingChange = { search -> onSearchingChanged(search) }
                                )
                            )
                        },
                        onPreviousClick = { onPreviousClicked() },
                        onPlayPauseClick = { onPlayPauseClicked() },
                        onNextClick = { onNextClicked() },
                        onSwitchGroupClick = { onSwitchGroupClicked() },
                        onAllowPairingClick = { onAllowPairingClicked() },
                        onVolumeChange = { volume ->
                            onVolumeChanged(volume)
                            viewModel.updateVolume(volume)
                        },
                        onDisconnectClick = { onDisconnectClicked() },
                        onAddServerClick = { showAddServerWizard() },
                        onStatsClick = {
                            StatsBottomSheet().show(supportFragmentManager, "stats")
                        },
                        onSettingsClick = {
                            startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java))
                        },
                        onEditServerClick = {
                            val serverId = currentConnectedServerId
                            if (serverId != null) {
                                val server = UnifiedServerRepository.getServer(serverId)
                                if (server != null) {
                                    showEditServerWizard(server)
                                }
                            }
                        },
                        onExitAppClick = { onExitAppClicked() }
                    )
                }
            }
        }

        // Browsing is refused until the advertisement and the listener are
        // gone, which is a moment after "Search for servers instead" is
        // pressed: start it when it becomes possible.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiscoveryGate.allowed.drop(1).collect { allowed ->
                    if (allowed && UserSettings.searchForServers &&
                        connectionState is AppConnectionState.ServerList
                    ) {
                        startAutoDiscovery()
                    }
                }
            }
        }

        // Pairing is driven by the server, so how it ended is said here,
        // where the user is, and not only in Settings. drop(1): the outcome
        // already there when the activity starts has been seen or is old.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PairedServers.lastOutcome.drop(1).filterNotNull().collect { outcome ->
                    // The explanations run to a few sentences on a phone.
                    Snackbar.make(snackbarView, outcome.message(resources), Snackbar.LENGTH_LONG)
                        .setTextMaxLines(5)
                        .show()
                }
            }
        }

        // Sync scanning state with discovery manager.
        lifecycleScope.launch {
            // Update scanning state when discovery starts/stops.
            // repeatOnLifecycle ensures polling stops when the activity is not visible.
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    composeIsScanning.value = discoveryManager?.isDiscovering() == true
                    delay(1000)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerVolumeObserver()
        // Notify pinger of foreground state for adaptive intervals
        defaultServerPinger?.onForegroundChanged(true)
    }

    override fun onStop() {
        super.onStop()
        unregisterVolumeObserver()
        // Notify pinger of background state for adaptive intervals
        defaultServerPinger?.onForegroundChanged(false)
    }

    /**
     * Called when activity comes to foreground.
     * Re-syncs UI with current playback state to handle cases where:
     * - User returns from another app
     * - Screen was turned off and on
     * - Activity was in background while playback continued
     */
    override fun onResume() {
        super.onResume()
        // Re-apply full screen mode (picks up changes made in Settings)
        applyFullScreenMode()
        // Re-evaluate keep screen on (picks up setting changes + current playback state)
        updateKeepScreenOn(mediaController?.isPlaying == true)
        // Re-sync UI state with MediaController
        syncUIWithPlayerState()
        // Re-sync volume slider with device volume (may have changed while in background)
        syncSliderWithDeviceVolume()
    }

    /**
     * Called when activity receives a new intent while already running.
     * This happens when user taps the notification (FLAG_ACTIVITY_SINGLE_TOP).
     * Without this, the activity doesn't know it was re-launched and UI can get stuck.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-sync UI state with MediaController
        syncUIWithPlayerState()
    }

    // ============================================================================
    // View State Management (Two Views: Server List, Now Playing)
    // ============================================================================

    /**
     * Transitions to server list state.
     * Called on disconnect or when returning from now playing.
     */
    private fun transitionToServerList() {
        connectionState = AppConnectionState.ServerList
        currentConnectedServerId = null  // Clear tracked server
        // Sync state to ViewModel for Compose UI
        viewModel.resetToServerList()
        showServerListView()
    }

    /**
     * Shows the server list view with saved and discovered servers.
     * mDNS discovery runs in the background, updating the discovered section.
     */
    private fun showServerListView() {
        if (!UserSettings.searchForServers) {
            // Advertising, and waiting for a server to connect: the app does
            // not look for servers or dial one until the user picks it.
            discoveryManager?.stopDiscovery()
            defaultServerPinger?.stop()
            UnifiedServerRepository.clearDiscoveredServers()
            return
        }

        // Start discovery automatically (runs in background)
        startAutoDiscovery()

        // Check for default server and auto-connect after a brief delay
        checkDefaultServerAutoConnect()

        // Start default server pinger for when mDNS hasn't found the server yet
        // (mDNS announcements can be missed; this pings the server directly to catch that case)
        if (!userManuallyDisconnected) {
            defaultServerPinger?.start()
        }
    }

    /**
     * The user chose to search for servers, or to go back to advertising and
     * waiting for one. The service switches; this side starts or stops
     * looking.
     */
    private fun onSearchingChanged(search: Boolean) {
        Log.d(TAG, "Search for servers: $search")
        // Stored here as well as applied by the service, so the choice
        // holds even if the service is not up yet.
        UserSettings.searchForServers = search
        composeSearching.value = search
        mediaController?.sendCustomCommand(
            SessionCommand(PlaybackService.COMMAND_SET_SEARCH_FOR_SERVERS, Bundle.EMPTY),
            Bundle().apply { putBoolean(PlaybackService.ARG_SEARCH_FOR_SERVERS, search) }
        )
        showServerListView()
    }

    /**
     * Checks if there's a default server configured and auto-connects to it.
     *
     * This runs once at app launch to attempt immediate connection to the default server.
     * It provides a brief startup window for the server to be available before falling
     * back to waiting for mDNS discovery (which triggers checkAutoConnectOnDiscovery).
     *
     * Note: This uses a one-time startup flag separate from userManuallyDisconnected,
     * since this delay-based approach should only run once per app launch.
     */
    private var hasRunStartupAutoConnect = false

    private fun checkDefaultServerAutoConnect() {
        if (hasRunStartupAutoConnect) return
        hasRunStartupAutoConnect = true

        // Don't auto-connect if user manually disconnected in a previous session
        // (though this will typically be false at startup)
        if (userManuallyDisconnected) {
            Log.d(TAG, "Skipping startup auto-connect - user manually disconnected")
            return
        }

        val defaultServer = UnifiedServerRepository.getDefaultServer()
        if (defaultServer != null) {
            Log.d(TAG, "Default server found: ${defaultServer.name}, scheduling auto-connect")

            // Delay to allow UI to render and user to see the server list
            lifecycleScope.launch {
                delay(DEFAULT_SERVER_AUTO_CONNECT_DELAY_MS)

                // Only auto-connect if still in ServerList state and user hasn't manually disconnected.
                if (connectionState != AppConnectionState.ServerList || userManuallyDisconnected) {
                    return@launch
                }

                // If MediaController is already available, connect immediately.
                // Otherwise, gate auto-connect on the mediaControllerFuture listener
                // instead of busy-polling (avoids wasting CPU on 250ms poll iterations).
                if (mediaController != null) {
                    attemptAutoConnect(defaultServer)
                } else {
                    Log.d(TAG, "MediaController not ready yet - waiting for future callback")
                    mediaControllerFuture?.addListener(
                        {
                            runOnUiThread {
                                attemptAutoConnect(defaultServer)
                            }
                        },
                        MoreExecutors.directExecutor()
                    )
                }
            }
        }
    }

    /**
     * Attempts auto-connection to the default server if conditions are still valid.
     * Called either immediately (if MediaController is ready) or from the
     * mediaControllerFuture listener callback.
     */
    private fun attemptAutoConnect(defaultServer: UnifiedServer) {
        if (mediaController != null &&
            connectionState == AppConnectionState.ServerList &&
            !userManuallyDisconnected) {
            Log.d(TAG, "Auto-connecting to default server: ${defaultServer.name}")
            onUnifiedServerSelected(defaultServer)
        } else {
            Log.d(TAG, "Auto-connect skipped: controller=${mediaController != null}, state=$connectionState")
        }
    }

    /**
     * Checks if a newly discovered server matches the default server and triggers auto-connect.
     *
     * This is called when mDNS discovers a server on the local network. If the discovered
     * server matches the default server's local address, and the user hasn't manually
     * disconnected, we automatically connect.
     *
     * This handles the app starting while the server is temporarily
     * unavailable. A connection that drops later is the service's to restore.
     *
     * @param discoveredAddress The IP address of the newly discovered server
     */
    private fun checkAutoConnectOnDiscovery(discoveredAddress: String) {
        // Don't auto-connect if user manually disconnected ("Switch Server" or manual switch)
        if (userManuallyDisconnected) {
            Log.d(TAG, "Skipping auto-connect on discovery - user manually disconnected")
            return
        }

        // Don't auto-connect if already connected or connecting
        if (connectionState !is AppConnectionState.ServerList) {
            Log.d(TAG, "Skipping auto-connect on discovery - not in ServerList state")
            return
        }

        // The service is already reconnecting; it retries on mDNS itself
        if (PlaybackService.reconnectStatus.value is ReconnectStatus.Attempting) {
            Log.d(TAG, "Skipping auto-connect on discovery - reconnect in progress")
            return
        }

        // Check if discovered server matches default server's local address
        val defaultServer = UnifiedServerRepository.getDefaultServer() ?: return
        if (defaultServer.local?.address == discoveredAddress) {
            Log.i(TAG, "Default server discovered on mDNS ($discoveredAddress) - auto-connecting")
            onUnifiedServerSelected(defaultServer)
        }
    }

    /**
     * Called when connected to a server (the Compose shell shows now playing).
     */
    private fun showNowPlayingView() {
        // Stop discovery and pinging while connected (saves battery)
        discoveryManager?.stopDiscovery()
        defaultServerPinger?.stop()

        // Sync volume slider with current device volume
        syncSliderWithDeviceVolume()
    }

    /**
     * Initializes Android-native NsdManager for server discovery.
     *
     * Why NsdManager instead of Go's hashicorp/mdns?
     * - NsdManager is Android's native mDNS implementation
     * - It properly handles network interface selection on Android
     * - It works reliably with Android's WiFi stack and multicast lock
     * - hashicorp/mdns has issues selecting the correct interface on Android
     */
    private fun initializeDiscoveryManager() {
        try {
            discoveryManager = NsdDiscoveryManager(
                context = this,
                listener = object : NsdDiscoveryManager.DiscoveryListener {
                    override fun onServerDiscovered(name: String, address: String, path: String, friendlyName: String) {
                        runOnUiThread {
                            Log.d(TAG, "Server discovered: $name at $address path=$path friendlyName=$friendlyName")
                            UnifiedServerRepository.addDiscoveredServer(friendlyName, address, path)

                            // Check if this discovery should trigger auto-connect to default server
                            checkAutoConnectOnDiscovery(address)
                        }
                    }

                    override fun onServerLost(name: String) {
                        runOnUiThread {
                            Log.d(TAG, "Server lost: $name")
                            val address = UnifiedServerRepository.discoveredServers.value
                                .find { it.name == name }?.local?.address
                            if (address != null) {
                                UnifiedServerRepository.removeDiscoveredServer(address)
                            }
                        }
                    }

                    override fun onDiscoveryStarted() {
                        runOnUiThread {
                            Log.d(TAG, "Discovery started")
                        }
                    }

                    override fun onDiscoveryStopped() {
                        runOnUiThread {
                            Log.d(TAG, "Discovery stopped")
                        }
                    }

                    override fun onDiscoveryError(error: String) {
                        runOnUiThread {
                            Log.e(TAG, "Discovery error: $error")
                            // Show error snackbar - server list is still usable with saved servers
                            showErrorSnackbar(
                                message = getString(R.string.error_discovery),
                                errorType = ErrorType.DISCOVERY,
                                retryAction = { startAutoDiscovery() }
                            )
                        }
                    }
                }
            )
            Log.d(TAG, "NsdDiscoveryManager initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize discovery manager", e)
            showErrorSnackbar(
                message = getString(R.string.error_discovery_start),
                errorType = ErrorType.GENERAL
            )
        }
    }

    /**
     * Initializes the DefaultServerPinger for auto-connect when mDNS hasn't
     * found the default server yet.
     *
     * When the default server isn't discovered via mDNS (e.g. its
     * announcement was missed), this pinger periodically checks if the
     * server is reachable and triggers auto-connect on success.
     */
    private fun initializeDefaultServerPinger() {
        // Initialize the pinger
        defaultServerPinger = DefaultServerPinger(
            onServerReachable = { server ->
                runOnUiThread {
                    // Only connect if conditions still allow
                    if (!userManuallyDisconnected &&
                        connectionState == AppConnectionState.ServerList &&
                        PlaybackService.reconnectStatus.value !is ReconnectStatus.Attempting) {
                        Log.i(TAG, "Default server reachable via ping - auto-connecting to ${server.name}")
                        onUnifiedServerSelected(server)
                    } else {
                        Log.d(TAG, "Ping found server but conditions changed - skipping connect")
                    }
                }
            }
        )

        // Register charging state receiver for adaptive ping intervals
        chargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val isCharging = intent.action == Intent.ACTION_POWER_CONNECTED
                Log.d(TAG, "Charging state changed: isCharging=$isCharging")
                defaultServerPinger?.onChargingChanged(isCharging)
            }
        }
        registerReceiver(chargingReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, Context.RECEIVER_NOT_EXPORTED)

        Log.d(TAG, "DefaultServerPinger initialized")
    }

    /**
     * Starts auto-discovery for servers.
     * Called automatically when showing the server list view.
     */
    private fun startAutoDiscovery() {
        Log.d(TAG, "Starting auto-discovery")
        try {
            discoveryManager?.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            showErrorSnackbar(
                message = getString(R.string.error_discovery_start),
                errorType = ErrorType.DISCOVERY,
                retryAction = { startAutoDiscovery() }
            )
        }
    }

    /**
     * Initializes MediaController to communicate with PlaybackService.
     *
     * MediaController provides:
     * - Playback control (play, pause, stop)
     * - State observation (playing, paused, buffering)
     * - Metadata updates (title, artist, album)
     * - Custom commands (connect, disconnect, volume)
     */
    private fun initializeMediaController() {
        // Create session token for our PlaybackService
        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )

        // Build MediaController asynchronously
        mediaControllerFuture = MediaController.Builder(this, sessionToken)
            .setListener(MediaControllerListener())
            .buildAsync()

        // Add callback when controller is ready
        mediaControllerFuture?.addListener(
            {
                try {
                    mediaController = mediaControllerFuture?.get()
                    Log.d(TAG, "MediaController connected to PlaybackService")

                    // Add player listener for state updates
                    mediaController?.addListener(PlayerStateListener())

                    // Read current session extras from an already-running service.
                    // onExtrasChanged only fires on future changes, so we must
                    // read the initial extras explicitly to restore connection
                    // state, metadata, and server name on Activity reconnection.
                    val extras = mediaController?.sessionExtras
                    if (extras != null && !extras.isEmpty) {
                        runOnUiThread { processSessionExtras(extras) }
                    }

                    // Fallback: infer state from player if no extras were available
                    syncUIWithPlayerState()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect MediaController", e)
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    /**
     * Listener for MediaController connection events and session extras.
     */
    private inner class MediaControllerListener : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            Log.d(TAG, "MediaController disconnected from service")
        }

        /**
         * Called when session extras change (metadata and connection state updates from PlaybackService).
         * This is how PlaybackService notifies us of:
         * - Connection state changes (connected, disconnected, error)
         * - Metadata updates (since we can't use MediaItem metadata with custom protocol)
         */
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            runOnUiThread { processSessionExtras(extras) }
        }
    }

    /**
     * Processes session extras from PlaybackService, handling connection state,
     * metadata, progress, volume, and group updates.
     *
     * Called both from onExtrasChanged (incremental updates) and from
     * initializeMediaController (initial state restore on Activity reconnection).
     * Must be called on the main thread.
     */
    private fun processSessionExtras(extras: Bundle) {
        // Handle connection state changes
        val connectionStateStr = extras.getString(PlaybackService.EXTRA_CONNECTION_STATE)
        if (connectionStateStr != null) {
            handleConnectionStateChange(connectionStateStr, extras)
        }

        // The service sends this only while connected, so an absent value means
        // "nothing to explain" - which is also the right answer for an unknown
        // name from a newer service than this build understands.
        val admission = extras.getString(PlaybackService.EXTRA_ADMISSION_STATE)
            ?.let { name -> AdmissionState.entries.firstOrNull { it.name == name } }
            ?: AdmissionState.READY
        viewModel.updateAdmissionState(admission)

        // Both null/false when absent, matching "nothing to explain" above.
        viewModel.updatePairingCode(extras.getString(PlaybackService.EXTRA_PAIRING_CODE))
        viewModel.updatePairingGestureRequested(
            extras.getBoolean(PlaybackService.EXTRA_PAIRING_GESTURE_REQUESTED, false)
        )

        // Handle metadata updates
        val title = extras.getString(PlaybackService.EXTRA_TITLE, "")
        val artist = extras.getString(PlaybackService.EXTRA_ARTIST, "")
        val album = extras.getString(PlaybackService.EXTRA_ALBUM, "")
        val artworkUrl = extras.getString(PlaybackService.EXTRA_ARTWORK_URL, "")

        if (title.isNotEmpty() || artist.isNotEmpty() || album.isNotEmpty()) {
            Log.d(TAG, "Metadata changed: $title / $artist (artwork: $artworkUrl)")
            viewModel.updateMetadata(title, artist, album)

            if (artworkUrl.isNotEmpty()) {
                viewModel.updateArtwork(ArtworkSource.Url(artworkUrl))
            }
        }

        // Handle track progress updates
        val durationMs = extras.getLong(PlaybackService.EXTRA_DURATION_MS, -1)
        val positionMs = extras.getLong(PlaybackService.EXTRA_POSITION_MS, -1)
        if (durationMs >= 0 || positionMs >= 0) {
            val positionUpdatedAt = extras.getLong(
                PlaybackService.EXTRA_POSITION_UPDATED_AT,
                SystemClock.elapsedRealtime()
            )
            viewModel.updateTrackProgress(
                positionMs = if (positionMs >= 0) positionMs else 0,
                durationMs = if (durationMs >= 0) durationMs else 0,
                positionUpdatedAt = positionUpdatedAt
            )
        }

        // Handle volume updates from server
        val volume = extras.getInt(PlaybackService.EXTRA_VOLUME, -1)
        if (volume in 0..100) {
            Log.d(TAG, "Server volume update received: $volume%")
            // Sync state to ViewModel for Compose UI
            viewModel.updateVolume(volume / 100f)
        }

        // Handle group name updates
        val groupName = extras.getString(PlaybackService.EXTRA_GROUP_NAME)
        if (groupName != null) {
            Log.d(TAG, "Group name update received: $groupName")
            viewModel.updateGroupName(groupName)
        }
    }

    /**
     * Handles connection state changes broadcast from PlaybackService.
     * Updates the UI state machine based on the connection state.
     */
    private fun handleConnectionStateChange(stateStr: String, extras: Bundle) {
        // Don't update UI if activity is finishing or destroyed
        if (isFinishing || isDestroyed) {
            Log.d(TAG, "Ignoring connection state change - activity finishing/destroyed")
            return
        }

        Log.d(TAG, "Connection state changed: $stateStr")

        when (stateStr) {
            PlaybackService.STATE_CONNECTING -> {
                // Already handled by connectToServer(), but sync if needed
                if (connectionState !is AppConnectionState.Connecting) {
                    Log.d(TAG, "Received CONNECTING state from service")
                }
            }
            PlaybackService.STATE_CONNECTED -> {
                val serverName = extras.getString(PlaybackService.EXTRA_SERVER_NAME, "Unknown Server")
                Log.d(TAG, "Connected to: $serverName")

                // Only act on actual connection transitions (Connecting/Reconnecting -> Connected).
                // broadcastSessionExtras() re-sends STATE_CONNECTED on every metadata/volume/group
                // update, so we must ignore it when already Connected.
                if (connectionState is AppConnectionState.Connected) {
                    return
                }

                // Get address from current connecting state or reconnecting state.
                // Fall back to currentConnectedServerId when state has moved past
                // Connecting/Reconnecting (e.g., stale broadcast or race condition).
                val address = when (val currentState = connectionState) {
                    is AppConnectionState.Connecting -> currentState.serverAddress
                    is AppConnectionState.Reconnecting -> currentState.serverAddress
                    // A server connected to us: there is no saved server
                    // behind the connection.
                    is AppConnectionState.ServerList -> ""
                    else -> {
                        Log.w(TAG, "STATE_CONNECTED received in unexpected state: $currentState, using serverId fallback")
                        currentConnectedServerId ?: ""
                    }
                }

                connectionState = AppConnectionState.Connected(serverName, address)
                // Sync state to ViewModel for Compose UI
                viewModel.updateConnectionState(connectionState)
                viewModel.clearReconnectingState()
                updateKeepScreenOn(mediaController?.isPlaying == true)

                showNowPlayingView()
                hideReconnectingIndicator()  // Hide any reconnecting indicator
            }
            PlaybackService.STATE_RECONNECTING -> {
                val serverName = extras.getString(PlaybackService.EXTRA_SERVER_NAME, "Unknown Server")
                val attempt = extras.getInt(PlaybackService.EXTRA_RECONNECT_ATTEMPT, 1)
                Log.d(TAG, "Reconnecting to: $serverName (attempt $attempt)")

                // Preserve server address from previous state.
                // Fall back to currentConnectedServerId for robustness.
                val address = when (val currentState = connectionState) {
                    is AppConnectionState.Connected -> currentState.serverAddress
                    is AppConnectionState.Reconnecting -> currentState.serverAddress
                    else -> {
                        Log.w(TAG, "STATE_RECONNECTING received in unexpected state: $currentState, using serverId fallback")
                        currentConnectedServerId ?: ""
                    }
                }

                connectionState = AppConnectionState.Reconnecting(
                    serverName = serverName,
                    serverAddress = address,
                    attempt = attempt,
                    nextRetrySeconds = (1 shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30)
                )
                // Sync state to ViewModel for Compose UI
                viewModel.updateConnectionState(connectionState)
                viewModel.updateReconnectingState(serverName, attempt)

                // Show reconnecting indicator without leaving the now playing view
                showReconnectingIndicator(attempt)
            }
            PlaybackService.STATE_DISCONNECTED -> {
                // Whether to reconnect is the service's decision: had it
                // wanted to, this would have said RECONNECTING.
                Log.d(TAG, "Disconnected from server")

                connectionState = AppConnectionState.ServerList
                // Sync state to ViewModel for Compose UI
                viewModel.updateConnectionState(connectionState)
                viewModel.resetPlaybackState()
                updateKeepScreenOn(false)
                hideReconnectingIndicator()
                showServerListView()
            }
            PlaybackService.STATE_ERROR -> {
                val errorMessage = extras.getString(PlaybackService.EXTRA_ERROR_MESSAGE, "Unknown error")
                Log.e(TAG, "Connection error: $errorMessage")

                connectionState = AppConnectionState.Error(errorMessage)
                // Sync state to ViewModel for Compose UI
                viewModel.updateConnectionState(connectionState)
                showServerListView()

                // Clear unified server adapter statuses

                showErrorSnackbar(
                    message = errorMessage,
                    errorType = ErrorType.CONNECTION
                )
            }
        }
    }

    /**
     * Listener for player state changes from the service.
     */
    private inner class PlayerStateListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runOnUiThread {
                Log.d(TAG, "isPlaying changed: $isPlaying")
                // Sync isPlaying to ViewModel for Compose UI.
                // Keep the current playback state -- only onPlaybackStateChanged
                // should transition between IDLE/BUFFERING/READY/ENDED.
                // Previously this set IDLE on pause, which disabled all controls.
                viewModel.updatePlaybackState(isPlaying, viewModel.playbackState.value)

                updateKeepScreenOn(isPlaying)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            runOnUiThread {
                Log.d(TAG, "Playback state: $playbackState")
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        // Sync state to ViewModel for Compose UI
                        viewModel.updatePlaybackState(false, PlaybackState.IDLE)
                        // Only transition to server list if we were connected/connecting
                        // (not during initial startup), and not while the service is
                        // reconnecting: the player goes idle on a drop too.
                        val currentState = connectionState
                        if ((currentState is AppConnectionState.Connected ||
                            currentState is AppConnectionState.Connecting) &&
                            PlaybackService.reconnectStatus.value !is ReconnectStatus.Attempting) {
                            connectionState = AppConnectionState.ServerList
                            viewModel.updateConnectionState(connectionState)
                            showServerListView()
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        // Sync state to ViewModel for Compose UI
                        viewModel.updatePlaybackState(false, PlaybackState.BUFFERING)
                        // Transition to Connected state and show now playing view
                        val currentState = connectionState
                        if (currentState is AppConnectionState.Connecting) {
                            connectionState = AppConnectionState.Connected(
                                currentState.serverName,
                                currentState.serverAddress
                            )
                            viewModel.updateConnectionState(connectionState)
                            showNowPlayingView()
                        }
                    }
                    Player.STATE_READY -> {
                        // Sync state to ViewModel for Compose UI
                        viewModel.updatePlaybackState(mediaController?.isPlaying ?: false, PlaybackState.READY)
                        // Only show now playing on initial connection (Connecting -> Connected).
                        // If already Connected, do NOT call showNowPlayingView() --
                        // the user may be browsing tabs or interacting with the mini player.
                        val currentState = connectionState
                        if (currentState is AppConnectionState.Connecting) {
                            connectionState = AppConnectionState.Connected(
                                currentState.serverName,
                                currentState.serverAddress
                            )
                            viewModel.updateConnectionState(connectionState)
                            showNowPlayingView()
                        }
                    }
                    Player.STATE_ENDED -> {
                        // Sync state to ViewModel for Compose UI
                        viewModel.updatePlaybackState(false, PlaybackState.ENDED)
                    }
                }
            }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            runOnUiThread {
                val title = mediaMetadata.title?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                val album = mediaMetadata.albumTitle?.toString() ?: ""
                Log.d(TAG, "Metadata from service: $title / $artist / $album")
                viewModel.updateMetadata(title, artist, album)

                // Load album art from MediaMetadata
                updateAlbumArt(mediaMetadata)
            }
        }
    }

    /**
     * Syncs UI with current player state when controller connects.
     * Also restores the correct view (now playing vs searching) based on playback state.
     * Called from onResume(), onNewIntent(), and when MediaController first connects.
     */
    private fun syncUIWithPlayerState() {
        mediaController?.let { controller ->
            val isPlaying = controller.isPlaying
            val state = controller.playbackState

            runOnUiThread {
                // PlayerStateListener only hears changes. An Activity recreated
                // over a service that is already playing gets none, and its new
                // ViewModel would sit at IDLE with every control disabled.
                viewModel.updatePlaybackState(
                    isPlaying,
                    when (state) {
                        Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                        Player.STATE_READY -> PlaybackState.READY
                        Player.STATE_ENDED -> PlaybackState.ENDED
                        else -> PlaybackState.IDLE
                    }
                )

                // Check if we're actively connected (playing or ready to play)
                val isConnected = isPlaying || state == Player.STATE_READY || state == Player.STATE_BUFFERING

                if (isConnected) {
                    // Restore connection state if needed (e.g., after activity recreation)
                    // Don't overwrite Reconnecting state - the service is still trying
                    if (connectionState !is AppConnectionState.Connected &&
                        connectionState !is AppConnectionState.Reconnecting) {
                        // The service publishes the server it is connected to
                        val serverName = controller.sessionExtras
                            .getString(PlaybackService.EXTRA_SERVER_NAME) ?: "Connected"
                        connectionState = AppConnectionState.Connected(serverName, "")
                        viewModel.updateConnectionState(connectionState)
                        showNowPlayingView()
                    }
                } else {
                    // Don't tear down the UI on transient STATE_IDLE during activity resume.
                    // The authoritative disconnect comes from PlayerStateListener.onPlaybackStateChanged(STATE_IDLE),
                    // not from polling the controller state at resume time.
                    if (connectionState is AppConnectionState.Connected ||
                        connectionState is AppConnectionState.Reconnecting) {
                        Log.d(TAG, "syncUIWithPlayerState: player reports idle/ended but connectionState=$connectionState -- keeping UI (transient)")
                    } else {
                        if (connectionState is AppConnectionState.Connecting) {
                            Log.d(TAG, "Player not ready while connecting - resetting to server list")
                            connectionState = AppConnectionState.ServerList
                            viewModel.updateConnectionState(connectionState)
                            showServerListView()
                        }
                    }
                }

                updateKeepScreenOn(isPlaying)

                // Sync metadata and artwork
                val metadata = controller.mediaMetadata
                viewModel.updateMetadata(
                    metadata.title?.toString() ?: "",
                    metadata.artist?.toString() ?: "",
                    metadata.albumTitle?.toString() ?: ""
                )
                updateAlbumArt(metadata)
            }
        }
    }

    // ============================================================================
    // Unified Server Support (MVP)
    // ============================================================================

    /**
     * Sets up unified server functionality.
     * This MVP implementation adds a FAB for adding unified servers and observes
     * the UnifiedServerRepository for server list updates.
     */
    private fun setupUnifiedServers() {
        // Initialize the connector for handling unified server connections
        unifiedServerConnector = UnifiedServerConnector { selected ->
            // Callback when connection method is selected
            Log.d(TAG, "Connection method selected: ${ConnectionSelector.getConnectionDescription(selected)}")
        }

        // Server list UI is now Compose-based (ServerListScreen in AppShell)
    }

    /**
     * Shows the add server wizard activity for creating unified servers.
     * Uses full-screen Activity instead of Dialog for proper keyboard handling.
     */
    private fun showAddServerWizard() {
        val intent = Intent(this, AddServerWizardActivity::class.java)
        addServerWizardLauncher.launch(intent)
    }

    /**
     * Shows the add server wizard activity for editing an existing server.
     */
    private fun showEditServerWizard(server: UnifiedServer) {
        val intent = Intent(this, AddServerWizardActivity::class.java).apply {
            putExtra(AddServerWizardActivity.EXTRA_EDIT_SERVER_ID, server.id)
        }
        addServerWizardLauncher.launch(intent)
    }

    /**
     * Handles tap on a unified server - connects using auto-selection.
     */
    private fun onUnifiedServerSelected(server: UnifiedServer) {
        Log.d(TAG, "Server selected from Compose UI: ${server.name} (id=${server.id})")
        val controller = mediaController
        if (controller == null) {
            showErrorSnackbar(
                message = getString(R.string.error_service_not_connected),
                errorType = ErrorType.CONNECTION
            )
            return
        }

        val connector = unifiedServerConnector
        if (connector == null) {
            Log.e(TAG, "UnifiedServerConnector not initialized")
            return
        }

        // The service is already working on this server; connecting to
        // another one makes it stop.
        val reconnectingId = (PlaybackService.reconnectStatus.value as? ReconnectStatus.Attempting)?.serverId
        if (reconnectingId == server.id) {
            Log.d(TAG, "User tapped reconnecting server - reconnect continues")
            return
        }

        // Handle userManuallyDisconnected flag based on what the user selected
        if (server.isDefaultServer) {
            // User manually connected to default server - allow future auto-connects
            userManuallyDisconnected = false
            Log.d(TAG, "User selected default server - cleared userManuallyDisconnected flag")
        } else if (connectionState is AppConnectionState.Connected) {
            // User switching from one server to another (non-default) - block auto-connect
            userManuallyDisconnected = true
            defaultServerPinger?.stop()  // Don't ping after manual switch
            Log.d(TAG, "User switched to non-default server - set userManuallyDisconnected flag")
        }

        // Stop discovery if running
        discoveryManager?.stopDiscovery()

        // Track the server ID for editing while connected
        currentConnectedServerId = server.id

        // Update state to connecting
        connectionState = AppConnectionState.Connecting(server.name, server.id)
        viewModel.updateConnectionState(connectionState)

        // Connect using auto-selection
        val selected = connector.connect(server, controller)
        if (selected == null) {
            connectionState = AppConnectionState.Error("No connection method available")
            showErrorSnackbar(
                message = getString(R.string.no_connection_available),
                errorType = ErrorType.CONNECTION
            )
            return
        }

        // Update adapter status for both adapters

        // Show which method was selected
        val methodDesc = ConnectionSelector.getConnectionDescription(selected)
        Log.d(TAG, "Connecting to ${server.name} via $methodDesc")
    }

    /**
     * Shows context menu for a unified server (long press).
     */
    private fun showUnifiedServerContextMenu(server: UnifiedServer) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // Edit option (only for saved servers)
        if (!server.isDiscovered) {
            items.add(getString(R.string.edit_server))
            actions.add {
                showEditServerWizard(server)
            }
        }

        // Set as default / Remove default (only for saved servers)
        if (!server.isDiscovered) {
            if (server.isDefaultServer) {
                items.add(getString(R.string.remove_default))
                actions.add {
                    UnifiedServerRepository.setDefaultServer(null)
                    showInfoSnackbar("Default server cleared")
                }
            } else {
                items.add(getString(R.string.set_as_default))
                actions.add {
                    UnifiedServerRepository.setDefaultServer(server.id)
                    showInfoSnackbar("${server.name} set as default")
                }
            }
        }

        // Delete option (only for saved servers)
        if (!server.isDiscovered) {
            items.add(getString(R.string.delete_server))
            actions.add {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_server)
                    .setMessage("Delete \"${server.name}\"?")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        UnifiedServerRepository.deleteServer(server.id)
                        showInfoSnackbar("Server deleted")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        // Save option (only for discovered servers)
        if (server.isDiscovered) {
            items.add(getString(R.string.save_server))
            actions.add {
                UnifiedServerRepository.promoteDiscoveredServer(server)
                showSuccessSnackbar(getString(R.string.server_added, server.name))
            }
        }

        if (items.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(server.name)
                .setItems(items.toTypedArray()) { _, which ->
                    actions[which]()
                }
                .show()
        }
    }

    // updateUnifiedServerConnectionStatus() removed - server list is now Compose-based

    /**
     * Handles previous button click.
     * Sends previous track command to PlaybackService via custom command.
     */
    private fun onPreviousClicked() {
        Log.d(TAG, "Previous clicked")
        val controller = mediaController ?: return
        val command = SessionCommand(PlaybackService.COMMAND_PREVIOUS, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)
    }

    /**
     * Handles play/pause toggle button click.
     * Toggles between play and pause based on current state.
     */
    private fun onPlayPauseClicked() {
        val controller = mediaController ?: return

        if (controller.isPlaying) {
            Log.d(TAG, "Pause clicked")
            controller.pause()
        } else {
            Log.d(TAG, "Play clicked")
            controller.play()
        }
    }

    /**
     * Handles next button click.
     * Sends next track command to PlaybackService via custom command.
     */
    private fun onNextClicked() {
        Log.d(TAG, "Next clicked")
        val controller = mediaController ?: return
        val command = SessionCommand(PlaybackService.COMMAND_NEXT, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)
    }

    /**
     * Handles switch group button click.
     * Sends switch group command to PlaybackService to cycle to next available group.
     */
    private fun onSwitchGroupClicked() {
        Log.d(TAG, "Switch group clicked")
        val controller = mediaController ?: return
        val command = SessionCommand(PlaybackService.COMMAND_SWITCH_GROUP, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)
    }

    /**
     * Handles the "Allow pairing" gesture button click.
     * Sends the allow-pairing command to PlaybackService, which forwards it
     * to the dynamic pairing flow as a WindowOpened event.
     */
    private fun onAllowPairingClicked() {
        Log.d(TAG, "Allow pairing clicked")
        val controller = mediaController ?: return
        val command = SessionCommand(PlaybackService.COMMAND_ALLOW_PAIRING, Bundle.EMPTY)
        controller.sendCustomCommand(command, Bundle.EMPTY)
    }

    /**
     * Observes PlaybackService.networkState (mirrored from ConnectionCoordinator) to:
     * - Notify DefaultServerPinger of every network change so it can trigger an immediate ping.
     * - Show a "Network connection lost" snackbar when the network goes away while the user
     *   is connected or connecting to a server.
     *
     * This replaces the deleted ConnectivityManager.NetworkCallback that MainActivity
     * registered directly. The Coordinator owns the single NetworkCallback; both the
     * validation-loss debounce and full link-loss paths emit isConnected=false, so a
     * single snackbar message covers both cases.
     */
    private fun observeNetworkState() {
        lifecycleScope.launch {
            var prevConnected: Boolean? = null
            PlaybackService.networkState.collect { state ->
                // Notify pinger on every emission (matches the unconditional onAvailable /
                // onCapabilitiesChanged behavior of the deleted networkCallback).
                defaultServerPinger?.onNetworkChanged()

                val connected = state.isConnected
                if (prevConnected == true && !connected) {
                    // Network was connected and is now gone (covers both full link-loss and
                    // validation-loss-after-debounce paths in the Coordinator).
                    Log.w(TAG, "networkState: connection lost (isConnected false)")
                    if (connectionState is AppConnectionState.Connected ||
                        connectionState is AppConnectionState.Connecting) {
                        showErrorSnackbar(
                            message = "Network connection lost",
                            errorType = ErrorType.NETWORK
                        )
                    }
                }
                prevConnected = connected
            }
        }
    }

    /**
     * Handles disconnect button click.
     * Shows confirmation dialog before disconnecting.
     */
    private fun onDisconnectClicked() {
        Log.d(TAG, "Disconnect clicked")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disconnect_dialog_title)
            .setMessage(R.string.disconnect_dialog_message)
            .setPositiveButton(R.string.disconnect_dialog_positive) { _, _ ->
                performDisconnect()
            }
            .setNegativeButton(R.string.disconnect_dialog_negative, null)
            .show()
    }

    /**
     * Performs the actual disconnect operation.
     * Sends disconnect command to PlaybackService and returns to server list view.
     *
     * This is a user-initiated disconnect ("Switch Server"), so we set
     * userManuallyDisconnected to prevent auto-connecting to the default server.
     */
    private fun performDisconnect() {
        val controller = mediaController ?: return

        // User explicitly chose to disconnect - block auto-connect to default server
        userManuallyDisconnected = true
        defaultServerPinger?.stop()  // Don't ping after manual disconnect

        try {
            val command = SessionCommand(PlaybackService.COMMAND_DISCONNECT, Bundle.EMPTY)
            controller.sendCustomCommand(command, Bundle.EMPTY)
            transitionToServerList()
            showInfoSnackbar("Disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            showErrorSnackbar(
                message = "Failed to disconnect",
                errorType = ErrorType.CONNECTION
            )
        }
    }

    /**
     * Handles exit app button click.
     * Shows confirmation dialog before exiting.
     */
    private fun onExitAppClicked() {
        Log.d(TAG, "Exit app clicked")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_app_dialog_title)
            .setMessage(R.string.exit_app_dialog_message)
            .setPositiveButton(R.string.exit_app_dialog_positive) { _, _ ->
                performExitApp()
            }
            .setNegativeButton(R.string.disconnect_dialog_negative, null)
            .show()
    }

    /**
     * Performs full app exit: disconnects from server, stops PlaybackService,
     * and finishes all activities.
     */
    private fun performExitApp() {
        Log.d(TAG, "Performing app exit")

        // Disconnect from server if connected
        try {
            mediaController?.let { controller ->
                val command = SessionCommand(PlaybackService.COMMAND_DISCONNECT, Bundle.EMPTY)
                controller.sendCustomCommand(command, Bundle.EMPTY)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect during exit", e)
        }

        // Stop the PlaybackService
        stopService(android.content.Intent(this, PlaybackService::class.java))

        // Close all activities
        finishAffinity()
    }

    /**
     * Handles volume slider changes.
     *
     * Sets device STREAM_MUSIC volume directly (Spotify-style) AND syncs to server
     * via PlaybackService custom command for multi-client coordination.
     *
     * @param volume Normalized volume from 0.0 to 1.0
     */
    private fun onVolumeChanged(volume: Float) {
        Log.d(TAG, "Volume changed: $volume")

        // Set device volume directly (Spotify-style)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (volume * maxVolume).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

        // Also notify PlaybackService to sync to server (for multi-client coordination)
        val controller = mediaController ?: return
        val args = Bundle().apply {
            putFloat(PlaybackService.ARG_VOLUME, volume)
        }
        val command = SessionCommand(PlaybackService.COMMAND_SET_VOLUME, Bundle.EMPTY)
        controller.sendCustomCommand(command, args)
    }

    /**
     * Syncs the volume slider with the current device STREAM_MUSIC volume.
     * Called on startup and when returning from background.
     */
    private fun syncSliderWithDeviceVolume() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // Whole percent, truncated
        val sliderValue = ((currentVolume.toFloat() / maxVolume) * 100).toInt().toFloat()

        // Sync Compose UI (now playing Compose slider)
        viewModel.updateVolume(sliderValue / 100f)

        Log.d(TAG, "Synced slider with device volume: $currentVolume/$maxVolume ($sliderValue%)")
    }

    /**
     * Registers a ContentObserver to detect device volume changes from hardware buttons.
     * This keeps the UI slider in sync when user presses hardware volume buttons.
     */
    private fun registerVolumeObserver() {
        if (volumeObserver != null) return  // Already registered

        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // Sync slider to device volume when hardware buttons are pressed
                syncSliderWithDeviceVolume()
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver!!
        )
        Log.d(TAG, "Volume observer registered")
    }

    /**
     * Unregisters the volume ContentObserver.
     */
    private fun unregisterVolumeObserver() {
        volumeObserver?.let {
            contentResolver.unregisterContentObserver(it)
            volumeObserver = null
            Log.d(TAG, "Volume observer unregistered")
        }
    }

    /**
     * Updates album art from MediaMetadata.
     *
     * Tries to load artwork from:
     * 1. artworkData (byte array embedded in metadata)
     * 2. artworkUri (URI reference)
     */
    private fun updateAlbumArt(mediaMetadata: MediaMetadata) {
        val artworkData = mediaMetadata.artworkData
        val artworkUri = mediaMetadata.artworkUri

        when {
            artworkData != null && artworkData.isNotEmpty() -> {
                viewModel.updateArtwork(ArtworkSource.ByteArray(artworkData))
            }
            artworkUri != null -> {
                viewModel.updateArtwork(ArtworkSource.Uri(artworkUri))
            }
            else -> {
                viewModel.clearArtwork()
            }
        }
    }

    /**
     * Activity cleanup - critical for preventing resource leaks.
     *
     * Best practice: Proper resource cleanup in lifecycle methods
     * Order matters: Release MediaController before cleaning up other resources
     */
    override fun onDestroy() {
        super.onDestroy()

        // Cancel any pending handler callbacks
        handler.removeCallbacksAndMessages(null)

        // Release MediaController connection to service
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        mediaController = null
        mediaControllerFuture = null

        // Cleanup NsdDiscoveryManager (handles multicast lock internally)
        discoveryManager?.cleanup()
        discoveryManager = null

        // Cleanup DefaultServerPinger and charging receiver
        defaultServerPinger?.destroy()
        defaultServerPinger = null
        chargingReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister charging receiver", e)
            }
        }
        chargingReceiver = null
    }

    // ============================================================================
    // Android TV Remote Control Support
    // ============================================================================

    /**
     * Handles key events from TV remotes and D-pad controllers.
     *
     * Media keys (play/pause, next, previous) are handled when connected to a server.
     * On TV devices, volume up/down keys adjust the app volume slider instead of
     * system volume, providing a more intuitive experience.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle media keys when connected to a server
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (connectionState is AppConnectionState.Connected) {
                    onPlayPauseClicked()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (connectionState is AppConnectionState.Connected) {
                    onPlayPauseClicked()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (connectionState is AppConnectionState.Connected) {
                    onPlayPauseClicked()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (connectionState is AppConnectionState.Connected) {
                    onNextClicked()
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (connectionState is AppConnectionState.Connected) {
                    onPreviousClicked()
                    return true
                }
            }
            // On TV devices, handle volume keys to adjust app volume
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (isTvDevice && connectionState is AppConnectionState.Connected) {
                    adjustVolume(+5)
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (isTvDevice && connectionState is AppConnectionState.Connected) {
                    adjustVolume(-5)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Adjusts the volume by a delta value.
     * Used for TV remote volume button handling.
     *
     * @param delta The amount to adjust (positive = up, negative = down)
     */
    private fun adjustVolume(delta: Int) {
        val currentValue = (viewModel.volume.value * 100).roundToInt()
        val newValue = (currentValue + delta).coerceIn(0, 100)
        viewModel.updateVolume(newValue / 100f)
        onVolumeChanged(newValue / 100f)
    }
}
