package com.sendspindroid.sendspin.pairing

/**
 * How the last pairing exchange with a server ended.
 *
 * @property server the server's name, for display.
 * @property atMs when it happened. Also what makes two identical outcomes in
 *   a row two values, so the second one is still announced.
 */
sealed interface PairingOutcome {
    val server: String
    val atMs: Long

    /** The server stored the record and so did we. */
    data class Paired(
        override val server: String,
        override val atMs: Long = System.currentTimeMillis(),
    ) : PairingOutcome

    /**
     * The attempt ended in `pair/abort`.
     *
     * @param reason the wire reason, one of [PairAbortReason.ALL] unless the
     *   server sent something else.
     * @param sentByUs false when the server sent it.
     */
    data class Aborted(
        val reason: String,
        val sentByUs: Boolean,
        override val server: String,
        override val atMs: Long = System.currentTimeMillis(),
    ) : PairingOutcome

    /**
     * The server ended the attempt with a `server/activate` and no
     * `pair/abort`: "On receipt the client abandons the attempt, discarding
     * all pairing state". Nothing was persisted.
     */
    data class Abandoned(
        override val server: String,
        override val atMs: Long = System.currentTimeMillis(),
    ) : PairingOutcome

    /** `server/unpair`: the server dropped its pairing with this device. */
    data class Unpaired(
        override val server: String,
        override val atMs: Long = System.currentTimeMillis(),
    ) : PairingOutcome
}
