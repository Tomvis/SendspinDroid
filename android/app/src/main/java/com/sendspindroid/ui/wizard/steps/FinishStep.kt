package com.sendspindroid.ui.wizard.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.tvFocusRing
import com.sendspindroid.ui.theme.SendSpinTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.sendspindroid.ui.wizard.keepVisibleWhenFocused
import com.sendspindroid.ui.adaptive.rememberTvKeyboardGate

/**
 * Final wizard step — name the server, set as default, and show a summary
 * of configured connections.
 */
@Composable
fun FinishStep(
    serverName: String,
    isDefault: Boolean,
    connectionSummary: List<String>,
    onNameChange: (String) -> Unit,
    onDefaultChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    saveEnabled: Boolean = false,
    onSave: () -> Unit = {},
    nameFocusRequester: FocusRequester = remember { FocusRequester() }
) {
    val isTv = LocalFormFactor.current == FormFactor.TV
    val keyboardGate = rememberTvKeyboardGate()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        // Success icon
        Icon(
            painter = painterResource(R.drawable.ic_check_circle),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .padding(bottom = 16.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        // A TV keeps the title in the top bar even while typing, so the step
        // does not repeat it there.
        if (!isTv) {
            Text(
                text = stringResource(R.string.wizard_save_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = stringResource(R.string.wizard_save_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Server name input
        OutlinedTextField(
            value = serverName,
            onValueChange = onNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .keepVisibleWhenFocused()
                .focusRequester(nameFocusRequester)
                .then(keyboardGate.modifier),
            readOnly = keyboardGate.readOnly,
            label = { Text(stringResource(R.string.wizard_name_title)) },
            placeholder = { Text(stringResource(R.string.wizard_name_hint)) },
            singleLine = true,
            // Done saves, as Save would. Until there is something to save it
            // only closes the keyboard, which brings Save back into reach.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (saveEnabled) onSave() else defaultKeyboardAction(ImeAction.Done)
            }),
            supportingText = {
                Text(stringResource(R.string.wizard_name_description))
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Default server checkbox
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isDefault,
                    onCheckedChange = onDefaultChange,
                    modifier = Modifier.tvFocusRing(shape = CircleShape)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.set_as_default),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.wizard_default_server_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Connection summary
        if (connectionSummary.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Connection Summary",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    connectionSummary.forEach { line ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check_circle),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FinishStepPreview() {
    SendSpinTheme {
        FinishStep(
            serverName = "Living Room",
            isDefault = true,
            connectionSummary = listOf(
                "Local: 192.168.1.100:8927"
            ),
            onNameChange = {},
            onDefaultChange = {}
        )
    }
}
