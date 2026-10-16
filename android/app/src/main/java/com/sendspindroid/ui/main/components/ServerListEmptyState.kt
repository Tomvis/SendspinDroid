package com.sendspindroid.ui.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sendspindroid.R
import com.sendspindroid.model.LocalConnection
import com.sendspindroid.model.UnifiedServer
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.tvFocusRing
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.tv.LocalTvMenu

/**
 * Welcome / empty state shown when no saved servers are available.
 *
 * Displays a hero card with a prominent "Add Your First Server" CTA and
 * the connection mode: waiting for a server to connect, or searching for
 * one, with a scanning status indicator and the servers found below for
 * quick-connect.
 */
@Composable
fun ServerListEmptyState(
    isScanning: Boolean,
    discoveredServers: List<UnifiedServer>,
    onAddServerClick: () -> Unit,
    onQuickConnectClick: (UnifiedServer) -> Unit,
    mode: ConnectionModeState,
    modifier: Modifier = Modifier
) {
    val contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp)
    val isTv = LocalFormFactor.current == FormFactor.TV

    // On a TV, Add Your First Server takes focus when the screen appears.
    // Once: the hero is a lazy item, composed again when it scrolls back
    // into view.
    var addTakesFocus by remember { mutableStateOf(isTv) }

    // A found server's card that leaves the list while it has focus takes the
    // focus with it, to nowhere: Add Your First Server gets it back.
    val listState = rememberLazyListState()
    var focusedServer by remember { mutableStateOf<String?>(null) }
    if (isTv) {
        LaunchedEffect(discoveredServers) {
            val gone = focusedServer
            if (gone != null && discoveredServers.none { it.id == gone }) {
                focusedServer = null
                listState.scrollToItem(0)
                addTakesFocus = true
            }
        }
    }
    val currentServers by rememberUpdatedState(discoveredServers)
    val trackFocus: (String, Boolean) -> Unit = { id, hasFocus ->
        if (hasFocus) {
            focusedServer = id
        } else if (focusedServer == id && currentServers.any { it.id == id }) {
            // Focus moved on. A card leaving the list keeps it, for the
            // effect above to find.
            focusedServer = null
        }
    }

    // In landscape the hero card is as tall as the screen, which would leave
    // the servers that were found below the fold with nothing to say they
    // are there. They go beside it instead. Not on a TV, where the hero is
    // laid out in two columns that fit the screen: there one structure in
    // every state keeps its buttons, and focus on them, when the first
    // server is found.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape && discoveredServers.isNotEmpty() && !isTv) {
        Row(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = contentPadding
            ) {
                item(key = "hero") {
                    HeroCard(
                        isScanning = isScanning,
                        discoveredCount = discoveredServers.size,
                        onAddServerClick = onAddServerClick,
                        mode = mode
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = contentPadding
            ) {
                discoveredServerItems(discoveredServers, onQuickConnectClick)
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = contentPadding
    ) {
        // Hero card
        item(key = "hero") {
            HeroCard(
                isScanning = isScanning,
                discoveredCount = discoveredServers.size,
                onAddServerClick = onAddServerClick,
                mode = mode,
                addTakesFocus = addTakesFocus,
                onAddFocused = { addTakesFocus = false }
            )
        }

        // Discovered servers section — appears when servers are found
        if (discoveredServers.isNotEmpty()) {
            item(key = "divider") {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            discoveredServerItems(discoveredServers, onQuickConnectClick, onFocusChanged = trackFocus)
        }
    }
}

/** The "Quick Connect" heading and one row per server found on the network. */
private fun LazyListScope.discoveredServerItems(
    discoveredServers: List<UnifiedServer>,
    onQuickConnectClick: (UnifiedServer) -> Unit,
    onFocusChanged: (id: String, hasFocus: Boolean) -> Unit = { _, _ -> },
) {
    item(key = "quick_connect_header") {
        val formFactor = LocalFormFactor.current
        val isTv = formFactor == FormFactor.TV
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.welcome_quick_connect),
                style = MaterialTheme.typography.titleMedium,
                fontSize = if (isTv) AdaptiveDefaults.bodyTextSize(formFactor) else TextUnit.Unspecified,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.welcome_found_on_network),
                style = MaterialTheme.typography.bodySmall,
                fontSize = if (isTv) AdaptiveDefaults.captionTextSize(formFactor) else TextUnit.Unspecified,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    itemsIndexed(
        items = discoveredServers,
        key = { _, server -> "quick_${server.id}" }
    ) { index, server ->
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = 300,
                    delayMillis = index * 80
                )
            ) + slideInVertically(
                initialOffsetY = { 60 },
                animationSpec = tween(
                    durationMillis = 300,
                    delayMillis = index * 80
                )
            )
        ) {
            ServerListItem(
                server = server,
                status = ServerItemStatus.ONLINE,
                onClick = { onQuickConnectClick(server) },
                // No menu here
                onLongClick = null,
                onQuickConnectClick = { onQuickConnectClick(server) },
                modifier = Modifier.onFocusChanged { onFocusChanged(server.id, it.hasFocus) }
            )
        }
    }
}

