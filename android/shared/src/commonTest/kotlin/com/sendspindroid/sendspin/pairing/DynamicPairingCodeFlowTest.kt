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
 *  - a server_kc mismatch is an in-band pair/abort, but a bad commitment,
 *    a low-order share or a malformed field is a PROTOCOL ERROR that closes
 *    the socket silently -- telling an unauthenticated peer which check it
 *    failed is the leak this distinction prevents.
 *  - an attempt is gesture-gated only while the method is escalated.
 */
class DynamicPairingCodeFlowTest {

    private class FakeStore(var value: Int = 0) : PairingCounterStore {
        override fun load(): Int = value
        override fun save(v: Int) { value = v }
    }

    private fun flow(failures: Int = 0) = DynamicPairingCodeFlow(
        handshakeHash = ByteArray(32),
        counter = PairingFailureCounter(FakeStore(failures)),
        suite = NoiseCipherSuite.CHACHA_POLY,
    )

    @Test
    fun `an unescalated activation sends pair-init immediately`() {
        val actions = flow().onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        assertTrue(actions.any { it is DynamicPairingAction.SendPairInit })
        assertTrue(actions.none { it is DynamicPairingAction.SendPairPending })
        assertTrue(actions.none { it is DynamicPairingAction.RequestGesture })
    }

    @Test
    fun `an escalated activation sends pair-pending and waits for the gesture`() {
        val f = flow(failures = 5)
        val actions = f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        assertTrue(actions.any { it is DynamicPairingAction.SendPairPending })
        assertTrue(actions.none { it is DynamicPairingAction.SendPairInit })
        // Task 14's UI renders the "Allow pairing" button from this signal --
        // pin it here so a regression is caught in this task, not silently in
        // that one.
        assertTrue(actions.any { it is DynamicPairingAction.RequestGesture })

        val opened = f.onEvent(DynamicPairingEvent.WindowOpened)
        assertTrue(opened.any { it is DynamicPairingAction.SendPairInit })
    }

    @Test
    fun `a terminal event while gesture-gated still stops emitting`() {
        // Task 14 fix round 1: the service clears its "Allow pairing" gesture
        // flag on StopEmittingCode, on the assumption that this action fires
        // on every path out of AWAITING_GESTURE -- including one that never
        // reached a code at all. Pin that assumption here, at the flow level,
        // rather than leaving it implicit in the service wiring.
        val f = flow(failures = 5)
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.ConnectionClosed)
        assertTrue(actions.any { it is DynamicPairingAction.StopEmittingCode })
    }

    @Test
    fun `server pair-init emits the code and increments the counter`() {
        val store = FakeStore()
        val f = DynamicPairingCodeFlow(ByteArray(32), PairingFailureCounter(store), NoiseCipherSuite.CHACHA_POLY)
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        val actions = f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        val emit = actions.filterIsInstance<DynamicPairingAction.EmitPairingCode>().single()
        assertEquals(6, emit.code.length)
        assertEquals(1, store.value)
    }

    @Test
    fun `a server_kc mismatch aborts in band`() {
        val f = flow()
        f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        f.onEvent(DynamicPairingEvent.ServerPairInit(ByteArray(32) { 5 }))
        f.onEvent(DynamicPairingEvent.ServerPairAuth(VALID_SHARE))
        val actions = f.onEvent(DynamicPairingEvent.ServerPairConfirm(ByteArray(64)))
        val abort = actions.filterIsInstance<DynamicPairingAction.SendPairAbort>().single()
        assertEquals("pairing_code_mismatch", abort.reason)
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
    fun `the first attempt's sid uses pairing_index 1, unshifted`() {
        // aiosendspin's `_pairing_index` counter (client/connection.py,
        // server/connection.py) starts at 0 and is incremented BEFORE use, so
        // by the time `_pake_sid` (noise/pairing.py) runs for the first
        // attempt on a connection, pairing_index is already 1. The flow must
        // NOT re-base the value it is handed on `PairingActivation`.
        val h = ByteArray(32) { it.toByte() }
        val expected = "sendspin-pair-pake-v1".encodeToByteArray() + h + byteArrayOf(0, 0, 0, 1)
        assertContentEquals(expected, DynamicPairingCodeFlow.sidFor(h, pairingIndex = 1))
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
        val sid = DynamicPairingCodeFlow.sidFor(handshakeHash, pairingIndex = 1)
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

    private companion object {
        /** A valid, non-low-order share; reuse the B.1.10 u6 vector. */
        val VALID_SHARE = ByteArray(32) { 0xff.toByte() }.also { it[0] = 0xda.toByte() }
    }
}
