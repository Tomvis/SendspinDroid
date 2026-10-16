package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
import com.sendspindroid.sendspin.crypto.aeadOpen
import com.sendspindroid.sendspin.crypto.cpace.CPaceX25519
import com.sendspindroid.sendspin.crypto.x25519ScalarMult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sequencing rules from pairing.md, exercised without a socket.
 *
 * The three that matter most and are easiest to get wrong:
 *  - client/pair-confirm and client/pair-finalize go out TOGETHER, with no
 *    server response awaited in between.
 *  - a server_kc mismatch asks for another round with client/pair-retry,
 *    keeping the code shown and the timer running, until the round limit,
 *    where it is an in-band pair/abort after which the code is no longer
 *    shown and in-flight pairing messages are discarded silently; but a bad
 *    commitment, a low-order share or a malformed field is a PROTOCOL ERROR
 *    that closes the socket silently -- telling an unauthenticated peer which
 *    check it failed is the leak this distinction prevents.
 *  - an attempt is held back only at the round limit.
 */
class DynamicPairingCodeFlowTest {

    private class FakeStore(var value: Int = 0) : PairingCounterStore {
        override fun load(): Int = value
        override fun save(v: Int) { value = v }
    }

    private fun flow(rounds: Int = 0) = DynamicPairingCodeFlow(
        handshakeHash = ByteArray(32),
        counter = PairingFailureCounter(FakeStore(rounds)),
        suite = NoiseCipherSuite.CHACHA_POLY,
    )

    @Test
    fun `below the round limit an activation sends pair-init immediately`() {
        val actions = flow(rounds = 19).onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        assertTrue(actions.any { it is DynamicPairingAction.SendPairInit })
        assertTrue(actions.none { it is DynamicPairingAction.SendPairPending })
        assertTrue(actions.none { it is DynamicPairingAction.RequestGesture })
    }

    @Test
    fun `at the round limit an activation is held back until the operator allows it`() {
        val store = FakeStore(20)
        val f = DynamicPairingCodeFlow(ByteArray(32), PairingFailureCounter(store), NoiseCipherSuite.CHACHA_POLY)
        val actions = f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        // client/pair-pending "Does not start the attempt or its timeout."
        assertEquals(
            listOf(DynamicPairingAction.SendPairPending(1), DynamicPairingAction.RequestGesture),
            actions,
        )
        assertTrue(!f.attemptInProgress)

        // "until a deliberate, manufacturer-defined operator action, which
        // also resets the count."
        val opened = f.onEvent(DynamicPairingEvent.WindowOpened)
        assertEquals(0, store.value)
        assertEquals(1, opened.filterIsInstance<DynamicPairingAction.SendPairInit>().single().pairingIndex)
        assertEquals(DynamicPairingAction.StartAttemptTimeout, opened.last())
    }

