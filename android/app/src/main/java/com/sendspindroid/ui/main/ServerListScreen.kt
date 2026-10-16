package com.sendspindroid.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.overscanSafe
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.RemoteConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.ui.main.components.ConnectionModeCard
import com.sendspindroid.ui.main.components.ConnectionModeState
import com.sendspindroid.ui.main.components.ServerItemStatus
import com.sendspindroid.ui.main.components.ServerListEmptyState
import com.sendspindroid.ui.main.components.ServerListItem
import com.sendspindroid.ui.main.components.ServerSectionHeader
import com.sendspindroid.ui.preview.AllDevicePreviews
import com.sendspindroid.ui.theme.SendSpinTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Server list screen showing saved and discovered servers in sections.
 *
 * When no saved servers exist, shows the welcome/hero empty state with
 * discovered servers inline for quick-connect. When saved servers exist,
 * shows the standard sectioned list with a FAB (not on a TV).
 */
@Composable
fun ServerListScreen(
    savedServers: StateFlow<List<UnifiedServer>>,
    discoveredServers: StateFlow<List<UnifiedServer>>,
    onlineSavedServerIds: StateFlow<Set<String>>,
    isScanning: Boolean,
    serverStatuses: Map<String, ServerItemStatus>,
    reconnectInfo: Map<String, Pair<Int, Int>>, // serverId -> (attempt, maxAttempts)
    onServerClick: (UnifiedServer) -> Unit,
    onServerLongClick: (UnifiedServer) -> Unit,
    onQuickConnectClick: (UnifiedServer) -> Unit,
    onAddServerClick: () -> Unit,
    mode: ConnectionModeState,
    modifier: Modifier = Modifier
) {
    val saved by savedServers.collectAsStateWithLifecycle()
    val discovered by discoveredServers.collectAsStateWithLifecycle()
    val onlineIds by onlineSavedServerIds.collectAsStateWithLifecycle()

    ServerListScreenContent(
        savedServers = saved,
        discoveredServers = discovered,
        onlineSavedServerIds = onlineIds,
        isScanning = isScanning,
        serverStatuses = serverStatuses,
        reconnectInfo = reconnectInfo,
        onServerClick = onServerClick,
        onServerLongClick = onServerLongClick,
        onQuickConnectClick = onQuickConnectClick,
        onAddServerClick = onAddServerClick,
        mode = mode,
        modifier = modifier
    )
}

/**
 * Stateless version of ServerListScreen for simpler integration.
 */
@Composable
fun ServerListScreen(
    savedServers: List<UnifiedServer>,
    discoveredServers: List<UnifiedServer>,
    onlineSavedServerIds: Set<String>,
    isScanning: Boolean,
    serverStatuses: Map<String, ServerItemStatus>,
    reconnectInfo: Map<String, Pair<Int, Int>>,
    onServerClick: (UnifiedServer) -> Unit,
    onServerLongClick: (UnifiedServer) -> Unit,
    onQuickConnectClick: (UnifiedServer) -> Unit,
    onAddServerClick: () -> Unit,
    mode: ConnectionModeState,
    modifier: Modifier = Modifier
) {
    ServerListScreenContent(
        savedServers = savedServers,
        discoveredServers = discoveredServers,
        onlineSavedServerIds = onlineSavedServerIds,
        isScanning = isScanning,
        serverStatuses = serverStatuses,
        reconnectInfo = reconnectInfo,
        onServerClick = onServerClick,
        onServerLongClick = onServerLongClick,
        onQuickConnectClick = onQuickConnectClick,
        onAddServerClick = onAddServerClick,
        mode = mode,
        modifier = modifier
    )
}

/**
 * Shared implementation for both overloads.
 */
