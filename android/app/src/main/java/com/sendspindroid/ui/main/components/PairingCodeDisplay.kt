package com.sendspindroid.ui.main.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.sendspindroid.sendspin.pairing.PairingCode
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * Shows a dynamic pairing code to the operator, grouped and spaced for
 * legibility across a room.
 *
 * A screen reader has to be told when the code appears, the same way
 * [ConnectionProgress] and [AdmissionNotice] announce their own state
 * changes -- nothing else prompts the user to go looking for it.
 */
@Composable
fun PairingCodeDisplay(
    code: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = PairingCode.group(code),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        letterSpacing = 4.sp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Preview(showBackground = true)
@Composable
private fun PairingCodeDisplayPreview() {
    SendSpinTheme {
        PairingCodeDisplay(code = "123456")
    }
}
