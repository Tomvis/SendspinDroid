package com.sendspindroid.e2e

import com.sendspindroid.UserSettings
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.protocol.SendSpinProtocolHandler
import com.sendspindroid.sendspin.protocol.timesync.TimeSyncManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

/**
 * Every way a connection can end goes through one teardown, and two things
 * must hold for each of them:
 *
 * - the owner is told whether to reconnect: yes for anything nobody asked
 *   for, no when this client chose to leave;
 * - nothing of the connection is left behind, so the next one starts from
 *   the state a new client starts in.
 */
class ConnectionEndTest : E2ETestBase() {

    /**
     * Every per-connection field, by name. Read off the object rather than
     * through accessors so that a field added later and not reset shows up as
     * a difference from a client that has never connected.
     */
    private fun connectionState(): Map<String, String> {
        val handler = SendSpinProtocolHandler::class.java
        val handlerFields = listOf(
            "handshakeComplete", "wireCodec", "rehandshakeInProgress",
            "activationSeen", "activeRoles", "activities",
            "advertisedFormats", "preferredFormat",
            "_streamActive", "_currentStreamConfig", "outputStarted",
            "artworkStreamActive", "artworkChannelConfig", "pendingArtwork",
            "pendingMetadata", "currentControllerState",
            "lastPlaybackState", "lastGroupInfo",
            "activePairingMethod", "dynamicPairingFlow", "pairingIndex",
            "pairingAborted", "unpairHandled",
            "attemptTimeoutJob", "dynamicAttemptTimeoutJob",
        ).associateWith { getField<Any?>(client, it, handler).toString() }
        val clientFields = listOf(
            "transport", "handshakeDriver", "handshakeTimeoutJob",
            "matchedPsk", "sessionFacts", "stallWatchdogJob", "connectedAtMs",
        ).associateWith { getField<Any?>(client, it).toString() }
        val timeSync: TimeSyncManager = getField(client, "timeSyncManager", handler)
        return handlerFields + clientFields + mapOf(
            "timeSyncRunning" to timeSync.isRunning.toString(),
            "controllerState" to client.controllerState.value.toString(),
        )
    }

    private lateinit var pristine: Map<String, String>

    /** A paired connection with a stream, metadata, controller and group state on it. */
    private fun connectedAndBusy() {
        pristine = connectionState()
        every { UserSettings.getOrCreateTrustStore() } returns mockk(relaxed = true)

        connectAndHandshake()
        setField(client, "matchedPsk", Psk(ByteArray(32) { 7 }, PskCategory.LONG_TERM, "server-id"))
        fakeServer.sendStreamStart()
        fakeServer.sendServerState()
        fakeServer.sendGroupUpdate()

        assertEquals(TransportState.Ready, client.connectionState.value)
        assertTrue("time sync runs once activated", connectionState()["timeSyncRunning"] == "true")
        assertTrue("the connection left state behind to clear", connectionState() != pristine)
    }

    private fun assertEnded(reconnect: Boolean) {
        assertEquals(TransportState.Idle, client.connectionState.value)
        verify(exactly = 1) { mockCallback.onDisconnected(reconnect) }
        verify(exactly = 0) { mockCallback.onDisconnected(!reconnect) }
        assertEquals(pristine, connectionState())
    }

    private fun invoke(name: String, vararg args: Any) {
        val m = SendSpin::class.java.getDeclaredMethod(name, *args.map { it::class.java }.toTypedArray())
        m.isAccessible = true
        m.invoke(client, *args)
    }

    /** The goodbye-then-close paths close from the client's timer thread. */
    private fun awaitClosed() {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!fakeTransport.closed && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("transport should have been closed", fakeTransport.closed)
    }

    // ========== Not asked for: reconnect ==========

    @Test
    fun `a dropped socket is reconnected`() {
        connectedAndBusy()

        fakeTransport.simulateClosed(1006, "abnormal")

        assertEnded(reconnect = true)
    }

    @Test
    fun `a server closing normally is reconnected`() {
        // A restarting server may well close with 1000. It did not say goodbye
        // and neither did we, so it is a drop like any other.
        connectedAndBusy()

        fakeTransport.simulateClosed(1000, "")

        assertEnded(reconnect = true)
    }

    @Test
    fun `a recoverable transport failure is reconnected`() {
        connectedAndBusy()

        fakeTransport.simulateFailure(SocketException("connection reset"), isRecoverable = true)

        assertEnded(reconnect = true)
    }

    @Test
    fun `an unrecoverable transport failure is reconnected too`() {
        // "Unrecoverable" is the transport's guess about retrying a connect;
        // a connection that was up and broke has ended like any other.
        connectedAndBusy()

        fakeTransport.simulateFailure(IllegalStateException("boom"), isRecoverable = false)

        assertEnded(reconnect = true)
    }

    @Test
    fun `a stall is reconnected`() {
        connectedAndBusy()
        getField<AtomicLong>(client, "lastByteReceivedAtMs").set(System.currentTimeMillis() - 60_000L)

        invoke("checkStall")

        assertEquals(1001, fakeTransport.closeCode)
        assertEnded(reconnect = true)
    }

