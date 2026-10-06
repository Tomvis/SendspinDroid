package com.sendspindroid.conformance

import com.sendspindroid.sendspin.crypto.ClientIdentity
import com.sendspindroid.sendspin.crypto.NoiseCrypto
import com.sendspindroid.sendspin.crypto.PskCandidateSet
import com.sendspindroid.sendspin.protocol.NoiseWireCodec
import com.sendspindroid.sendspin.protocol.SendSpinHandshakeDriver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * A WebSocket taken through the Noise handshake by the app's own
 * `SendSpinHandshakeDriver`, then read and written through its
 * `NoiseWireCodec`.
 *
 * The one piece the harness adapter (Main.kt) and [NoiseHandshakeCheck] have
 * in common, so neither carries its own copy of the wire.
 *
 * Callbacks run on OkHttp's reader thread, one at a time.
 */
internal class EncryptedSocket(
    client: OkHttpClient,
    url: String,
    identity: ClientIdentity,
    candidates: PskCandidateSet,
    private val onReady: (SendSpinHandshakeDriver.Event.TransportReady) -> Unit = {},
    private val onFrame: (NoiseWireCodec.Decoded) -> Unit,
    private val onFail: (String) -> Unit,
    private val onClosed: (code: Int, reason: String) -> Unit,
    private val onCleartextSent: (String) -> Unit = {},
) {
    /** Null until the handshake has completed. */
    @Volatile
    var codec: NoiseWireCodec? = null
        private set

    @Volatile
    private var socket: WebSocket? = null

    private val driver = SendSpinHandshakeDriver(
        identity = identity,
        candidates = candidates,
        onEvent = { event ->
            when (event) {
                is SendSpinHandshakeDriver.Event.SendCleartext -> {
                    onCleartextSent(event.text)
                    socket?.send(event.text)
                }
                is SendSpinHandshakeDriver.Event.TransportReady -> {
                    // Nothing is sent yet: client/hello answers server/hello.
                    codec = NoiseWireCodec(event.transport)
                    onReady(event)
                }
                is SendSpinHandshakeDriver.Event.Fail ->
                    onFail("${event.reason}: ${event.detail}")
            }
        },
    )

    init {
        client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                driver.start()
            }

            override fun onMessage(webSocket: WebSocket, text: String) =
                driver.onCleartextFrame(text.toByteArray(Charsets.UTF_8))

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val c = codec ?: return onFail("binary frame before transport mode")
                onFrame(c.decode(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                onClosed(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onFail("socket failure: ${t.message}")
        })
    }

    /** Encrypt and send a JSON message. @return false before the handshake has completed. */
    @Synchronized
    fun send(text: String): Boolean {
        val c = codec ?: return false
        runBlocking { c.encodeJson(text) }.forEach { socket?.send(ByteString.of(*it)) }
        return true
    }

    /** Re-handshake message 2: sent under the current keys, which are then swapped for [next]. */
    @Synchronized
    fun sendAndSwap(type: Int, payload: ByteArray, next: NoiseCrypto) {
        val c = codec ?: return
        runBlocking { c.encodeAndSwap(type, payload, next) }.forEach { socket?.send(ByteString.of(*it)) }
    }

    fun close() {
        socket?.close(1000, "done")
    }
}
