package com.sendspindroid.e2e

import com.sendspindroid.UserSettings
import com.sendspindroid.sendspin.protocol.PlaintextCrypto
import com.sendspindroid.sendspin.protocol.SendSpinProtocol
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each connection starts from, whichever side opened it. The client
 * lives for the whole service, so it can carry state from one connection to
 * the next: the persisted `output_delay_ms` is read again ("Clients MUST
 * persist `output_delay_ms` locally across reboots and server
 * reconnections"), and a sync offset one server pushed is not applied on
 * the next.
 */
class ConnectionStartStateTest : E2ETestBase() {

    /** The server connects, as far as the transport being up. */
    private fun acceptConnection(transport: FakeTransport = fakeTransport) {
        transport.afterConnected = { client.installEncryptedChannel(PlaintextCrypto) }
        client.accept(transport, "192.168.1.50:51234")
        transport.simulateConnected()
    }

    private fun pushSyncOffset(offsetMs: Int) = fakeTransport.simulateTextMessage(
        """{"type":"client/sync_offset","payload":{"player_id":"p","offset_ms":$offsetMs,"source":"test"}}"""
    )

    @Test
    fun `a dialled connection starts from the persisted output delay`() {
        assertEquals(0.0, client.getTimeFilter().outputDelayMs, 0.0)
        // Another connection's server set it since the client was made.
        every { UserSettings.getOutputDelayMs() } returns 250

        // The new connection itself goes nowhere in a unit test.
        runCatching { client.connectLocal("127.0.0.1:1") }

        assertEquals(250.0, client.getTimeFilter().outputDelayMs, 0.0)
    }

    @Test
    fun `an accepted connection starts from the persisted output delay and reports it`() {
        assertEquals(0.0, client.getTimeFilter().outputDelayMs, 0.0)
        every { UserSettings.getOutputDelayMs() } returns 250

        acceptConnection()
        fakeServer.sendServerHello()
        fakeServer.sendServerActivate(listOf("playback"), activeRoles = listOf("player@v1"))

        assertEquals(250.0, client.getTimeFilter().outputDelayMs, 0.0)
        assertTrue(fakeTransport.hasSentMessageContaining("\"output_delay_ms\":250,"))
    }

    @Test
    fun `a server's sync offset is kept when the network changes under the connection`() {
        connectAndHandshake()
        pushSyncOffset(-40)

        client.onNetworkChanged()

        assertEquals(-40.0, client.getTimeFilter().staticDelayMs, 0.0)
    }

    @Test
    fun `a server's sync offset is not applied on the next dialled connection`() {
        client.setUserSyncOffsetMs(25.0)
        connectAndHandshake()
        pushSyncOffset(-40)
        assertEquals(-40.0, client.getTimeFilter().staticDelayMs, 0.0)

        runCatching { client.connectLocal("127.0.0.1:1") }

        assertEquals(25.0, client.getTimeFilter().staticDelayMs, 0.0)
    }

    @Test
    fun `a server's sync offset is not applied on the next accepted connection`() {
        client.setUserSyncOffsetMs(25.0)
        connectAndHandshake()
        pushSyncOffset(-40)
        client.disconnect()
        assertEquals(-40.0, client.getTimeFilter().staticDelayMs, 0.0)

        acceptConnection(FakeTransport())

        assertEquals(25.0, client.getTimeFilter().staticDelayMs, 0.0)
    }

    @Test
    fun `restoring the saved offset leaves a server's in force, and the user's own change does not`() {
        connectAndHandshake()
        pushSyncOffset(-40)

        client.setUserSyncOffsetMs(0.0, overrideServer = false)
        assertEquals(-40.0, client.getTimeFilter().staticDelayMs, 0.0)

        client.setUserSyncOffsetMs(0.0)
        assertEquals(0.0, client.getTimeFilter().staticDelayMs, 0.0)
    }

    @Test
    fun `the user's offset change is reported in the timing parameters at once`() {
        connectAndHandshake()
        // The activation's client/state is built when it is sent: let it go
        // first, so it cannot be the one that carries the new offset.
        assertTrue(fakeServer.clientSentState())
        fakeTransport.clearRecordedMessages()

        client.setUserSyncOffsetMs(-120.0)

        assertTrue(fakeTransport.hasSentMessageContaining(
            "\"required_lead_time_ms\":${SendSpinProtocol.PlayerTiming.REQUIRED_LEAD_TIME_MS + 120},"
        ))
    }
}
