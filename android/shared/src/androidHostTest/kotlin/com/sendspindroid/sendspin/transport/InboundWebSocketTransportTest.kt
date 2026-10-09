package com.sendspindroid.sendspin.transport

import com.sendspindroid.shared.log.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The listener for server-initiated connections and the transport it hands
 * out, driven over real sockets by the app's own outbound [WebSocketTransport]
 * standing in for the server.
 */
class InboundWebSocketTransportTest {

    /** Everything a transport reports, in the order it reported it. */
    private class Recorder : SendSpinTransport.Listener {
        val events = LinkedBlockingQueue<String>()
        val binary = LinkedBlockingQueue<ByteArray>()
        val rawText = LinkedBlockingQueue<ByteArray>()

        override fun onConnected() { events += "connected" }
        override fun onMessage(text: String, rawUtf8: ByteArray) {
            rawText += rawUtf8
            events += "text:$text"
        }
        override fun onMessage(bytes: ByteArray) {
            binary += bytes
            events += "binary"
        }
        override fun onClosing(code: Int, reason: String) { events += "closing:$code" }
        override fun onClosed(code: Int, reason: String) { events += "closed:$code" }
        override fun onFailure(error: Throwable, isRecoverable: Boolean) { events += "failure" }

        fun next(): String? = events.poll(WAIT_S, TimeUnit.SECONDS)
    }

