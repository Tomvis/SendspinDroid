package com.sendspindroid.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX 3 regression guard: a protocol error must actually close the
 * transport, sending nothing at the application level.
 *
 * `onProtocolFailure`'s own KDoc says a protocol-level failure "requires
 * closing the socket", and the design doc makes "close the WebSocket, persist
 * nothing" a security property of pairing - but the base implementation only
 * logged. [com.sendspindroid.sendspin.SendSpin] must override it to close the
 * transport at the WebSocket level (no `pair/abort`, no `client/goodbye`).
 */
class ProtocolFailureClosesTransportTest : E2ETestBase() {

    @Test
    fun `onProtocolFailure closes the transport and sends nothing`() {
        connectAndHandshake()
        val sentBefore = fakeTransport.sentTextMessages.size

        val m = client::class.java.getDeclaredMethod("onProtocolFailure", String::class.java)
        m.isAccessible = true
        m.invoke(client, "test-induced protocol failure")

        assertTrue("transport should be closed", fakeTransport.closed)
        assertEquals(1002, fakeTransport.closeCode)

        // Count alone is fragile: connectAndHandshake() starts the background
        // client/time sync burst on its own real-time dispatcher thread, so
        // an unrelated client/time message can land in the window between
        // capturing sentBefore and this assertion. What the test actually
        // guards is that a protocol failure discloses nothing to the peer -
        // no message announcing the failure - so assert on content instead.
        val sentAfter = fakeTransport.sentTextMessages.drop(sentBefore)
        assertTrue(
            "no pair/abort should be sent",
            sentAfter.none { it.contains("pair/abort") },
        )
        assertTrue(
            "no client/goodbye should be sent",
            sentAfter.none { it.contains("client/goodbye") },
        )
    }
}
