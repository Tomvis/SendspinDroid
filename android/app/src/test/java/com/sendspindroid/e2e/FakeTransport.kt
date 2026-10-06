package com.sendspindroid.e2e

import com.sendspindroid.sendspin.protocol.asJsonFrame
import com.sendspindroid.sendspin.protocol.jsonFrameText
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.TransportState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake SendSpinTransport for E2E tests.
 *
 * Records all sent messages and provides methods to simulate incoming
 * messages and connection events. Allows tests to drive the full
 * connection lifecycle without real network I/O.
 *
 * The client under test runs its encrypted channel over
 * [com.sendspindroid.sendspin.protocol.PlaintextCrypto], so every application
 * message is a binary frame `[type][body]` here, exactly as it is on the wire
 * after decryption. JSON messages (type 0) are unwrapped into
 * [sentTextMessages] alongside the cleartext handshake frames.
 */
class FakeTransport : SendSpinTransport {

    private var _state: TransportState = TransportState.Disconnected
    override val state: TransportState get() = _state
    override val isConnected: Boolean get() = _state == TransportState.Connected

    private var listener: SendSpinTransport.Listener? = null

    /** Every JSON message the client sent: cleartext handshake frames and type-0 binary frames. */
    val sentTextMessages = CopyOnWriteArrayList<String>()

    /** Binary frames the client sent that are not JSON messages. */
    val sentBinaryMessages = CopyOnWriteArrayList<ByteArray>()

    /** Whether close() was called. */
    var closed = false
        private set

    /** Whether destroy() was called. */
    var destroyed = false
        private set

    /** Close code passed to close(). */
    var closeCode: Int = -1
        private set

    /** Close reason passed to close(). */
    var closeReason: String = ""
        private set

    override fun connect() {
        _state = TransportState.Connecting
    }

    override fun send(text: String): Boolean {
        if (_state != TransportState.Connected) return false
        sentTextMessages.add(text)
        return true
    }

    override fun send(bytes: ByteArray): Boolean {
        if (_state != TransportState.Connected) return false
        val json = bytes.jsonFrameText()
        if (json != null) sentTextMessages.add(json) else sentBinaryMessages.add(bytes)
        return true
    }

    override fun close(code: Int, reason: String) {
        closed = true
        closeCode = code
        closeReason = reason
        _state = TransportState.Closed
    }

    /** Whether the close was asked to let queued frames out first. */
    var flushedBeforeClose = false
        private set

    override fun closeAfterFlush(code: Int, reason: String) {
        flushedBeforeClose = true
        close(code, reason)
    }

    override fun destroy() {
        destroyed = true
        _state = TransportState.Closed
    }

    override fun setListener(listener: SendSpinTransport.Listener?) {
        this.listener = listener
    }

    // ========== Simulation Methods ==========

    /**
     * Runs once the client has started its handshake on this transport. No
     * fake server speaks Noise, so this is where a test installs the channel
     * the handshake would have produced.
     */
    var afterConnected: () -> Unit = {}

    /**
     * Simulate the transport becoming connected (onConnected callback).
     */
    fun simulateConnected() {
        _state = TransportState.Connected
        listener?.onConnected()
        afterConnected()
    }

    /**
     * Simulate receiving a JSON message from the server, in the type-0 binary
     * frame every application message travels in.
     */
    fun simulateTextMessage(text: String) {
        listener?.onMessage(text.asJsonFrame())
    }

    /**
     * Simulate a text frame arriving off the socket, the way the real
     * transport delivers it: with its raw bytes.
     */
    fun simulateRawTextFrame(text: String) {
        listener?.onMessage(text, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Simulate receiving a binary message from the server.
     */
    fun simulateBinaryMessage(bytes: ByteArray) {
        listener?.onMessage(bytes)
    }

    /**
     * Simulate the transport closing.
     */
    fun simulateClosed(code: Int = 1000, reason: String = "") {
        _state = TransportState.Closed
        listener?.onClosed(code, reason)
    }

    /**
     * Simulate a transport failure.
     */
    fun simulateFailure(error: Throwable, isRecoverable: Boolean = true) {
        _state = TransportState.Failed
        listener?.onFailure(error, isRecoverable)
    }

    /**
     * Get the current listener (for verification in tests).
     */
    fun getListener(): SendSpinTransport.Listener? = listener

    /**
     * Find sent text messages matching a predicate.
     */
    fun findSentMessages(predicate: (String) -> Boolean): List<String> {
        return sentTextMessages.filter(predicate)
    }

    /**
     * Check if any sent message contains the given substring.
     *
     * The client encrypts and sends on its own timer thread, so a message
     * triggered by the line before this call may not have landed yet; this
     * waits briefly for it.
     */
    fun hasSentMessageContaining(substring: String): Boolean {
        val deadline = System.nanoTime() + SEND_WAIT_NANOS
        while (true) {
            if (sentTextMessages.any { it.contains(substring) }) return true
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(5)
        }
    }

    /**
     * Clear recorded messages.
     */
    fun clearRecordedMessages() {
        sentTextMessages.clear()
        sentBinaryMessages.clear()
    }

    private companion object {
        const val SEND_WAIT_NANOS = 1_000_000_000L
    }
}
