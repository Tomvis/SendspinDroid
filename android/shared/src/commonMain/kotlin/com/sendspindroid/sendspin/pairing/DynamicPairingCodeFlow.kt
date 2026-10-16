package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.cpace.CPaceResponder
import com.sendspindroid.sendspin.crypto.secureRandomBytes

/** What happened, for the Dynamic Pairing Code flow. */
sealed interface DynamicPairingEvent {

    /**
     * A `server/activate` admitting a `dynamic_pairing_code` attempt.
     *
     * @param pairingIndex the number of pairing activations received since the
     *   last Noise handshake, as sent in `client/pair-init` and
     *   `client/pair-pending` and folded into the CPace `sid`.
     */
    data class PairingActivation(val pairingIndex: Int) : DynamicPairingEvent

    /** The local pairing window opened, ending a gesture-gated wait. */
    object WindowOpened : DynamicPairingEvent

    /**
     * `server/pair-init`, which begins a round. Only the first round's
     * carries the server's nonce contribution, `nonce_A`.
     */
    data class ServerPairInit(val nonceA: ByteArray?) : DynamicPairingEvent

    /** `server/pair-auth`: the server's CPace public share, `Ya`. */
    data class ServerPairAuth(val ya: ByteArray) : DynamicPairingEvent

    /** `server/pair-confirm`: the server's MCF tag, `Ta`. */
    data class ServerPairConfirm(val ta: ByteArray) : DynamicPairingEvent

    /** `server/pair-finalize`: the server has persisted its side. */
    object ServerPairFinalize : DynamicPairingEvent

    /** A `server/activate` that does not admit a `dynamic_pairing_code` attempt, which ends any attempt. */
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

    /**
     * Send `client/pair-retry`, asking for another round. The code stays
     * shown and the attempt timer keeps running.
     */
    object SendPairRetry : DynamicPairingAction

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
 * AwaitingConfirm -> AwaitingFinalize -> Done`, with `AwaitingConfirm ->
 * AwaitingNextRound -> AwaitingAuth` for each round after the first, or
 * `Aborted` once either side has sent `pair/abort`. Single attempt at a time;
 * the connection owns one of these.
 *
 * An attempt runs rounds against one pairing code (`pairing.md#rounds`). A
 * failed `server_kc` sends `client/pair-retry`, and the server's next
 * `server/pair-init` starts a separate CPace run under that round's own
 * `sid`; the code stays shown and the attempt timer keeps running. At the
 * round limit the failure aborts with `pairing_code_mismatch` instead, and
 * later attempts are held back with `client/pair-pending` until the operator
 * allows pairing. This client holds back only at the limit, never earlier.
 *
 * @param handshakeHash the Noise handshake hash `h` for this connection.
 * @param counter the round count; shared across attempts on this client, not
 *   partitioned by server.
 * @param suite the negotiated cipher suite, needed to seal `client/pair-confirm`'s
 *   `nonce_B` opening and `client/pair-finalize`'s wrapped PSK.
 */
class DynamicPairingCodeFlow(
    private val handshakeHash: ByteArray,
    private val counter: PairingFailureCounter,
    private val suite: NoiseCipherSuite,
) {

    private enum class State {
        IDLE, AWAITING_GESTURE, AWAITING_SERVER_INIT, AWAITING_AUTH, AWAITING_CONFIRM, AWAITING_FINALIZE, DONE,

        /** `client/pair-retry` sent; the next round's `server/pair-init` is due. */
        AWAITING_NEXT_ROUND,

        /**
         * `pair/abort` has been sent or received. "A client that has aborted
         * an attempt likewise silently discards pairing messages received
         * before the next `server/activate`."
         */
        ABORTED;

        val inAttempt: Boolean get() = this != IDLE && this != DONE && this != ABORTED
    }

    private var state: State = State.IDLE

    /**
     * "In progress from `client/pair-init` until success or `pair/abort`."
     * Waiting for the gesture is before that: `client/pair-pending` "precedes
     * an attempt and does not start it".
     */
    val attemptInProgress: Boolean get() = state.inAttempt && state != State.AWAITING_GESTURE

    /** Holding the attempt back until the operator's gesture, having sent `client/pair-pending`. */
    val awaitingGesture: Boolean get() = state == State.AWAITING_GESTURE

    private var pairingIndex: Int = 0

    /** The client's nonce contribution. Discarded on every exit path. */
    private var nonceB: ByteArray? = null

    /** The attempt's pairing code, derived in the first round and emitted again in each. */
    private var code: String? = null

    /** The round within the attempt, 1 for the first. It goes into the CPace `sid`. */
    private var round: Int = 0

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
        DynamicPairingEvent.AttemptTimeout -> abort(PairAbortReason.ATTEMPT_TIMEOUT)
        DynamicPairingEvent.UserCancelled -> abort(PairAbortReason.USER_CANCELLED)
        is DynamicPairingEvent.PairAbortReceived -> onPairAbortReceived()
        DynamicPairingEvent.NonPairingActivation,
        DynamicPairingEvent.ConnectionClosed,
        -> onTerminal()
    }

