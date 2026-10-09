package com.sendspindroid.sendspin.transport

import com.sendspindroid.shared.log.Log
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

        /** RFC 6455: the connection closed without a close frame. */
        const val CLOSE_ABNORMAL = 1006
    }

    private class Outgoing(val frame: Frame, val beforeWrite: (() -> Unit)? = null)

    /** A local close: what to tell the peer, and whether what is queued goes first. */
    private class CloseRequest(val code: Int, val reason: String, val flush: Boolean)

    private val _state = AtomicReference(TransportState.Disconnected)
    override val state: TransportState get() = _state.load()

    @Volatile
    private var listener: SendSpinTransport.Listener? = null

    private val outgoing = Channel<Outgoing>(Channel.BUFFERED)

    /** True once the owner has taken the connection, false if it refused it. */
    private val taken = CompletableDeferred<Boolean>()

    // The first close asked for. A later one changes nothing: a destroy()
    // that follows closeAfterFlush() must not turn the flush into a discard.
    private val closeRequest = AtomicReference<CloseRequest?>(null)

    /** True once a close has been asked for, whether or not it has finished. */
    internal val isClosing: Boolean get() = closeRequest.load() != null

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
     *
     * Every write to the socket is made here, in order: the queued frames,
     * then the close frame. A close is a request to this coroutine and never
     * something done to the socket from outside it, so no timing can put the
     * close ahead of a frame that was queued before it.
     */
    internal suspend fun serve() {
        if (!taken.await()) {
            sayWhyAndClose()
            return
        }
        try {
            pump()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket failure from $remoteAddress: ${e.message}")
            if (endConnection(TransportState.Failed)) listener?.onFailure(e, true)
        } finally {
            // A connection reset reaches here as a cancellation, of the
            // frame channel or of this handler, with nothing reported yet.
            // Whatever ended the pump, the connection is over.
            if (endConnection(TransportState.Closed)) {
                Log.d(TAG, "WebSocket from $remoteAddress lost")
                listener?.onClosed(CLOSE_ABNORMAL, "connection lost")
            }
        }
    }

    private suspend fun pump() = coroutineScope {
        val receiver = launch { receive() }
        // However the receiving side ends, there is nobody left to send to.
        receiver.invokeOnCompletion { outgoing.close() }

        // Ends when the queue is closed: by close(), by closeAfterFlush(), or
        // because the connection ended. What was queued before that is still
        // handed out by the loop, in order.
        for (msg in outgoing) {
            if (closeRequest.load()?.flush == false) break
            msg.beforeWrite?.invoke()
            session.send(msg.frame)
        }
        sayWhyAndClose()
        receiver.cancel()
    }

    private suspend fun receive() {
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

        // The server closed it, or the socket dropped.
        val reason = session.closeReason.await()
        val code = reason?.code?.toInt() ?: CLOSE_ABNORMAL
        val message = reason?.message ?: ""
        Log.d(TAG, "WebSocket from $remoteAddress closed: $code $message")
        if (endConnection(TransportState.Closed)) {
            listener?.onClosing(code, message)
            listener?.onClosed(code, message)
        }
    }

    /** Send the close frame of a local close, behind everything already written. */
    private suspend fun sayWhyAndClose() {
        val request = closeRequest.load() ?: return
        runCatching { session.close(CloseReason(request.code.toShort(), request.reason)) }
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

    /** Close now: what is still queued is discarded. */
    override fun close(code: Int, reason: String) =
        requestClose(CloseRequest(code, reason, flush = false))

    /**
     * Close once everything already queued has been written. The pump does
     * both, one after the other, so nothing here waits or times out.
     */
    override fun closeAfterFlush(code: Int, reason: String) =
        requestClose(CloseRequest(code, reason, flush = true))

    private fun requestClose(request: CloseRequest) {
        if (!closeRequest.compareAndSet(null, request)) return
        Log.d(TAG, "Closing WebSocket from $remoteAddress: code=${request.code} reason=${request.reason}")
        val wasLive = endConnection(TransportState.Closed)
        // Wakes the pump, or tells serve() the connection was refused.
        outgoing.close()
        taken.complete(false)
        if (wasLive) listener?.onClosed(request.code, request.reason)
    }

    override fun destroy() {
        close(1000, "Transport destroyed")
    }

    /** True for the first caller only, so a local close racing a remote one is reported once. */
    private fun endConnection(terminal: TransportState): Boolean =
        _state.compareAndSet(TransportState.Connected, terminal) ||
            _state.compareAndSet(TransportState.Disconnected, terminal)
}
