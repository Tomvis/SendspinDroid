package com.sendspindroid.sendspin.transport

import com.sendspindroid.shared.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Tests for [BaseWebSocketTransport.isRecoverableError].
 *
 * Coverage moved here from `SendSpinClientReconnectBackoffTest` after the
 * duplicate `SendSpin.isRecoverableError` was deleted. The new tests also
 * exercise multi-level cause chains, which the previous reflective tests
 * could not reach because the old classifier only inspected `t.cause`.
 *
 * Uses a tiny test subclass that exposes the protected method directly.
 */
class IsRecoverableErrorTest {

    private lateinit var transport: TestTransport

    private class TestTransport(client: HttpClient) :
        BaseWebSocketTransport(tag = "TestTransport", httpClient = client) {
        override fun buildWebSocketUrl(): String = "ws://localhost/test"

        // Expose the protected classifier for direct testing.
        fun classify(t: Throwable): Boolean = isRecoverableError(t)
    }

    @Before
    fun setUp() {
        mockkObject(Log)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        transport = TestTransport(HttpClient { install(WebSockets) })
    }

    @After
    fun tearDown() {
        transport.destroy()
        unmockkAll()
    }

    // ── Single-level cases (parity with the deleted SendSpin tests) ──────────

    @Test
    fun `unknown throwables resolve as unrecoverable`() {
        // A generic RuntimeException (the shape a parser bug or NPE takes) must
        // not be treated as recoverable, to avoid infinite reconnect on
        // programmer errors.
        val unknown = RuntimeException("something strange nobody has matched")
        assertEquals(false, transport.classify(unknown))
    }

    @Test
    fun `known transient errors resolve as recoverable`() {
        assertEquals(true, transport.classify(SocketException("Connection reset by peer")))
        assertEquals(true, transport.classify(EOFException("unexpected eof")))
        assertEquals(true, transport.classify(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `known permanent errors resolve as unrecoverable`() {
        assertEquals(false, transport.classify(UnknownHostException("no such host")))
        assertEquals(false, transport.classify(SSLHandshakeException("cert bad")))
        assertEquals(false, transport.classify(ConnectException("connection refused")))
        assertEquals(false, transport.classify(NoRouteToHostException("no route")))
        assertEquals(false, transport.classify(IOException("connection refused")))
    }

    // ── Chain-walked cases (new coverage, the bug this batch fixed) ──────────

    @Test
    fun `wrapped UnknownHostException is unrecoverable even when wrapped in IOException`() {
        // Ktor over OkHttp wraps DNS failures inside generic IOExceptions; the
        // old single-level classifier saw only "ioexception" and fell through
        // to the unrecognized branch, treating it as unrecoverable by luck of
        // the default. With chain walking, the real root is recognised.
        val wrapped = IOException("connect failure", IOException("dns", UnknownHostException("api.example.com")))
        assertEquals(false, transport.classify(wrapped))
    }

    @Test
    fun `wrapped UnknownHostException wins over a sibling SocketException in the chain`() {
        // SocketException is recoverable, UnknownHostException is not. When
        // both appear in a chain, the config error must win -- DNS is broken,
        // retrying won't help. Old code returned `true` here because it only
        // saw the immediate cause class. (SocketException has no
        // (message, cause) ctor, so use initCause.)
        val outer = SocketException("socket: dns fail").apply {
            initCause(UnknownHostException("api.example.com"))
        }
        assertEquals(false, transport.classify(outer))
    }

    @Test
    fun `wrapped SocketException is still recoverable`() {
        val inner = SocketException("connection reset")
        val wrapped = RuntimeException("wrapper", IOException("io", inner))
        assertEquals(true, transport.classify(wrapped))
    }

    @Test
    fun `message-based detection still works through wrappers`() {
        // Without a recognised cause class in the chain, the t.message check
        // catches familiar phrases.
        val wrapped = RuntimeException("transport error: broken pipe on write")
        assertEquals(true, transport.classify(wrapped))
    }

    @Test
    fun `pathological self-referencing cause does not loop`() {
        // generateSequence(t) { it.cause } would loop forever on a cause
        // cycle. The take(16) cap keeps the classifier bounded; the test
        // would hang or stack-overflow on regression.
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        // Construct A→B→A via reflection (initCause refuses if the cause was
        // already set in the constructor, so use the no-cause ctor and set it
        // afterwards).
        a.initCause(b)
        b.initCause(a)
        // Just needs to terminate; the actual classification is "unrecognized"
        // → false because none of the chain members match a known category.
        assertEquals(false, transport.classify(a))
    }
}
