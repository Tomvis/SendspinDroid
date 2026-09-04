package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.cpace.CPaceResponder
import com.sendspindroid.sendspin.crypto.secureRandomBytes

/** What happened, for the Dynamic Pairing Code flow. */
sealed interface DynamicPairingEvent {

    /**
     * A `server/activate` declaring the `dynamic_pin` method.
     *
     * @param pairingIndex the attempt counter, as sent in `client/pair-init`
     *   and `client/pair-pending` and folded into the CPace `sid`.
     */
    data class PairingActivation(val pairingIndex: Int) : DynamicPairingEvent

    /** The local pairing window opened, ending a gesture-gated wait. */
    object WindowOpened : DynamicPairingEvent

    /** `server/pair-init`: the server's nonce contribution, `nonce_A`. */
    data class ServerPairInit(val nonceA: ByteArray) : DynamicPairingEvent

    /** `server/pair-auth`: the server's CPace public share, `Ya`. */
    data class ServerPairAuth(val ya: ByteArray) : DynamicPairingEvent

    /** `server/pair-confirm`: the server's MCF tag, `Ta`. */
    data class ServerPairConfirm(val ta: ByteArray) : DynamicPairingEvent

    /** `server/pair-finalize`: the server has persisted its side. */
    object ServerPairFinalize : DynamicPairingEvent

    /** A `server/activate` without `dynamic_pin`, which ends any attempt. */
    object NonPairingActivation : DynamicPairingEvent

    object AttemptTimeout : DynamicPairingEvent

    /** The server ended the attempt. Not an inner-authentication failure. */
    data class PairAbortReceived(val reason: String) : DynamicPairingEvent

    /** The operator cancelled through a local UI. */
    object UserCancelled : DynamicPairingEvent

    object ConnectionClosed : DynamicPairingEvent
}

/** What the connection (or the UI) should do about it. */
sealed interface DynamicPairingAction {

    data class SendPairInit(val pairingIndex: Int, val commitB: ByteArray) : DynamicPairingAction

    data class SendPairPending(val pairingIndex: Int) : DynamicPairingAction

    /**
     * Ask the UI to show an "Allow pairing" gesture.
     *
     * `SendPairPending` is a wire message, not a UI instruction -- this is the
     * signal the UI actually keys off while the attempt sits gesture-gated.
     */
    object RequestGesture : DynamicPairingAction

    object StartAttemptTimeout : DynamicPairingAction

    /** Show the code to the operator. */
    data class EmitPairingCode(val code: String) : DynamicPairingAction

    /** Send `client/pair-auth` carrying the CPace public share `Yb`. */
    data class SendPairAuth(val yb: ByteArray) : DynamicPairingAction

    data class SendPairAbort(val reason: String) : DynamicPairingAction

    /** Send `client/pair-confirm` carrying the MCF tag `Tb` and the wrapped `nonce_B` opening. */
    class SendPairConfirm(tb: ByteArray, wrappedNonceB: ByteArray) : DynamicPairingAction {
        private val tbCopy = tb.copyOf()
        private val wrappedNonceBCopy = wrappedNonceB.copyOf()
        val tb: ByteArray get() = tbCopy.copyOf()
        val wrappedNonceB: ByteArray get() = wrappedNonceBCopy.copyOf()
    }

    /** Send `client/pair-finalize` carrying the new long-term PSK, sealed under the CPace ISK. */
    class SendPairFinalize(wrappedPsk: ByteArray) : DynamicPairingAction {
        private val secret = wrappedPsk.copyOf()
        val wrappedPsk: ByteArray get() = secret.copyOf()
    }

    /** Store the record. Only ever emitted after the server acknowledges. */
    class PersistRecord(psk: ByteArray) : DynamicPairingAction {
        private val secret = psk.copyOf()
        val psk: ByteArray get() = secret.copyOf()
    }

    object StopEmittingCode : DynamicPairingAction

    /**
     * Close the WebSocket with no application-level message and persist nothing.
     *
     * A malformed field, a low-order share, a commitment that does not open, or
     * an out-of-sequence message are protocol errors, not in-band aborts:
     * telling an unauthenticated peer which check it failed is exactly the leak
     * this distinction prevents. Deliberately carries no reason.
     */
    object ProtocolError : DynamicPairingAction
}

