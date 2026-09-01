package com.sendspindroid.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sendspindroid.R
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.NowPlayingScreen
import com.sendspindroid.ui.main.components.ConnectionStatusDot
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.tvFocusable
import com.sendspindroid.ui.player.PlayerBottomSheet
import com.sendspindroid.ui.player.PlayerViewModel
import com.sendspindroid.ui.queue.QueueViewModel

private const val TAG = "AppShell"

/**
 * Root Compose shell for the entire app.
 *
 * Manages the top-level state machine:
 * - ServerList/Error: shows server list
 * - Connecting/Connected/Reconnecting: shows connected shell with Now Playing
 *
 * This replaces `activity_main.xml` and all Fragment-based navigation.
 *
 * @param viewModel The main activity ViewModel (shared with MainActivity)
 * @param serverListContent Composable for the server list
 * @param onPreviousClick Playback: previous track
 * @param onPlayPauseClick Playback: play/pause toggle
 * @param onNextClick Playback: next track
 * @param onSwitchGroupClick Switch playback group
 * @param onFavoriteClick Toggle favorite on current track
 * @param onVolumeChange Volume slider callback (0-1 range)
 * @param onQueueClick Open queue view
 * @param onDisconnectClick Disconnect from server
 * @param onAddServerClick FAB: launch add server wizard
 * @param onShowSuccess Show success snackbar message
 * @param onShowError Show error snackbar message
 * @param onShowUndoSnackbar Show undo snackbar (for playlist deletion)
 */
