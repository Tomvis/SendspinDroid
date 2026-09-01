package com.sendspindroid.ui

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
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.main.MainActivityViewModel
import com.sendspindroid.ui.main.NowPlayingScreen
import com.sendspindroid.ui.main.components.ConnectionStatusDot
import com.sendspindroid.ui.adaptive.FormFactor

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
 * @param onDisconnectClick Disconnect from server
 * @param onAddServerClick FAB: launch add server wizard
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
    onDisconnectClick: () -> Unit,
    onAddServerClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditServerClick: () -> Unit,
    onExitAppClick: () -> Unit,
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
                onDisconnectClick = onDisconnectClick,
                onStatsClick = onStatsClick,
                onSettingsClick = onSettingsClick,
                onEditServerClick = onEditServerClick,
                onExitAppClick = onExitAppClick,
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
 * Uses Scaffold (TopAppBar + overflow menu). Now Playing is the only destination;
 * SendSpin defines no browse surface.
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
    onDisconnectClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditServerClick: () -> Unit,
    onExitAppClick: () -> Unit,
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
            onFavoriteClick = onFavoriteClick,
            onVolumeChange = onVolumeChange,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = topBar,
        content = contentArea
    )
}
