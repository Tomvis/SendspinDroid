package com.sendspindroid.e2e

import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.crypto.Psk
import com.sendspindroid.sendspin.crypto.PskCategory
import com.sendspindroid.sendspin.protocol.Activity
import com.sendspindroid.sendspin.protocol.PlaintextCrypto
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.SendspinWebSocketServer
import com.sendspindroid.sendspin.transport.WebSocketTransport
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A connection the server opened (`connection.md`, "Server Initiated
 * Connections"). The app is still the Sendspin client on it; only who opened
 * the socket differs.
 */
class ServerInitiatedConnectionTest : E2ETestBase() {

    private val serverAddress = "192.168.1.50:51234"

    /** The server connects, as far as the transport being up. */
    private fun acceptConnection() {
        fakeTransport.afterConnected = { client.installEncryptedChannel(PlaintextCrypto) }
        client.accept(fakeTransport, serverAddress)
        fakeTransport.simulateConnected()
    }

    private fun acceptAndActivate(activities: List<String> = listOf("playback")) {
        acceptConnection()
        fakeServer.sendServerHello()
        fakeServer.sendServerActivate(activities, activeRoles = listOf("player@v1"))
    }

    private fun sentTypes(): List<String> = fakeTransport.sentTextMessages.map {
        Json.parseToJsonElement(it).jsonObject["type"]!!.jsonPrimitive.content
    }

    private fun goodbyes() = fakeTransport.sentTextMessages.filter { "client/goodbye" in it }

    @Test
    fun `client init is the first frame on a socket the server opened`() {
        // Real sockets: a listener, and a WebSocket client dialling it the
        // way a server does.
        val listener = SendspinWebSocketServer(onConnection = { client.accept(it, it.remoteAddress) })
        val port = listener.start(preferredPort = 0)
        val received = LinkedBlockingQueue<String>()
        val server = WebSocketTransport("127.0.0.1:$port")
        server.setListener(object : SendSpinTransport.Listener {
            override fun onConnected() {}
            override fun onMessage(text: String, rawUtf8: ByteArray) { received += text }
            override fun onMessage(bytes: ByteArray) { received += "binary" }
            override fun onClosing(code: Int, reason: String) {}
            override fun onClosed(code: Int, reason: String) {}
            override fun onFailure(error: Throwable, isRecoverable: Boolean) {}
        })
        try {
            // The server says nothing: the client speaks first even though
            // it did not open the connection.
            server.connect()

            val first = received.poll(5, TimeUnit.SECONDS)
            assertNotNull("the client sent nothing", first)
            val message = Json.parseToJsonElement(first!!).jsonObject
            assertEquals("client/init", message["type"]!!.jsonPrimitive.content)
            val payload = message["payload"]!!.jsonObject
            assertEquals(1, payload["version"]!!.jsonPrimitive.int)
            assertEquals(43, payload["client_id"]!!.jsonPrimitive.content.length)
            assertTrue(client.isServerInitiated)
        } finally {
            server.destroy()
            listener.stop()
        }
    }

    @Test
    fun `an accepted connection handshakes and activates like a dialled one`() {
        acceptAndActivate()

        assertEquals("client/init", sentTypes().first())
        assertTrue(fakeTransport.hasSentMessageContaining("client/state"))
        assertEquals(listOf("client/init", "client/hello"), sentTypes().take(2))
        assertTrue(client.isConnected)
        assertEquals(serverAddress, client.getServerAddress())
    }

    @Test
    fun `a dialled connection is not server-initiated`() {
        acceptAndActivate()
        runCatching { client.connectLocal("127.0.0.1:1") }

        assertFalse(client.isServerInitiated)
    }

    @Test
    fun `a refused connection is told concurrent_attempt, closed, and reports nothing`() {
        val asked = mutableListOf<Set<Activity>>()
        client.admission = { _, activities -> asked += activities; false }

        acceptAndActivate()

        assertTrue(fakeTransport.hasSentMessageContaining("client/goodbye"))
        assertEquals(
            listOf("""{"type":"client/goodbye","payload":{"reason":"concurrent_attempt"}}"""),
            goodbyes(),
        )
        assertTrue("the close must let the goodbye out first", fakeTransport.flushedBeforeClose)
        assertEquals(listOf(setOf(Activity.PLAYBACK)), asked)
        assertEquals(listOf("client/init", "client/hello", "client/goodbye"), sentTypes())
        verify(exactly = 0) { mockCallback.onAdmissionStateChanged(any()) }
        // It chose to leave, so nobody is asked to reconnect.
        verify(exactly = 1) { mockCallback.onDisconnected(false) }
    }

    @Test
    fun `later activations are put to admission too and change nothing when admitted`() {
        var asked = 0
        client.admission = { _, _ -> asked++; true }
        acceptAndActivate(activities = emptyList())

        fakeServer.sendServerActivate(listOf("playback"))

        assertEquals(2, asked)
        assertTrue(client.isConnected)
        assertEquals(emptyList<String>(), goodbyes())
    }

    @Test
    fun `a displaced playback connection is told another_server`() {
        acceptAndActivate()

        client.leaveForAnotherServer()

        assertEquals(
            listOf("""{"type":"client/goodbye","payload":{"reason":"another_server"}}"""),
            goodbyes(),
        )
        assertTrue(fakeTransport.closed)
        assertTrue(fakeTransport.flushedBeforeClose)
        verify(exactly = 1) { mockCallback.onDisconnected(false) }
    }

    @Test
    fun `a displaced pairing connection has its pair abort on the wire when the call returns`() {
        acceptConnection()
        setField(client, "matchedPsk", Psk(ByteArray(32) { 3 }, PskCategory.PAIRING))
        fakeServer.sendServerHello()
        fakeTransport.simulateTextMessage(
            """{"type":"server/activate","payload":{"activities":["pairing"],""" +
                """"active_roles":[],"pairing":{"method":"pairing_psk"}}}"""
        )
        assertTrue(fakeTransport.hasSentMessageContaining("client/pair-init"))

        client.leaveForAnotherServer()

        // Checked at once, with no waiting: whoever displaced it may close
        // the listener next, and a pair/abort still being encrypted on
        // another thread would be lost with the socket.
        assertEquals(
            listOf("""{"type":"pair/abort","payload":{"reason":"concurrent_attempt"}}"""),
            fakeTransport.sentTextMessages.filter { "pair/abort" in it },
        )
        assertTrue(fakeTransport.closed)
        assertTrue(fakeTransport.flushedBeforeClose)
        assertEquals(emptyList<String>(), goodbyes())
    }

    @Test
    fun `a connection displaced before its activation was applied is still told another_server`() {
        acceptConnection()
        fakeServer.sendServerHello()
        assertTrue(fakeTransport.hasSentMessageContaining("client/hello"))

        client.leaveForAnotherServer()

        assertEquals(
            listOf("""{"type":"client/goodbye","payload":{"reason":"another_server"}}"""),
            goodbyes(),
        )
        assertTrue(fakeTransport.flushedBeforeClose)
    }

    @Test
    fun `a connection dropped before its activation gets no goodbye`() {
        acceptConnection()
        fakeServer.sendServerHello()
        assertTrue(fakeTransport.hasSentMessageContaining("client/hello"))

        client.drop()

        assertEquals(emptyList<String>(), goodbyes())
        assertTrue(fakeTransport.closed)
        verify(exactly = 1) { mockCallback.onDisconnected(false) }
    }

    @Test
    fun `the stall watchdog leaves a connection alone until its first activation`() {
        val checkStall = SendSpin::class.java.getDeclaredMethod("checkStall").apply { isAccessible = true }
        val lastByte: AtomicLong = getField(client, "lastByteReceivedAtMs")
        acceptConnection()
        fakeServer.sendServerHello()

        // Longer than the idle threshold, shorter than the 30 seconds the
        // server has to activate.
        lastByte.set(System.currentTimeMillis() - 25_000)
        checkStall.invoke(client)
        assertFalse("a provisional connection is not the watchdog's to close", fakeTransport.closed)

        fakeServer.sendServerActivate(listOf("playback"), activeRoles = listOf("player@v1"))
        lastByte.set(System.currentTimeMillis() - 25_000)
        checkStall.invoke(client)
        assertTrue("an activated connection is", fakeTransport.closed)
    }
}
