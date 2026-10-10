package com.sendspindroid.sendspin.pairing

import com.sendspindroid.UserSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One stored pairing record, as the UI shows it: identities, never the PSK.
 *
 * @param serverId null only for a record left over from before 1.0.0-rc1.
 * @param name the name the server last gave in `server/hello`, if it has
 *   connected since this build started remembering names.
 */
data class PairedServer(
    val pskId: String,
    val serverId: String?,
    val name: String?,
)

/**
 * How the last pairing exchange with a server ended.
 *
 * @property server the server's name, for display.
 * @property atMs when it happened. Also what makes two identical outcomes in
 *   a row two values, so the second one is still announced.
 */
sealed interface PairingOutcome {
    val server: String
    val atMs: Long

    /** The server stored the record and so did we. */
    data class Paired(
        override val server: String,
        override val atMs: Long = System.currentTimeMillis(),
    ) : PairingOutcome

    /**
     * The attempt ended in `pair/abort`.
     *
     * @param reason the wire reason, one of [PairAbortReason.ALL] unless the
     *   server sent something else.
     * @param sentByUs false when the server sent it.
     */
    data class Aborted(
        val reason: String,
        val sentByUs: Boolean,
        override val server: String,
        override val atMs: Long = System.currentTimeMillis(),
    ) : PairingOutcome

    /** `server/unpair`: the server dropped its pairing with this device. */
    data class Unpaired(
        override val server: String,
        override val atMs: Long = System.currentTimeMillis(),
    ) : PairingOutcome
}

/**
 * The stored pairing records and the last pairing outcome, for the UI.
 *
 * The protocol layer runs in `PlaybackService` and writes here; Settings and
 * the main screen are other activities in the same process and read here.
 * Nothing crosses a process boundary, so if the service is ever moved to a
 * process of its own this stops updating.
 *
 * The records themselves stay in the trust store. This is a view of it, and
 * [refresh] is called wherever the store changes.
 */
object PairedServers {

    private val _servers = MutableStateFlow<List<PairedServer>>(emptyList())
    val servers: StateFlow<List<PairedServer>> = _servers.asStateFlow()

    /** Kept for as long as the process lives; not persisted. */
    private val _lastOutcome = MutableStateFlow<PairingOutcome?>(null)
    val lastOutcome: StateFlow<PairingOutcome?> = _lastOutcome.asStateFlow()

    private val _forgotten = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * The `psk_id` of each record the user removed, for whoever holds a
     * connection that record admitted.
     */
    val forgotten: SharedFlow<String> = _forgotten.asSharedFlow()

    /** Read the trust store again. */
    fun refresh() {
        _servers.value = UserSettings.getOrCreateTrustStore().listRecords().map { record ->
            PairedServer(
                pskId = record.pskId,
                serverId = record.serverId,
                name = record.serverId?.let { UserSettings.getPairedServerName(it) },
            )
        }
    }

    /**
     * Remove a record from this device. The server is not told and keeps its
     * own: it cannot authenticate to this player again until it pairs again.
     *
     * The record goes first, so that by the time the connection it admitted
     * is closed there is no key left to admit it again with.
     */
    fun forget(pskId: String) {
        val store = UserSettings.getOrCreateTrustStore()
        val serverId = store.findByPskId(pskId)?.serverId
        if (!store.removeRecord(pskId)) return
        serverId?.let { UserSettings.setPairedServerName(it, null) }
        refresh()
        // "Paired with ..." over a list it is no longer in would mislead.
        _lastOutcome.value = null
        _forgotten.tryEmit(pskId)
    }

    /** A paired server said what it is called. */
    fun rememberName(serverId: String, name: String) {
        if (UserSettings.getPairedServerName(serverId) == name) return
        UserSettings.setPairedServerName(serverId, name)
        refresh()
    }

    /** A pairing exchange ended. The store may have changed with it. */
    fun report(outcome: PairingOutcome) {
        refresh()
        _lastOutcome.value = outcome
    }
}
