package com.sendspindroid.ui.main.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.sendspin.pairing.PairingCodeFormat
import com.sendspindroid.sendspin.protocol.AdmissionState
import com.sendspindroid.ui.adaptive.AdaptiveDefaults
import com.sendspindroid.ui.adaptive.FormFactor
import com.sendspindroid.ui.adaptive.LocalFormFactor
import com.sendspindroid.ui.adaptive.TvOverscanHorizontal
import com.sendspindroid.ui.adaptive.tvFocusRing
import com.sendspindroid.ui.settings.PairingQrImage
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * What to show when a connection was accepted but cannot carry playback, or
 * while a pairing code or the Allow pairing prompt is up alongside playback.
 *
 * Shown in place of the transport controls rather than over them: with no
 * active roles there is nothing for the controls to act on, so leaving them
 * on screen offers the user buttons that silently do nothing. Alongside
 * playback the display is also the code's out-channel, so the artwork it
 * shows is the output the client "suspends ... for the duration of the
 * attempt" (pairing.md, Entering and leaving pairing), and this takes Now
 * Playing's place. The Allow pairing prompt comes before the attempt
 * ("client/pair-pending precedes an attempt and does not start it") and
 * takes its place the same way.
 *
 * [AdmissionState.READY] must never reach here - the caller passes PAIRING
 * for a prompt alongside playback, and otherwise returns early on it - so
 * this takes only the two blocked states and has no third branch to render.
 *
 * @param pairingCodeFormat the emission format of [pairingCode]. In
 *   [PairingCodeFormat.QR_CODE] the code is a pairing token, shown as a QR
 *   code only.
 * @param lastFailure what happened to the last pairing attempt, if it did
 *   not finish. Not shown while a new attempt's code or gesture prompt is.
 */
