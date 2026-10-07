package com.sendspindroid.e2e

import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failures of the cleartext init exchange (connection.md, Failure Handling).
 */
class HandshakePhaseFailureTest : E2ETestBase() {

    private fun failure(): FailureReason? =
        (client.connectionState.value as? TransportState.Failed)?.reason

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    @Test
    fun `server error is a handshake failure, not a server that lacks encryption`() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()

        fakeTransport.simulateRawTextFrame(
            """{"type":"server/error","payload":{"reason":"unsupported_suite"}}"""
        )

        assertEquals(FailureReason.HandshakeFailed, failure())
        assertTrue("transport should be closed", fakeTransport.closed)
        assertEquals(1002, fakeTransport.closeCode)
    }

    @Test
    fun `a pre-encryption server is still reported as lacking encryption`() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()

        fakeTransport.simulateRawTextFrame("""{"type":"server/hello","payload":{"name":"Old"}}""")

        assertEquals(FailureReason.ServerLacksEncryption, failure())
    }

    @Test
    fun `a handshake that stalls is closed when the timeout expires`() {
        injectTransportAndConnect()
        client.handshakeTimeoutMs = 50

        // client/init goes out and the server never answers.
        fakeTransport.simulateConnected()

        waitFor("the handshake timeout") { fakeTransport.closed }
        assertEquals(1002, fakeTransport.closeCode)
        assertEquals(FailureReason.HandshakeFailed, failure())
        assertEquals(
            "a silent failure: nothing but client/init was sent",
            1,
            fakeTransport.sentTextMessages.size,
        )
    }

    @Test
    fun `the timeout leaves a connection the user already left alone`() {
        injectTransportAndConnect()
        client.handshakeTimeoutMs = 50
        fakeTransport.simulateConnected()

        client.disconnect()
        Thread.sleep(300)

        assertFalse(
            "a stale timer must not report a failure, was: ${client.connectionState.value}",
            client.connectionState.value is TransportState.Failed,
        )
    }
}
