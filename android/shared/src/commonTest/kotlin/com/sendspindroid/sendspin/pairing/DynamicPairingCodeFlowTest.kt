package com.sendspindroid.sendspin.pairing

import com.sendspindroid.sendspin.crypto.NoiseCipherSuite
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
    }

    @Test
    fun `an escalated activation sends pair-pending and waits for the gesture`() {
        val f = flow(failures = 5)
        val actions = f.onEvent(DynamicPairingEvent.PairingActivation(pairingIndex = 1))
        assertTrue(actions.any { it is DynamicPairingAction.SendPairPending })
        assertTrue(actions.none { it is DynamicPairingAction.SendPairInit })

        val opened = f.onEvent(DynamicPairingEvent.WindowOpened)
        assertTrue(opened.any { it is DynamicPairingAction.SendPairInit })
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

    private companion object {
        /** A valid, non-low-order share; reuse the B.1.10 u6 vector. */
        val VALID_SHARE = ByteArray(32) { 0xff.toByte() }.also { it[0] = 0xda.toByte() }
    }
}
