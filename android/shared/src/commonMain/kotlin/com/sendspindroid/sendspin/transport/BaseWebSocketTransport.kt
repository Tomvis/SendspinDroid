package com.sendspindroid.sendspin.transport

import com.sendspindroid.shared.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Base class for WebSocket-based SendSpin transports using Ktor.
 *
 * Encapsulates the shared connection lifecycle, channel management, send/receive loops,
 * and close logic. Subclasses provide only the transport-specific setup:
 * - [WebSocketTransport]: direct `ws://host:port/path` for local network
 * - [ProxyWebSocketTransport]: `wss://` URL with optional Bearer auth header for proxies
 *
 * ## Thread Safety
 * This class is thread-safe. All state changes are atomic, and Ktor handles
 * WebSocket coroutines internally.
 *
 * @param tag Log tag for this transport instance
 * @param httpClient Ktor HttpClient configured for WebSocket connections
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class BaseWebSocketTransport(
    protected val tag: String,
    protected val httpClient: HttpClient
) : SendSpinTransport {

    companion object {
        /** A goodbye is best-effort; a wedged socket must not stall the close. */
        private const val FLUSH_TIMEOUT_MS = 500L

        /**
         * Create a default Ktor HttpClient configured for WebSocket connections.
         *
         * Delegates to the platform-specific [createWebSocketHttpClient] so the
         * ping interval is applied on the actual engine (OkHttp on Android),
         * not via Ktor's `install(WebSockets) { pingIntervalMillis = ... }` —
         * the OkHttp engine silently ignores the latter.
         */
        fun createDefaultClient(
            pingIntervalSeconds: Long = 30,
            connectTimeoutMs: Long = 5000
        ): HttpClient = createWebSocketHttpClient(pingIntervalSeconds, connectTimeoutMs)
    }

    private val _state = AtomicReference(TransportState.Disconnected)
    override val state: TransportState get() = _state.load()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // @Volatile: connectionJob and outgoingChannel are written by connect()
    // (caller thread), close() (caller thread), and the websocket coroutine's
    // finally block (IO dispatcher). Plain `var` has no happens-before edge
    // across those threads, so a send()/close() racing with the finally-null
    // write may read a stale snapshot. AtomicReference would be heavier and
    // gain nothing here; we only need read/write visibility, not CAS.
    @Volatile
    private var connectionJob: Job? = null

    // @Volatile: setListener(null) must be visible on the IO-dispatcher receive
    // thread before the next callback fires. A caller that clears the listener
    // and then calls close() relies on the cancellation-catch path below not
    // dispatching against a now-stale reference.
    @Volatile
    private var listener: SendSpinTransport.Listener? = null

    // Channel for outgoing messages (text or binary).
    @Volatile
    private var outgoingChannel: Channel<OutgoingMessage>? = null

    // Close intent captured by close()/destroy() so the cancellation-catch path
    // can deliver the right code/reason to the listener (e.g., 1001 from the
    // stall watchdog must reach onClosed so the upper layer drops to Idle and
    // the Coordinator-driven reconnect kicks in).
    @Volatile
    private var pendingCloseCode: Int = 1000
    @Volatile
    private var pendingCloseReason: String = "cancelled"

    // Held so closeAfterFlush can wait for the queue to drain. The sender is a
    // child of connectionJob, so cancelling that kills it mid-queue.
    private var senderJob: Job? = null

    private sealed class OutgoingMessage {
        data class Text(val text: String) : OutgoingMessage()
        data class Binary(val bytes: ByteArray) : OutgoingMessage()
    }

    // ------------------------------------------------------------------
    // Abstract hooks for subclasses
    // ------------------------------------------------------------------

    /**
     * Return the WebSocket URL to connect to (e.g., "ws://192.168.1.100:8927/sendspin"
     * or "wss://proxy.example.com/sendspin").
     */
    protected abstract fun buildWebSocketUrl(): String

    /**
     * Optional hook to customise the HTTP upgrade request (e.g., add auth headers).
     * Default implementation does nothing.
     */
    protected open fun configureRequest(builder: HttpRequestBuilder) {
        // No-op by default
    }

    /**
     * Check if an error is likely temporary (network glitch) vs. permanent (config error
     * or a leaked programming bug). Subclasses may override to add transport-specific
     * checks (e.g., auth errors).
     *
     * Defaults to NOT recoverable for unknown errors. Historically this defaulted to
     * true, which meant a `RuntimeException` leaking through (NPE, parser bug, etc.)
     * would trigger infinite reconnect loops against a server that was already fine.
     * Erring on "give up" for unknowns lets the UI surface the failure and keeps a
     * misbehaving client from pinging forever.
     */
    protected open fun isRecoverableError(t: Throwable): Boolean {
        // Walk the full cause chain rather than just `t.cause`. Ktor over OkHttp
        // wraps exceptions 2-3 levels deep (e.g., WebSocketException -> IOException
        // -> SocketException, or IOException -> IOException -> UnknownHostException);
        // checking only the immediate cause misses the real root and falls through
        // to the "unrecognized" branch, mis-classifying transient errors as
        // unrecoverable and -- worse -- mis-classifying DNS / SSL / connection-
        // refused failures as transient when wrapped in a generic IO/Socket
        // exception. take(16) is cheap cycle protection against pathologically
        // self-referencing causes.
        val chain = generateSequence(t) { it.cause }.take(16).toList()
        val causeNames = chain.map { it::class.simpleName?.lowercase() ?: "" }
        val message = t.message?.lowercase() ?: ""

        return when {
            // Configuration errors come first so they win when a wrapper layer
            // ALSO matches one of the transient categories below. Example:
            // [SocketException -> UnknownHostException]. Old single-level logic
            // returned false correctly only by luck of which level the check
            // hit first; chain-walking makes both visible, and "unrecoverable"
            // is the right answer.
            causeNames.any { it.contains("unknownhostexception") } -> false
            causeNames.any { it.contains("sslhandshakeexception") } -> false
            causeNames.any { it.contains("connectexception") } -> false
            causeNames.any { it.contains("noroutetohostexception") } -> false
            message.contains("refused") -> false
            message.contains("unknown host") -> false
            message.contains("no route") -> false

            // Transient network errors.
            causeNames.any { it.contains("socketexception") } -> true
            causeNames.any { it.contains("eofexception") } -> true
            causeNames.any { it.contains("sockettimeoutexception") } -> true
            causeNames.any { it.contains("timeoutexception") } -> true
            message.contains("reset") -> true
            message.contains("abort") -> true
            message.contains("broken pipe") -> true
            message.contains("connection closed") -> true
            message.contains("timeout") -> true

            else -> {
                Log.d(tag, "isRecoverableError: unrecognized chain=$causeNames msg='$message' -> treating as unrecoverable")
                false
            }
        }
    }

    // ------------------------------------------------------------------
    // SendSpinTransport implementation
    // ------------------------------------------------------------------

    override fun setListener(listener: SendSpinTransport.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (!_state.compareAndSet(TransportState.Disconnected, TransportState.Connecting) &&
            !_state.compareAndSet(TransportState.Failed, TransportState.Connecting) &&
            !_state.compareAndSet(TransportState.Closed, TransportState.Connecting)) {
            Log.w(tag, "Cannot connect: already ${state}")
            return
        }

        val wsUrl = buildWebSocketUrl()
        Log.d(tag, "Connecting to: $wsUrl")

        val sendChannel = Channel<OutgoingMessage>(Channel.BUFFERED)
        outgoingChannel = sendChannel

        connectionJob = scope.launch {
            try {
                httpClient.webSocket(
                    urlString = wsUrl,
                    request = { configureRequest(this) }
                ) {
                    Log.d(tag, "WebSocket connected")
                    _state.store(TransportState.Connected)
                    listener?.onConnected()

                    // Launch sender coroutine
                    val sender = launch {
                        try {
                            for (msg in sendChannel) {
                                when (msg) {
                                    is OutgoingMessage.Text -> send(Frame.Text(msg.text))
                                    is OutgoingMessage.Binary -> send(Frame.Binary(true, msg.bytes))
                                }
                            }
                        } catch (_: ClosedSendChannelException) {
                            // Channel closed, normal shutdown
                        } catch (_: CancellationException) {
                            // Coroutine cancelled
                        }
                    }
                    senderJob = sender

                    // Receive loop
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    // Keep the raw bytes: the Noise prologue is
                                    // built from the exact bytes of server/init
                                    // as received, and readText() alone throws
                                    // them away. Decoding from the same array
                                    // rather than calling readText() means the
                                    // String and the bytes cannot disagree.
                                    val raw = frame.data
                                    val txt = raw.decodeToString()
                                    Log.i(tag, "[cmd-trace] T0 wire-text len=${txt.length}")
                                    listener?.onMessage(txt, raw)
                                }
                                is Frame.Binary -> listener?.onMessage(frame.readBytes())
                                is Frame.Close -> {
                                    val reason = closeReason.await()
                                    val code = reason?.code?.toInt() ?: 1000
                                    val msg = reason?.message ?: ""
                                    Log.d(tag, "WebSocket closing: $code $msg")
                                    listener?.onClosing(code, msg)
                                }
                                else -> { /* Ping/Pong handled by Ktor */ }
                            }
                        }
                    } catch (_: CancellationException) {
                        // Normal cancellation during close
                    }

                    sender.cancel()

                    // Session ended normally
                    val reason = closeReason.await()
                    val code = reason?.code?.toInt() ?: 1000
                    val msg = reason?.message ?: ""
                    Log.d(tag, "WebSocket closed: $code $msg")
                    _state.store(TransportState.Closed)
                    listener?.onClosed(code, msg)
                }
            } catch (e: CancellationException) {
                // Intentional close via destroy()/close(). The webSocket block
                // never reached its post-loop listener?.onClosed(...) call
                // because closeReason.await() re-threw the cancellation.
                // Deliver the close to the listener here with the code/reason
                // captured by close(); without this, callers that drive close()
                // expecting an onClosed callback (notably the stall watchdog)
                // never learn the connection died and the upper-layer reconnect
                // path is never invoked.
                Log.d(tag, "WebSocket cancelled")
                _state.store(TransportState.Closed)
                listener?.onClosed(pendingCloseCode, pendingCloseReason)
            } catch (e: Exception) {
                Log.e(tag, "WebSocket failure: ${e.message}")
                _state.store(TransportState.Failed)
                listener?.onFailure(e, isRecoverableError(e))
            } finally {
                sendChannel.close()
                outgoingChannel = null
            }
        }
    }

    override fun send(text: String): Boolean {
        if (!isConnected) {
            Log.w(tag, "Cannot send text: not connected (state=$state)")
            return false
        }
        val channel = outgoingChannel ?: return false
        return channel.trySend(OutgoingMessage.Text(text)).isSuccess
    }

    override fun send(bytes: ByteArray): Boolean {
        if (!isConnected) {
            Log.w(tag, "Cannot send bytes: not connected (state=$state)")
            return false
        }
        val channel = outgoingChannel ?: return false
        return channel.trySend(OutgoingMessage.Binary(bytes)).isSuccess
    }

    override fun close(code: Int, reason: String) {
        Log.d(tag, "Closing WebSocket: code=$code reason=$reason")
        pendingCloseCode = code
        pendingCloseReason = reason
        // Set state synchronously so a caller that immediately re-checks
        // isConnected (or attempts to reuse this instance via connect()'s
        // CAS chain that accepts Closed) sees the transition without
        // having to wait for the IO-dispatcher cancellation-catch to run.
        // The cancellation catch also stores Closed; the second write is
        // a redundant no-op.
        _state.store(TransportState.Closed)
        outgoingChannel?.close()
        connectionJob?.cancel()
        connectionJob = null
    }

    /**
     * Closing the channel stops new sends but leaves what is already buffered
     * deliverable, so the sender's `for (msg in channel)` loop drains and then
     * completes on its own. Waiting for that completion before cancelling is
     * the whole difference from [close], which cancels the sender's parent and
     * takes the queue down with it.
     *
     * Bounded: a wedged socket must not hold the close open indefinitely, and a
     * goodbye is best-effort by nature.
     */
    override fun closeAfterFlush(code: Int, reason: String) {
        Log.d(tag, "Closing WebSocket after flush: code=$code reason=$reason")
        val sender = senderJob
        outgoingChannel?.close()
        if (sender == null) {
            close(code, reason)
            return
        }
        scope.launch {
            val drained = withTimeoutOrNull(FLUSH_TIMEOUT_MS) { sender.join() } != null
            if (!drained) Log.w(tag, "Outgoing queue did not drain in ${FLUSH_TIMEOUT_MS}ms")
            close(code, reason)
        }
    }

    override fun destroy() {
        close(1000, "Transport destroyed")
        _state.store(TransportState.Closed)
        // Cancel the transport's coroutine scope so its SupervisorJob (and any
        // straggler child coroutines) are torn down with the instance, rather
        // than leaking one rooted SupervisorJob per reconnect for the lifetime
        // of the process.
        scope.cancel()
        httpClient.close()
    }
}
