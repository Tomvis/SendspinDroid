package com.sendspindroid.coordinator

import android.util.Log
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.protocol.Activity
import com.sendspindroid.sendspin.protocol.ConnectionAdmission
import com.sendspindroid.sendspin.protocol.ConnectionAdmission.Decision
import com.sendspindroid.sendspin.transport.SendSpinTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The connections servers have opened to this client, and which one of them
 * it plays from (`connection.md`, "Multiple servers (server-initiated)").
 *
 * Each connection gets a [SendSpin] of its own, with its own clock filter
 * and protocol state, created silent ([SendSpin.reporting] off). It runs its
 * handshake by itself, so a connection that is still provisional cannot
 * touch the audio, clock or UI of the one being played. When its first
 * `server/activate` is admitted it is handed to [onAdmitted] and starts
 * reporting; the one it displaced is silenced, told why, and closed.
 *
 * A client created here is destroyed here, once its connection has ended and
 * it is not the one that was handed over.
 *
 * @param newClient a client for one connection, configured but not connected.
 * @param onAdmitted called, on the connection's own thread, with the client
 *   to play from now. Its activation is applied when this returns.
 * @param lastPlaybackServerId the persisted last-playback server.
 * @param storeLastPlaybackServerId persists it.
 */
class InboundConnections(
    private val scope: CoroutineScope,
    private val newClient: () -> SendSpin,
    private val onAdmitted: (SendSpin) -> Unit,
    private val lastPlaybackServerId: () -> String?,
    private val storeLastPlaybackServerId: (String) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private companion object {
        const val TAG = "InboundConnections"

        /** WebSocket close code "try again later". */
        const val CLOSE_TRY_AGAIN_LATER = 1013
    }

    private class Inbound(val client: SendSpin, val remote: String) {
        var ended = false
    }

    private val lock = Any()
    private var admission = newAdmission()

    /** Every connection that has not ended. */
    private val connections = mutableSetOf<Inbound>()

    /** The connection handed to [onAdmitted] last, whether or not it is still up. */
    private var held: Inbound? = null
    private var accepting = false

    private fun newAdmission() =
        ConnectionAdmission<Inbound>(lastPlaybackServerId(), storeLastPlaybackServerId)

    /** Start taking connections, with nothing held and nothing remembered but the last-playback server. */
    fun open() = synchronized(lock) {
        admission = newAdmission()
        accepting = true
    }

    /** A server connected. Called for every accepted socket, on the listener's thread. */
    fun onConnection(transport: SendSpinTransport, remoteAddress: String) {
        val connection = Inbound(newClient(), remoteAddress)
        val room = synchronized(lock) {
            (accepting && admission.onConnected(connection, nowMs())).also {
                if (it) connections += connection
            }
        }
        if (!room) {
            // "Rejecting further incoming connections as if they were lower
            // priority." There is no encrypted channel to say so on yet.
            Log.w(TAG, "Refusing $remoteAddress: not accepting, or too many provisional connections")
            transport.close(CLOSE_TRY_AGAIN_LATER, "busy")
            connection.client.destroy()
            return
        }
        Log.i(TAG, "Connection from $remoteAddress (provisional)")
        val client = connection.client
        client.reporting = false
        client.admission = { serverId, activities -> admit(connection, serverId, activities) }
        client.accept(transport, remoteAddress)

        scope.launch {
            client.connectionState.first { it is TransportState.Idle || it is TransportState.Failed }
            onEnded(connection)
        }
        scope.launch {
            delay(ConnectionAdmission.PROVISIONAL_TIMEOUT_MS)
            dropExpired()
        }
    }

    /**
     * An admissible `server/activate` arrived on [connection]: is it, or
     * does it stay, the connection the client keeps?
     */
    private fun admit(connection: Inbound, serverId: String, activities: Set<Activity>): Boolean {
        val replaced: Inbound?
        synchronized(lock) {
            if (!accepting) return false
            val holderPairing = admission.admitted?.client?.pairingAttemptInProgress == true
            when (admission.onActivation(connection, serverId, activities, holderPairing)) {
                Decision.Reject -> {
                    Log.i(TAG, "Refusing ${connection.remote}: activities=$activities")
                    return false
                }
                is Decision.Admit -> {
                    // A later activation on the connection already held.
                    if (held === connection) return true
                    replaced = held
                    held = connection
                }
            }
        }
        Log.i(TAG, "Admitting ${connection.remote}: activities=$activities" +
            (replaced?.takeUnless { it.ended }?.let { ", displacing ${it.remote}" } ?: ""))
        replaced?.let { release(it) }
        connection.client.reporting = true
        onAdmitted(connection.client)
        return true
    }

    /** Let go of a connection that was handed over: silence it, and end it if it is still up. */
    private fun release(connection: Inbound) {
        connection.client.reporting = false
        val ended = synchronized(lock) { connection.ended }
        // If it is still up, onEnded destroys it once the goodbye is out.
        if (ended) connection.client.destroy() else connection.client.leaveForAnotherServer()
    }

    private fun onEnded(connection: Inbound) {
        val keep = synchronized(lock) {
            connection.ended = true
            connections -= connection
            admission.onClosed(connection)
            held === connection
        }
        // The held one stays with its owner until something replaces it.
        if (!keep) connection.client.destroy()
    }

    private fun dropExpired() {
        val expired = synchronized(lock) { admission.expired(nowMs()) }
        expired.forEach {
            Log.w(TAG, "No server/activate from ${it.remote} in " +
                "${ConnectionAdmission.PROVISIONAL_TIMEOUT_MS / 1000}s; dropping it")
            it.client.drop()
        }
    }

    /**
     * Stop taking connections and end the ones there are. The caller has
     * already stopped playing from the held one.
     */
    fun close() {
        val (released, others) = synchronized(lock) {
            accepting = false
            val released = held
            held = null
            released to connections.filter { it !== released }
        }
        // Every connection that is not the held one is provisional, and has
        // had no activation to say goodbye to.
        others.forEach { it.client.destroy() }
        released?.let { release(it) }
    }
}