    @Test
    fun `a terminal event while gesture-gated still stops emitting`() {
        // Task 14 fix round 1: the service clears its "Allow pairing" gesture
        // flag on StopEmittingCode, on the assumption that this action fires
        // on every path out of AWAITING_GESTURE -- including one that never
        // reached a code at all. Pin that assumption here, at the flow level,
        // rather than leaving it implicit in the service wiring.
        val f = flow(rounds = 20)
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.ConnectionClosed)
        assertTrue(actions.any { it is DynamicPairingAction.StopEmittingCode })
    }

    @Test
    fun `server pair-init emits the code and counts the round`() {
        val store = FakeStore()
        val f = DynamicPairingCodeFlow(ByteArray(32), PairingFailureCounter(store), NoiseCipherSuite.CHACHA_POLY)
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        val emit = actions.filterIsInstance<DynamicPairingAction.EmitPairingCode>().single()
        assertEquals(6, emit.code.length)
        assertEquals(1, store.value)
    }

    @Test
    fun `a first round without nonce_A is a protocol error`() {
        // nonce_A: "Present in the first round only." - and so required there.
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        assertEquals(
            listOf<DynamicPairingAction>(DynamicPairingAction.ProtocolError),
            f.onEvent(DynamicPairingEvent.ServerPairInit(null)),
        )
    }

    /** A flow whose first round has just failed `server_kc`, counting rounds in [store]. */
    private fun mismatched(
        store: FakeStore = FakeStore(),
    ): Triple<DynamicPairingCodeFlow, String, List<DynamicPairingAction>> {
        val f = DynamicPairingCodeFlow(ByteArray(32), PairingFailureCounter(store), NoiseCipherSuite.CHACHA_POLY)
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val code = f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
            .filterIsInstance<DynamicPairingAction.EmitPairingCode>().single().code
        f.onEvent(DynamicPairingEvent.ServerPairAuth(VALID_SHARE))
        return Triple(f, code, f.onEvent(DynamicPairingEvent.ServerPairConfirm(ByteArray(64))))
    }

    /** A flow whose attempt has just ended in a `server_kc` mismatch at the round limit. */
    private fun abortedAtLimit(): DynamicPairingCodeFlow = mismatched(FakeStore(19)).first

    @Test
    fun `a server_kc mismatch below the limit asks for another round and keeps the attempt`() {
        val (f, _, actions) = mismatched()
        // "The client SHOULD retry". No StopEmittingCode: that is also what
        // cancels the attempt timer, and "A retry keeps the attempt, its
        // pairing code, and its running attempt timeout in place".
        assertEquals(listOf<DynamicPairingAction>(DynamicPairingAction.SendPairRetry), actions)
        assertTrue(f.attemptInProgress)
    }

    @Test
    fun `the next round re-emits the same code and counts it`() {
        val store = FakeStore()
        val (f, code, _) = mismatched(store)

        // "after client/pair-retry it is sent without" nonce_A, and "On each
        // receipt it emits the code".
        val actions = f.onEvent(DynamicPairingEvent.ServerPairInit(null))

        assertEquals(listOf<DynamicPairingAction>(DynamicPairingAction.EmitPairingCode(code)), actions)
        assertEquals(2, store.value)
    }

    @Test
    fun `nonce_A in a later round is a protocol error`() {
        val (f, _, _) = mismatched()
        assertEquals(
            listOf<DynamicPairingAction>(DynamicPairingAction.ProtocolError),
            f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 6 })),
        )
    }

    @Test
    fun `at the round limit a server_kc mismatch aborts in band and stops showing the code`() {
        val store = FakeStore(19)
        val (_, _, actions) = mismatched(store)
        // "The client SHOULD retry, and MUST abort instead at the round
        // limit." StopEmittingCode is also what cancels the attempt timer.
        assertEquals(
            listOf(
                DynamicPairingAction.SendPairAbort("pairing_code_mismatch"),
                DynamicPairingAction.StopEmittingCode,
            ),
            actions,
        )
        assertEquals(20, store.value)
    }

    @Test
    fun `abandoning while waiting for the next round keeps the rounds counted`() {
        // An abandoned attempt "counts toward the round limit only when the
        // code was already being emitted" - which it was, in round 1.
        val store = FakeStore()
        val (f, _, _) = mismatched(store)
        val actions = f.onEvent(DynamicPairingEvent.NonPairingActivation)
        assertEquals(listOf<DynamicPairingAction>(DynamicPairingAction.StopEmittingCode), actions)
        assertEquals(1, store.value)
        assertTrue(!f.attemptInProgress)
    }

    @Test
    fun `a received abort while waiting for the next round ends the attempt`() {
        val (f, _, _) = mismatched()
        assertEquals(
            listOf<DynamicPairingAction>(DynamicPairingAction.StopEmittingCode),
            f.onEvent(DynamicPairingEvent.PairAbortReceived("user_cancelled")),
        )
        assertEquals(emptyList<DynamicPairingAction>(), f.onEvent(DynamicPairingEvent.ServerPairInit(null)))
    }

    @Test
    fun `the attempt timeout while waiting for the next round aborts`() {
        val (f, _, _) = mismatched()
        assertEquals(
            listOf(
                DynamicPairingAction.SendPairAbort("attempt_timeout"),
                DynamicPairingAction.StopEmittingCode,
            ),
            f.onEvent(DynamicPairingEvent.AttemptTimeout),
        )
    }

    @Test
    fun `a share or finalize while waiting for the next round is a protocol error`() {
        for (event in listOf(
            DynamicPairingEvent.ServerPairAuth(VALID_SHARE),
            DynamicPairingEvent.ServerPairConfirm(ByteArray(64)),
            DynamicPairingEvent.ServerPairFinalize,
        )) {
            val (f, _, _) = mismatched()
            assertEquals(listOf<DynamicPairingAction>(DynamicPairingAction.ProtocolError), f.onEvent(event), "$event")
        }
    }

    @Test
    fun `pairing messages in flight after our abort are discarded silently`() {
        // "A client that has aborted an attempt likewise silently discards
        // pairing messages received before the next server/activate."
        val f = abortedAtLimit()
        val inFlight = listOf(
            DynamicPairingEvent.ServerPairInit(ByteArray(32)),
            DynamicPairingEvent.ServerPairAuth(VALID_SHARE),
            DynamicPairingEvent.ServerPairConfirm(ByteArray(64)),
            DynamicPairingEvent.ServerPairFinalize,
            // The attempt timer, were it still running, has nothing to abort.
            DynamicPairingEvent.AttemptTimeout,
        )
        for (event in inFlight) {
            assertEquals(emptyList<DynamicPairingAction>(), f.onEvent(event), "$event")
        }
    }

    @Test
    fun `the next activation after an abort starts a fresh attempt`() {
        val (f, _, _) = mismatched()
        f.onEvent(DynamicPairingEvent.UserCancelled)
        val actions = f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 2))
        assertEquals(2, actions.filterIsInstance<DynamicPairingAction.SendPairInit>().single().pairingIndex)
        val emitted = f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 6 }))
        assertTrue(emitted.single() is DynamicPairingAction.EmitPairingCode)
    }

    @Test
    fun `a local cancel aborts with user_cancelled`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        assertEquals(
            listOf(
                DynamicPairingAction.SendPairAbort("user_cancelled"),
                DynamicPairingAction.StopEmittingCode,
            ),
            f.onEvent(DynamicPairingEvent.UserCancelled),
        )
        // And only once: there is no attempt left to cancel.
        assertEquals(emptyList<DynamicPairingAction>(), f.onEvent(DynamicPairingEvent.UserCancelled))
    }

    @Test
    fun `a cancel while gesture-gated aborts the unstarted attempt`() {
        // pair/abort "aborts a pairing attempt, started or not".
        val f = flow(rounds = 20)
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.UserCancelled)
        assertEquals("user_cancelled", actions.filterIsInstance<DynamicPairingAction.SendPairAbort>().single().reason)
    }

    @Test
    fun `a cancel with no attempt sends nothing`() {
        assertEquals(emptyList<DynamicPairingAction>(), flow().onEvent(DynamicPairingEvent.UserCancelled))
    }

    @Test
    fun `a received abort ends the attempt without an answer`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        assertEquals(
            listOf<DynamicPairingAction>(DynamicPairingAction.StopEmittingCode),
            f.onEvent(DynamicPairingEvent.PairAbortReceived("user_cancelled")),
        )
        assertEquals(emptyList<DynamicPairingAction>(), f.onEvent(DynamicPairingEvent.ServerPairAuth(VALID_SHARE)))
    }

    @Test
    fun `a low-order share is a protocol error, not an abort`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        val actions = f.onEvent(DynamicPairingEvent.ServerPairAuth(ByteArray(32)))
        assertTrue(actions.any { it is DynamicPairingAction.ProtocolError })
        assertTrue(actions.none { it is DynamicPairingAction.SendPairAbort })
        // Already tearing the connection down: our own share must not go out
        // either, since it would be a response to a peer that did nothing to
        // earn one.
        assertTrue(actions.none { it is DynamicPairingAction.SendPairAuth })
    }

    @Test
    fun `an out-of-sequence message is a protocol error`() {
        val actions = flow().onEvent(DynamicPairingEvent.ServerPairConfirm(ByteArray(64)))
        assertTrue(actions.any { it is DynamicPairingAction.ProtocolError })
    }

    @Test
    fun `the attempt timeout aborts`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.AttemptTimeout)
        assertEquals(
            "attempt_timeout",
            actions.filterIsInstance<DynamicPairingAction.SendPairAbort>().single().reason
        )
        assertTrue(actions.any { it is DynamicPairingAction.StopEmittingCode })
    }

    @Test
    fun `a non-pairing activation discards state and stops emitting`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        val actions = f.onEvent(DynamicPairingEvent.NonPairingActivation)
        assertTrue(actions.any { it is DynamicPairingAction.StopEmittingCode })
        assertTrue(actions.none { it is DynamicPairingAction.PersistRecord })
    }

    @Test
    fun `a connection drop persists nothing`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.ConnectionClosed)
        assertTrue(actions.none { it is DynamicPairingAction.PersistRecord })
    }

    @Test
    fun `the sid is label, h, pairing_index and round`() {
        // The sid ci/conformance/cpace_oracle.py prints, which that script
        // holds to aiosendspin's own `_pake_sid(h, 1, 1)`: handshake hash
        // 0x00..0x1f, pairing_index 1, round 1. CPaceResponderTest pins the
        // CPace outputs for this same sid.
        val h = ByteArray(32) { it.toByte() }
        assertEquals(
            "73656e647370696e2d706169722d70616b652d763100010203040506070809" +
                "0a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000100000001",
            DynamicPairingCodeFlow.sidFor(h, pairingIndex = 1, round = 1).hex(),
        )
        // aiosendspin 10.0.0 `_pake_sid(h, 1, 2)`: the server counts the round
        // up when the retry arrives, so its second round is 2, not 1 or 0.
        assertEquals(
            "73656e647370696e2d706169722d70616b652d763100010203040506070809" +
                "0a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000100000002",
            DynamicPairingCodeFlow.sidFor(h, pairingIndex = 1, round = 2).hex(),
        )
        // Both are used as given, big-endian: `_pake_sid(h, 258, 3)`.
        assertEquals(
            "0000010200000003",
            DynamicPairingCodeFlow.sidFor(h, pairingIndex = 258, round = 3).hex().takeLast(16),
        )
    }

    @Test
    fun `a wrong first round then a right second pairs under the round 2 sid`() {
        val handshakeHash = ByteArray(32) { it.toByte() }
        val store = FakeStore()
        val f = DynamicPairingCodeFlow(handshakeHash, PairingFailureCounter(store), NoiseCipherSuite.CHACHA_POLY)
        val commitB = f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
            .filterIsInstance<DynamicPairingAction.SendPairInit>().single().commitB
        val code = f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { (it + 1).toByte() }))
            .filterIsInstance<DynamicPairingAction.EmitPairingCode>().single().code

        // Round 1: the operator types the code wrong.
        val wrongCode = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        val round1 = Initiator(wrongCode, DynamicPairingCodeFlow.sidFor(handshakeHash, 1, round = 1))
        val yb1 = f.onEvent(DynamicPairingEvent.ServerPairAuth(round1.ya))
            .filterIsInstance<DynamicPairingAction.SendPairAuth>().single().yb
        assertEquals(
            listOf<DynamicPairingAction>(DynamicPairingAction.SendPairRetry),
            f.onEvent(DynamicPairingEvent.ServerPairConfirm(round1.tag(yb1))),
        )

        // Round 2: the same code, a separate CPace run under its own sid.
        assertEquals(
            listOf<DynamicPairingAction>(DynamicPairingAction.EmitPairingCode(code)),
            f.onEvent(DynamicPairingEvent.ServerPairInit(null)),
        )
        val sid2 = DynamicPairingCodeFlow.sidFor(handshakeHash, 1, round = 2)
        val round2 = Initiator(code, sid2, scalarSeed = 9)
        val yb2 = f.onEvent(DynamicPairingEvent.ServerPairAuth(round2.ya))
            .filterIsInstance<DynamicPairingAction.SendPairAuth>().single().yb
        assertTrue(!yb1.contentEquals(yb2), "each round runs CPace with fresh scalars")
        val confirmActions = f.onEvent(DynamicPairingEvent.ServerPairConfirm(round2.tag(yb2)))

        val confirm = confirmActions.filterIsInstance<DynamicPairingAction.SendPairConfirm>().single()
        val finalize = confirmActions.filterIsInstance<DynamicPairingAction.SendPairFinalize>().single()
        assertEquals(0, store.value, "a verified server_kc resets the count")
        // Both wraps are keyed by the sid of the round that verified.
        val nonceB = aeadOpen(
            NoiseCipherSuite.CHACHA_POLY.aead,
            PairingWrap.wrapKey(PairingWrap.NONCE_LABEL, sid2, round2.isk(yb2)),
            ByteArray(12), ByteArray(0), confirm.wrappedNonceB,
        )
        assertContentEquals(commitB, PairingCode.commit(nonceB))
        val psk = aeadOpen(
            NoiseCipherSuite.CHACHA_POLY.aead,
            PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid2, round2.isk(yb2)),
            ByteArray(12), ByteArray(0), finalize.wrappedPsk,
        )
        val persisted = f.onEvent(DynamicPairingEvent.ServerPairFinalize)
            .filterIsInstance<DynamicPairingAction.PersistRecord>().single()
        assertContentEquals(psk, persisted.psk)
    }

    @Test
    fun `a re-activation mid-attempt discards the old attempt and starts a fresh one`() {
        val f = flow()
        val first = f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
            .filterIsInstance<DynamicPairingAction.SendPairInit>().single()
        // Drive past ServerPairInit so a code has been emitted and a
        // CPaceResponder exists -- genuinely mid-attempt, not just pending.
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))

        val actions = f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 2))
        assertTrue(
            actions.any { it is DynamicPairingAction.StopEmittingCode },
            "the abandoned attempt must stop emitting its code: $actions",
        )
        val second = actions.filterIsInstance<DynamicPairingAction.SendPairInit>().single()
        assertEquals(2, second.pairingIndex)
        assertTrue(
            !first.commitB.contentEquals(second.commitB),
            "a reused commit_B would prove the old nonce_B survived the reset",
        )
    }

    @Test
    fun `wrong-state events are protocol errors`() {
        // Idle expects only PairingActivation; every other event here is
        // out-of-sequence.
        val cases = listOf(
            "ServerPairInit while Idle" to DynamicPairingEvent.ServerPairInit(ByteArray(32)),
            "ServerPairAuth while Idle" to DynamicPairingEvent.ServerPairAuth(ByteArray(32)),
            "ServerPairFinalize while Idle" to DynamicPairingEvent.ServerPairFinalize,
        )
        for ((name, event) in cases) {
            val actions = flow().onEvent(event)
            assertTrue(
                actions.any { it is DynamicPairingAction.ProtocolError },
                "$name: expected ProtocolError, got $actions",
            )
        }
    }

    /**
     * FIX 4: `WindowOpened` is a local UI gesture (the operator's "Allow
     * pairing" tap), never something a peer can trigger. Outside
     * `AwaitingGesture` - e.g. a stray double-tap - it must be a no-op, not a
     * protocol error: a protocol error now actually closes the connection, and
     * a local UI tap must never be able to drop a healthy one.
     */
    @Test
    fun `WindowOpened outside AwaitingGesture is a no-op`() {
        val actions = flow().onEvent(DynamicPairingEvent.WindowOpened)
        assertEquals(emptyList<DynamicPairingAction>(), actions)
    }

    /**
     * Only the tap that lifts a hold-back resets the count: the client holds
     * attempts back "until a deliberate, manufacturer-defined operator action,
     * which also resets the count". A stray tap anywhere else - a double tap,
     * or a command that lands after the attempt has moved on - must leave the
     * round budget alone.
     */
    @Test
    fun `an Allow pairing tap outside the hold-back leaves the round count alone`() {
        val store = FakeStore(5)
        val f = DynamicPairingCodeFlow(ByteArray(32), PairingFailureCounter(store), NoiseCipherSuite.CHACHA_POLY)
        fun strayTap(where: String, count: Int) {
            assertEquals(emptyList<DynamicPairingAction>(), f.onEvent(DynamicPairingEvent.WindowOpened), where)
            assertEquals(count, store.value, where)
        }

        strayTap("idle", 5)
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        strayTap("awaiting server/pair-init", 5)
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        strayTap("awaiting server/pair-auth", 6)
        f.onEvent(DynamicPairingEvent.ServerPairAuth(VALID_SHARE))
        assertEquals(
            listOf<DynamicPairingAction>(DynamicPairingAction.SendPairRetry),
            f.onEvent(DynamicPairingEvent.ServerPairConfirm(ByteArray(64))),
        )
        strayTap("awaiting the next round", 6)

        // Nor once the round limit has ended the attempt: the next one must
        // still be held back.
        val limited = FakeStore(19)
        val (aborted, _, _) = mismatched(limited)
        assertEquals(emptyList<DynamicPairingAction>(), aborted.onEvent(DynamicPairingEvent.WindowOpened))
        assertEquals(20, limited.value)
    }

    /**
     * Drives the flow end to end with a genuinely valid `server_kc`, computed
     * by playing the CPace initiator (server) side directly against
     * [CPaceX25519]'s primitives -- the same primitives [CPaceResponder]
     * itself is built from.
     *
     * The pairing code is not known ahead of time (it depends on the flow's
     * internally-generated `nonce_B`), so the fake server side is built AFTER
     * capturing the code from `EmitPairingCode`, using the flow's own
     * `sidFor` and the client's public share as emitted on `SendPairAuth`.
     */
    @Test
    fun `the happy path persists the PSK it actually sent, and only on server pair-finalize`() {
        val handshakeHash = ByteArray(32) { it.toByte() }
        val f = DynamicPairingCodeFlow(
            handshakeHash = handshakeHash,
            counter = PairingFailureCounter(FakeStore()),
            suite = NoiseCipherSuite.CHACHA_POLY,
        )
        val allActions = mutableListOf<DynamicPairingAction>()

        allActions += f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))

        val nonceA = ByteArray(32) { (it + 1).toByte() }
        val initActions = f.onEvent(DynamicPairingEvent.ServerPairInit(nonceA))
        allActions += initActions
        val code = initActions.filterIsInstance<DynamicPairingAction.EmitPairingCode>().single().code

        // Play the server (CPace initiator) side for real, using the code the
        // flow actually derived.
        val sid = DynamicPairingCodeFlow.sidFor(handshakeHash, pairingIndex = 1, round = 1)
        val emptyCi = ByteArray(0)
        val adServer = "server".encodeToByteArray()
        val adClient = "client".encodeToByteArray()
        val yaScalar = ByteArray(32) { (it + 7).toByte() }
        val generator = CPaceX25519.calculateGenerator(code.encodeToByteArray(), emptyCi, sid)
        val ya = x25519ScalarMult(yaScalar, generator)

        val authActions = f.onEvent(DynamicPairingEvent.ServerPairAuth(ya))
        allActions += authActions
        val yb = authActions.filterIsInstance<DynamicPairingAction.SendPairAuth>().single().yb

        val k = CPaceX25519.scalarMultVfy(yaScalar, yb) ?: error("test setup produced a low-order Yb")
        val isk = CPaceX25519.deriveIsk(sid, k, ya, adServer, yb, adClient)
        val macKey = CPaceX25519.macKey(sid, isk)
        val ta = CPaceX25519.mcfTag(macKey, ya, adServer)

        val confirmActions = f.onEvent(DynamicPairingEvent.ServerPairConfirm(ta))
        allActions += confirmActions
        val wrappedPsk = confirmActions.filterIsInstance<DynamicPairingAction.SendPairFinalize>()
            .single().wrappedPsk

        val finalizeActions = f.onEvent(DynamicPairingEvent.ServerPairFinalize)
        allActions += finalizeActions

        assertTrue(
            allActions.none { it is DynamicPairingAction.ProtocolError || it is DynamicPairingAction.SendPairAbort },
            "the happy path must not hit either failure branch: $allActions",
        )

        val persisted = finalizeActions.filterIsInstance<DynamicPairingAction.PersistRecord>().single()
        assertEquals(32, persisted.psk.size)
        assertTrue(finalizeActions.any { it is DynamicPairingAction.StopEmittingCode })

        // The PSK persisted must be the exact bytes sealed into the
        // client/pair-finalize we already sent -- not a fresh value.
        val wrapKey = PairingWrap.wrapKey(PairingWrap.PSK_LABEL, sid, isk)
        val unwrappedPsk = aeadOpen(NoiseCipherSuite.CHACHA_POLY.aead, wrapKey, ByteArray(12), ByteArray(0), wrappedPsk)
        assertContentEquals(unwrappedPsk, persisted.psk)
    }

    /**
     * The server's (CPace initiator's) side of one round, played for real
     * against [CPaceX25519], for the operator's [code] under [sid].
     */
    private class Initiator(code: String, private val sid: ByteArray, scalarSeed: Int = 7) {
        private val scalar = ByteArray(32) { (it + scalarSeed).toByte() }
        val ya: ByteArray = x25519ScalarMult(
            scalar,
            CPaceX25519.calculateGenerator(code.encodeToByteArray(), ByteArray(0), sid),
        )

        fun isk(yb: ByteArray): ByteArray {
            val k = CPaceX25519.scalarMultVfy(scalar, yb) ?: error("test setup produced a low-order Yb")
            return CPaceX25519.deriveIsk(sid, k, ya, AD_SERVER, yb, AD_CLIENT)
        }

        /** `server_kc`, the MCF tag `Ta`. */
        fun tag(yb: ByteArray): ByteArray = CPaceX25519.mcfTag(CPaceX25519.macKey(sid, isk(yb)), ya, AD_SERVER)

        private companion object {
            val AD_SERVER = "server".encodeToByteArray()
            val AD_CLIENT = "client".encodeToByteArray()
        }
    }

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        /** A valid, non-low-order share; reuse the B.1.10 u6 vector. */
        val VALID_SHARE = ByteArray(32) { 0xff.toByte() }.also { it[0] = 0xda.toByte() }
    }
}
