package com.sendspindroid.sendspin.transport

import com.sendspindroid.shared.log.Log
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A WebSocket a server opened to us, accepted by [SendspinWebSocketServer].
 *
 * Only who opened the TCP connection differs from [WebSocketTransport]. The
 * app is still the Sendspin client on it: it sends `client/init` first and is
 * the Noise responder, "regardless of which side initiated the WebSocket
 * connection" (`connection.md`, Pattern).
 *
 * The socket already exists, so [connect] does not open anything: it is how
 * the owner takes the connection, and what starts frames flowing to the
 * listener. An owner that does not want the connection calls [close] instead.
 * One of the two must be called, or the socket stays open.
 *
 * @param remoteAddress "host:port" of the server that connected.
 */
@OptIn(ExperimentalAtomicApi::class)
class InboundWebSocketTransport internal constructor(
    private val session: DefaultWebSocketSession,
    val remoteAddress: String,
) : SendSpinTransport {

    private companion object {
        const val TAG = "InboundWsTransport"

        /** A goodbye is best-effort; a wedged socket must not stall the close. */
        const val FLUSH_TIMEOUT_MS = 500L
    }

    private class Outgoing(val frame: Frame, val beforeWrite: (() -> Unit)? = null)

    private val _state = AtomicReference(TransportState.Disconnected)
    override val state: TransportState get() = _state.load()

    @Volatile
    private var listener: SendSpinTransport.Listener? = null

    private val outgoing = Channel<Outgoing>(Channel.BUFFERED)

    /** True once the owner has taken the connection, false if it refused it. */
    private val taken = CompletableDeferred<Boolean>()

    // The frame pump, and its sender half so closeAfterFlush can wait for the
    // queue to drain.
    @Volatile private var pumpJob: Job? = null
    @Volatile private var senderJob: Job? = null

    // Outlives the Ktor session's own scope, which ends with the socket.
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun setListener(listener: SendSpinTransport.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (!_state.compareAndSet(TransportState.Disconnected, TransportState.Connected)) {
            Log.w(TAG, "Cannot take connection from $remoteAddress: already $state")
            return
        }
        Log.d(TAG, "Connection from $remoteAddress taken")
        // First, so whatever the listener sends from onConnected is at the
        // head of the queue when the pump starts.
        listener?.onConnected()
        taken.complete(true)
    }

    /**
     * Pump frames until the connection ends. Runs in the server's handler for
     * this socket, which Ktor closes when this returns.
     */
    internal suspend fun serve() {
        if (!taken.await()) return
        try {
            coroutineScope {
                val pump = launch { pump() }
                pumpJob = pump
                // A close that raced the line above found no job to cancel.
                if (state != TransportState.Connected) pump.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket failure from $remoteAddress: ${e.message}")
            if (endConnection(TransportState.Failed)) listener?.onFailure(e, true)
        }
    }

    private suspend fun pump() = coroutineScope {
        val sender = launch {
            for (msg in outgoing) {
                msg.beforeWrite?.invoke()
                session.send(msg.frame)
            }
        }
        senderJob = sender

        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> {
                    // The raw bytes go with the text: the Noise prologue is
                    // built from server/init exactly as it arrived.
                    val raw = frame.data
                    listener?.onMessage(raw.decodeToString(), raw)
                }
                is Frame.Binary -> listener?.onMessage(frame.data)
                else -> { /* Ping/Pong/Close are handled by Ktor */ }
            }
        }
        sender.cancel()

        // The server closed it, or the socket dropped.
        val reason = withTimeoutOrNull(FLUSH_TIMEOUT_MS) { session.closeReason.await() }
        val code = reason?.code?.toInt() ?: 1006
        val message = reason?.message ?: ""
        Log.d(TAG, "WebSocket from $remoteAddress closed: $code $message")
        listener?.onClosing(code, message)
        if (endConnection(TransportState.Closed)) listener?.onClosed(code, message)
    }

    override fun send(text: String): Boolean = enqueue(Outgoing(Frame.Text(text)))

    override fun send(bytes: ByteArray): Boolean = enqueue(Outgoing(Frame.Binary(true, bytes)))

    override fun send(bytes: ByteArray, beforeWrite: () -> Unit): Boolean =
        enqueue(Outgoing(Frame.Binary(true, bytes), beforeWrite))

    private fun enqueue(message: Outgoing): Boolean {
        if (!isConnected) {
            Log.w(TAG, "Cannot send: not connected (state=$state)")
            return false
        }
        return outgoing.trySend(message).isSuccess
    }

    override fun close(code: Int, reason: String) {
        Log.d(TAG, "Closing WebSocket from $remoteAddress: code=$code reason=$reason")
        outgoing.close()
        val wasLive = endConnection(TransportState.Closed)
        taken.complete(false)
        closeScope.launch {
            // Say why while there is still a socket to say it on, then let
            // the handler return so Ktor releases it.
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
                runCatching { session.close(CloseReason(code.toShort(), reason)) }
            }
            pumpJob?.cancel()
        }
        if (wasLive) listener?.onClosed(code, reason)
    }

    /**
     * Closing the channel stops new sends but leaves what is queued
     * deliverable, so the sender drains and completes on its own. See
     * [BaseWebSocketTransport.closeAfterFlush].
     */
    override fun closeAfterFlush(code: Int, reason: String) {
        val sender = senderJob
        outgoing.close()
        if (sender == null) {
            close(code, reason)
            return
        }
        closeScope.launch {
            val drained = withTimeoutOrNull(FLUSH_TIMEOUT_MS) { sender.join() } != null
            if (!drained) Log.w(TAG, "Outgoing queue did not drain in ${FLUSH_TIMEOUT_MS}ms")
            close(code, reason)
        }
    }

    override fun destroy() {
        close(1000, "Transport destroyed")
    }

    /** True for the first caller only, so a local close racing a remote one is reported once. */
    private fun endConnection(terminal: TransportState): Boolean =
        _state.compareAndSet(TransportState.Connected, terminal) ||
            _state.compareAndSet(TransportState.Disconnected, terminal)
}
