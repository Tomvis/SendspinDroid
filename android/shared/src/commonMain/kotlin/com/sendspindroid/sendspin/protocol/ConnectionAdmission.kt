package com.sendspindroid.sendspin.protocol

/**
 * Which server-initiated connection the client keeps.
 *
 * `connection.md`, "Multiple servers (server-initiated)": "A client holds at
 * most one admitted connection at a time", classified by the highest-ranked
 * activity it declares. A server that connects is *provisional* until its
 * first admissible `server/activate`; that activation is compared with the
 * admitted connection's, and "higher or equal is accepted, lower is rejected",
 * with two exceptions (see [onActivation]).
 *
 * The spec's optional third exception, holding an incoming `pairing`
 * connection alongside the admitted `playback` one, is not implemented: "a
 * client that does not admit both connections rejects the incoming", which is
 * what the ranking already does.
 *
 * Pure and synchronous: it knows no sockets, clocks or threads. The caller
 * serialises the calls, passes the time in, and acts on what comes back.
 *
 * @param C whatever the caller uses to identify a connection.
 * @param lastPlaybackServerId the persisted last-playback server, read before
 *   the first decision.
 * @param onLastPlaybackServerChanged persists it: "Clients MUST persistently
 *   store the `server_id` of the server that most recently held the admitted
 *   connection while `'playback'` was among its `activities`."
 */
class ConnectionAdmission<C : Any>(
    lastPlaybackServerId: String? = null,
    private val onLastPlaybackServerChanged: (String) -> Unit = {},
) {

    /** A connection's class: "the highest-ranked activity in its declared activities". */
    enum class Rank {
        EMPTY, PAIRING, PLAYBACK;

        companion object {
            fun of(activities: Set<Activity>): Rank = when {
                Activity.PLAYBACK in activities -> PLAYBACK
                Activity.PAIRING in activities -> PAIRING
                else -> EMPTY
            }
        }
    }

    sealed interface Decision<out C> {
        /**
         * The connection is the admitted one. [displaced] is the holder it
         * replaced, which is owed `client/goodbye` reason `another_server`
         * (or `pair/abort` reason `concurrent_attempt` if it is a pairing
         * connection) and a close.
         */
        data class Admit<C>(val displaced: C?) : Decision<C>

        /**
         * The connection is refused. It is owed `client/goodbye` reason
         * `concurrent_attempt` (or `pair/abort` reason `concurrent_attempt`
         * for a pairing connection) and a close.
         */
        data object Reject : Decision<Nothing>
    }

    private class Holder<C>(val connection: C, val serverId: String, var activities: Set<Activity>)

    private var holder: Holder<C>? = null

    /** Provisional connections and when each one connected. */
    private val provisional = LinkedHashMap<C, Long>()

    var lastPlaybackServerId: String? = lastPlaybackServerId
        private set

    /** The admitted connection, if there is one. */
    val admitted: C? get() = holder?.connection

    val provisionalCount: Int get() = provisional.size

    /**
     * A server connected. False when the client already holds
     * [MAX_PROVISIONAL] provisional connections: "Clients MAY cap how many
     * provisional connections they hold at once, rejecting further incoming
     * connections as if they were lower priority."
     */
    fun onConnected(connection: C, nowMs: Long): Boolean {
        if (provisional.size >= MAX_PROVISIONAL) return false
        provisional[connection] = nowMs
        return true
    }

    /**
     * An admissible `server/activate` arrived on [connection]. "The client
     * MUST NOT apply the priority rules to an activation that is not
     * admissible", so an inadmissible one never gets here.
     *
     * Only a provisional connection's activation is arbitrated. On the
     * admitted connection it is recorded and nothing else: "Subsequent
     * `server/activate` updates do not otherwise trigger arbitration, even
     * when a connection escalates its activities."
     *
     * @param holderPairingInProgress whether the admitted connection has a
     *   pairing attempt in progress right now. "A pairing attempt is not
     *   displaced by an incoming `'playback'` or `'pairing'` connection."
     */
    fun onActivation(
        connection: C,
        serverId: String,
        activities: Set<Activity>,
        holderPairingInProgress: Boolean = false,
    ): Decision<C> {
        val current = holder
        if (current != null && current.connection == connection) {
            current.activities = activities
            notePlayback(current)
            return Decision.Admit(displaced = null)
        }
        // Neither admitted nor provisional: it was already dropped.
        if (provisional.remove(connection) == null) return Decision.Reject

        if (current != null && !displaces(serverId, activities, current, holderPairingInProgress)) {
            return Decision.Reject
        }
        val admittedNow = Holder(connection, serverId, activities)
        holder = admittedNow
        notePlayback(admittedNow)
        return Decision.Admit(displaced = current?.connection)
    }

    private fun displaces(
        serverId: String,
        activities: Set<Activity>,
        current: Holder<C>,
        holderPairingInProgress: Boolean,
    ): Boolean {
        val incoming = Rank.of(activities)
        val held = Rank.of(current.activities)
        return when {
            holderPairingInProgress && incoming != Rank.EMPTY -> false
            // "When both the current holder and the incoming connection have
            // empty activities, the incoming is admitted only if its
            // server_id matches the last-playback server (and the existing
            // one's does not); otherwise the existing is kept."
            incoming == Rank.EMPTY && held == Rank.EMPTY ->
                serverId == lastPlaybackServerId && current.serverId != lastPlaybackServerId
            else -> incoming >= held
        }
    }

    private fun notePlayback(admitted: Holder<C>) {
        if (Activity.PLAYBACK !in admitted.activities) return
        if (admitted.serverId == lastPlaybackServerId) return
        lastPlaybackServerId = admitted.serverId
        onLastPlaybackServerChanged(admitted.serverId)
    }

    /** [connection] is gone, whoever closed it. */
    fun onClosed(connection: C) {
        provisional.remove(connection)
        if (holder?.connection == connection) holder = null
    }

    /**
     * The provisional connections to drop, which are forgotten here: "A
     * provisional connection that has not sent `server/activate` within 30
     * seconds is dropped."
     */
    fun expired(nowMs: Long): List<C> {
        val late = provisional.filterValues { nowMs - it >= PROVISIONAL_TIMEOUT_MS }.keys.toList()
        late.forEach { provisional.remove(it) }
        return late
    }

    companion object {
        const val PROVISIONAL_TIMEOUT_MS = 30_000L
        const val MAX_PROVISIONAL = 4
    }
}