    @Test
    fun `a protocol failure is reconnected`() {
        connectedAndBusy()

        invoke("onProtocolFailure", "test-induced protocol failure")

        assertEquals(1002, fakeTransport.closeCode)
        assertEnded(reconnect = true)
    }

    @Test
    fun `a network change is reconnected`() {
        connectedAndBusy()

        client.disconnectForReselection()

        assertTrue(fakeTransport.hasSentMessageContaining("\"reason\":\"restart\""))
        assertEnded(reconnect = true)
    }

    // ========== This client chose to leave: no reconnect ==========

    @Test
    fun `a user disconnect is not reconnected`() {
        connectedAndBusy()

        client.disconnect()

        assertTrue(fakeTransport.hasSentMessageContaining("\"reason\":\"user_request\""))
        assertEnded(reconnect = false)
    }

    @Test
    fun `a switch to another server is not reconnected`() {
        connectedAndBusy()

        // The new connection itself goes nowhere in a unit test; what is
        // checked is how the old one ended.
        val leftBehind = fakeTransport
        runCatching { client.connectLocal("127.0.0.1:1") }

        assertTrue(leftBehind.hasSentMessageContaining("\"reason\":\"another_server\""))
        verify(exactly = 1) { mockCallback.onDisconnected(false) }
        verify(exactly = 0) { mockCallback.onDisconnected(true) }
        assertTrue(fakeTransport.getListener() == null)
    }

    @Test
    fun `server unpair is not reconnected`() {
        connectedAndBusy()

        fakeTransport.simulateTextMessage("""{"type":"server/unpair"}""")
        awaitClosed()

        assertTrue(fakeTransport.hasSentMessageContaining("\"reason\":\"unpaired\""))
        verify { mockCallback.onUnpaired("server-id") }
        assertEnded(reconnect = false)
    }

    @Test
    fun `a rejected activation is not reconnected`() {
        connectedAndBusy()

        // A paired session may not be told to pair.
        fakeServer.sendServerActivate(activities = listOf("pairing"))
        awaitClosed()

        assertTrue(fakeTransport.hasSentMessageContaining("client/goodbye"))
        assertEnded(reconnect = false)
    }

    @Test
    fun `shutting down is not reconnected`() {
        connectedAndBusy()

        client.destroy()

        verify(exactly = 1) { mockCallback.onDisconnected(false) }
        verify(exactly = 0) { mockCallback.onDisconnected(true) }
    }

    // ========== Never established: nothing to report ==========

    @Test
    fun `an attempt that cannot connect is failed and reports no disconnect`() {
        pristine = connectionState()
        injectTransportAndConnect()
        fakeTransport.simulateConnected()

        fakeTransport.simulateFailure(IllegalStateException("boom"), isRecoverable = false)

        assertEquals(TransportState.Failed(FailureReason.TransientNetwork), client.connectionState.value)
        verify(exactly = 0) { mockCallback.onDisconnected(any()) }
        assertEquals(pristine, connectionState())
    }

    @Test
    fun `an attempt that fails before server hello reports no disconnect`() {
        pristine = connectionState()
        injectTransportAndConnect()
        fakeTransport.simulateConnected()

        fakeTransport.simulateFailure(SocketException("connection refused"), isRecoverable = true)

        assertEquals(TransportState.Idle, client.connectionState.value)
        verify(exactly = 0) { mockCallback.onDisconnected(any()) }
        assertEquals(pristine, connectionState())
    }

    @Test
    fun `a disconnect before server hello reports no disconnect`() {
        pristine = connectionState()
        injectTransportAndConnect()
        fakeTransport.simulateConnected()

        client.disconnect()

        assertEquals(TransportState.Idle, client.connectionState.value)
        verify(exactly = 0) { mockCallback.onDisconnected(any()) }
        assertEquals(pristine, connectionState())
    }

    // ========== The connection after ==========

    @Test
    fun `the next connection starts time sync only once it is activated`() {
        connectedAndBusy()
        fakeTransport.simulateClosed(1006, "abnormal")
        fakeTransport.clearRecordedMessages()

        injectTransportAndConnect()
        fakeTransport.simulateConnected()
        fakeServer.sendServerHello()

        assertTrue(fakeTransport.hasSentMessageContaining("client/hello"))
        assertEquals("false", connectionState()["timeSyncRunning"])
        assertFalse(
            "nothing but client/hello may precede the activation",
            fakeTransport.sentTextMessages.any { "client/time" in it || "client/state" in it },
        )

        fakeServer.sendServerActivate(activities = listOf("playback"), activeRoles = listOf("player@v1"))

        assertEquals("true", connectionState()["timeSyncRunning"])
        assertTrue(fakeTransport.hasSentMessageContaining("client/state"))
    }

    @Test
    fun `server unpair is handled again on the next connection`() {
        connectedAndBusy()
        fakeTransport.simulateTextMessage("""{"type":"server/unpair"}""")
        awaitClosed()

        injectTransportAndConnect()
        fakeServer.completeHandshake()
        setField(client, "matchedPsk", Psk(ByteArray(32) { 7 }, PskCategory.LONG_TERM, "server-id"))
        fakeTransport.simulateTextMessage("""{"type":"server/unpair"}""")

        verify(timeout = 2_000, exactly = 2) { mockCallback.onUnpaired("server-id") }
    }
}