    private val accepted = LinkedBlockingQueue<InboundWebSocketTransport>()
    private val servers = CopyOnWriteArrayList<SendspinWebSocketServer>()
    private val peers = CopyOnWriteArrayList<WebSocketTransport>()

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        peers.forEach { it.destroy() }
        servers.forEach { it.stop() }
        unmockkAll()
    }

    /** A listener on a free port that queues what it accepts. */
    private fun listen(preferredPort: Int = 0): Int {
        val server = SendspinWebSocketServer(onConnection = { accepted += it })
        servers += server
        return server.start(preferredPort)
    }

    /** A server dialling the listener. */
    private fun dial(port: Int, path: String = "/sendspin"): Recorder {
        val recorder = Recorder()
        val peer = WebSocketTransport("127.0.0.1:$port", path)
        peers += peer
        peer.setListener(recorder)
        peer.connect()
        return recorder
    }

    private fun peerFor(recorder: Recorder): WebSocketTransport =
        peers.last().also { assertEquals("connected", recorder.next()) }

    /** Accept the next connection and take it. */
    private fun take(): Pair<InboundWebSocketTransport, Recorder> {
        val transport = accepted.poll(WAIT_S, TimeUnit.SECONDS) ?: error("nothing connected")
        val recorder = Recorder()
        transport.setListener(recorder)
        transport.connect()
        assertEquals("connected", recorder.next())
        return transport to recorder
    }

    @Test
    fun `frames round-trip in both directions`() {
        val server = dial(listen())
        val peer = peerFor(server)
        val (inbound, client) = take()

        assertTrue(inbound.send("""{"type":"client/init"}"""))
        assertEquals("""text:{"type":"client/init"}""", server.next())
        assertTrue(inbound.send(byteArrayOf(4, 0, 1, 2)))
        assertEquals("binary", server.next())
        assertArrayEquals(byteArrayOf(4, 0, 1, 2), server.binary.poll())

        // Text keeps its exact bytes: the Noise prologue hashes server/init
        // as it arrived.
        val serverInit = """{"type":"server/init","payload":{"n":"é"}}"""
        assertTrue(peer.send(serverInit))
        assertEquals("text:$serverInit", client.next())
        assertArrayEquals(serverInit.toByteArray(Charsets.UTF_8), client.rawText.poll())
        assertTrue(peer.send(byteArrayOf(0, 9, 8)))
        assertEquals("binary", client.next())
        assertArrayEquals(byteArrayOf(0, 9, 8), client.binary.poll())
    }

    @Test
    fun `what the owner sends on taking the connection is the first frame out`() {
        val server = dial(listen())
        peerFor(server)
        val transport = accepted.poll(WAIT_S, TimeUnit.SECONDS)!!
        transport.setListener(object : SendSpinTransport.Listener by Recorder() {
            override fun onConnected() {
                transport.send("first")
            }
        })

        transport.connect()
        transport.send("second")

        assertEquals("text:first", server.next())
        assertEquals("text:second", server.next())
    }

    @Test
    fun `the before-write hook runs when the frame is written, not when it is queued`() {
        val server = dial(listen())
        peerFor(server)
        val (inbound, _) = take()

        val order = CopyOnWriteArrayList<String>()
        val firstIsBeingWritten = CountDownLatch(1)
        val release = CountDownLatch(1)

        inbound.send(byteArrayOf(1)) {
            order += "hook-1"
            firstIsBeingWritten.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
        }
        assertTrue(firstIsBeingWritten.await(WAIT_S, TimeUnit.SECONDS))
        // Queued behind a frame that has not left yet.
        inbound.send(byteArrayOf(2)) { order += "hook-2" }
        Thread.sleep(100)
        assertEquals(listOf("hook-1"), order.toList())
        assertNull("nothing reaches the peer before its hook returns", server.events.poll())

        release.countDown()
        assertEquals("binary", server.next())
        assertEquals("binary", server.next())
        assertEquals(listOf("hook-1", "hook-2"), order.toList())
        assertArrayEquals(byteArrayOf(1), server.binary.poll())
        assertArrayEquals(byteArrayOf(2), server.binary.poll())
    }

    @Test
    fun `a close by the server is reported as closing then closed`() {
        val server = dial(listen())
        val peer = peerFor(server)
        val (inbound, client) = take()
        peer.send("hello")
        assertEquals("text:hello", client.next())

        peer.destroy()

        assertTrue(client.next()!!.startsWith("closing:"))
        assertTrue(client.next()!!.startsWith("closed:"))
        assertEquals(TransportState.Closed, inbound.state)
        assertFalse(inbound.send("too late"))
    }

    @Test
    fun `closing an accepted connection releases its socket`() {
        val server = dial(listen())
        peerFor(server)
        val (inbound, client) = take()

        inbound.close(1000, "done")

        assertEquals("closed:1000", client.next())
        // The other end sees the socket go, which it would not if the
        // accepted socket were still held open.
        assertTrue(server.next()!!.startsWith("clos"))
    }

    @Test
    fun `a close after flush delivers what was queued first`() {
        val server = dial(listen())
        peerFor(server)
        val (inbound, _) = take()

        inbound.send("goodbye")
        inbound.closeAfterFlush(1000, "goodbye")

        assertEquals("text:goodbye", server.next())
        assertTrue(server.next()!!.startsWith("clos"))
    }

    @Test
    fun `a connection that is refused instead of taken is closed`() {
        val server = dial(listen())
        peerFor(server)

        accepted.poll(WAIT_S, TimeUnit.SECONDS)!!.close(1013, "busy")

        assertTrue(server.next()!!.startsWith("clos"))
    }

    @Test
    fun `only the configured path is served`() {
        val port = listen()

        val server = dial(port, path = "/elsewhere")

        assertEquals("failure", server.next())
        assertNull(accepted.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `falls back to a free port when the preferred one is taken`() {
        ServerSocket().use { occupied ->
            occupied.reuseAddress = false
            occupied.bind(InetSocketAddress("0.0.0.0", 0))

            val port = listen(preferredPort = occupied.localPort)

            assertNotEquals(occupied.localPort, port)
            assertTrue(port > 0)
            val server = dial(port)
            assertEquals("connected", server.next())
        }
    }

    @Test
    fun `stopping drops open connections and frees the port`() {
        val port = listen()
        val server = dial(port)
        peerFor(server)
        take()

        servers.single().stop()

        // Abrupt, so the peer may see a reset where it would otherwise see a close.
        assertTrue(server.next()!!.let { it.startsWith("clos") || it == "failure" })
        ServerSocket().use { it.bind(InetSocketAddress("0.0.0.0", port)) }
    }

    private companion object {
        const val WAIT_S = 5L
    }
}
