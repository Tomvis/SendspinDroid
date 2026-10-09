package com.sendspindroid.sendspin.transport

import com.sendspindroid.shared.log.Log
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The listener for server-initiated connections (`connection.md`, "Server
 * Initiated Connections"): servers that find the client's `_sendspin._tcp`
 * advertisement connect to it here.
 *
 * Plain `ws://` on every interface, so servers on the LAN can reach it, and
 * only [path] is served. "The WebSocket transport MUST be plain `ws://`":
 * confidentiality comes from the Noise layer inside it.
 *
 * @param onConnection called for each accepted socket, on a Ktor thread. The
 *   callee takes the connection with [InboundWebSocketTransport.connect] or
 *   refuses it with [InboundWebSocketTransport.close].
 */
class SendspinWebSocketServer(
    private val path: String = DEFAULT_PATH,
    private val onConnection: (InboundWebSocketTransport) -> Unit,
) {

    companion object {
        private const val TAG = "SendspinWsServer"

        /** "Port: The port the Sendspin client is listening on (recommended: `8928`)". */
        const val DEFAULT_PORT = 8928

        /** "`path` key specifying the WebSocket endpoint ... (recommended value: `/sendspin`)". */
        const val DEFAULT_PATH = "/sendspin"

        private const val PING_INTERVAL_MS = 30_000L
        private const val STOP_TIMEOUT_MS = 500L

        /**
         * How long [stop] waits for a closing connection whose peer has
         * stopped reading. A peer that reads is done in milliseconds and is
         * never cut short by this.
         */
        private const val UNRESPONSIVE_PEER_MS = 10_000L
    }

    private var server: EmbeddedServer<*, *>? = null

    /** Connections, each with the job of the handler serving it. */
    private val open = mutableListOf<Pair<InboundWebSocketTransport, Job>>()

    /**
     * Start listening, on [preferredPort] if it is free and on a port the
     * system picks if it is not.
     *
     * @return the port actually bound, which is the one to advertise.
     */
    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        check(server == null) { "already listening" }
        val (started, port) = try {
            bind(preferredPort)
        } catch (e: Exception) {
            Log.w(TAG, "Port $preferredPort unavailable (${e.message}); letting the system pick one")
            bind(0)
        }
        server = started
        Log.i(TAG, "Listening on port $port, path $path")
        return port
    }

    private fun bind(port: Int): Pair<EmbeddedServer<*, *>, Int> {
        val candidate = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriodMillis = PING_INTERVAL_MS
                timeoutMillis = PING_INTERVAL_MS
            }
            routing {
                webSocket(path) {
                    val remote = call.request.local.let { "${it.remoteAddress}:${it.remotePort}" }
                    val transport = InboundWebSocketTransport(this, remote)
                    track(transport, coroutineContext.job)
                    onConnection(transport)
                    transport.serve()
                }
            }
        }
        try {
            candidate.start(wait = false)
            return candidate to runBlocking { candidate.engine.resolvedConnectors().first().port }
        } catch (e: Exception) {
            candidate.stop(0, STOP_TIMEOUT_MS)
            throw e
        }
    }

    @Synchronized
    private fun track(transport: InboundWebSocketTransport, handler: Job) {
        open.removeAll { !it.second.isActive }
        open += transport to handler
    }

    /**
     * Stop listening and drop every connection still open.
     *
     * Ktor cuts connections off the moment it stops, so this first waits for
     * the connections that are already closing to finish: each writes what
     * it had queued, then its close frame, and its handler returns. A
     * goodbye queued just before this is therefore on the wire before the
     * socket goes. With no connection closing there is nothing to wait for.
     *
     * Blocks until the port is released, so not for the main thread.
     */
    @Synchronized
    fun stop() {
        val running = server ?: return
        val closing = open.filter { it.first.isClosing }.map { it.second }
        if (closing.isNotEmpty()) {
            runBlocking { withTimeoutOrNull(UNRESPONSIVE_PEER_MS) { closing.joinAll() } }
        }
        open.clear()
        running.stop(0, STOP_TIMEOUT_MS)
        server = null
    }
}