@Composable
fun AdmissionNotice(
    state: AdmissionState,
    serverName: String,
    onOpenPairingClick: () -> Unit,
    pairingCode: String? = null,
    pairingCodeFormat: PairingCodeFormat = PairingCodeFormat.DIGITS,
    gestureRequested: Boolean = false,
    onAllowPairingClick: () -> Unit = {},
    lastFailure: String? = null,
    modifier: Modifier = Modifier
) {
    // Three shapes for AdmissionState.PAIRING: a code to show, a gesture to
    // confirm, or (absent both) the Pairing PSK token guidance this branch
    // has always shown. AWAITING_APPROVAL takes none of them.
    val showingCode = state == AdmissionState.PAIRING && pairingCode != null
    val showingQr = showingCode && pairingCodeFormat == PairingCodeFormat.QR_CODE
    val showingGesture = state == AdmissionState.PAIRING && pairingCode == null && gestureRequested

    val titleRes = when {
        showingQr -> R.string.pairing_qr_title
        showingCode -> R.string.pairing_code_title
        showingGesture -> R.string.pairing_allow_title
        state == AdmissionState.PAIRING -> R.string.admission_pairing_title
        else -> R.string.admission_awaiting_approval_title
    }
    val bodyRes = when {
        showingQr -> R.string.pairing_qr_body
        showingCode -> R.string.pairing_code_body
        showingGesture -> R.string.pairing_allow_body
        state == AdmissionState.PAIRING -> R.string.admission_pairing_body
        else -> R.string.admission_awaiting_approval_body
    }

    // A TV is read from across the room, and driven by a remote that can
    // only press what has focus: the button is focused as each shape that
    // has one appears.
    val formFactor = LocalFormFactor.current
    val isTv = formFactor == FormFactor.TV
    val buttonFocus = remember { FocusRequester() }
    val hasButton = state == AdmissionState.PAIRING && !showingCode
    if (isTv && hasButton) {
        LaunchedEffect(showingGesture) { buttonFocus.requestFocus() }
    }
    val titleStyle = if (isTv) {
        val size = AdaptiveDefaults.titleTextSize(formFactor)
        MaterialTheme.typography.titleLarge.copy(fontSize = size, lineHeight = size * 1.2f)
    } else {
        MaterialTheme.typography.titleLarge
    }
    val bodyStyle = if (isTv) {
        val size = AdaptiveDefaults.bodyTextSize(formFactor)
        MaterialTheme.typography.bodyMedium.copy(fontSize = size, lineHeight = size * 1.2f)
    } else {
        MaterialTheme.typography.bodyMedium
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (isTv) TvOverscanHorizontal else 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(titleRes),
            style = titleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            // The state changes without the user touching anything, so a
            // screen reader has to be told rather than waiting to be asked.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (showingGesture) stringResource(bodyRes) else stringResource(bodyRes, serverName),
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Why the last pairing attempt did not finish. In either state: a
        // server that gives up on a pairing goes back to waiting for
        // approval, and this is still why the player cannot play. Not over a
        // code or the gesture prompt: those belong to a new attempt, and the
        // old one's "start pairing again" would contradict them.
        if (lastFailure != null && !showingCode && !showingGesture) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = lastFailure,
                style = bodyStyle,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        if (showingQr) {
            Spacer(modifier = Modifier.height(24.dp))
            // The token is "rendered as a QR code on its display" (pairing.md,
            // "QR-code emission"), and never as text: the server chose
            // qr_code because its operator scans. Square, and as large as
            // the screen leaves room for up to qrSize, which a phone in
            // landscape does not.
            val qrSize = if (isTv) 300.dp else 240.dp
            PairingQrImage(
                token = requireNotNull(pairingCode),
                size = qrSize,
                contentDescription = stringResource(R.string.pairing_code_qr_description),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .sizeIn(maxWidth = qrSize, maxHeight = qrSize)
                    .aspectRatio(1f)
            )
        } else if (showingCode) {
            Spacer(modifier = Modifier.height(24.dp))
            PairingCodeDisplay(code = requireNotNull(pairingCode))
        } else if (showingGesture) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onAllowPairingClick,
                modifier = Modifier.tvFocusRing(focusRequester = buttonFocus, shape = ButtonDefaults.shape)
            ) {
                Text(text = stringResource(R.string.pairing_allow_button))
            }
        } else if (state == AdmissionState.PAIRING) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onOpenPairingClick,
                modifier = Modifier.tvFocusRing(focusRequester = buttonFocus, shape = ButtonDefaults.shape)
            ) {
                Text(text = stringResource(R.string.admission_open_pairing))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AdmissionNoticePairingPreview() {
    SendSpinTheme {
        AdmissionNotice(
            state = AdmissionState.PAIRING,
            serverName = "Living Room",
            onOpenPairingClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AdmissionNoticeAwaitingApprovalPreview() {
    SendSpinTheme {
        AdmissionNotice(
            state = AdmissionState.AWAITING_APPROVAL,
            serverName = "Living Room",
            onOpenPairingClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AdmissionNoticePairingCodePreview() {
    SendSpinTheme {
        AdmissionNotice(
            state = AdmissionState.PAIRING,
            serverName = "Living Room",
            onOpenPairingClick = {},
            pairingCode = "123456"
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AdmissionNoticePairingQrPreview() {
    SendSpinTheme {
        AdmissionNotice(
            state = AdmissionState.PAIRING,
            serverName = "Living Room",
            onOpenPairingClick = {},
            // pairing.md's QR-code emission reference vector
            pairingCode = "SP:14DQ6FY7E4XTOP9HJ5LV6Z3PO57YPD4XT6T97N5Y",
            pairingCodeFormat = PairingCodeFormat.QR_CODE
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AdmissionNoticeAllowPairingPreview() {
    SendSpinTheme {
        AdmissionNotice(
            state = AdmissionState.PAIRING,
            serverName = "Living Room",
            onOpenPairingClick = {},
            gestureRequested = true,
            onAllowPairingClick = {}
        )
    }
}