/**
 * Hero card with welcome message and primary CTA.
 *
 * On a TV the welcome and what to do sit side by side, so that the card fits
 * the height of the screen without scrolling.
 *
 * @param addTakesFocus Add Your First Server is to take focus (TV)
 * @param onAddFocused it has taken it
 */
@Composable
private fun HeroCard(
    isScanning: Boolean,
    discoveredCount: Int,
    onAddServerClick: () -> Unit,
    mode: ConnectionModeState,
    modifier: Modifier = Modifier,
    addTakesFocus: Boolean = false,
    onAddFocused: () -> Unit = {}
) {
    val addFocusRequester = remember { FocusRequester() }
    if (addTakesFocus) {
        LaunchedEffect(Unit) {
            addFocusRequester.requestFocus()
            onAddFocused()
        }
    }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (LocalFormFactor.current == FormFactor.TV) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HeroWelcome()
                }
                Spacer(modifier = Modifier.width(48.dp))
                // Nothing on the screen is between these and the side menu,
                // so moving toward it goes there. A found server's card below,
                // as wide as the list, would otherwise count as on that side.
                val menu = LocalTvMenu.current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .focusProperties { if (menu != null) start = menu },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HeroActions(
                        isScanning = isScanning,
                        discoveredCount = discoveredCount,
                        onAddServerClick = onAddServerClick,
                        mode = mode,
                        addFocusRequester = addFocusRequester
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HeroWelcome()

                Spacer(modifier = Modifier.height(28.dp))

                HeroActions(
                    isScanning = isScanning,
                    discoveredCount = discoveredCount,
                    onAddServerClick = onAddServerClick,
                    mode = mode,
                    addFocusRequester = addFocusRequester
                )
            }
        }
    }
}

