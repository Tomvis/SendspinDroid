package com.sendspindroid.sendspin

import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.e2e.E2ETestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that SendSpin.connectionState transitions follow the expected
 * lifecycle: Idle -> Connecting -> Ready -> Idle, or Connecting -> Failed.
 */
class SendSpinConnectionStateTest : E2ETestBase() {

    @Test
    fun `initial state is Idle`() {
        assertEquals(TransportState.Idle, client.connectionState.value)
    }

    @Test
    fun `connectLocal transitions to Connecting`() {
        // The transport itself goes nowhere in a unit test; the state is set
        // before it is created.
        runCatching { client.connectLocal("127.0.0.1:8080") }

        val state = client.connectionState.value
        assertTrue(
            "State should be Connecting or Failed after connectLocal, was: $state",
            state is TransportState.Connecting || state is TransportState.Failed
        )
    }

    @Test
    fun `server hello transitions to Ready`() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()

        fakeServer.sendServerHello()

        assertEquals(TransportState.Ready, client.connectionState.value)
        assertEquals(fakeServer.serverName, client.getServerName())
    }

    @Test
    fun `non-recoverable transport failure transitions to Failed`() {
        injectTransportAndConnect()
        fakeTransport.simulateConnected()

        fakeTransport.simulateFailure(java.net.ConnectException("Connection refused"), isRecoverable = false)

        assertTrue(client.connectionState.value is TransportState.Failed)
    }

    @Test
    fun `disconnect transitions from Ready back to Idle`() {
        connectAndHandshake()
        assertEquals(TransportState.Ready, client.connectionState.value)

        client.disconnect()

        assertEquals(TransportState.Idle, client.connectionState.value)
    }

    @Test
    fun `full lifecycle Idle to Connecting to Ready to Idle, then a failed attempt`() {
        assertEquals(TransportState.Idle, client.connectionState.value)

        injectTransportAndConnect()
        assertEquals(TransportState.Connecting, client.connectionState.value)

        fakeServer.completeHandshake()
        assertEquals(TransportState.Ready, client.connectionState.value)

        fakeTransport.simulateClosed(1006, "abnormal")
        assertEquals(TransportState.Idle, client.connectionState.value)

        injectTransportAndConnect()
        fakeTransport.simulateConnected()
        fakeTransport.simulateFailure(java.net.ConnectException("Connection refused"), isRecoverable = false)
        assertTrue(client.connectionState.value is TransportState.Failed)
    }
}
