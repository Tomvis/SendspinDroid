package com.sendspindroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Surface
import com.sendspindroid.ui.tv.TvMenuEntry
import com.sendspindroid.ui.tv.TvShell
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.sendspin.protocol.ColorState
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.NowPlayingScreen
import com.sendspindroid.ui.main.components.ConnectionStatusDot
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.theme.ServerColorTheme

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
 * @param onVolumeChange Volume slider callback (0-1 range)
 * @param onMuteToggle Volume slider mute button callback
 * @param onSeek Progress bar scrub released at a position in milliseconds
 * @param onAllowPairingClick Confirm the dynamic pairing gesture gate
 * @param onDisconnectClick Disconnect from server
 * @param onAddServerClick TV: the side menu's Add Server entry
 * @param canEditServer TV: whether the connection is to a saved server, which Edit Server opens
 * @param onLeaveApp TV: Back pressed in the side menu, to leave the app
 * @param serverColors The server's palette for the current audio, which colors
 *   Now Playing and its frame while connected; null for the Material colors
 */
@Composable
fun AppShell(
    viewModel: MainActivityViewModel,
    serverListContent: @Composable () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onAllowPairingClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onAddServerClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditServerClick: () -> Unit,
    onExitAppClick: () -> Unit,
    modifier: Modifier = Modifier,
    canEditServer: Boolean = true,
    onLeaveApp: () -> Unit = {},
    serverColors: ColorState? = null,
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    if (LocalFormFactor.current == FormFactor.TV) {
        TvAppShell(
            viewModel = viewModel,
            serverListContent = serverListContent,
            nowPlaying = {
                NowPlayingScreen(
                    viewModel = viewModel,
                    onPreviousClick = onPreviousClick,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onSwitchGroupClick = onSwitchGroupClick,
                    onVolumeChange = onVolumeChange,
                    onMuteToggle = onMuteToggle,
                    onSeek = onSeek,
                    onOpenPairingClick = onSettingsClick,
                    onAllowPairingClick = onAllowPairingClick,
                    modifier = Modifier.fillMaxSize()
                )
            },
            onDisconnectClick = onDisconnectClick,
            onAddServerClick = onAddServerClick,
            onStatsClick = onStatsClick,
            onSettingsClick = onSettingsClick,
            onEditServerClick = onEditServerClick.takeIf { canEditServer },
            onExitAppClick = onExitAppClick,
            onLeaveApp = onLeaveApp,
            serverColors = serverColors,
            modifier = modifier
        )
        return
    }

    when (connectionState) {
        is AppConnectionState.ServerList,
        is AppConnectionState.Error -> {
            ServerListShell(
                serverListContent = serverListContent,
                onSettingsClick = onSettingsClick,
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
                onVolumeChange = onVolumeChange,
                onMuteToggle = onMuteToggle,
                onSeek = onSeek,
                onAllowPairingClick = onAllowPairingClick,
                onDisconnectClick = onDisconnectClick,
                onStatsClick = onStatsClick,
                onSettingsClick = onSettingsClick,
                onEditServerClick = onEditServerClick,
                onExitAppClick = onExitAppClick,
                serverColors = serverColors,
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
    onSettingsClick: () -> Unit,
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
            // The pairing token lives in Settings, and a server that needs
            // it has usually not connected yet.
            actions = {
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.action_app_settings)
                    )
                }
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
 * Uses Scaffold (TopAppBar + overflow menu). Now Playing is the only destination;
 * SendSpin defines no browse surface.
 *
 * [serverColors] color the whole Scaffold, so the top bar is on the same
 * background as Now Playing. The overflow menu keeps its Material container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedShell(
    viewModel: MainActivityViewModel,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSwitchGroupClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onMuteToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onAllowPairingClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditServerClick: () -> Unit,
    onExitAppClick: () -> Unit,
    serverColors: ColorState?,
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    // Overflow menu state
    var showOverflowMenu by remember { mutableStateOf(false) }

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

    // Content area passed to Scaffold
    val contentArea: @Composable (PaddingValues) -> Unit = { innerPadding ->
        NowPlayingScreen(
            viewModel = viewModel,
            onPreviousClick = onPreviousClick,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick,
            onSwitchGroupClick = onSwitchGroupClick,
            onVolumeChange = onVolumeChange,
            onMuteToggle = onMuteToggle,
            onSeek = onSeek,
            onOpenPairingClick = onSettingsClick,
            onAllowPairingClick = onAllowPairingClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    ServerColorTheme(serverColors) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            content = contentArea
        )
    }
}

/**
 * The TV frame: the side menu of [TvShell] in place of the top bar, the
 * overflow menu and the corner buttons, around the server list or Now Playing.
 *
 * The connection status the phone shows in its top bar is at the top of the
 * menu: a dot while it is collapsed, the server's name with it when open.
 *
 * [serverColors] color the frame while connected, so the collapsed menu's
 * rail is on the same background as Now Playing. The open menu, a panel over
 * the screen, keeps the Material colors, as menus do: the spec's contrast for
 * `on_X` holds only on the server's background.
 *
 * @param onEditServerClick Null when the connection is not to a saved server.
 */
@Composable
private fun TvAppShell(
    viewModel: MainActivityViewModel,
    serverListContent: @Composable () -> Unit,
    nowPlaying: @Composable () -> Unit,
    onDisconnectClick: () -> Unit,
    onAddServerClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditServerClick: (() -> Unit)?,
    onExitAppClick: () -> Unit,
    onLeaveApp: () -> Unit,
    serverColors: ColorState?,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connected = when (connectionState) {
        is AppConnectionState.ServerList, is AppConnectionState.Error -> false
        is AppConnectionState.Connecting,
        is AppConnectionState.Connected,
        is AppConnectionState.Reconnecting -> true
    }

    val settings = TvMenuEntry(stringResource(R.string.action_app_settings), Icons.Default.Settings, onSettingsClick)
    val exit = TvMenuEntry(stringResource(R.string.action_exit_app), Icons.AutoMirrored.Filled.ExitToApp, onExitAppClick)
    val home: TvMenuEntry
    val entries: List<TvMenuEntry>
    if (connected) {
        home = TvMenuEntry(stringResource(R.string.now_playing), Icons.Default.PlayArrow)
        entries = listOfNotNull(
            TvMenuEntry(stringResource(R.string.action_switch_server), Icons.AutoMirrored.Filled.List, onDisconnectClick),
            onEditServerClick?.let { TvMenuEntry(stringResource(R.string.action_edit_server), Icons.Default.Edit, it) },
            TvMenuEntry(stringResource(R.string.action_stats), Icons.Default.Info, onStatsClick),
        )
    } else {
        home = TvMenuEntry(stringResource(R.string.tv_menu_servers), Icons.AutoMirrored.Filled.List)
        entries = listOf(TvMenuEntry(stringResource(R.string.add_server), Icons.Default.Add, onAddServerClick))
    }

    val material = MaterialTheme.colorScheme
    // Not blended out of when leaving: the server list keeps the Material colors
    ServerColorTheme(serverColors.takeIf { connected }, blend = connected) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            TvShell(
                home = home,
                entries = entries,
                footerEntries = listOf(settings, exit),
                onLeave = onLeaveApp,
                header = { open -> TvMenuHeader(connectionState, open) },
                menuColorScheme = material
            ) {
                if (connected) nowPlaying() else serverListContent()
            }
        }
    }
}

/** The app's name and, when connected, the server and its status. */
@Composable
private fun TvMenuHeader(connectionState: AppConnectionState, open: Boolean) {
    val serverName = when (connectionState) {
        is AppConnectionState.Connected -> connectionState.serverName
        is AppConnectionState.Connecting -> connectionState.serverName
        is AppConnectionState.Reconnecting -> connectionState.serverName
        else -> null
    }
    if (!open) {
        // Lines the dot up with the icons of the collapsed entries
        Box(modifier = Modifier.width(56.dp).height(48.dp), contentAlignment = Alignment.Center) {
            if (serverName != null) {
                ConnectionStatusDot(connectionState = connectionState, modifier = Modifier.size(12.dp))
            }
        }
        return
    }
    Column(modifier = Modifier.width(232.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (serverName != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionStatusDot(connectionState = connectionState, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (connectionState is AppConnectionState.Reconnecting) {
                        stringResource(R.string.reconnecting_toolbar_subtitle)
                    } else {
                        serverName
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
