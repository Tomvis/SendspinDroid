package com.sendspindroid.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sent by the client once it has received server/hello. ... The client MUST
 * NOT send other Sendspin messages until it receives that activation."
 *
 * Volume, mute and sync state can all change in the window between the two,
 * and each used to send a client/state as soon as server/hello had arrived.
 */
class ActivationGateTest : E2ETestBase() {

    private fun sentAfterHello(): List<String> {
        // Sends are encrypted on the client's timer thread; give a wrongly
        // sent message time to land before concluding there is none.
        Thread.sleep(150)
        return fakeTransport.sentTextMessages.filterNot { "client/init" in it || "client/hello" in it }
    }

    private fun helloOnly() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()
        fakeServer.sendServerHello()
        assertTrue(fakeTransport.hasSentMessageContaining("client/hello"))
    }

    @Test
    fun `no client state before the initial activation`() {
        helloOnly()

        client.setVolume(0.5)
        client.setMuted(true)
        client.setExternalSource(true)
        client.sendClientStateSnapshot()

        assertEquals(emptyList<String>(), sentAfterHello())
    }

    @Test
    fun `no client time before the initial activation`() {
        helloOnly()

        // What a time-sync burst left over from the previous connection does.
        val send = com.sendspindroid.sendspin.protocol.SendSpinProtocolHandler::class.java
            .getDeclaredMethod("sendClientTime")
        send.isAccessible = true
        send.invoke(client)

        assertEquals(emptyList<String>(), sentAfterHello())
    }

    @Test
    fun `the activation sends the state that changed while waiting for it`() {
        helloOnly()
        client.setVolume(0.5)
        client.setMuted(true)

        fakeServer.sendServerActivate(activities = listOf("playback"), activeRoles = listOf("player@v1"))

        assertTrue(fakeTransport.hasSentMessageContaining("client/state"))
        val state = fakeTransport.sentTextMessages.first { "client/state" in it }
        assertTrue(state, "\"volume\":50" in state && "\"muted\":true" in state)
    }

    @Test
    fun `no goodbye before the initial activation`() {
        helloOnly()

        client.disconnect()

        assertEquals(emptyList<String>(), sentAfterHello())
        assertTrue("transport should be closed", fakeTransport.closed)
    }
}
