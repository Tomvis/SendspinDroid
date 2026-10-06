package com.sendspindroid.e2e

import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failures of the cleartext init exchange (connection.md, Failure Handling).
 */
class HandshakePhaseFailureTest : E2ETestBase() {

    private fun failure(): FailureReason? =
        (client.connectionState.value as? TransportState.Failed)?.reason

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
}
