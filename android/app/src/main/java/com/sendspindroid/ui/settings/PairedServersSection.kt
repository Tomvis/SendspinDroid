package com.sendspindroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.sendspin.pairing.PairedServer
import com.sendspindroid.sendspin.pairing.PairingOutcome
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.TvInitialFocus

/**
 * The device's `client_id`, next to the token it belongs with.
 *
 * A server that is given a token for one client and connects to another
 * reports a mismatch, and this is the only place the user can see which
 * client this device is. Public, unlike the token.
 */
@Composable
internal fun PairingClientId(clientId: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.pairing_client_id_title),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.pairing_client_id_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        SelectionContainerOffTv {
            Text(
                text = clientId,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * How the last pairing ended, then one row per stored pairing record.
 *
 * A record is a key and the `server_id` it is bound to; rc1 has one kind of
 * record, so there is nothing to tell apart with a badge. The rows show who
 * the server is and never the key.
 *
 * On a TV each row is one focus stop whose OK asks to forget it: a Forget
 * button at the right of the row is hard to reach with the D-pad.
 *
 * @param onForget called once the user has confirmed.
 * @param afterListFocusRequester on a TV, takes focus when the last row is
 *   forgotten. A forgotten row is removed while it has focus, which would
 *   leave nothing focused; the next row takes focus, or failing that this.
 */
@Composable
internal fun PairedServersSection(
    servers: List<PairedServer>,
    lastOutcome: PairingOutcome?,
    onForget: (PairedServer) -> Unit,
    modifier: Modifier = Modifier,
    afterListFocusRequester: FocusRequester? = null
) {
    val isTv = LocalFormFactor.current == FormFactor.TV
    var forgetting by remember { mutableStateOf<PairedServer?>(null) }
    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var refocus by remember { mutableStateOf<FocusRequester?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (lastOutcome != null) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.pairing_last_outcome_title),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = lastOutcome.message(LocalContext.current.resources),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (lastOutcome is PairingOutcome.Paired) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }

        Text(
            text = stringResource(R.string.paired_servers_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
        )
        if (servers.isEmpty()) {
            Text(
                text = stringResource(R.string.paired_servers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 16.dp)
            )
        }
        servers.forEach { server ->
            key(server.pskId) {
                PairedServerRow(
                    server = server,
                    onForgetClick = { forgetting = server },
                    modifier = Modifier.focusRequester(
                        rowFocusRequesters.getOrPut(server.pskId) { FocusRequester() }
                    )
                )
            }
        }
    }

    forgetting?.let { server ->
        val cancelFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.paired_server_forget_title, server.label())) },
            text = { Text(stringResource(R.string.paired_server_forget_message)) },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = null
                    if (isTv) {
                        val next = servers.getOrNull(servers.indexOf(server) + 1)
                        refocus = next?.let { rowFocusRequesters[it.pskId] } ?: afterListFocusRequester
                    }
                    onForget(server)
                }) {
                    Text(stringResource(R.string.paired_server_forget))
                }
            },
            dismissButton = {
                // Forgetting cannot be undone, so on a TV the dialog opens on Cancel.
                TvInitialFocus(cancelFocus)
                TextButton(
                    onClick = { forgetting = null },
                    modifier = Modifier.focusRequester(cancelFocus)
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    refocus?.let { requester ->
        LaunchedEffect(requester) {
            requester.requestFocus()
            refocus = null
        }
    }
}

/** The server's name if it has given one, otherwise its `server_id`. */
@Composable
private fun PairedServer.label(): String =
    name ?: serverId ?: stringResource(R.string.paired_server_unbound)

@Composable
private fun PairedServerRow(
    server: PairedServer,
    onForgetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isTv = LocalFormFactor.current == FormFactor.TV
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isTv) Modifier.clickable(onClick = onForgetClick) else Modifier)
            .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // One line each, cut short where the screen is: a server_id is 43
        // characters with nowhere to break, and its start is what tells two
        // servers apart.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.label(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (server.name != null && server.serverId != null) {
                Text(
                    text = server.serverId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isTv) {
            // The row is the control; this only says what OK on it does.
            Text(
                text = stringResource(R.string.paired_server_forget),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        } else {
            TextButton(onClick = onForgetClick) {
                Text(stringResource(R.string.paired_server_forget))
            }
        }
    }
}
