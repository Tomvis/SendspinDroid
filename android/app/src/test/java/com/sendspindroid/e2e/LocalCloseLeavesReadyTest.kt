package com.sendspindroid.e2e

import com.sendspindroid.coordinator.TransportState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * A close the client starts itself must end the connection as far as the
 * client is concerned. The transport used to report nothing for a local
 * close, so the stall watchdog, a protocol failure and a goodbye-then-close
 * all dropped the socket and left the state at Ready with nothing to notice.
 */
class LocalCloseLeavesReadyTest : E2ETestBase() {

    private fun connectedAndReady() {
        connectAndHandshake()
        // As in production: the coordinator owns reconnect, so a drop is Idle.
        client.selfReconnectEnabled = false
        assertEquals(TransportState.Ready, client.connectionState.value)
    }

    private fun invoke(name: String, vararg args: Any) {
        val m = client::class.java.getDeclaredMethod(name, *args.map { it::class.java }.toTypedArray())
        m.isAccessible = true
        m.invoke(client, *args)
    }

    @Test
    fun `the stall watchdog leaves Ready`() {
        connectedAndReady()
        getField<AtomicLong>(client, "lastByteReceivedAtMs").set(System.currentTimeMillis() - 60_000L)

        invoke("checkStall")

        assertEquals(1001, fakeTransport.closeCode)
        assertEquals(TransportState.Idle, client.connectionState.value)
    }

    @Test
    fun `a protocol failure leaves Ready`() {
        connectedAndReady()

        invoke("onProtocolFailure", "test-induced protocol failure")

        assertEquals(1002, fakeTransport.closeCode)
        assertEquals(TransportState.Idle, client.connectionState.value)
    }

    @Test
    fun `a goodbye-then-close leaves Ready`() {
        connectedAndReady()

        invoke("closeConnectionAfterFlush")

        assertEquals(1000, fakeTransport.closeCode)
        assertEquals(TransportState.Idle, client.connectionState.value)
    }
}
