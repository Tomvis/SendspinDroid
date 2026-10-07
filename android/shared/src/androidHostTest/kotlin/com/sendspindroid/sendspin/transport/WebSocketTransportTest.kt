package com.sendspindroid.sendspin.transport

import com.sendspindroid.shared.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tests for WebSocketTransport lifecycle, specifically verifying the
 * close() vs destroy() contract that is critical for avoiding HttpClient leaks.
 *
 * Bug C-02: disconnect() was calling close() which does NOT release the
 * underlying Ktor HttpClient. Only destroy() performs full cleanup.
 */
class WebSocketTransportTest {

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
        unmockkAll()
    }

    /**
     * Verifies that close() does NOT close the HttpClient.
     * This is intentional -- close() only cancels the WebSocket session coroutine,
     * allowing the HttpClient to be reused if needed.
     */
    @Test
    fun `close does not close HttpClient`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        transport.close(1000, "test close")

        verify(exactly = 0) { client.close() }
    }

    /**
     * Verifies that destroy() DOES close the HttpClient, releasing the
     * underlying engine's connection pool and threads.
     *
     * This is the fix for bug C-02: SendSpinClient.disconnect() must call
     * destroy() (not close()) to avoid leaking the HttpClient.
     */
    @Test
    fun `destroy closes HttpClient`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        transport.destroy()

        verify(exactly = 1) { client.close() }
    }

    /**
     * Verifies that destroy() transitions state to Closed.
     */
    @Test
    fun `destroy sets state to Closed`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        transport.destroy()

        assert(transport.state == TransportState.Closed) {
            "Expected state Closed but got ${transport.state}"
        }
    }

    /**
     * Verifies that close() is safe to call before connect() is ever called.
     * There is no channel, job or live connection yet, so nothing is closed
     * and nothing is reported.
     */
    @Test
    fun `close before connect does not crash`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        // Should not throw
        transport.close(1000, "pre-connect close")
    }

    /**
     * Verifies that close() can be called multiple times without crashing.
     * The second call has a null channel and null job, both handled gracefully.
     */
    @Test
    fun `close is idempotent`() {
        val client = spyk(HttpClient { install(WebSockets) })
        val transport = WebSocketTransport(
            address = "127.0.0.1:8927",
            path = "/sendspin",
            httpClient = client
        )

        transport.close(1000, "first close")
        transport.close(1001, "second close")
        // No exception means success
    }

    // ------------------------------------------------------------------
    // What a locally initiated close reports
    // ------------------------------------------------------------------

    /** A server that completes one WebSocket upgrade and then says nothing. */
    private class SilentServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private var socket: Socket? = null
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                val s = server.accept().also { socket = it }
                val reader = s.getInputStream().bufferedReader()
                var key = ""
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                        key = line.substringAfter(":").trim()
                    }
                }
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                )
                s.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                            "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
                    )
                    flush()
                }
            }
        }

        override fun close() {
            socket?.close()
            server.close()
        }
    }

    /** Connects to [server] and returns the transport with every close or failure it reports. */
    private fun connectTo(server: SilentServer): Pair<WebSocketTransport, List<String>> {
        val connected = CountDownLatch(1)
        val reports = CopyOnWriteArrayList<String>()
        val transport = WebSocketTransport(address = "127.0.0.1:${server.port}")
        transport.setListener(object : SendSpinTransport.Listener {
            override fun onConnected() = connected.countDown()
            override fun onMessage(text: String, rawUtf8: ByteArray) {}
            override fun onMessage(bytes: ByteArray) {}
            override fun onClosing(code: Int, reason: String) {}
            override fun onClosed(code: Int, reason: String) {
                reports.add("closed $code $reason")
            }
            override fun onFailure(error: Throwable, isRecoverable: Boolean) {
                reports.add("failure $error")
            }
        })
        transport.connect()
        assertTrue("transport did not connect", connected.await(5, TimeUnit.SECONDS))
        return transport to reports
    }

    private fun awaitReport(reports: List<String>) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (reports.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        // Long enough for the cancelled session to add a second report if it were going to.
        Thread.sleep(300)
    }

    /**
     * A close the caller starts used to cancel the session and report nothing,
     * leaving the state at Connected: whoever closed a dead socket never heard
     * that the connection had ended.
     */
    @Test
    fun `a local close of a live connection reports onClosed once`() {
        SilentServer().use { server ->
            val (transport, reports) = connectTo(server)

            transport.close(1001, "local")
            transport.close(1001, "again")

            assertEquals(TransportState.Closed, transport.state)
            awaitReport(reports)
            assertEquals(listOf("closed 1001 local"), reports)
            transport.destroy()
        }
    }

    @Test
    fun `closeAfterFlush reports onClosed once`() {
        SilentServer().use { server ->
            val (transport, reports) = connectTo(server)

            transport.closeAfterFlush(1000, "goodbye")

            awaitReport(reports)
            assertEquals(listOf("closed 1000 goodbye"), reports)
            assertEquals(TransportState.Closed, transport.state)
            transport.destroy()
        }
    }

    @Test
    fun `a detached listener hears nothing from a local close`() {
        SilentServer().use { server ->
            val (transport, reports) = connectTo(server)

            transport.setListener(null)
            transport.close(1000, "goodbye")

            Thread.sleep(300)
            assertEquals(emptyList<String>(), reports)
            assertEquals(TransportState.Closed, transport.state)
            transport.destroy()
        }
    }
}
