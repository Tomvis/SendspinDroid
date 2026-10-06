package com.sendspindroid.e2e

import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A text frame that arrives off the socket once the cleartext handshake is
 * over was never decrypted, so it must close the connection instead of being
 * dispatched. connection.md, Failure Handling: "a cleartext message received
 * after switching to transport mode - is a silent failure: the detecting side
 * closes the connection".
 */
class CleartextAfterHandshakeTest : E2ETestBase() {

    @Test
    fun `raw text frame outside the handshake closes the transport and is not dispatched`() {
        connectAndHandshake()
        // No cleartext handshake is in progress any more.
        setField(client, "handshakeDriver", null)

        fakeTransport.simulateRawTextFrame(
            """{"type":"group/update","payload":{"playback_state":"playing",""" +
                """"group_id":"injected","group_name":"injected"}}"""
        )

        assertTrue("transport should be closed", fakeTransport.closed)
        assertEquals(1002, fakeTransport.closeCode)
        verify(exactly = 0) { mockCallback.onGroupUpdate("injected", any(), any()) }
    }
}