/** The hero icon, the welcome and what the app is for, laid out in a Column. */
@Composable
private fun HeroWelcome() {
    val formFactor = LocalFormFactor.current
    val isTv = formFactor == FormFactor.TV

    // Hero icon with background circle
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(120.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = CircleShape
            )
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_speaker_group),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Welcome title
    Text(
        text = stringResource(R.string.welcome_title),
        style = if (isTv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Description. A TV is read from across the room.
    Text(
        text = stringResource(R.string.welcome_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        fontSize = if (isTv) AdaptiveDefaults.bodyTextSize(formFactor) else TextUnit.Unspecified,
        lineHeight = if (isTv) 28.sp else TextUnit.Unspecified,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 320.dp)
    )
}

/**
 * Add Your First Server, the connection mode and the discovery status, laid
 * out in a Column.
 */
@Composable
private fun HeroActions(
    isScanning: Boolean,
    discoveredCount: Int,
    onAddServerClick: () -> Unit,
    mode: ConnectionModeState,
    addFocusRequester: FocusRequester
) {
    val formFactor = LocalFormFactor.current
    val buttonShape = RoundedCornerShape(16.dp)

    // Primary CTA - Add Server
    FilledTonalButton(
        onClick = onAddServerClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .widthIn(max = 280.dp)
            .tvFocusRing(focusRequester = addFocusRequester, shape = buttonShape),
        shape = buttonShape
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.welcome_add_first_server),
            style = MaterialTheme.typography.labelLarge,
            fontSize = if (formFactor == FormFactor.TV) {
                AdaptiveDefaults.captionTextSize(formFactor)
            } else {
                TextUnit.Unspecified
            }
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Waiting for a server, or searching for one, and the way to
    // the other.
    ConnectionModeCard(mode = mode)

    if (mode.searching) {
        Spacer(modifier = Modifier.height(12.dp))

        // Scanning status chip
        DiscoveryStatusChip(
            isScanning = isScanning,
            discoveredCount = discoveredCount
        )
    }
}

/**
 * Status chip showing current mDNS discovery state.
 *
 * - Scanning: pulsing wifi icon + "Searching for servers nearby..."
 * - Found:   static wifi icon + "N server(s) found nearby"
 * - None:    wifi-off style   + "No servers found on this network"
 */
@Composable
private fun DiscoveryStatusChip(
    isScanning: Boolean,
    discoveredCount: Int,
    modifier: Modifier = Modifier
) {
    val formFactor = LocalFormFactor.current
    // A TV is read from across the room; elsewhere the style's own size.
    val labelSize = if (formFactor == FormFactor.TV) {
        AdaptiveDefaults.captionTextSize(formFactor)
    } else {
        TextUnit.Unspecified
    }
    when {
        isScanning && discoveredCount == 0 -> {
            // Still scanning, nothing found yet — pulsing alpha animation
            val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scan_pulse_alpha"
            )
            AssistChip(
                onClick = { },
                label = {
                    Text(
                        text = stringResource(R.string.welcome_scanning),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = labelSize
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi),
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .alpha(pulseAlpha)
                    )
                },
                modifier = modifier,
                enabled = false
            )
        }
        discoveredCount > 0 -> {
            // Servers found
            AssistChip(
                onClick = { },
                label = {
                    Text(
                        text = stringResource(R.string.welcome_servers_found, discoveredCount),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = labelSize
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                ),
                modifier = modifier,
                enabled = false
            )
        }
        else -> {
            // Done scanning, nothing found
            AssistChip(
                onClick = { },
                label = {
                    Text(
                        text = stringResource(R.string.welcome_no_servers_found),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = labelSize
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_wifi),
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .alpha(0.4f)
                    )
                },
                modifier = modifier,
                enabled = false
            )
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyStateAdvertisingPreview() {
    SendSpinTheme {
        ServerListEmptyState(
            isScanning = false,
            discoveredServers = emptyList(),
            onAddServerClick = {},
            onQuickConnectClick = {},
            mode = ConnectionModeState(searching = false, "Kitchen Tablet", 8928) {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyStateScanningPreview() {
    SendSpinTheme {
        ServerListEmptyState(
            isScanning = true,
            discoveredServers = emptyList(),
            onAddServerClick = {},
            onQuickConnectClick = {},
            mode = ConnectionModeState(searching = true, "Kitchen Tablet", null) {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyStateWithServersPreview() {
    SendSpinTheme {
        ServerListEmptyState(
            isScanning = false,
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
            onAddServerClick = {},
            onQuickConnectClick = {},
            mode = ConnectionModeState(searching = true, "Kitchen Tablet", null) {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun EmptyStateNoServersFoundPreview() {
    SendSpinTheme {
        ServerListEmptyState(
            isScanning = false,
            discoveredServers = emptyList(),
            onAddServerClick = {},
            onQuickConnectClick = {},
            mode = ConnectionModeState(searching = true, "Kitchen Tablet", null) {}
        )
    }
}