/**
 * The Dynamic Pairing Code flow, as a pure state machine.
 *
 * `pairing.md#dynamic-pairing-code-flow`. No I/O and no coroutines: the rules
 * that matter are about *whether* something is emitted and *which* of two
 * failure shapes it is, and both are far easier to pin here than through a
 * live connection.
 *
 * States: `Idle -> AwaitingGesture -> AwaitingServerInit -> AwaitingAuth ->
 * AwaitingConfirm -> AwaitingFinalize -> Done`. Single attempt at a time; the
 * connection owns one of these.
 *
 * @param handshakeHash the Noise handshake hash `h` for this connection.
 * @param counter brute-force protection; shared across attempts on this
 *   client, not partitioned by server.
 * @param suite the negotiated cipher suite, needed to seal `client/pair-confirm`'s
 *   `nonce_B` opening and `client/pair-finalize`'s wrapped PSK.
 */
class DynamicPairingCodeFlow(
    private val handshakeHash: ByteArray,
    private val counter: PairingFailureCounter,
    private val suite: NoiseCipherSuite,
) {

    private enum class State {
        IDLE, AWAITING_GESTURE, AWAITING_SERVER_INIT, AWAITING_AUTH, AWAITING_CONFIRM, AWAITING_FINALIZE, DONE
    }

    private var state: State = State.IDLE

    private var pairingIndex: Int = 0

    /** The client's nonce contribution. Discarded on every exit path. */
    private var nonceB: ByteArray? = null

    private var responder: CPaceResponder? = null

    /** The new long-term PSK, minted at `server/pair-confirm`, held for the finalize ack. */
    private var pendingPsk: ByteArray? = null

    fun onEvent(event: DynamicPairingEvent): List<DynamicPairingAction> = when (event) {
        is DynamicPairingEvent.PairingActivation -> onPairingActivation(event)
        DynamicPairingEvent.WindowOpened -> onWindowOpened()
        is DynamicPairingEvent.ServerPairInit -> onServerPairInit(event)
        is DynamicPairingEvent.ServerPairAuth -> onServerPairAuth(event)
        is DynamicPairingEvent.ServerPairConfirm -> onServerPairConfirm(event)
        DynamicPairingEvent.ServerPairFinalize -> onServerPairFinalize()
        DynamicPairingEvent.AttemptTimeout -> onAttemptTimeout()
        DynamicPairingEvent.NonPairingActivation,
        is DynamicPairingEvent.PairAbortReceived,
        DynamicPairingEvent.UserCancelled,
        DynamicPairingEvent.ConnectionClosed,
        -> onTerminal()
    }

    private fun onPairingActivation(event: DynamicPairingEvent.PairingActivation): List<DynamicPairingAction> {
        pairingIndex = event.pairingIndex
        if (counter.isEscalated) {
            state = State.AWAITING_GESTURE
            return listOf(DynamicPairingAction.SendPairPending(pairingIndex), DynamicPairingAction.RequestGesture)
        }
        return startAttempt()
    }

    private fun onWindowOpened(): List<DynamicPairingAction> {
        if (state != State.AWAITING_GESTURE) return protocolError()
        return startAttempt()
    }

    private fun startAttempt(): List<DynamicPairingAction> {
        val nonce = PairingCode.generateNonce()
        nonceB = nonce
        state = State.AWAITING_SERVER_INIT
        return listOf(
            DynamicPairingAction.SendPairInit(pairingIndex, PairingCode.commit(nonce)),
            DynamicPairingAction.StartAttemptTimeout,
        )
    }

    private fun onServerPairInit(event: DynamicPairingEvent.ServerPairInit): List<DynamicPairingAction> {
        val nonce = nonceB
        if (state != State.AWAITING_SERVER_INIT || nonce == null) return protocolError()

        val code = PairingCode.deriveDigits(handshakeHash, event.nonceA, nonce)
        counter.onEmissionStarted()
        responder = CPaceResponder(prs = code.encodeToByteArray(), sid = sidFor(pairingIndex))
        state = State.AWAITING_AUTH
        return listOf(DynamicPairingAction.EmitPairingCode(code))
    }

    private fun onServerPairAuth(event: DynamicPairingEvent.ServerPairAuth): List<DynamicPairingAction> {
        val r = responder
        if (state != State.AWAITING_AUTH || r == null) return protocolError()

        val actions = mutableListOf<DynamicPairingAction>(DynamicPairingAction.SendPairAuth(r.publicShare))
        if (!r.derive(event.ya)) {
            discard()
            actions.add(DynamicPairingAction.ProtocolError)
            return actions
        }
        state = State.AWAITING_CONFIRM
        return actions
    }

    private fun onServerPairConfirm(event: DynamicPairingEvent.ServerPairConfirm): List<DynamicPairingAction> {
        val r = responder
        val nonce = nonceB
        if (state != State.AWAITING_CONFIRM || r == null || nonce == null) return protocolError()

        if (!r.verify(event.ta)) {
            discard()
            return listOf(DynamicPairingAction.SendPairAbort(PairAbortReason.PAIRING_CODE_MISMATCH))
        }

        counter.onServerKcVerified()
        val sid = sidFor(pairingIndex)
        val isk = r.isk
        val wrappedNonceB = PairingWrap.seal(suite, PairingWrap.wrapKey(PairingWrap.NONCE_LABEL, sid, isk), nonce)
        val psk = secureRandomBytes(Psk.PSK_SIZE)
        val wrappedPsk = PairingWrap.seal(suite, PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk), psk)
        pendingPsk = psk
        state = State.AWAITING_FINALIZE
        return listOf(
            DynamicPairingAction.SendPairConfirm(r.tag(), wrappedNonceB),
            DynamicPairingAction.SendPairFinalize(wrappedPsk),
        )
    }

    private fun onServerPairFinalize(): List<DynamicPairingAction> {
        val psk = pendingPsk
        if (state != State.AWAITING_FINALIZE || psk == null) return protocolError()

        // Build the action FIRST: it copies, and discard() zeroes the very
        // array `psk` points at.
        val action = DynamicPairingAction.PersistRecord(psk)
        discard()
        state = State.DONE
        return listOf(action, DynamicPairingAction.StopEmittingCode)
    }

    private fun onAttemptTimeout(): List<DynamicPairingAction> {
        if (state == State.IDLE || state == State.DONE) return protocolError()
        discard()
        return listOf(DynamicPairingAction.SendPairAbort(PairAbortReason.ATTEMPT_TIMEOUT), DynamicPairingAction.StopEmittingCode)
    }

    /**
     * `NonPairingActivation`, `PairAbortReceived`, `UserCancelled`,
     * `ConnectionClosed`: always terminal, regardless of the current state --
     * discard everything, persist nothing.
     */
    private fun onTerminal(): List<DynamicPairingAction> {
        discard()
        return listOf(DynamicPairingAction.StopEmittingCode)
    }

    private fun protocolError(): List<DynamicPairingAction> {
        discard()
        return listOf(DynamicPairingAction.ProtocolError)
    }

    /** Zero secrets rather than dropping references, then reset to Idle. */
    private fun discard() {
        nonceB?.fill(0)
        nonceB = null
        responder = null
        pendingPsk?.fill(0)
        pendingPsk = null
        state = State.IDLE
    }

    companion object {
        private val SID_LABEL = "sendspin-pair-pake-v1".encodeToByteArray()

        /**
         * `sid = "sendspin-pair-pake-v1" || h || pairing_index` (big-endian uint32).
         *
         * `pairing_index` is used exactly as received on `PairingActivation`,
         * with no re-basing here. The reference implementation's counter
         * (`aiosendspin`'s `client/connection.py` `_pairing_index`, also
         * `server/connection.py`) starts at 0 and is incremented BEFORE use,
         * so the first attempt on a connection is already `pairing_index = 1`
         * by the time it reaches `_pake_sid` (`aiosendspin/noise/pairing.py`).
         */
        internal fun sidFor(handshakeHash: ByteArray, pairingIndex: Int): ByteArray {
            val counterBytes = byteArrayOf(
                (pairingIndex ushr 24).toByte(),
                (pairingIndex ushr 16).toByte(),
                (pairingIndex ushr 8).toByte(),
                pairingIndex.toByte(),
            )
            return SID_LABEL + handshakeHash + counterBytes
        }
    }

    private fun sidFor(pairingIndex: Int): ByteArray = sidFor(handshakeHash, pairingIndex)
}
