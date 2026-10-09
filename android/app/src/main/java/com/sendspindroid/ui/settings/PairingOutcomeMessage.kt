package com.sendspindroid.ui.settings

import android.content.res.Resources
import androidx.annotation.StringRes
import com.sendspindroid.R
import com.sendspindroid.sendspin.pairing.PairAbortReason
import com.sendspindroid.sendspin.pairing.PairingOutcome

/** What happened to a pairing, in words the user can act on. */
fun PairingOutcome.message(resources: Resources): String = when (this) {
    is PairingOutcome.Paired -> resources.getString(R.string.pairing_outcome_paired, server)
    is PairingOutcome.Unpaired -> resources.getString(R.string.pairing_outcome_unpaired, server)
    is PairingOutcome.Aborted -> abortMessageRes(reason, sentByUs)
        ?.let { resources.getString(it, server) }
        // A reason this client does not know still ended the attempt.
        ?: resources.getString(R.string.pairing_outcome_aborted_other, server, reason)
}

/**
 * The copy for a `pair/abort` reason, or null for one the protocol does not
 * define. Two reasons can come from either side, and who cancelled or whose
 * check failed is the part the user needs.
 */
@StringRes
internal fun abortMessageRes(reason: String, sentByUs: Boolean): Int? = when (reason) {
    PairAbortReason.ATTEMPT_TIMEOUT -> R.string.pairing_outcome_attempt_timeout
    PairAbortReason.CONCURRENT_ATTEMPT -> R.string.pairing_outcome_concurrent_attempt
    PairAbortReason.METHOD_NOT_SUPPORTED -> R.string.pairing_outcome_method_not_supported
    PairAbortReason.PAIRING_CODE_MISMATCH ->
        if (sentByUs) R.string.pairing_outcome_code_mismatch_sent
        else R.string.pairing_outcome_code_mismatch_received
    PairAbortReason.USER_CANCELLED ->
        if (sentByUs) R.string.pairing_outcome_cancelled_sent
        else R.string.pairing_outcome_cancelled_received
    else -> null
}