@Composable
fun AppShell(
    viewModel: MainActivityViewModel,
    serverListContent: @Composable () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQueueClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onAddServerClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditServerClick: () -> Unit,
    onExitAppClick: () -> Unit,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit,
    onShowUndoSnackbar: (message: String, onUndo: () -> Unit, onDismissed: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    when (connectionState) {
        is AppConnectionState.ServerList,
        is AppConnectionState.Error -> {
            ServerListShell(
                serverListContent = serverListContent,
                modifier = modifier
            )
        }

        is AppConnectionState.Connecting,
        is AppConnectionState.Connected,
        is AppConnectionState.Reconnecting -> {
            ConnectedShell(
                viewModel = viewModel,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                onSwitchGroupClick = onSwitchGroupClick,
                onFavoriteClick = onFavoriteClick,
                onVolumeChange = onVolumeChange,
                onQueueClick = onQueueClick,
                onDisconnectClick = onDisconnectClick,
                onStatsClick = onStatsClick,
                onSettingsClick = onSettingsClick,
                onEditServerClick = onEditServerClick,
                onExitAppClick = onExitAppClick,
                onShowSuccess = onShowSuccess,
                onShowError = onShowError,
                onShowUndoSnackbar = onShowUndoSnackbar,
                modifier = modifier
            )
        }
    }
}

/**
 * Shell for the server list (disconnected) state.
 * Shows toolbar + server list content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerListShell(
    serverListContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.app_name),
                    style = if (formFactor == FormFactor.TV)
                        MaterialTheme.typography.headlineMedium
                    else
                        MaterialTheme.typography.titleLarge
                )
            },
            expandedHeight = AdaptiveDefaults.topBarHeight(formFactor),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        )

        Box(modifier = Modifier.weight(1f)) {
            serverListContent()
        }
    }
}

/**
 * Shell for the connected state.
 *
 * Uses Scaffold (TopAppBar + overflow menu) nested inside NavigationSuiteScaffold
 * (auto-switches BottomNav / NavigationRail / Drawer based on window size class).
 *
 * Now Playing is the only destination; SendSpin defines no browse surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedShell(
    viewModel: MainActivityViewModel,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onQueueClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditServerClick: () -> Unit,
    onExitAppClick: () -> Unit,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit,
    onShowUndoSnackbar: (message: String, onUndo: () -> Unit, onDismissed: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    val isMaConnected by viewModel.isMaConnected.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    // Overflow menu state
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Now Playing queue sidebar visibility (tablet)
    var nowPlayingQueueVisible by rememberSaveable { mutableStateOf(true) }

    // Player / Speaker Group bottom sheet state
    var showPlayerSheet by remember { mutableStateOf(false) }
    // M-24: Always call viewModel() unconditionally (Compose rule: composable calls must not be conditional)
    val playerViewModel: PlayerViewModel = viewModel()

    // Server name for the toolbar subtitle
    val serverName = when (val state = connectionState) {
        is AppConnectionState.Connected -> state.serverName
        is AppConnectionState.Connecting -> state.serverName
        is AppConnectionState.Reconnecting -> state.serverName
        else -> null
    }

    // Subtitle text: show "Reconnecting..." instead of plain server name when reconnecting
    val subtitleText = when (connectionState) {
        is AppConnectionState.Reconnecting -> stringResource(R.string.reconnecting_toolbar_subtitle)
        else -> serverName
    }

    // Title for the top bar
    val topBarTitle = stringResource(R.string.now_playing)

    // Shared top bar composable
    val topBar: @Composable () -> Unit = {
        TopAppBar(
            expandedHeight = AdaptiveDefaults.topBarHeight(formFactor),
            title = {
                Column {
                    Text(
                        text = topBarTitle,
                        style = if (formFactor == FormFactor.TV)
                            MaterialTheme.typography.headlineMedium
                        else
                            MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitleText != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ConnectionStatusDot(
                                connectionState = connectionState
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            actions = {
                // Queue toggle button (tablet/TV)
                val showQueueToggle = AdaptiveDefaults.showBrowseQueueSidebar(formFactor) && isMaConnected
                if (showQueueToggle) {
                    val queueModifier = if (formFactor == FormFactor.TV) {
                        Modifier.tvFocusable()
                    } else {
                        Modifier
                    }
                    IconButton(
                        onClick = { nowPlayingQueueVisible = !nowPlayingQueueVisible },
                        modifier = queueModifier
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_queue_music),
                            contentDescription = stringResource(R.string.queue_view),
                            tint = if (nowPlayingQueueVisible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.action_menu)
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_stats)) },
                            onClick = {
                                showOverflowMenu = false
                                onStatsClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_edit_server)) },
                            onClick = {
                                showOverflowMenu = false
                                onEditServerClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_switch_server)) },
                            onClick = {
                                showOverflowMenu = false
                                onDisconnectClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_app_settings)) },
                            onClick = {
                                showOverflowMenu = false
                                onSettingsClick()
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_exit_app)) },
                            onClick = {
                                showOverflowMenu = false
                                onExitAppClick()
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        )
    }

    // QueueViewModel for tablet inline queue panel and TV queue sidebar
    // M-24: Always call viewModel() unconditionally; gate usage on the condition instead
    val queueViewModel: QueueViewModel = viewModel()
    val showQueueViewModel = (AdaptiveDefaults.showInlineQueuePanel(formFactor) ||
         AdaptiveDefaults.hasTvQueueSidebar(formFactor) ||
         AdaptiveDefaults.showBrowseQueueSidebar(formFactor) ||
         formFactor == FormFactor.HEADUNIT) && isMaConnected

    // Content composable shared between both layouts
    val contentArea: @Composable (PaddingValues) -> Unit = { innerPadding ->
        NowPlayingScreen(
            viewModel = viewModel,
            onPreviousClick = onPreviousClick,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick,
            onSwitchGroupClick = onSwitchGroupClick,
            onFavoriteClick = onFavoriteClick,
            onVolumeChange = onVolumeChange,
            onQueueClick = onQueueClick,
            queueViewModel = if (showQueueViewModel) queueViewModel else null,
            showPlayerButton = isMaConnected,
            onPlayerClick = { showPlayerSheet = true },
            inlineQueueVisible = nowPlayingQueueVisible,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    if (!isMaConnected) {
        // No MA -> just Scaffold with top bar, no bottom nav
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            content = contentArea
        )
    } else {
        // MA connected -> NavigationSuiteScaffold with the Now Playing nav item
        // Override navigation type based on form factor and orientation:
        // - Phone landscape: force NavigationRail (auto-detect sometimes stays on BottomNav)
        // - Tablet portrait: force BottomNav (default gives Rail, but portrait tablets should
        //   match phone portrait behavior)
        // - HEADUNIT: force BottomNav
        // - Everything else: use adaptive default
        val configuration = LocalConfiguration.current
        val isPhoneLandscape = configuration.smallestScreenWidthDp < 600 &&
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isTabletPortrait = (formFactor == FormFactor.TABLET_7 || formFactor == FormFactor.TABLET_10) &&
            configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val navSuiteType = if (isPhoneLandscape) {
            NavigationSuiteType.NavigationRail
        } else if (isTabletPortrait || formFactor == FormFactor.HEADUNIT) {
            NavigationSuiteType.NavigationBar
        } else {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        }

        NavigationSuiteScaffold(
            modifier = modifier,
            layoutType = navSuiteType,
            navigationSuiteItems = {
                // Now Playing tab (replaces mini player on TV; not needed on phone/tablet
                // since the mini player handles returning to the now playing screen)
                if (!AdaptiveDefaults.showMiniPlayer(formFactor)) {
                    item(
                        icon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nav_now_playing),
                                contentDescription = stringResource(R.string.nav_now_playing)
                            )
                        },
                        label = { Text(stringResource(R.string.nav_now_playing)) },
                        selected = true,
                        onClick = {
                            viewModel.clearDetailNavigation()
                            viewModel.setNavigationContentVisible(false)
                        }
                    )
                }
            }
        ) {
            Scaffold(
                topBar = topBar,
                content = contentArea
            )
        }
    }

    // Player / Speaker Group bottom sheet
    if (showPlayerSheet && isMaConnected) {
        PlayerBottomSheet(
            viewModel = playerViewModel,
            onDismiss = { showPlayerSheet = false }
        )
    }
}