@Composable
private fun ServerListScreenContent(
    savedServers: List<UnifiedServer>,
    discoveredServers: List<UnifiedServer>,
    onlineSavedServerIds: Set<String>,
    isScanning: Boolean,
    serverStatuses: Map<String, ServerItemStatus>,
    reconnectInfo: Map<String, Pair<Int, Int>>,
    onServerClick: (UnifiedServer) -> Unit,
    onServerLongClick: (UnifiedServer) -> Unit,
    onQuickConnectClick: (UnifiedServer) -> Unit,
    onAddServerClick: () -> Unit,
    mode: ConnectionModeState,
    modifier: Modifier = Modifier
) {
    val hasSavedServers = savedServers.isNotEmpty()
    val formFactor = LocalFormFactor.current
    val isTv = formFactor == FormFactor.TV
    val maxWidth = AdaptiveDefaults.serverListMaxWidth(formFactor)

    // On wider form factors, constrain list width and center it
    val contentModifier = if (maxWidth != null) {
        Modifier.widthIn(max = maxWidth).fillMaxWidth()
    } else {
        Modifier.fillMaxSize()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .overscanSafe(),
        contentAlignment = if (maxWidth != null) Alignment.TopCenter else Alignment.TopStart
    ) {
        if (!hasSavedServers) {
            // Welcome / empty state — hero card with discovered servers inline.
            // In landscape it lays the two out side by side, so it gets twice
            // the width a single column is held to. A TV lays out the hero
            // card itself in two columns instead.
            val isLandscape =
                LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val emptyStateModifier = when {
                isTv -> Modifier.widthIn(max = TvEmptyStateMaxWidth).fillMaxWidth()
                maxWidth != null && isLandscape -> Modifier.widthIn(max = maxWidth * 2).fillMaxWidth()
                else -> contentModifier
            }
            Box(modifier = emptyStateModifier) {
                ServerListEmptyState(
                    isScanning = isScanning,
                    discoveredServers = discoveredServers,
                    onAddServerClick = onAddServerClick,
                    onQuickConnectClick = onQuickConnectClick,
                    mode = mode
                )
            }
        } else {
            // Normal server list with saved + discovered sections. The list
            // draws edge to edge, so its last row has to clear the navigation
            // bar as well as the FAB. A TV has neither.
            val navigationBarHeight =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val listState = rememberLazyListState()

            // Focus on a TV, where there is no pointer to put it back once it
            // is lost. The default saved server's card (or the first) takes it
            // when the list appears. A card that leaves the list while it has
            // focus (deleted, saved from Nearby, gone offline) takes focus with
            // it, to nowhere, so the card that takes its place, or the first,
            // gets it instead. focusTarget is the key of the card that is to
            // take focus once it is composed, and focusedCard that of the card
            // with focus.
            var focusTarget by remember { mutableStateOf<String?>(null) }
            var focusedCard by remember { mutableStateOf<String?>(null) }
            var shownCards by remember { mutableStateOf(emptyList<String>()) }
            val cards = savedServers.map { savedItemKey(it) } +
                if (mode.searching) discoveredServers.map { discoveredItemKey(it) } else emptyList()
            val currentCards by rememberUpdatedState(cards)
            val currentSavedServers by rememberUpdatedState(savedServers)
            if (isTv) {
                LaunchedEffect(Unit) {
                    val first = savedServers.firstOrNull { it.isDefaultServer } ?: savedServers.first()
                    focusTarget = savedItemKey(first)
                }
                LaunchedEffect(cards) {
                    val before = shownCards
                    shownCards = cards
                    val gone = focusedCard
                    if (gone != null && gone !in cards) {
                        focusTarget = cards.getOrNull(before.indexOf(gone)) ?: cards.first()
                        focusedCard = null
                    }
                }
                // Only a card in view is composed, and so able to take focus.
                // The default server's may be further down.
                LaunchedEffect(focusTarget) {
                    val target = focusTarget ?: return@LaunchedEffect
                    val shown = snapshotFlow { listState.layoutInfo.visibleItemsInfo }
                        .first { it.isNotEmpty() }
                    val index = currentSavedServers.indexOfFirst { savedItemKey(it) == target }
                    if (index >= 0 && shown.none { it.key == target }) {
                        // After the mode card and the Saved header
                        listState.scrollToItem(2 + index)
                    }
                }
            }

            // Gives a card focus when it is the target, and keeps track of
            // the card that has it. Nothing off a TV.
            @Composable
            fun tvCardFocus(key: String): Modifier {
                if (!isTv) return Modifier
                val focusRequester = remember { FocusRequester() }
                if (key == focusTarget) {
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                        focusTarget = null
                    }
                }
                return Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.hasFocus) {
                            focusedCard = key
                        } else if (focusedCard == key && key in currentCards) {
                            // Focus moved on. A card leaving the list keeps
                            // it, for the effect above to find a new place.
                            focusedCard = null
                        }
                    }
            }

            LazyColumn(
                modifier = contentModifier,
                state = listState,
                contentPadding = if (isTv) {
                    PaddingValues()
                } else {
                    PaddingValues(bottom = 88.dp + navigationBarHeight) // Space for FAB
                }
            ) {
                item(key = "mode") {
                    ConnectionModeCard(
                        mode = mode,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                }

                // Saved Servers Section
                item(key = "header_saved") {
                    ServerSectionHeader(
                        title = stringResource(R.string.saved_servers_section)
                    )
                }

                items(
                    items = savedServers,
                    key = { savedItemKey(it) }
                ) { server ->
                    val status = serverStatuses[server.id]
                        ?: if (onlineSavedServerIds.contains(server.id)) ServerItemStatus.ONLINE
                        else ServerItemStatus.DISCONNECTED
                    val reconnect = reconnectInfo[server.id]

                    ServerListItem(
                        server = server,
                        status = status,
                        onClick = { onServerClick(server) },
                        onLongClick = { onServerLongClick(server) },
                        modifier = tvCardFocus(savedItemKey(server)),
                        reconnectAttempt = reconnect?.first,
                        reconnectMaxAttempts = reconnect?.second
                    )
                }

                // Discovered Servers Section. Nothing is discovered while
                // the app advertises itself instead.
                if (mode.searching) item(key = "header_discovered") {
                    ServerSectionHeader(
                        title = stringResource(R.string.nearby_servers_section),
                        showScanning = isScanning,
                        emptyHint = if (discoveredServers.isEmpty() && isScanning)
                            stringResource(R.string.scanning_ellipsis)
                        else null
                    )
                }

                if (mode.searching && discoveredServers.isNotEmpty()) {
                    items(
                        items = discoveredServers,
                        key = { discoveredItemKey(it) }
                    ) { server ->
                        val status = serverStatuses[server.id] ?: ServerItemStatus.ONLINE

                        ServerListItem(
                            server = server,
                            status = status,
                            onClick = { onServerClick(server) },
                            onLongClick = { onServerLongClick(server) },
                            modifier = tvCardFocus(discoveredItemKey(server)),
                            onQuickConnectClick = { onQuickConnectClick(server) }
                        )
                    }
                }
            }

            // FAB for adding servers — only when saved servers exist
            // (empty state has its own prominent Add Server button). Not on a
            // TV, where a remote reaches Add Server in the side menu.
            if (!isTv) {
                FloatingActionButton(
                    onClick = onAddServerClick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.add_server)
                    )
                }
            }
        }
    }
}

