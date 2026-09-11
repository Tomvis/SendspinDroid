package com.sendspindroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.annotation.StringRes
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.model.AppConnectionState
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.LocalTvFocusReclaimToken
import com.sendspindroid.ui.adaptive.tvFocusable
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
 * @param onAllowPairingClick Confirm the dynamic pairing gesture gate
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
    onAllowPairingClick: () -> Unit,
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
                onAllowPairingClick = onAllowPairingClick,
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
    onAllowPairingClick: () -> Unit,
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
    // Incremented each time a transient TV overlay (overflow menu) dismisses,
    // so the underlying NowPlaying focus anchor can reclaim D-pad focus.
    // Without this, focus is stranded after the overlay tears down and the
    // user has to back out to the Activity-level BackHandler to recover.
    var tvFocusReclaimToken by remember { mutableIntStateOf(0) }

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

    // Single definition of the overflow menu, rendered twice: inside the top-bar
    // DropdownMenu, and (on TV, where the top bar is hidden) inside the parallel
    // D-pad-operable overlay triggered by the OK / DPAD_CENTER remote button.
    // Each renderer supplies its own dismiss behaviour and divider styling.
    val overflowActions = listOf(
        OverflowAction(R.string.action_stats, onClick = onStatsClick),
        OverflowAction(R.string.action_edit_server, onClick = onEditServerClick),
        OverflowAction(R.string.action_switch_server, onClick = onDisconnectClick),
        OverflowAction(R.string.action_app_settings, onClick = onSettingsClick),
        OverflowAction(R.string.action_exit_app, dividerBefore = true, onClick = onExitAppClick),
    )

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
                        overflowActions.forEach { action ->
                            if (action.dividerBefore) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(action.labelRes)) },
                                onClick = {
                                    showOverflowMenu = false
                                    action.onClick()
                                }
                            )
                        }
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
            onOpenPairingClick = onSettingsClick,
            onAllowPairingClick = onAllowPairingClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    // On TV the top bar is hidden so Now Playing owns the whole screen, which
    // also puts the overflow icon out of reach -- OK / DPAD_CENTER on the
    // passive focus anchor opens the menu instead. The handler returns false
    // unless it actually consumes the press, so focused buttons elsewhere keep
    // their normal behavior.
    val hideTopBar = formFactor == FormFactor.TV

    Box(modifier = modifier.onKeyEvent { event ->
        when {
            !hideTopBar || showOverflowMenu -> false
            event.type != KeyEventType.KeyUp -> false
            event.key == Key.DirectionCenter ||
                event.key == Key.Enter ||
                event.key == Key.NumPadEnter -> {
                showOverflowMenu = true
                true
            }
            else -> false
        }
    }) {
        CompositionLocalProvider(
            LocalTvFocusReclaimToken provides tvFocusReclaimToken,
        ) {
            Scaffold(
                topBar = if (hideTopBar) ({}) else topBar,
                content = contentArea
            )
        }
        if (hideTopBar && showOverflowMenu) {
            // Material3 DropdownMenu items are not D-pad operable on TV;
            // render a TV-tailored overlay with tvFocusable items instead.
            TvOverflowMenuOverlay(
                actions = overflowActions,
                onDismiss = {
                    showOverflowMenu = false
                    // Bump the reclaim token so NowPlayingFocus's anchor
                    // re-requests focus once the overlay tears down.
                    tvFocusReclaimToken++
                },
            )
        }
    }
}

/**
 * TV overflow menu overlay: shown on Now Playing when the user presses OK on
 * the remote (top bar is hidden so the dropdown is unreachable). Items are
 * tvFocusable with large hit targets and visible focus ring; first item gets
 * initial focus; Back dismisses.
 */
@Composable
private fun TvOverflowMenuOverlay(
    actions: List<OverflowAction>,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    // Intercept BACK during the tunnel (preview) phase so it dismisses the
    // overlay in one press. Without this, BACK first reaches the focused
    // menu item's `clickable` modifier and is consumed there (clearing the
    // focus highlight without dismissing), so the user has to press BACK
    // twice -- once to clear focus, once for the surrounding BackHandler to
    // fire. Routing BACK here first puts the dismiss above any child focus
    // consumer. KeyDown filter avoids handling the up event.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .padding(48.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                actions.forEachIndexed { index, action ->
                    if (action.dividerBefore) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    TvOverflowMenuItem(
                        label = stringResource(action.labelRes),
                        onClick = { onDismiss(); action.onClick() },
                        focusRequester = if (index == 0) firstFocus else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvOverflowMenuItem(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    // Hand clickable's own interaction source to tvFocusable so the shared focus
    // ring is driven by this Box's single focus target. Letting the helper add
    // its own focus target instead would stack two of them: the outer one would
    // absorb focus while the inner clickable kept the OK key handler, so the
    // item would highlight but pressing OK would do nothing.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .tvFocusable(
                focusRequester = focusRequester,
                cornerRadius = 10.dp,
                focusScale = 1.02f,
                interactionSource = interactionSource,
            )
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One entry in the app's overflow menu. Declared once in `ConnectedShell` and
 * rendered by both the Material dropdown (phone/tablet) and the D-pad overlay
 * (TV), so adding or reordering an entry is a single edit.
 *
 * [onClick] is the bare action -- each renderer wraps it with its own dismiss.
 * [dividerBefore] asks the renderer to draw a separator above this entry, in
 * whatever style that surface uses.
 */
private class OverflowAction(
    @param:StringRes val labelRes: Int,
    val dividerBefore: Boolean = false,
    val onClick: () -> Unit,
)
