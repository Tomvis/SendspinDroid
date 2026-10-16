package com.sendspindroid.ui.wizard.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.tvFocusRing
import com.sendspindroid.ui.theme.SendSpinTheme
import com.sendspindroid.ui.wizard.DiscoveredServerUi
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.sendspindroid.ui.wizard.keepVisibleWhenFocused
import com.sendspindroid.ui.adaptive.rememberTvKeyboardGate

/**
 * Find server step - discover servers via mDNS or enter address manually.
 */
@Composable
fun FindServerStep(
    discoveredServers: List<DiscoveredServerUi>,
    localAddress: String,
    isSearching: Boolean,
    onAddressChange: (String) -> Unit,
    onServerSelected: (DiscoveredServerUi) -> Unit,
    onStartSearch: () -> Unit,
    onSubmit: () -> Unit = {},
    modifier: Modifier = Modifier,
    // False while the app advertises itself: it does one or the other.
    searchAllowed: Boolean = true,
    // Why the last connection test failed, if it did.
    error: String? = null,
    listState: LazyListState = rememberLazyListState(),
    addressFocusRequester: FocusRequester = remember { FocusRequester() },
    firstServerFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    val isTv = LocalFormFactor.current == FormFactor.TV
    val keyboardGate = rememberTvKeyboardGate()
    var addressFocused by remember { mutableStateOf(false) }
    val hasServers = searchAllowed && discoveredServers.isNotEmpty()

    if (isTv) {
        // Discovery starts with this step, so its servers turn up a moment
        // after focus has gone to the address field. While nothing has been
        // done there, the first server found takes the focus.
        LaunchedEffect(hasServers) {
            if (hasServers && addressFocused && keyboardGate.readOnly && localAddress.isBlank()) {
                withFrameNanos { } // the server's card is laid out in the next frame
                // The list composes only what is in view
                listState.scrollToItem(0)
                firstServerFocusRequester.requestFocus()
            }
        }
    }

    // A server's card that leaves the list while it has focus takes the focus
    // with it, to nowhere: the address field, near the end, gets it back.
    var focusedServer by remember { mutableStateOf<String?>(null) }
    if (isTv) {
        LaunchedEffect(discoveredServers) {
            val gone = focusedServer
            if (gone != null && discoveredServers.none { it.id == gone }) {
                focusedServer = null
                withFrameNanos { } // the list without it is laid out in the next frame
                listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                addressFocusRequester.requestFocus()
            }
        }
    }
    val currentServers by rememberUpdatedState(discoveredServers)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Keyed, so that the address field keeps its place in the list, and
        // its focus and keyboard, as servers come and go above it.
        item(key = "intro") {
            Spacer(modifier = Modifier.height(16.dp))

            // Title and description. A TV keeps the title in the top bar
            // even while typing, so the step does not repeat it there.
            if (!isTv) {
                Text(
                    text = stringResource(R.string.wizard_find_server_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = stringResource(R.string.wizard_find_server_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!searchAllowed) {
            item(key = "not_searching") {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.wizard_find_server_not_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Discovered servers section
        if (searchAllowed && (discoveredServers.isNotEmpty() || isSearching)) {
            item(key = "discovered") {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.wizard_local_discovered),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isSearching) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            itemsIndexed(
                items = discoveredServers,
                key = { _, server -> "server:" + server.id }
            ) { index, server ->
                DiscoveredServerCard(
                    server = server,
                    // Picking a server on a TV moves focus on to Next, so the
                    // card has to show that it was the one picked.
                    selected = isTv && server.address == localAddress,
                    onClick = { onServerSelected(server) },
                    focusRequester = if (index == 0) firstServerFocusRequester else null,
                    modifier = Modifier.onFocusChanged {
                        if (it.hasFocus) {
                            focusedServer = server.id
                        } else if (focusedServer == server.id && currentServers.any { s -> s.id == server.id }) {
                            // Focus moved on. A card leaving the list keeps
                            // it, for the effect above to find.
                            focusedServer = null
                        }
                    }
                )
            }
        }

        // Manual entry section
        item(key = "manual_entry") {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.wizard_local_manual_entry),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = localAddress,
                onValueChange = onAddressChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .keepVisibleWhenFocused()
                    .focusRequester(addressFocusRequester)
                    .onFocusChanged { addressFocused = it.isFocused }
                    .then(keyboardGate.modifier),
                readOnly = keyboardGate.readOnly,
                label = { Text(stringResource(R.string.server_address)) },
                placeholder = { Text(stringResource(R.string.wizard_local_address_hint)) },
                singleLine = true,
                // A failed test returns here; its reason stays by the address
                // it was about until the address is changed.
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                // The keyboard's own key does what the Next button does, so
                // the step can be finished without reaching past the keyboard.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { onSubmit() })
            )
        }

        // Refresh button
        if (searchAllowed && !isSearching) {
            item(key = "search_again") {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        // The button goes away as the search starts. On a TV
                        // the focus would go with it, so the address field
                        // takes it first.
                        if (isTv) addressFocusRequester.requestFocus()
                        onStartSearch()
                    },
                    modifier = Modifier.tvFocusRing(shape = ButtonDefaults.textShape)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.search_again))
                }
            }
        }

        item(key = "end") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DiscoveredServerCard(
    server: DiscoveredServerUi,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val shape = RoundedCornerShape(12.dp)
    // Card(onClick) rather than Modifier.clickable, so the press and focus
    // highlight are clipped to the card's rounded shape.
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // The card is as wide as the list, which clips its children 30dp
            // out at the sides; a larger scale would carry the ring past that.
            .tvFocusRing(focusRequester, shape, focusScale = 1.05f)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            // Not secondaryContainer, which the server icon's circle uses
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_wifi),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Server info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = server.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Chevron, or a check on the server picked
            Icon(
                painter = painterResource(
                    if (selected) R.drawable.ic_check_circle else R.drawable.ic_chevron_right
                ),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FindServerStepPreview() {
    SendSpinTheme {
        FindServerStep(
            discoveredServers = listOf(
                DiscoveredServerUi("1", "Living Room", "192.168.1.100:8927"),
                DiscoveredServerUi("2", "Office", "192.168.1.101:8927")
            ),
            localAddress = "",
            isSearching = true,
            onAddressChange = {},
            onServerSelected = {},
            onStartSearch = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FindServerStepEmptyPreview() {
    SendSpinTheme {
        FindServerStep(
            discoveredServers = emptyList(),
            localAddress = "192.168.1.100:8927",
            isSearching = false,
            onAddressChange = {},
            onServerSelected = {},
            onStartSearch = {}
        )
    }
}