    private fun onPairingActivation(event: DynamicPairingEvent.PairingActivation): List<DynamicPairingAction> {
        // "A server MAY send such a cancelling server/activate at any point
        // during a pairing attempt. On receipt the client abandons the
        // attempt, discarding all pairing state." A re-activation mid-attempt
        // must reset completely, not layer new state over live state.
        val abandoning = state.inAttempt
        discard()
        pairingIndex = event.pairingIndex

        val actions = mutableListOf<DynamicPairingAction>()
        if (abandoning) actions.add(DynamicPairingAction.StopEmittingCode)

        // At the round limit the client must "hold attempts back until a
        // deliberate, manufacturer-defined operator action".
        if (counter.roundLimitReached) {
            state = State.AWAITING_GESTURE
            actions.add(DynamicPairingAction.SendPairPending(pairingIndex))
            actions.add(DynamicPairingAction.RequestGesture)
        } else {
            actions.addAll(startAttempt())
        }
        return actions
    }

    private fun onWindowOpened(): List<DynamicPairingAction> {
        // WindowOpened is a local UI gesture (the operator's "Allow pairing"
        // tap), never something a peer can trigger - so arriving outside
        // AWAITING_GESTURE (e.g. a stray double-tap) is not protocol
        // misbehaviour and must not close a healthy connection.
        if (state != State.AWAITING_GESTURE) return emptyList()
        // The operator action "also resets the count".
        counter.onOperatorAllowed()
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

    /**
     * `server/pair-init` begins a round: "On the first round's message the
     * client derives the pairing code. On each receipt it emits the code".
     */
    private fun onServerPairInit(event: DynamicPairingEvent.ServerPairInit): List<DynamicPairingAction> {
        if (state == State.ABORTED) return emptyList()
        // nonce_A is "Present in the first round only", so a first round
        // without one and a later round with one are both out of sequence.
        val nonceA = event.nonceA
        val roundCode = when (state) {
            State.AWAITING_SERVER_INIT -> {
                val nonce = nonceB
                if (nonceA == null || nonce == null) return protocolError()
                round = 1
                PairingCode.deriveDigits(handshakeHash, nonceA, nonce).also { code = it }
            }
            State.AWAITING_NEXT_ROUND -> {
                val current = code
                if (nonceA != null || current == null) return protocolError()
                round += 1
                current
            }
            else -> return protocolError()
        }

        // Counted before the code goes out, so the round counts even if the
        // attempt then ends.
        counter.onRoundStarted()
        // "a separate CPace run - fresh scalars, its own sid".
        responder = CPaceResponder(prs = roundCode.encodeToByteArray(), sid = sidFor(pairingIndex, round))
        state = State.AWAITING_AUTH
        return listOf(DynamicPairingAction.EmitPairingCode(roundCode))
    }

    private fun onServerPairAuth(event: DynamicPairingEvent.ServerPairAuth): List<DynamicPairingAction> {
        if (state == State.ABORTED) return emptyList()
        val r = responder
        if (state != State.AWAITING_AUTH || r == null) return protocolError()

        // Compute the share, but do not send it until the peer's share is
        // known to be usable: at the point we decide to tear the connection
        // down, sending into a socket we are about to close would hand an
        // unauthenticated peer a response it did nothing to earn.
        val ourShare = r.publicShare
        if (!r.derive(event.ya)) {
            return protocolError()
        }
        state = State.AWAITING_CONFIRM
        return listOf(DynamicPairingAction.SendPairAuth(ourShare))
    }

    private fun onServerPairConfirm(event: DynamicPairingEvent.ServerPairConfirm): List<DynamicPairingAction> {
        if (state == State.ABORTED) return emptyList()
        val r = responder
        val nonce = nonceB
        if (state != State.AWAITING_CONFIRM || r == null || nonce == null) return protocolError()

        if (!r.verify(event.ta)) {
            // "The client SHOULD retry, and MUST abort instead at the round
            // limit."
            if (counter.roundLimitReached) return abort(PairAbortReason.PAIRING_CODE_MISMATCH)
            // "A retry keeps the attempt, its pairing code, and its running
            // attempt timeout in place", so no StopEmittingCode: it would
            // clear the code and the timer.
            responder = null
            state = State.AWAITING_NEXT_ROUND
            return listOf(DynamicPairingAction.SendPairRetry)
        }

        counter.onServerKcVerified()
        val sid = sidFor(pairingIndex, round)
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
        if (state == State.ABORTED) return emptyList()
        val psk = pendingPsk
        if (state != State.AWAITING_FINALIZE || psk == null) return protocolError()

        // Build the action FIRST: it copies, and discard() zeroes the very
        // array `psk` points at.
        val action = DynamicPairingAction.PersistRecord(psk)
        discard()
        state = State.DONE
        return listOf(action, DynamicPairingAction.StopEmittingCode)
    }

    /**
     * End the attempt from this side: a failed `server_kc` at the round
     * limit, the attempt timeout, or the operator cancelling. "Aborts a
     * pairing attempt, started or not", so a gesture-gated attempt is aborted
     * the same way.
     */
    private fun abort(reason: String): List<DynamicPairingAction> {
        // A local timer or a stray tap with nothing in flight has nothing to abort.
        if (!state.inAttempt) return emptyList()
        discard()
        state = State.ABORTED
        return listOf(DynamicPairingAction.SendPairAbort(reason), DynamicPairingAction.StopEmittingCode)
    }

    /** The server ended the attempt. Nothing is sent back. */
    private fun onPairAbortReceived(): List<DynamicPairingAction> {
        // "A pair/abort received after the receiver has itself ended the
        // attempt has no effect."
        if (state.inAttempt) {
            discard()
            state = State.ABORTED
        }
        return listOf(DynamicPairingAction.StopEmittingCode)
    }

    /**
     * `NonPairingActivation`, `ConnectionClosed`: always terminal, regardless
     * of the current state -- discard everything, persist nothing.
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
        code = null
        round = 0
        responder = null
        pendingPsk?.fill(0)
        pendingPsk = null
        state = State.IDLE
    }

    companion object {
        private val SID_LABEL = "sendspin-pair-pake-v1".encodeToByteArray()

        /**
         * `sid = "sendspin-pair-pake-v1" || h || pairing_index || round`, the
         * last two as big-endian uint32 (`pairing.md#pake`).
         *
         * `pairing_index` is used exactly as received on `PairingActivation`,
         * with no re-basing here: the first pairing activation after a Noise
         * handshake is index 1. `round` is "the number of the round within
         * the attempt, 1 for the first"; the server counts it up when
         * `client/pair-retry` arrives, so the round after the first retry is 2.
         */
        internal fun sidFor(handshakeHash: ByteArray, pairingIndex: Int, round: Int): ByteArray =
            SID_LABEL + handshakeHash + uint32(pairingIndex) + uint32(round)

        private fun uint32(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    private fun sidFor(pairingIndex: Int, round: Int): ByteArray = sidFor(handshakeHash, pairingIndex, round)
}
