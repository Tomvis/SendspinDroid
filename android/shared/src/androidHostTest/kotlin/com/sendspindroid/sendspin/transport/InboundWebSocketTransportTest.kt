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
import org.junit.Assert.assertNotNull
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

        /** The next event that is not "connected" or the "closing" ahead of a "closed". */
        fun nextClose(): String? {
            while (true) {
                val event = next() ?: return null
                if (event != "connected" && !event.startsWith("closing:")) return event
            }
        }
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
    fun `a close after flush right behind the sends loses none of them`() {
        val server = dial(listen())
        peerFor(server)
        val transport = accepted.poll(WAIT_S, TimeUnit.SECONDS)!!
        transport.setListener(Recorder())

        // No waiting for the pump to start, or for anything to be written:
        // the close is asked for the instant the last frame is queued.
        transport.connect()
        repeat(50) { transport.send("frame-$it") }
        transport.closeAfterFlush(1000, "goodbye")
        // What an owner does next. It must not turn the flush into a discard.
        transport.destroy()

        repeat(50) { assertEquals("text:frame-$it", server.next()) }
        assertTrue(server.next()!!.startsWith("clos"))
    }

    @Test
    fun `a plain close does not wait for the queue`() {
        val server = dial(listen())
        peerFor(server)
        val (inbound, _) = take()
        val firstIsBeingWritten = CountDownLatch(1)
        val release = CountDownLatch(1)
        inbound.send(byteArrayOf(1)) {
            firstIsBeingWritten.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
        }
        assertTrue(firstIsBeingWritten.await(WAIT_S, TimeUnit.SECONDS))
        inbound.send("never sent")

        inbound.close(1001, "going")
        release.countDown()

        // The frame being written goes; the one behind it does not.
        assertEquals("binary", server.next())
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
    fun `stopping tells every connected server and frees the port`() {
        val port = listen()
        val connected = dial(port)
        peerFor(connected)
        val (inbound, client) = take()
        // And one that was accepted and not yet taken or refused.
        val waiting = dial(port)
        peerFor(waiting)
        assertNotNull(accepted.poll(WAIT_S, TimeUnit.SECONDS))

        servers.single().stop()

        // All of it has happened by the time stop() returns: nothing below
        // is waited for. A close frame, not a cut connection, which the
        // peer might not notice before its next ping.
        assertEquals("closed:1001", client.events.poll())
        assertEquals(TransportState.Closed, inbound.state)
        ServerSocket().use { it.bind(InetSocketAddress("0.0.0.0", port)) }
        assertEquals("closed:1001", connected.nextClose())
        assertEquals("closed:1001", waiting.nextClose())
    }

    @Test
    fun `stopping lets a connection that is closing deliver its last frame`() {
        val port = listen()
        val server = dial(port)
        peerFor(server)
        val (inbound, _) = take()

        inbound.send("goodbye")
        inbound.closeAfterFlush(1000, "goodbye")
        servers.single().stop()

        assertEquals("text:goodbye", server.next())
        ServerSocket().use { it.bind(InetSocketAddress("0.0.0.0", port)) }
    }

    /** Open a WebSocket to the listener over a bare socket, as a server process would. */
    private fun dialRaw(port: Int): java.net.Socket {
        val socket = java.net.Socket("127.0.0.1", port)
        socket.getOutputStream().write(
            ("GET /sendspin HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray()
        )
        val reader = socket.getInputStream().bufferedReader()
        assertTrue(reader.readLine().startsWith("HTTP/1.1 101"))
        while (reader.readLine().isNotEmpty()) { /* skip the response headers */ }
        return socket
    }

    /** A masked binary frame of [size] bytes, with the length the header declares. */
    private fun binaryFrameHeader(size: Long): ByteArray {
        val length = if (size <= 0xFFFF) {
            byteArrayOf((0x80 or 126).toByte(), (size shr 8).toByte(), size.toByte())
        } else {
            byteArrayOf((0x80 or 127).toByte()) + ByteArray(8) { (size shr (8 * (7 - it))).toByte() }
        }
        // FIN + binary, the length, and an all-zero masking key.
        return byteArrayOf(0x82.toByte()) + length + ByteArray(4)
    }

    @Test
    fun `a frame as large as a Noise message is delivered`() {
        val socket = dialRaw(listen())
        val (_, client) = take()

        socket.getOutputStream().write(binaryFrameHeader(65535) + ByteArray(65535) { 7 })

        assertEquals("binary", client.next())
        assertEquals(65535, client.binary.poll()!!.size)
        socket.close()
    }

    @Test
    fun `a frame larger than a Noise message is refused`() {
        val socket = dialRaw(listen())
        val (inbound, client) = take()

        // One byte over what the protocol can send. Without a limit the
        // listener allocates whatever the header asks for.
        socket.getOutputStream().write(binaryFrameHeader(65536) + ByteArray(65536) { 7 })

        val first = client.next()
        assertTrue("got $first", first != null && first != "binary")
        assertNotEquals(TransportState.Connected, inbound.state)
        socket.close()
    }

    @Test
    fun `a connection from a web page is refused before it is accepted`() {
        val port = listen()
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(
                ("GET /sendspin HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\nOrigin: http://example.com\r\n\r\n").toByteArray()
            )

            val status = socket.getInputStream().bufferedReader().readLine()

            assertTrue("answered $status", status.startsWith("HTTP/1.1 403"))
        }
        assertNull(accepted.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a listener that was stopped cannot be started`() {
        val server = SendspinWebSocketServer(onConnection = { accepted += it })
        servers += server
        val port = ServerSocket(0).use { it.localPort }

        // The stop that overtakes its start, as when the service is
        // destroyed while the listener is still being brought up.
        server.stop()
        val started = runCatching { server.start(port) }

        assertTrue(started.isFailure)
        ServerSocket().use { it.bind(InetSocketAddress("0.0.0.0", port)) }
    }

    @Test
    fun `a socket closed without a close frame is noticed at once`() {
        val socket = dialRaw(listen())
        val (inbound, client) = take()

        socket.close()

        assertGone(client, inbound)
    }

    @Test
    fun `a connection reset is noticed at once`() {
        val socket = dialRaw(listen())
        val (inbound, client) = take()

        // What a server process leaves behind when it is killed. Ktor
        // reports it by cancelling the frame channel, which must not pass
        // for the transport itself being cancelled.
        socket.setSoLinger(true, 0)
        socket.close()

        assertGone(client, inbound)
    }

    private fun assertGone(client: Recorder, inbound: InboundWebSocketTransport) {
        // "closing" may come first.
        val events = listOfNotNull(client.next()).let { first ->
            if (first.any { it.startsWith("closing:") }) first + listOfNotNull(client.next()) else first
        }
        assertTrue("reported $events", events.any { it.startsWith("closed:") || it == "failure" })
        assertNotEquals(TransportState.Connected, inbound.state)
    }

    private companion object {
        // How long to wait for something that is expected to happen. Every
        // wait returns as soon as it does, so this only bounds a failure.
        const val WAIT_S = 30L
    }
}
