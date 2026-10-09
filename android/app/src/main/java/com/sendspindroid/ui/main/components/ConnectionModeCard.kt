package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * How the app and a server find each other.
 *
 * By default the app advertises itself and waits for a server to connect.
 * The alternative is to search for servers that advertise themselves and
 * connect to one. It does one or the other, never both.
 *
 * @param searching the user chose to search for servers.
 * @param playerName the name the app is advertised under.
 * @param listeningPort the port servers can connect to; null until the
 *   listener is up.
 * @param failed advertising is selected but servers cannot find or reach
 *   the app: no port could be bound, or the mDNS registration failed.
 */
class ConnectionModeState(
    val searching: Boolean,
    val playerName: String,
    val listeningPort: Int?,
    val failed: Boolean = false,
    val onSearchingChange: (Boolean) -> Unit,
)

/** Says which mode the app is in, with the button that changes it. */
@Composable
fun ConnectionModeCard(
    mode: ConnectionModeState,
    modifier: Modifier = Modifier,
) {
    val searching = mode.searching
    val listeningPort = mode.listeningPort
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                when {
                    searching -> R.string.mode_searching_title
                    mode.failed -> R.string.mode_advertising_failed_title
                    else -> R.string.mode_advertising_title
                }
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                searching -> stringResource(R.string.mode_searching_body)
                mode.failed -> stringResource(R.string.mode_advertising_failed_body)
                else -> stringResource(R.string.mode_advertising_body, mode.playerName)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!searching && !mode.failed) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (listeningPort != null) {
                    stringResource(R.string.mode_advertising_port, listeningPort)
                } else {
                    stringResource(R.string.mode_advertising_starting)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = { mode.onSearchingChange(!searching) }) {
            Text(
                text = stringResource(
                    if (searching) R.string.mode_advertise_instead else R.string.mode_search_instead
                )
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ConnectionModeCardAdvertisingPreview() {
    SendSpinTheme {
        ConnectionModeCard(
            mode = ConnectionModeState(false, "Kitchen Tablet", 8928) {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ConnectionModeCardSearchingPreview() {
    SendSpinTheme {
        ConnectionModeCard(
            mode = ConnectionModeState(true, "Kitchen Tablet", null) {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
