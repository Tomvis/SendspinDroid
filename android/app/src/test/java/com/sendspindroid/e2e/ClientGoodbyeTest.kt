package com.sendspindroid.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `client/goodbye` on every deliberate disconnect: the right reason
 * (messaging.md, client/goodbye), and on the transport before the close
 * rather than racing it.
 *
 * [FakeTransport] refuses frames once closed and these assertions do not wait,
 * so a goodbye that is only queued when the disconnect returns fails them.
 */
class ClientGoodbyeTest : E2ETestBase() {

    private fun goodbyes() = fakeTransport.sentTextMessages.filter { it.contains("client/goodbye") }

    private fun assertGoodbye(reason: String) {
        assertEquals(
            listOf("""{"type":"client/goodbye","payload":{"reason":"$reason"}}"""),
            goodbyes(),
        )
        assertTrue("transport should be closed", fakeTransport.closed)
        assertTrue("the close must let the goodbye out first", fakeTransport.flushedBeforeClose)
        assertEquals(1000, fakeTransport.closeCode)
    }

    @Test
    fun `a user disconnect says user_request before closing`() {
        connectAndHandshake()

        client.disconnect()

        assertGoodbye("user_request")
    }

    @Test
    fun `leaving one server for another says another_server`() {
        connectAndHandshake(serverAddress = "192.168.1.100:8927")

        // The new connection itself goes nowhere in a unit test.
        runCatching { client.connectLocal("127.0.0.1:1") }

        assertGoodbye("another_server")
    }

    @Test
    fun `reconnecting to the same server says restart`() {
        connectAndHandshake(serverAddress = "127.0.0.1:1")

        runCatching { client.connectLocal("127.0.0.1:1") }

        assertGoodbye("restart")
    }

    @Test
    fun `a reselection says restart`() {
        connectAndHandshake()

        client.disconnectForReselection()

        assertGoodbye("restart")
    }

    @Test
    fun `shutting down says shutdown`() {
        connectAndHandshake()

        // Cancels the client's coroutine scopes as soon as it returns.
        client.destroy()

        assertGoodbye("shutdown")
    }

    @Test
    fun `no goodbye before server hello`() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()

        client.disconnect()

        assertEquals(emptyList<String>(), goodbyes())
        assertTrue("transport should be closed", fakeTransport.closed)
    }

    @Test
    fun `a new connection does not send under the previous session's keys`() {
        connectAndHandshake()
        // The socket dropped and came back: a new handshake starts, and no
        // fake server completes it.
        fakeTransport.simulateClosed(1006, "abnormal")
        injectTransportAndConnect(installChannel = false)
        fakeTransport.simulateConnected()
        fakeTransport.clearRecordedMessages()

        client.setVolume(0.5)

        assertFalse(
            "client/state must wait for the new channel",
            fakeTransport.hasSentMessageContaining("client/state"),
        )
    }
}