private fun savedItemKey(server: UnifiedServer) = "saved_${server.id}"

private fun discoveredItemKey(server: UnifiedServer) = "discovered_${server.id}"

/**
 * The widest the empty state gets on a TV: room for the hero card's two
 * columns, with lines short enough to read.
 */
private val TvEmptyStateMaxWidth = 840.dp

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ServerListScreenPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = listOf(
                UnifiedServer(
                    id = "1",
                    name = "Living Room",
                    lastConnectedMs = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.100:8927"),
                    remote = RemoteConnection("ABCDE12345FGHIJ67890KLMNO1"),
                    isDefaultServer = true
                ),
                UnifiedServer(
                    id = "2",
                    name = "Bedroom",
                    lastConnectedMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.101:8927")
                )
            ),
            discoveredServers = listOf(
                UnifiedServer(
                    id = "3",
                    name = "Kitchen Speaker",
                    local = LocalConnection("192.168.1.102:8927"),
                    isDiscovered = true
                )
            ),
            onlineSavedServerIds = setOf("1"),
            isScanning = true,
            serverStatuses = emptyMap(),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {},
            mode = ConnectionModeState(searching = true, "Kitchen Tablet", null) {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ServerListScreenAdvertisingPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = listOf(
                UnifiedServer(
                    id = "1",
                    name = "Living Room",
                    local = LocalConnection("192.168.1.100:8927")
                )
            ),
            discoveredServers = emptyList(),
            onlineSavedServerIds = emptySet(),
            isScanning = false,
            serverStatuses = emptyMap(),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {},
            mode = ConnectionModeState(searching = false, "Kitchen Tablet", 8928) {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ServerListScreenEmptyPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = emptyList(),
            discoveredServers = emptyList(),
            onlineSavedServerIds = emptySet(),
            isScanning = true,
            serverStatuses = emptyMap(),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {},
            mode = ConnectionModeState(searching = true, "Kitchen Tablet", null) {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ServerListScreenEmptyWithDiscoveredPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = emptyList(),
            discoveredServers = listOf(
                UnifiedServer(
                    id = "1",
                    name = "Living Room",
                    local = LocalConnection("192.168.1.100:8927"),
                    isDiscovered = true
                ),
                UnifiedServer(
                    id = "2",
                    name = "Kitchen Speaker",
                    local = LocalConnection("192.168.1.101:8927"),
                    isDiscovered = true
                )
            ),
            onlineSavedServerIds = emptySet(),
            isScanning = false,
            serverStatuses = emptyMap(),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {},
            mode = ConnectionModeState(searching = true, "Kitchen Tablet", null) {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ServerListScreenConnectingPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = listOf(
                UnifiedServer(
                    id = "1",
                    name = "Living Room",
                    lastConnectedMs = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.100:8927"),
                    isDefaultServer = true
                )
            ),
            discoveredServers = emptyList(),
            onlineSavedServerIds = setOf("1"),
            isScanning = false,
            serverStatuses = mapOf("1" to ServerItemStatus.CONNECTING),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {},
            mode = ConnectionModeState(searching = true, "Kitchen Tablet", null) {}
        )
    }
}

// -- Multi-Device Previews --

@AllDevicePreviews
@Composable
private fun ServerListScreenAllDevicesPreview() {
    SendSpinTheme {
        ServerListScreen(
            savedServers = listOf(
                UnifiedServer(
                    id = "1",
                    name = "Living Room",
                    lastConnectedMs = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.100:8927"),
                    remote = RemoteConnection("ABCDE12345FGHIJ67890KLMNO1"),
                    isDefaultServer = true
                ),
                UnifiedServer(
                    id = "2",
                    name = "Bedroom",
                    lastConnectedMs = System.currentTimeMillis() - 24 * 60 * 60 * 1000,
                    local = LocalConnection("192.168.1.101:8927")
                ),
                UnifiedServer(
                    id = "4",
                    name = "Office",
                    lastConnectedMs = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000,
                    remote = RemoteConnection("XYZAB12345CDEFG67890HIJKL1")
                )
            ),
            discoveredServers = listOf(
                UnifiedServer(
                    id = "3",
                    name = "Kitchen Speaker",
                    local = LocalConnection("192.168.1.102:8927"),
                    isDiscovered = true
                )
            ),
            onlineSavedServerIds = setOf("1"),
            isScanning = true,
            serverStatuses = emptyMap(),
            reconnectInfo = emptyMap(),
            onServerClick = {},
            onServerLongClick = {},
            onQuickConnectClick = {},
            onAddServerClick = {},
            mode = ConnectionModeState(searching = true, "Kitchen Tablet", null) {}
        )
    }
}
